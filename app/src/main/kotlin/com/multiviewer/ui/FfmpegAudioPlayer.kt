package com.multiviewer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.EventQueue
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine

data class AudioFileInfo(val sampleRate: Int, val channels: Int, val duration: Double)

// Solo one channel of a stereo file for listening. The soloed channel is sent to BOTH output
// channels so it plays in both ears; output stays 2ch so the SourceDataLine format is unchanged.
enum class ChannelMode { STEREO, LEFT, RIGHT }

fun channelModeFilterArgs(mode: ChannelMode): List<String> = when (mode) {
    ChannelMode.STEREO -> emptyList()
    ChannelMode.LEFT -> listOf("-af", "pan=stereo|c0=c0|c1=c0")
    ChannelMode.RIGHT -> listOf("-af", "pan=stereo|c0=c1|c1=c1")
}

// The audio line stamped with the restartTrigger generation that spawned it, so the reader thread
// only affects the composition (publishes its clock, fires EOF) while its generation is current.
private data class LineSlot(val gen: Int, val line: SourceDataLine)

fun probeAudioFormat(file: File): AudioFileInfo? {
    return try {
        val process = ProcessBuilder(
            FfmpegLocator.ffprobePath(), "-v", "error", "-select_streams", "a:0",
            "-show_entries", "stream=sample_rate,channels,duration:format=duration",
            "-of", "default=noprint_wrappers=1", file.absolutePath,
        ).redirectErrorStream(false).redirectError(ProcessBuilder.Redirect.DISCARD)
            .also { FfmpegLocator.configureEnvironment(it) }.start()
        val lines = readProcessOutputWithTimeout(process, 5) { process.inputStream.bufferedReader().readLines() }
            ?: return null

        val values = mutableMapOf<String, String>()
        val validDurations = mutableListOf<Double>()
        for (line in lines) {
            val eq = line.indexOf('=')
            if (eq < 0) continue
            val key = line.substring(0, eq)
            val value = line.substring(eq + 1)
            if (key == "duration") {
                val d = value.toDoubleOrNull()
                if (d != null && d > 0.0) {
                    validDurations.add(d)
                }
            } else {
                values[key] = value
            }
        }
        val sampleRate = values["sample_rate"]?.toIntOrNull() ?: return null
        val channels = values["channels"]?.toIntOrNull() ?: return null
        val duration = validDurations.firstOrNull() ?: 0.0
        AudioFileInfo(sampleRate, channels, duration)
    } catch (e: Exception) {
        null
    }
}

// GoldWave-style waveform player: a top header ([ Open Audio ] + MM:SS.mmm / MM:SS.mmm), the
// scrolling L/R waveform (click to seek, wheel to zoom), an optional bottom scrollbar while zoomed
// in, and a transport bar (rewind / play-pause / stop + zoom -/+).
//
// The playhead (`cursorSeconds`) is driven ONLY by the audio mixer clock
// (SourceDataLine.microsecondPosition + the current pipe's -ss offset) -- no wall-clock or
// bytes-read bookkeeping -- so it never drifts against what you actually hear. Every seek tears
// down the ffmpeg pipe + SourceDataLine and respawns them with a fresh -ss (restartTrigger).
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun FfmpegAudioPlayer(
    file: File,
    rawAudioParams: RawAudioParams? = null,
    onOpenAudio: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    var isPlaying by remember(file) { mutableStateOf(false) }
    val isPlayingAtomic = remember(file) { AtomicBoolean(false) }
    var hasEnded by remember(file) { mutableStateOf(false) }
    var restartTrigger by remember(file) { mutableStateOf(0) }
    var pipeStartSeconds by remember(file) { mutableStateOf(0.0) }   // -ss of the current pipe
    var cursorSeconds by remember(file) { mutableStateOf(0.0) }      // displayed playhead (absolute)
    var loadError by remember(file) { mutableStateOf(false) }
    var channelMode by remember(file) { mutableStateOf(ChannelMode.STEREO) }

    var probedInfo by remember(file) { mutableStateOf<AudioFileInfo?>(null) }
    var probing by remember(file) { mutableStateOf(true) }
    var waveformPeaks by remember(file) { mutableStateOf<WaveformPeaks?>(null) }

    // The audio line, published by the reader thread so the follow coroutine can read its clock.
    // Wrapped in a LineSlot stamped with the restartTrigger generation that spawned it.
    val lineHolder = remember(file) { AtomicReference<LineSlot?>(null) }

    // The live restartTrigger, readable on the EDT so a superseded reader thread's EOF callback can
    // check whether its generation is still current before touching the composition.
    val currentGenState = rememberUpdatedState(restartTrigger)

    // Called by the reader thread (via EventQueue.invokeLater) when the PCM stream ends. Captured
    // through rememberUpdatedState so the thread always invokes the current composition's state
    // setters, never a stale closure from a superseded DisposableEffect.
    val onEndOfStream = rememberUpdatedState {
        isPlaying = false
        isPlayingAtomic.set(false)
        hasEnded = true
    }

    LaunchedEffect(file) {
        probing = true
        val info = withContext(Dispatchers.IO) {
            if (rawAudioParams != null) {
                AudioFileInfo(
                    sampleRate = rawAudioParams.sampleRate,
                    channels = rawAudioParams.channels,
                    duration = computeRawAudioDuration(
                        file.length(), rawAudioParams.offsetBytes, rawAudioParams.sampleRate,
                        rawAudioParams.channels, rawAudioParams.format.bytesPerSample,
                    ),
                )
            } else {
                probeAudioFormat(file)
            }
        }
        probedInfo = info
        probing = false
        if (info != null) {
            waveformPeaks = withContext(Dispatchers.IO) {
                computeWaveformPeaks(file, info, bucketCount = waveformBucketCountFor(info.duration), rawAudioParams = rawAudioParams)
            }
        }
    }

    if (probing) {
        Box(modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            DecodingIndicator("오디오 정보 분석 중...")
        }
        return
    }

    val info = probedInfo
    if (info == null) {
        Box(modifier.fillMaxSize().background(Color.DarkGray), contentAlignment = Alignment.Center) {
            Text("Could not read audio (is ffmpeg installed?)", color = Color.White)
        }
        return
    }

    var view by remember(file) {
        mutableStateOf(AudioViewWindow(0.0, info.duration.coerceAtLeast(MIN_VISIBLE_DURATION_SECONDS)))
    }

    // --- reader thread + audio pipe (respawned on restartTrigger, e.g. every seek) ---
    DisposableEffect(file, restartTrigger) {
        val myGen = restartTrigger
        isPlayingAtomic.set(isPlaying)
        val startSec = pipeStartSeconds
        // "%.6f" + Locale.ROOT: Double.toString emits scientific notation ("6.0E-4") below 1e-3,
        // which ffmpeg's -ss rejects; a comma-decimal locale would emit "0,000600".
        val seekArgs = if (startSec > 0.0) {
            listOf("-ss", "%.6f".format(java.util.Locale.ROOT, startSec))
        } else {
            emptyList()
        }
        val sr = info.sampleRate
        val ch = info.channels
        val inputFile = if (rawAudioParams != null) rawAudioSourceFile(file, rawAudioParams.offsetBytes) else file
        val rawInputArgs = if (rawAudioParams != null) {
            listOf("-f", rawAudioParams.ffmpegFormatCode(), "-ar", rawAudioParams.sampleRate.toString(), "-ac", rawAudioParams.channels.toString())
        } else {
            emptyList()
        }
        val process = try {
            ProcessBuilder(
                listOf(FfmpegLocator.ffmpegPath()) + seekArgs + rawInputArgs +
                    listOf("-i", inputFile.absolutePath, "-map", "0:a:0") +
                    (if (info.channels == 2) channelModeFilterArgs(channelMode) else emptyList()) +
                    listOf(
                        "-f", "s16le", "-ar", sr.toString(), "-ac", ch.toString(), "-acodec", "pcm_s16le", "-",
                    ),
            ).redirectError(ProcessBuilder.Redirect.DISCARD)
                .also { FfmpegLocator.configureEnvironment(it) }.start()
                .also { com.multiviewer.util.ProcessManager.register(it) }
        } catch (e: Exception) {
            null
        }
        // Assigned every run: a later successful respawn must clear a stale transient failure.
        loadError = process == null

        val stopped = AtomicBoolean(false)
        val format = AudioFormat(sr.toFloat(), 16, ch, true, false)

        val readerThread = if (process != null) {
            Thread {
                var line: SourceDataLine? = null
                try {
                    line = AudioSystem.getSourceDataLine(format).apply { open(format); start() }
                    lineHolder.set(LineSlot(myGen, line))
                    var wasPlaying = true
                    val buf = ByteArray(8192)
                    val input = process.inputStream
                    var ended = false
                    while (!stopped.get()) {
                        if (!isPlayingAtomic.get()) {
                            if (wasPlaying) { line.stop(); wasPlaying = false }
                            Thread.sleep(50)
                            continue
                        }
                        if (!wasPlaying) { line.start(); wasPlaying = true }
                        val n = input.read(buf)
                        if (n < 0) { ended = true; break }
                        line.write(buf, 0, n)
                    }
                    if (ended && !stopped.get()) {
                        // Let the line buffer drain so the cursor reaches the true end. Bail early
                        // if the line was stopped (paused) meanwhile -- available() won't move then.
                        val deadline = System.currentTimeMillis() + 2000
                        while (!stopped.get() && isPlayingAtomic.get() && line.isRunning &&
                            System.currentTimeMillis() < deadline &&
                            line.bufferSize - line.available() > 0
                        ) {
                            Thread.sleep(20)
                        }
                        // Only end the composition's playback if this thread's generation is still
                        // the live one -- a replay during the drain bumps restartTrigger and this
                        // callback must not kill the new pipe.
                        if (!stopped.get()) {
                            EventQueue.invokeLater {
                                if (currentGenState.value == myGen) onEndOfStream.value.invoke()
                            }
                        }
                    }
                } catch (e: InterruptedException) {
                    // Expected on dispose -- not an error.
                } catch (e: Exception) {
                    System.err.println("FfmpegAudioPlayer reader thread failed: $e")
                } finally {
                    line?.stop()
                    line?.flush()
                    line?.close()
                    // Only clear our own slot: on a seek/replay the NEXT reader thread may have
                    // already published its LineSlot by the time this (superseded) thread reaches
                    // its finally -- an unconditional null would wipe the live line and freeze the
                    // playhead.
                    lineHolder.updateAndGet { if (it?.gen == myGen) null else it }
                    com.multiviewer.util.ProcessManager.terminate(process)
                }
            }.apply { isDaemon = true }.also { it.start() }
        } else {
            null
        }

        onDispose {
            stopped.set(true)
            readerThread?.interrupt()
            com.multiviewer.util.ProcessManager.terminate(process)
            if (inputFile != file) inputFile.delete()
        }
    }

    fun setPlaying(play: Boolean) {
        if (play && hasEnded) {
            hasEnded = false
            pipeStartSeconds = 0.0
            cursorSeconds = 0.0
            restartTrigger++
        }
        isPlaying = play
        isPlayingAtomic.set(play)
    }

    fun seekToSeconds(t: Double) {
        val clamped = t.coerceIn(0.0, info.duration)
        hasEnded = false
        pipeStartSeconds = clamped
        cursorSeconds = clamped
        if (clamped < view.startSeconds || clamped > view.startSeconds + view.durationSeconds) {
            view = clampWindow(clamped - view.durationSeconds / 2.0, view.durationSeconds, info.duration)
        }
        restartTrigger++
    }

    fun zoomTo(newSpan: Double, anchor: Double) {
        val span = newSpan.coerceIn(
            MIN_VISIBLE_DURATION_SECONDS,
            info.duration.coerceAtLeast(MIN_VISIBLE_DURATION_SECONDS),
        )
        view = zoomAround(view, anchor, span, info.duration)
    }

    // Follow: while playing, drive cursorSeconds from the mixer clock and page-scroll the view.
    // Cancelled (and restarted) whenever isPlaying or restartTrigger changes.
    LaunchedEffect(isPlaying, restartTrigger) {
        if (!isPlaying) return@LaunchedEffect
        while (true) {
            withFrameNanos {
                // Gate on the generation: right after a seek-while-playing the OLD reader thread can
                // still hold its OLD (still-open) line for ~50 ms; reading its clock would compute a
                // bogus cursor against the NEW -ss offset and mis-page the view.
                val slot = lineHolder.get()
                val line = slot?.takeIf { it.gen == restartTrigger && it.line.isOpen }?.line
                if (line != null) {
                    cursorSeconds = (pipeStartSeconds + line.microsecondPosition / 1_000_000.0)
                        .coerceIn(0.0, info.duration)
                    pageScrollView(cursorSeconds, view, info.duration)?.let { view = it }
                }
            }
        }
    }

    Column(modifier.fillMaxSize().background(Color.Black)) {
        AudioPlayerHeader(
            cursorSeconds = cursorSeconds,
            totalSeconds = info.duration,
            onOpenAudio = onOpenAudio,
        )

        if (loadError) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("Could not start ffmpeg playback", color = Color.White)
            }
        } else {
            AudioWaveformView(
                peaks = waveformPeaks,
                view = view,
                totalDurationSeconds = info.duration,
                cursorSeconds = { cursorSeconds },
                onSeekTo = { seekToSeconds(it) },
                onZoom = { deltaY, anchor ->
                    // Compose reports a NEGATIVE scroll delta for scroll-up; scroll-up = zoom in
                    // = a SMALLER visible span. deltaY == 0 is a pure-horizontal scroll -- ignore.
                    if (deltaY != 0f) {
                        val factor = if (deltaY < 0f) 1.0 / 1.5 else 1.5
                        zoomTo(view.durationSeconds * factor, anchor)
                    }
                },
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
        }

        if (view.durationSeconds < info.duration) {
            AudioWaveformScrollbar(
                view = view,
                totalDuration = info.duration,
                onScroll = { view = it },
                modifier = Modifier.padding(top = 2.dp),
            )
        }

        AudioTransportBar(
            isPlaying = isPlaying,
            zoomPercentValue = zoomPercent(view, info.duration),
            canZoomOut = view.durationSeconds < info.duration,
            canZoomIn = view.durationSeconds > MIN_VISIBLE_DURATION_SECONDS,
            channelMode = if (info.channels == 2) channelMode else null,
            onChannelMode = { m ->
                if (m != channelMode) {
                    // Position-preserving restart, same as a seek. isPlayingAtomic re-syncs to
                    // isPlaying at the top of DisposableEffect so playback carries across.
                    channelMode = m
                    pipeStartSeconds = cursorSeconds
                    hasEnded = false
                    restartTrigger++
                }
            },
            onRewind = { seekToSeconds(0.0) },
            onPlayPause = { setPlaying(!isPlaying) },
            onStop = {
                setPlaying(false)
                seekToSeconds(0.0)
                view = clampWindow(0.0, view.durationSeconds, info.duration)
            },
            onZoomOut = { zoomTo(view.durationSeconds * 1.5, cursorSeconds) },
            onZoomIn = { zoomTo(view.durationSeconds / 1.5, cursorSeconds) },
        )
    }
}

// A full-width strip below the waveform: the highlighted segment is the current zoom window
// against the whole track; dragging it left/right pans the view. Same detectDragGestures
// convention as DraggableDivider (Components.kt), horizontal-position instead of a resize split.
@Composable
private fun AudioWaveformScrollbar(
    view: AudioViewWindow,
    totalDuration: Double,
    onScroll: (AudioViewWindow) -> Unit,
    modifier: Modifier = Modifier,
) {
    // pointerInput only restarts on totalDuration, and the drag lambda is captured once, so read
    // the live window through this handle rather than the value from first composition.
    val liveView by rememberUpdatedState(view)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(8.dp)
            .background(Color.White.copy(alpha = 0.10f))
            .pointerInput(totalDuration) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    if (totalDuration > 0.0 && size.width > 0) {
                        val deltaSeconds = (dragAmount.x / size.width) * totalDuration
                        onScroll(
                            clampWindow(
                                liveView.startSeconds + deltaSeconds,
                                liveView.durationSeconds,
                                totalDuration,
                            ),
                        )
                    }
                }
            },
    ) {
        if (totalDuration > 0.0) {
            val startFraction = (view.startSeconds / totalDuration).toFloat().coerceIn(0f, 1f)
            val durationFraction = (view.durationSeconds / totalDuration).toFloat().coerceIn(0.001f, 1f)
            val afterFraction = (1f - startFraction - durationFraction).coerceAtLeast(0f)
            Row(modifier = Modifier.fillMaxSize()) {
                Spacer(modifier = Modifier.weight(startFraction.coerceAtLeast(0.0001f)))
                Box(modifier = Modifier.weight(durationFraction).fillMaxHeight().background(Color(0xFF39FF14)))
                Spacer(modifier = Modifier.weight(afterFraction.coerceAtLeast(0.0001f)))
            }
        }
    }
}

