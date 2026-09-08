package com.multiviewer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image
import java.awt.EventQueue
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine

data class AudioFileInfo(val sampleRate: Int, val channels: Int, val duration: Double)

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

private const val AUDIO_VISUAL_TIMEOUT_MS = 10000L

// Renders the spectrogram via ffmpeg's own showspectrumpic filter -- already implements the
// standard color-mapped STFT rendering audio editors use for this, so there's no need to hand-roll
// FFT in Kotlin. (The waveform itself is no longer rendered this way -- see AudioWaveformPeaks.kt,
// which computes real PCM min/max peaks and draws them via Compose Canvas instead.)
// Follows the same temp-file ffmpeg-image-extraction convention as
// FfmpegImageSnapshotDecoder.decodeSingleFrameToBitmap: write to a temp PNG, wait with a timeout,
// check exit code and file size, decode via Skia, always clean up the temp file.
private fun renderAudioVisualization(
    file: File,
    filter: String,
    rawAudioParams: RawAudioParams? = null,
    window: AudioViewWindow? = null,
): ImageBitmap? {
    val tempPng = try {
        File.createTempFile("audio-visual-", ".png")
    } catch (e: Exception) {
        return null
    }
    // No deleteOnExit(): the finally below deletes it on every path, and this re-runs on every
    // spectrogram render/zoom (see RawPixelDecoder.decodeYuvFamily).
    var inputFile: File? = null
    var process: Process? = null
    return try {
        val resolvedInputFile = if (rawAudioParams != null) rawAudioSourceFile(file, rawAudioParams.offsetBytes) else file
        inputFile = resolvedInputFile
        val rawInputArgs = if (rawAudioParams != null) {
            listOf("-f", rawAudioParams.ffmpegFormatCode(), "-ar", rawAudioParams.sampleRate.toString(), "-ac", rawAudioParams.channels.toString())
        } else {
            emptyList()
        }
        // Trims the SOURCE to just the visible zoom window before ffmpeg ever sees the rest of the
        // file, rather than rendering the whole spectrum and cropping the image -- this is what
        // makes zooming in reveal genuinely more spectral detail instead of a blurrier crop of the
        // same fixed-resolution picture. Both -ss and -t are input-side flags (must precede -i to
        // trim the input rather than the output), same convention as the raw-PCM input flags above.
        val windowArgs = if (window != null) {
            listOf("-ss", window.startSeconds.toString(), "-t", window.durationSeconds.toString())
        } else {
            emptyList()
        }
        process = ProcessBuilder(
            listOf(FfmpegLocator.ffmpegPath(), "-y") + rawInputArgs + windowArgs + listOf(
                "-i", resolvedInputFile.absolutePath,
                "-lavfi", filter, "-frames:v", "1", tempPng.absolutePath,
            ),
        ).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD)
            .also { FfmpegLocator.configureEnvironment(it) }.start()
            .also { com.multiviewer.util.ProcessManager.register(it) }
        val finished = process.waitFor(AUDIO_VISUAL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        if (!finished) {
            // finally below force-kills and unregisters -- destroyForcibly here just hurries it.
            null
        } else if (process.exitValue() != 0 || tempPng.length() == 0L) {
            null
        } else {
            Image.makeFromEncoded(tempPng.readBytes()).toComposeImageBitmap()
        }
    } catch (e: Exception) {
        null
    } finally {
        // No-op if it already exited; ProcessManager.terminate also unregisters on every path.
        com.multiviewer.util.ProcessManager.terminate(process)
        tempPng.delete()
        val fileToClean = inputFile
        if (fileToClean != null && fileToClean != file) fileToClean.delete()
    }
}

// showspectrumpic draws a legend/axis border by default (legend=true), which reserves margin
// space around the actual spectrum data -- that margin is what was causing the rendered content
// to NOT line up with the waveform's edges, since the data region sits inset from the image
// bounds rather than flush to them (confirmed by rendering a probe file with sharp clicks at known
// timestamps and measuring where their energy actually landed in the output pixels: with the
// legend on, a click at true t=0.01s in a 4s clip landed at x-fraction 0.13 instead of 0.0025;
// with legend=0, it landed at 0.0025, matching the true timestamp). legend=0 removes that margin
// entirely, so the image is pure spectrum data edge-to-edge. scale=W:H (no aspect-ratio
// preservation) still forces the exact requested dimensions by stretching rather than
// letterboxing/pillarboxing, since showspectrumpic doesn't honor its own s=WxH request precisely.
// This matters because the progress overlay and the shared zoom/pan window both assume "image
// width == the full requested time range" linearly -- any inset margin would make the playhead and
// the waveform's visible range visually misaligned with the spectrogram's actual content.
fun generateSpectrogramImage(
    file: File,
    width: Int,
    height: Int,
    rawAudioParams: RawAudioParams? = null,
    window: AudioViewWindow? = null,
): ImageBitmap? =
    renderAudioVisualization(file, "showspectrumpic=s=${width}x${height}:legend=0,scale=${width}:${height}", rawAudioParams, window)

// Both scale with the CURRENT visible duration rather than being a fixed number of seconds per
// scroll unit, so zoom/pan feel consistent whether the view is showing the whole track or one
// second of it -- a fixed-seconds step would feel glacial zoomed out and twitchy zoomed in.
private const val ZOOM_STEP_FACTOR = 0.08
private const val PAN_STEP_FACTOR = 0.05

// Solo one channel of a stereo file for listening. The soloed channel is sent to BOTH output
// channels (so it plays in both ears); output stays 2ch so the SourceDataLine format is unchanged.
enum class ChannelMode { STEREO, LEFT, RIGHT }

fun channelModeFilterArgs(mode: ChannelMode): List<String> = when (mode) {
    ChannelMode.STEREO -> emptyList()
    ChannelMode.LEFT -> listOf("-af", "pan=stereo|c0=c0|c1=c0")
    ChannelMode.RIGHT -> listOf("-af", "pan=stereo|c0=c1|c1=c1")
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

    var probedInfo by remember(file) { mutableStateOf<AudioFileInfo?>(null) }
    var probing by remember(file) { mutableStateOf(true) }
    var waveformPeaks by remember(file) { mutableStateOf<WaveformPeaks?>(null) }

    // The audio line, published by the reader thread so the follow coroutine can read its clock.
    val lineHolder = remember(file) { AtomicReference<SourceDataLine?>(null) }

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
        isPlayingAtomic.set(isPlaying)
        val startSec = pipeStartSeconds
        val seekArgs = if (startSec > 0.0) listOf("-ss", startSec.toString()) else emptyList()
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
                listOf(FfmpegLocator.ffmpegPath()) + seekArgs + rawInputArgs + listOf(
                    "-i", inputFile.absolutePath, "-map", "0:a:0",
                    "-f", "s16le", "-ar", sr.toString(), "-ac", ch.toString(), "-acodec", "pcm_s16le", "-",
                ),
            ).redirectError(ProcessBuilder.Redirect.DISCARD)
                .also { FfmpegLocator.configureEnvironment(it) }.start()
                .also { com.multiviewer.util.ProcessManager.register(it) }
        } catch (e: Exception) {
            null
        }
        if (process == null) loadError = true

        val stopped = AtomicBoolean(false)
        val format = AudioFormat(sr.toFloat(), 16, ch, true, false)

        val readerThread = if (process != null) {
            Thread {
                var line: SourceDataLine? = null
                try {
                    line = AudioSystem.getSourceDataLine(format).apply { open(format); start() }
                    lineHolder.set(line)
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
                        while (!stopped.get() && line.isRunning && System.currentTimeMillis() < deadline &&
                            line.bufferSize - line.available() > 0
                        ) {
                            Thread.sleep(20)
                        }
                        EventQueue.invokeLater { onEndOfStream.value.invoke() }
                    }
                } catch (e: InterruptedException) {
                    // Expected on dispose -- not an error.
                } catch (e: Exception) {
                    System.err.println("FfmpegAudioPlayer reader thread failed: $e")
                } finally {
                    line?.stop()
                    line?.flush()
                    line?.close()
                    // compareAndSet, not set(null): on a seek/replay the NEXT reader thread may have
                    // already published its line by the time this (superseded) thread reaches its
                    // finally -- an unconditional null would wipe the live line and freeze the
                    // playhead. Only clear our own.
                    line?.let { lineHolder.compareAndSet(it, null) }
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
                val line = lineHolder.get()?.takeIf { it.isOpen }
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
                cursorSeconds = cursorSeconds,
                onSeekTo = { seekToSeconds(it) },
                onZoom = { deltaY, anchor ->
                    // Compose reports a NEGATIVE scroll delta for scroll-up; scroll-up = zoom in
                    // = a SMALLER visible span.
                    val factor = if (deltaY < 0f) 1.0 / 1.5 else 1.5
                    zoomTo(view.durationSeconds * factor, anchor)
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
            cursorSeconds = cursorSeconds,
            totalSeconds = info.duration,
            zoomPercentValue = zoomPercent(view, info.duration),
            canZoomOut = view.durationSeconds < info.duration,
            canZoomIn = view.durationSeconds > MIN_VISIBLE_DURATION_SECONDS,
            onOpenAudio = onOpenAudio,
            onRewind = { seekToSeconds(0.0) },
            onPlayPause = { setPlaying(!isPlaying) },
            onStop = {
                setPlaying(false)
                seekToSeconds(0.0)
                view = clampWindow(0.0, view.durationSeconds, info.duration)
            },
            onZoomOut = { zoomTo(view.durationSeconds * 1.5, cursorSeconds) },
            onZoomIn = { zoomTo(view.durationSeconds / 1.5, cursorSeconds) },
            showHeader = false,
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

// A thin draggable strip beneath the panels showing (and letting the user drag) the current zoom
// window as a highlighted segment against the full track -- same detectDragGestures convention
// already used by this app's DraggableDivider (Components.kt), just horizontal-position instead
// of a resize split.
@Composable
private fun AudioZoomScrollbar(
    window: AudioViewWindow,
    totalDuration: Double,
    onWindowChange: (AudioViewWindow) -> Unit,
    modifier: Modifier = Modifier,
) {
    // pointerInput only restarts on totalDuration (never changes), so the drag lambda would
    // otherwise compute from the window as of first composition. Read the live value instead.
    val currentWindow by rememberUpdatedState(window)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(6.dp)
            .background(Color.White.copy(alpha = 0.1f))
            .pointerInput(totalDuration) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    if (totalDuration > 0.0 && size.width > 0) {
                        val deltaSeconds = (dragAmount.x / size.width) * totalDuration
                        onWindowChange(clampWindow(currentWindow.startSeconds + deltaSeconds, currentWindow.durationSeconds, totalDuration))
                    }
                }
            },
    ) {
        if (totalDuration > 0.0) {
            val startFraction = (window.startSeconds / totalDuration).toFloat().coerceIn(0f, 1f)
            val durationFraction = (window.durationSeconds / totalDuration).toFloat().coerceIn(0.001f, 1f)
            val afterFraction = (1f - startFraction - durationFraction).coerceAtLeast(0f)
            Row(modifier = Modifier.fillMaxSize()) {
                Spacer(modifier = Modifier.weight(startFraction.coerceAtLeast(0.0001f)))
                Box(modifier = Modifier.weight(durationFraction).fillMaxHeight().background(Color(0xFF39FF14)))
                Spacer(modifier = Modifier.weight(afterFraction.coerceAtLeast(0.0001f)))
            }
        }
    }
}

@Composable
private fun AudioPauseIcon(modifier: Modifier = Modifier, color: Color = Color.White) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.fillMaxHeight().width(3.dp).background(color))
        Box(modifier = Modifier.fillMaxHeight().width(3.dp).background(color))
    }
}
