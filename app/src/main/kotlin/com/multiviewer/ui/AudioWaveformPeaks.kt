package com.multiviewer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp
import java.io.File

data class ChannelPeaks(val min: FloatArray, val max: FloatArray)
data class WaveformPeaks(val channelCount: Int, val bucketCount: Int, val channels: List<ChannelPeaks>)

// Far more buckets than any realistic panel width in pixels -- the Canvas renderer just redraws
// the same bucket values at new pixel positions on resize, no recomputation ever needed.
const val WAVEFORM_PEAK_BUCKET_COUNT = 4096

// Maps a time window onto an index range within a bucket array of the given size -- since
// computeWaveformPeaks already spaces its buckets evenly across the whole file duration, this is
// pure arithmetic, no new peak computation needed to redraw a zoomed-in sub-range.
fun visibleBucketRange(window: AudioViewWindow, totalDuration: Double, bucketCount: Int): IntRange {
    if (totalDuration <= 0.0 || bucketCount <= 0) return 0..0
    val startFraction = (window.startSeconds / totalDuration).coerceIn(0.0, 1.0)
    val endFraction = ((window.startSeconds + window.durationSeconds) / totalDuration).coerceIn(0.0, 1.0)
    val startBucket = (startFraction * bucketCount).toInt().coerceIn(0, bucketCount - 1)
    val endBucket = (endFraction * bucketCount).toInt().coerceIn(startBucket + 1, bucketCount)
    return startBucket until endBucket
}

// Streams the same ffmpeg PCM pipe already used for playback (see FfmpegAudioPlayer's
// DisposableEffect) to compute per-channel min/max amplitude peaks into a fixed-size bucket
// array, without holding the whole decoded file in memory (a multi-hour recording could
// otherwise use hundreds of MB). Frame boundaries don't align with arbitrary read-buffer
// boundaries, so leftover bytes from an incomplete trailing frame are carried into the next read.
fun computeWaveformPeaks(
    file: File,
    info: AudioFileInfo,
    bucketCount: Int = WAVEFORM_PEAK_BUCKET_COUNT,
    rawAudioParams: RawAudioParams? = null,
): WaveformPeaks? {
    val channels = info.channels
    if (channels <= 0 || bucketCount <= 0) return null

    val frameSizeBytes = channels * 2
    val estimatedTotalFrames = (info.duration * info.sampleRate).toLong().coerceAtLeast(1L)
    val framesPerBucket = (estimatedTotalFrames / bucketCount).coerceAtLeast(1L)

    val minPerChannel = Array(channels) { FloatArray(bucketCount) { Float.MAX_VALUE } }
    val maxPerChannel = Array(channels) { FloatArray(bucketCount) { -Float.MAX_VALUE } }

    val inputFile = if (rawAudioParams != null) rawAudioSourceFile(file, rawAudioParams.offsetBytes) else file
    val rawInputArgs = if (rawAudioParams != null) {
        listOf("-f", rawAudioParams.ffmpegFormatCode(), "-ar", rawAudioParams.sampleRate.toString(), "-ac", rawAudioParams.channels.toString())
    } else {
        emptyList()
    }
    val process = try {
        ProcessBuilder(
            listOf(FfmpegLocator.ffmpegPath()) + rawInputArgs + listOf(
                "-i", inputFile.absolutePath, "-map", "0:a:0",
                "-f", "s16le", "-ar", info.sampleRate.toString(), "-ac", channels.toString(),
                "-acodec", "pcm_s16le", "-",
            ),
        ).redirectError(ProcessBuilder.Redirect.DISCARD)
            .also { FfmpegLocator.configureEnvironment(it) }.start()
    } catch (e: Exception) {
        if (inputFile != file) inputFile.delete()
        return null
    }

    return try {
        val input = process.inputStream
        val readBuffer = ByteArray(65536)
        var carry = ByteArray(0)
        var frameIndex = 0L

        val completedFrameCount = readProcessOutputWithTimeout(process, 30) {
            while (true) {
                val bytesRead = input.read(readBuffer)
                if (bytesRead < 0) break
                val chunk = if (carry.isEmpty()) readBuffer.copyOf(bytesRead) else carry + readBuffer.copyOf(bytesRead)
                val usableFrames = chunk.size / frameSizeBytes
                val usableBytes = usableFrames * frameSizeBytes
                var offset = 0
                repeat(usableFrames) {
                    val bucket = (frameIndex / framesPerBucket).coerceAtMost((bucketCount - 1).toLong()).toInt()
                    for (c in 0 until channels) {
                        val sample = (((chunk[offset + 1].toInt() shl 8) or (chunk[offset].toInt() and 0xFF))).toShort().toFloat() / 32768f
                        if (sample < minPerChannel[c][bucket]) minPerChannel[c][bucket] = sample
                        if (sample > maxPerChannel[c][bucket]) maxPerChannel[c][bucket] = sample
                        offset += 2
                    }
                    frameIndex++
                }
                carry = chunk.copyOfRange(usableBytes, chunk.size)
            }
            frameIndex
        }

        if (completedFrameCount == null || completedFrameCount == 0L) {
            null
        } else {
            for (c in 0 until channels) {
                for (b in 0 until bucketCount) {
                    if (minPerChannel[c][b] == Float.MAX_VALUE) {
                        minPerChannel[c][b] = 0f
                        maxPerChannel[c][b] = 0f
                    }
                }
            }
            WaveformPeaks(
                channelCount = channels,
                bucketCount = bucketCount,
                channels = (0 until channels).map { ChannelPeaks(minPerChannel[it], maxPerChannel[it]) },
            )
        }
    } catch (e: Exception) {
        null
    } finally {
        process.destroyForcibly()
        if (inputFile != file) inputFile.delete()
    }
}

// channelCount >= 2 stacks channel 0 (L) above channel 1 (R); any channels beyond the first two
// are ignored (surround audio is out of scope). channelCount == 1 draws a single full-size Canvas.
@Composable
fun WaveformDisplay(peaks: WaveformPeaks, color: Color, visibleRange: IntRange, modifier: Modifier = Modifier) {
    val displayChannels = peaks.channels.take(2)
    if (displayChannels.size >= 2) {
        Column(modifier = modifier) {
            WaveformChannelCanvas(displayChannels[0], color, visibleRange, Modifier.weight(1f).fillMaxWidth())
            WaveformChannelCanvas(displayChannels[1], color, visibleRange, Modifier.weight(1f).fillMaxWidth())
        }
    } else if (displayChannels.size == 1) {
        WaveformChannelCanvas(displayChannels[0], color, visibleRange, modifier.fillMaxSize())
    }
}

@Composable
private fun WaveformChannelCanvas(peaks: ChannelPeaks, color: Color, visibleRange: IntRange, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        drawChannelPeaks(peaks, color, visibleRange)
    }
}

private fun DrawScope.drawChannelPeaks(peaks: ChannelPeaks, color: Color, visibleRange: IntRange) {
    val width = size.width
    val height = size.height
    val centerY = height / 2f
    val startBucket = visibleRange.first
    val visibleCount = visibleRange.last - visibleRange.first + 1
    if (visibleCount <= 0 || width <= 0f) return
    val strokeWidthPx = 1.5.dp.toPx()
    for (i in visibleRange) {
        val x = width * (i - startBucket) / visibleCount
        val yTop = centerY - peaks.max[i] * centerY
        val yBottom = centerY - peaks.min[i] * centerY
        drawLine(color = color, start = Offset(x, yTop), end = Offset(x, yBottom), strokeWidth = strokeWidthPx)
    }
}
