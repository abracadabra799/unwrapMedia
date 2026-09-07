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

// Peak buckets scale with file length so a zoomed-in view (the player's default is a 5-second
// window) still has ~one bucket per screen pixel of real detail. 300 buckets/sec ~= 3.3 ms.
// Floored at the old fixed count and capped so a multi-hour file stays bounded (1.8M buckets ~=
// 14.4 MB of min+max floats per stored channel, so ~29 MB for the stereo pair that is the most
// computeWaveformPeaks ever keeps). The decode pass in computeWaveformPeaks reads every sample
// regardless of bucket count, so a finer array costs memory, not I/O.
fun waveformBucketCountFor(durationSeconds: Double): Int =
    (durationSeconds * 300.0).toInt().coerceIn(WAVEFORM_PEAK_BUCKET_COUNT, 1_800_000)

// One vertical span to draw at one screen x-pixel.
data class PeakColumn(val min: Float, val max: Float)

// Collapses the buckets in visibleRange down to at most targetColumns (min,max) spans -- one per
// screen pixel -- so the Canvas draws O(width) lines regardless of how many buckets the range
// spans. When the range already fits in targetColumns, each bucket is emitted as its own column
// unchanged. An all-silent span yields (0f, 0f).
//
// Streamed through a callback -- no List / no PeakColumn boxing -- for the per-frame Canvas hot
// path (drawChannelPeaks re-runs every frame during playback, once per channel). `columnIndex`
// runs 0 until `columnCount`, and `columnCount` is the same for every call within one invocation.
inline fun forEachPeakColumn(
    peaks: ChannelPeaks,
    visibleRange: IntRange,
    targetColumns: Int,
    action: (columnIndex: Int, columnCount: Int, min: Float, max: Float) -> Unit,
) {
    val first = visibleRange.first
    val last = visibleRange.last
    val count = last - first + 1
    if (count <= 0 || targetColumns <= 0) return
    val size = minOf(peaks.min.size, peaks.max.size)
    if (count <= targetColumns) {
        var col = 0
        for (i in first..last) {
            if (i in 0 until size) action(col, count, peaks.min[i], peaks.max[i]) else action(col, count, 0f, 0f)
            col++
        }
        return
    }
    for (col in 0 until targetColumns) {
        val lo = (first + (col.toLong() * count / targetColumns).toInt()).coerceAtLeast(0)
        val hi = (first + ((col + 1).toLong() * count / targetColumns).toInt()).coerceAtMost(size)
        var mn = Float.MAX_VALUE
        var mx = -Float.MAX_VALUE
        var b = lo
        while (b < hi) {
            if (peaks.min[b] < mn) mn = peaks.min[b]
            if (peaks.max[b] > mx) mx = peaks.max[b]
            b++
        }
        if (mn == Float.MAX_VALUE) action(col, targetColumns, 0f, 0f) else action(col, targetColumns, mn, mx)
    }
}

// List-returning form, built on forEachPeakColumn so there is one source of truth. Used by the
// memoized minimap (AudioMinimap.kt) and the tests, neither of which is a per-frame path.
fun downsamplePeaks(peaks: ChannelPeaks, visibleRange: IntRange, targetColumns: Int): List<PeakColumn> {
    val out = ArrayList<PeakColumn>()
    forEachPeakColumn(peaks, visibleRange, targetColumns) { _, _, mn, mx -> out.add(PeakColumn(mn, mx)) }
    return out
}

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

    // Every channel is still decoded and scanned (the interleave offset must stay correct), but
    // only the first two are ever drawn (WaveformDisplay takes 2, the minimap takes the first), so
    // we allocate peak arrays for just those -- a 5.1 file no longer holds ~57 MB it never reads.
    val storedChannels = minOf(channels, 2)
    val minPerChannel = Array(storedChannels) { FloatArray(bucketCount) { Float.MAX_VALUE } }
    val maxPerChannel = Array(storedChannels) { FloatArray(bucketCount) { -Float.MAX_VALUE } }

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
                        if (c < storedChannels) {
                            if (sample < minPerChannel[c][bucket]) minPerChannel[c][bucket] = sample
                            if (sample > maxPerChannel[c][bucket]) maxPerChannel[c][bucket] = sample
                        }
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
            for (c in 0 until storedChannels) {
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
                channels = (0 until storedChannels).map { ChannelPeaks(minPerChannel[it], maxPerChannel[it]) },
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
    if (width <= 0f) return
    val strokeWidthPx = 1.5.dp.toPx()
    // Allocation-free: forEachPeakColumn streams the columns instead of building a List every frame.
    forEachPeakColumn(peaks, visibleRange, width.toInt()) { idx, columnCount, mn, mx ->
        val x = width * idx / columnCount
        val yTop = centerY - mx * centerY
        val yBottom = centerY - mn * centerY
        drawLine(color = color, start = Offset(x, yTop), end = Offset(x, yBottom), strokeWidth = strokeWidthPx)
    }
}
