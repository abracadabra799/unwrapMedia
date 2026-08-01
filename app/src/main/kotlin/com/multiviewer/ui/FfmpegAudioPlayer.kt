package com.multiviewer.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
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
private fun renderAudioVisualization(file: File, filter: String): ImageBitmap? {
    val tempPng = try {
        File.createTempFile("audio-visual-", ".png")
    } catch (e: Exception) {
        return null
    }
    tempPng.deleteOnExit()
    return try {
        val process = ProcessBuilder(
            FfmpegLocator.ffmpegPath(), "-y", "-i", file.absolutePath,
            "-lavfi", filter, "-frames:v", "1", tempPng.absolutePath,
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
    }
}

// showspectrumpic doesn't honor its own s=WxH request precisely (measured: requesting 1200x300
// actually renders 1482x428) -- scale=W:H (no aspect-ratio preservation) forces the exact
// requested dimensions by stretching rather than letterboxing/pillarboxing, so the spectrogram
// content fills the image edge-to-edge with no black padding bars. This matters because the
// progress overlay assumes "image width == full duration" linearly; padding here would make the
// playhead visually misalign with the actual spectrogram content near both edges.
fun generateSpectrogramImage(file: File, width: Int, height: Int): ImageBitmap? =
    renderAudioVisualization(file, "showspectrumpic=s=${width}x${height},scale=${width}:${height}")

private const val SPECTROGRAM_RESIZE_DEBOUNCE_MS = 400L

@Composable
fun FfmpegAudioPlayer(file: File, modifier: Modifier = Modifier) {
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
        val info = withContext(Dispatchers.IO) { probeAudioFormat(file) }
        probedInfo = info
        probing = false
        if (info != null) {
            waveformPeaks = withContext(Dispatchers.IO) { computeWaveformPeaks(file, info) }
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
        val process = try {
            ProcessBuilder(
                listOf(FfmpegLocator.ffmpegPath()) + seekArgs + listOf(
                    "-i", file.absolutePath, "-map", "0:a:0",
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
        }
    }

    var waveformSplit by remember(file) { mutableStateOf(0.6f) }
    var containerHeightPx by remember(file) { mutableStateOf(0) }
    var spectrogramBoxSize by remember(file) { mutableStateOf(IntSize.Zero) }
    val elapsedSeconds = (startFromSeconds + playedSeconds).coerceIn(0.0, if (info.duration > 0) info.duration else Double.MAX_VALUE)

    // Regenerates the spectrogram at the panel's actual current pixel size, debounced so a drag
    // resize doesn't spawn ffmpeg on every intermediate frame -- LaunchedEffect's key-change
    // semantics cancel the previous coroutine and start a fresh one on every size change, so only
    // the last size that survives the delay without being superseded actually triggers a
    // regeneration. The old bitmap (if any) stays visible via contentScale = FillBounds until the
    // new one is ready, so there's no flicker or blank flash mid-resize.
    LaunchedEffect(file, spectrogramBoxSize) {
        val boxSize = spectrogramBoxSize
        if (boxSize.width <= 0 || boxSize.height <= 0) return@LaunchedEffect
        delay(SPECTROGRAM_RESIZE_DEBOUNCE_MS)
        val newBitmap = withContext(Dispatchers.IO) { generateSpectrogramImage(file, boxSize.width, boxSize.height) }
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
                WaveformDisplay(peaks = peaks, color = Color(0xFF39FF14), modifier = Modifier.fillMaxSize())
            } else {
                DecodingIndicator("파형 생성 중...", modifier = Modifier.align(Alignment.Center))
            }

            if (info.duration > 0) {
                val progress = (elapsedSeconds / info.duration).toFloat().coerceIn(0f, 1f)
                Box(modifier = Modifier.align(Alignment.CenterStart).fillMaxHeight().fillMaxWidth(progress)) {
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
                .onGloballyPositioned { spectrogramBoxSize = it.size },
        ) {
            val spectrogram = spectrogramBitmap
            if (spectrogram != null) {
                Image(bitmap = spectrogram, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
            } else {
                DecodingIndicator("스펙트로그램 생성 중...", modifier = Modifier.align(Alignment.Center))
            }
            if (info.duration > 0) {
                val progress = (elapsedSeconds / info.duration).toFloat().coerceIn(0f, 1f)
                Box(modifier = Modifier.align(Alignment.CenterStart).fillMaxHeight().fillMaxWidth(progress)) {
                    Box(modifier = Modifier.align(Alignment.CenterEnd).width(2.dp).fillMaxHeight().background(Color.White))
                }
            }
        }
    }
}
