package com.multiviewer.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image
import java.awt.EventQueue
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine

data class AudioFileInfo(val sampleRate: Int, val channels: Int, val duration: Double)

fun probeAudioFormat(file: File): AudioFileInfo? {
    return try {
        val process = ProcessBuilder(
            FfmpegLocator.ffprobePath(), "-v", "error", "-select_streams", "a:0",
            "-show_entries", "stream=sample_rate,channels,duration",
            "-of", "default=noprint_wrappers=1", file.absolutePath,
        ).redirectErrorStream(false).redirectError(ProcessBuilder.Redirect.DISCARD)
            .also { FfmpegLocator.configureEnvironment(it) }.start()
        val lines = process.inputStream.bufferedReader().readLines()
        process.waitFor(5, TimeUnit.SECONDS)

        val values = mutableMapOf<String, String>()
        for (line in lines) {
            val eq = line.indexOf('=')
            if (eq < 0) continue
            values[line.substring(0, eq)] = line.substring(eq + 1)
        }
        val sampleRate = values["sample_rate"]?.toIntOrNull() ?: return null
        val channels = values["channels"]?.toIntOrNull() ?: return null
        val duration = values["duration"]?.toDoubleOrNull() ?: 0.0
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
    tempPng.deleteOnExit()
    var inputFile: File? = null
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
        val process = ProcessBuilder(
            listOf(FfmpegLocator.ffmpegPath(), "-y") + rawInputArgs + windowArgs + listOf(
                "-i", resolvedInputFile.absolutePath,
                "-lavfi", filter, "-frames:v", "1", tempPng.absolutePath,
            ),
        ).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD)
            .also { FfmpegLocator.configureEnvironment(it) }.start()
        val finished = process.waitFor(AUDIO_VISUAL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        if (!finished) {
            process.destroyForcibly()
            null
        } else if (process.exitValue() != 0 || tempPng.length() == 0L) {
            null
        } else {
            Image.makeFromEncoded(tempPng.readBytes()).toComposeImageBitmap()
        }
    } catch (e: Exception) {
        null
    } finally {
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

private const val SPECTROGRAM_RESIZE_DEBOUNCE_MS = 400L

// Both scale with the CURRENT visible duration rather than being a fixed number of seconds per
// scroll unit, so zoom/pan feel consistent whether the view is showing the whole track or one
// second of it -- a fixed-seconds step would feel glacial zoomed out and twitchy zoomed in.
private const val ZOOM_STEP_FACTOR = 0.08
private const val PAN_STEP_FACTOR = 0.05

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun FfmpegAudioPlayer(file: File, rawAudioParams: RawAudioParams? = null, modifier: Modifier = Modifier) {
    var isPlaying by remember(file) { mutableStateOf(false) }
    var hasEnded by remember(file) { mutableStateOf(false) }
    var restartTrigger by remember(file) { mutableStateOf(0) }
    var playedSeconds by remember(file) { mutableStateOf(0.0) }
    var startFromSeconds by remember(file) { mutableStateOf(0.0) }
    var loadError by remember(file) { mutableStateOf(false) }

    var probedInfo by remember(file) { mutableStateOf<AudioFileInfo?>(null) }
    var probing by remember(file) { mutableStateOf(true) }
    var waveformPeaks by remember(file) { mutableStateOf<WaveformPeaks?>(null) }
    var spectrogramBitmap by remember(file) { mutableStateOf<ImageBitmap?>(null) }

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
            waveformPeaks = withContext(Dispatchers.IO) { computeWaveformPeaks(file, info, rawAudioParams = rawAudioParams) }
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

    DisposableEffect(file, restartTrigger) {
        playedSeconds = 0.0
        val seekSeconds = startFromSeconds
        val seekArgs = if (seekSeconds > 0.0) listOf("-ss", seekSeconds.toString()) else emptyList()
        val sampleRate = info.sampleRate
        val channels = info.channels
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
                    "-f", "s16le", "-ar", sampleRate.toString(), "-ac", channels.toString(),
                    "-acodec", "pcm_s16le", "-",
                ),
            ).also { FfmpegLocator.configureEnvironment(it) }.start()
        } catch (e: Exception) {
            null
        }
        if (process == null) loadError = true

        val stopped = AtomicBoolean(false)
        val format = AudioFormat(sampleRate.toFloat(), 16, channels, true, false)
        val bytesPerSecond = sampleRate * channels * 2

        val readerThread = if (process != null) {
            Thread {
                var line: SourceDataLine? = null
                try {
                    line = AudioSystem.getSourceDataLine(format)
                    line.open(format)
                    line.start()
                    var wasPlaying = true
                    val buffer = ByteArray(8192)
                    val input = process.inputStream
                    while (!stopped.get()) {
                        if (!isPlaying) {
                            if (wasPlaying) {
                                line.stop()
                                wasPlaying = false
                            }
                            Thread.sleep(50)
                            continue
                        }
                        if (!wasPlaying) {
                            line.start()
                            wasPlaying = true
                        }
                        val bytesRead = input.read(buffer)
                        if (bytesRead < 0) {
                            EventQueue.invokeLater {
                                isPlaying = false
                                hasEnded = true
                            }
                            break
                        }
                        line.write(buffer, 0, bytesRead)
                        val secondsThisChunk = bytesRead.toDouble() / bytesPerSecond
                        EventQueue.invokeLater { playedSeconds += secondsThisChunk }
                    }
                } catch (e: InterruptedException) {
                    // Expected on dispose -- not an error.
                } catch (e: Exception) {
                    System.err.println("FfmpegAudioPlayer reader thread failed: $e")
                } finally {
                    line?.stop()
                    line?.flush()
                    line?.close()
                }
            }.apply { isDaemon = true }.also { it.start() }
        } else {
            null
        }

        onDispose {
            stopped.set(true)
            readerThread?.interrupt()
            process?.destroyForcibly()
            if (inputFile != file) inputFile.delete()
        }
    }

    var waveformSplit by remember(file) { mutableStateOf(0.6f) }
    var containerHeightPx by remember(file) { mutableStateOf(0) }
    var spectrogramBoxSize by remember(file) { mutableStateOf(IntSize.Zero) }
    val elapsedSeconds = (startFromSeconds + playedSeconds).coerceIn(0.0, if (info.duration > 0) info.duration else Double.MAX_VALUE)

    // Shared by the waveform, spectrogram, scrollbar, and minimap -- fully zoomed out (the whole
    // track) whenever a new file loads, same as today's pre-zoom behavior.
    var visibleWindow by remember(file) { mutableStateOf(AudioViewWindow(0.0, info.duration)) }

    fun applyZoomOrPan(scrollDeltaX: Float, scrollDeltaY: Float) {
        visibleWindow = if (scrollDeltaX != 0f) {
            // Two-finger trackpad horizontal scroll -- pan.
            clampWindow(
                visibleWindow.startSeconds + scrollDeltaX.toDouble() * PAN_STEP_FACTOR * visibleWindow.durationSeconds,
                visibleWindow.durationSeconds,
                info.duration,
            )
        } else {
            // Mouse wheel / trackpad vertical scroll -- zoom. Compose reports a NEGATIVE
            // scrollDelta.y when scrolling up/away (confirmed by GopAnalysisView's own existing
            // zoom code and comment), and scroll-up conventionally means "zoom in" -- zooming in
            // means a SMALLER visible duration (narrower time window), the opposite relationship
            // GopAnalysisView has (there, scroll-up grows a pixel width). So the sign here is
            // deliberately `+`, not `-`: factor = 1.0 + scrollDeltaY * ZOOM_STEP_FACTOR gives
            // factor < 1 (duration shrinks) when scrollDeltaY is negative (scroll up), and
            // factor > 1 (duration grows) when scrolling down.
            clampWindow(
                visibleWindow.startSeconds,
                visibleWindow.durationSeconds * (1.0 + scrollDeltaY.toDouble() * ZOOM_STEP_FACTOR),
                info.duration,
            )
        }
    }

    // Regenerates the spectrogram for the current visible window at the panel's actual pixel
    // size, debounced so a drag resize or a burst of zoom/pan scroll events doesn't spawn ffmpeg
    // on every intermediate frame -- LaunchedEffect's key-change semantics cancel the previous
    // coroutine and start a fresh one on every change, so only the last window/size that survives
    // the delay without being superseded actually triggers a regeneration. The old bitmap (if
    // any) stays visible via contentScale = FillBounds until the new one is ready, so there's no
    // flicker or blank flash mid-resize or mid-zoom.
    LaunchedEffect(file, spectrogramBoxSize, visibleWindow) {
        val boxSize = spectrogramBoxSize
        if (boxSize.width <= 0 || boxSize.height <= 0) return@LaunchedEffect
        delay(SPECTROGRAM_RESIZE_DEBOUNCE_MS)
        val newBitmap = withContext(Dispatchers.IO) {
            generateSpectrogramImage(file, boxSize.width, boxSize.height, rawAudioParams = rawAudioParams, window = visibleWindow)
        }
        if (newBitmap != null) spectrogramBitmap = newBitmap
    }

    fun seekToFraction(fraction: Float) {
        hasEnded = false
        isPlaying = false
        startFromSeconds = fraction.coerceIn(0f, 1f) * info.duration
        restartTrigger++
    }

    Column(modifier = modifier.fillMaxSize().onGloballyPositioned { containerHeightPx = it.size.height }) {
        Box(
            modifier = Modifier
                .weight(waveformSplit)
                .fillMaxWidth()
                .background(Color.Black)
                .onPointerEvent(PointerEventType.Scroll, pass = PointerEventPass.Initial) { event ->
                    val delta = event.changes.firstOrNull()?.scrollDelta ?: return@onPointerEvent
                    applyZoomOrPan(delta.x, delta.y)
                    event.changes.forEach { it.consume() }
                }
                .pointerInput(info.duration) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        seekToFraction(down.position.x / size.width.toFloat())
                        drag(down.id) { change ->
                            change.consume()
                            seekToFraction(change.position.x / size.width.toFloat())
                        }
                    }
                },
        ) {
            val peaks = waveformPeaks
            if (loadError) {
                Text("Could not start ffmpeg playback", color = Color.White, modifier = Modifier.align(Alignment.Center))
            } else if (peaks != null) {
                val visibleRange = visibleBucketRange(visibleWindow, info.duration, peaks.bucketCount)
                WaveformDisplay(peaks = peaks, color = Color(0xFF39FF14), visibleRange = visibleRange, modifier = Modifier.fillMaxSize())
            } else {
                DecodingIndicator("파형 생성 중...", modifier = Modifier.align(Alignment.Center))
            }

            if (info.duration > 0) {
                // Progress fill is relative to the VISIBLE window now, not the whole track -- when
                // the playhead is outside the current zoom window, this fraction clamps to 0 or 1
                // (fill empty or full) rather than pointing somewhere meaningless off-screen.
                val windowProgress = ((elapsedSeconds - visibleWindow.startSeconds) / visibleWindow.durationSeconds)
                    .toFloat().coerceIn(0f, 1f)
                Box(modifier = Modifier.align(Alignment.CenterStart).fillMaxHeight().fillMaxWidth(windowProgress)) {
                    Box(modifier = Modifier.align(Alignment.CenterEnd).width(2.dp).fillMaxHeight().background(Color.White))
                }
                PreviewCaption(
                    "${formatMmSs(elapsedSeconds)} / ${formatMmSs(info.duration)}",
                    modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp),
                )
            }

            if (!isPlaying) {
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.5f))
                        .clickable {
                            if (hasEnded) {
                                hasEnded = false
                                startFromSeconds = 0.0
                                restartTrigger++
                            }
                            isPlaying = true
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = "Play", tint = Color.White, modifier = Modifier.size(48.dp))
                }
            } else {
                Box(modifier = Modifier.fillMaxSize().clickable { isPlaying = false })
            }
        }

        DraggableDivider(
            orientation = Orientation.Horizontal,
            containerSizePx = containerHeightPx,
            getSplit = { waveformSplit },
            setSplit = { waveformSplit = it },
        )

        Box(
            modifier = Modifier
                .weight(1f - waveformSplit)
                .fillMaxWidth()
                .background(Color.Black)
                .onPointerEvent(PointerEventType.Scroll, pass = PointerEventPass.Initial) { event ->
                    val delta = event.changes.firstOrNull()?.scrollDelta ?: return@onPointerEvent
                    applyZoomOrPan(delta.x, delta.y)
                    event.changes.forEach { it.consume() }
                }
                .onGloballyPositioned { spectrogramBoxSize = it.size },
        ) {
            val spectrogram = spectrogramBitmap
            if (spectrogram != null) {
                Image(bitmap = spectrogram, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
            } else {
                DecodingIndicator("스펙트로그램 생성 중...", modifier = Modifier.align(Alignment.Center))
            }
            if (info.duration > 0) {
                val windowProgress = ((elapsedSeconds - visibleWindow.startSeconds) / visibleWindow.durationSeconds)
                    .toFloat().coerceIn(0f, 1f)
                Box(modifier = Modifier.align(Alignment.CenterStart).fillMaxHeight().fillMaxWidth(windowProgress)) {
                    Box(modifier = Modifier.align(Alignment.CenterEnd).width(2.dp).fillMaxHeight().background(Color.White))
                }
            }
        }

        AudioZoomScrollbar(
            window = visibleWindow,
            totalDuration = info.duration,
            onWindowChange = { visibleWindow = it },
            modifier = Modifier.padding(top = 2.dp),
        )

        AudioMinimap(
            peaks = waveformPeaks,
            window = visibleWindow,
            totalDuration = info.duration,
            elapsedSeconds = elapsedSeconds,
            onWindowChange = { visibleWindow = it },
            onSeek = { fraction -> seekToFraction(fraction) },
            modifier = Modifier.padding(top = 2.dp),
        )
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
                        onWindowChange(clampWindow(window.startSeconds + deltaSeconds, window.durationSeconds, totalDuration))
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
