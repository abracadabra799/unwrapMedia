package com.multiviewer.ui

import androidx.compose.ui.graphics.ImageBitmap
import java.awt.EventQueue
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

import java.util.concurrent.Future

// Dedicated thread pool for full-size frame decodes -- prevents blocking the UI or competing with
// thumbnail decoders, and reuses threads across requests.
private val fullSizeDecoderExecutor = Executors.newFixedThreadPool(4) { runnable ->
    Thread(runnable).apply { isDaemon = true }
}

/** One ffmpeg invocation (output path appended by the snapshot decoder) and its output extension. */
data class FrameDecodeAttempt(val args: List<String>, val extension: String)

// [seconds] is relative to the file's start (format start_time). A stream that starts at 0 keeps the
// original fast attempts (measured exact there). Otherwise `-ss` before -i lands on another frame or
// none at all (MPEG-TS, measured: docs/validation/video-frame-fixtures), so one exact absolute-time
// attempt is used instead -- see exactFrameArgs.
fun frameDecodeAttempts(ffmpeg: String, filePath: String, seconds: Double, startTime: Double): List<FrameDecodeAttempt> =
    if (kotlin.math.abs(startTime) < 0.001) {
        listOf(
            // Attempt 1: Fast Seek before -i (hardware accelerated)
            FrameDecodeAttempt(
                listOf(ffmpeg, "-y", "-hwaccel", "auto", "-ss", seconds.toString(), "-i", filePath,
                    "-frames:v", "1", "-q:v", "2", "-update", "1"),
                ".jpg",
            ),
            // Attempt 2: Precise Seek fallback (-ss after -i) if fast seek returned empty/failed
            FrameDecodeAttempt(
                listOf(ffmpeg, "-y", "-i", filePath, "-ss", seconds.toString(),
                    "-frames:v", "1", "-q:v", "2", "-update", "1"),
                ".jpg",
            ),
        )
    } else {
        listOf(FrameDecodeAttempt(exactFrameArgs(ffmpeg, filePath, seconds + startTime, startTime), ".png"))
    }

// Fast accurate-seek single-frame extraction at native resolution with hardware acceleration.
// Uses fast seek (-ss placed BEFORE -i) to immediately jump to the target timestamp without
// sequentially decoding all preceding frames, combined with JPEG (-q:v 2) for rapid Skia rendering.
object FrameFullSizeDecoder {
    // start_time per file version: one ffprobe per file instead of one per frame while scrubbing.
    private val startTimes = ConcurrentHashMap<String, Double>()

    private fun startTimeOf(file: File): Double {
        val key = "${file.absolutePath}|${file.length()}|${file.lastModified()}"
        startTimes[key]?.let { return it }
        return probeFrameStreamInfo(file).startTime.also { startTimes[key] = it }
    }

    private fun decode(file: File, seconds: Double, startTime: Double): ImageBitmap? {
        for (attempt in frameDecodeAttempts(FfmpegLocator.ffmpegPath(), file.absolutePath, seconds, startTime)) {
            if (Thread.currentThread().isInterrupted) return null
            FfmpegImageSnapshotDecoder.decodeSingleFrameToBitmap(attempt.args, tempExtension = attempt.extension, timeoutMs = 5000L)
                ?.let { return it }
        }
        return null
    }

    /** Blocking: the frame at [seconds] from the file's start (format start_time). */
    fun decodeFrameBlocking(file: File, seconds: Double): ImageBitmap? = decode(file, seconds, startTimeOf(file))

    /** Blocking: the frame at an absolute stream PTS (ffprobe frame=pts_time, as FrameInfo carries it). */
    fun decodeFrameAtPtsBlocking(file: File, ptsSeconds: Double): ImageBitmap? {
        val start = startTimeOf(file)
        return decode(file, (ptsSeconds - start).coerceAtLeast(0.0), start)
    }

    /** [seconds] is relative to the file's start (a timeline position, e.g. the compare window's clock). */
    fun decodeFrameAsync(file: File, seconds: Double, onResult: (ImageBitmap?) -> Unit): Future<*>? =
        submit(onResult) { decodeFrameBlocking(file, seconds) }

    /** [ptsSeconds] is an absolute stream PTS, as a selected frame carries it. */
    fun decodeFrameAtPtsAsync(file: File, ptsSeconds: Double, onResult: (ImageBitmap?) -> Unit): Future<*>? =
        submit(onResult) { decodeFrameAtPtsBlocking(file, ptsSeconds) }

    private fun submit(onResult: (ImageBitmap?) -> Unit, work: () -> ImageBitmap?): Future<*>? =
        submitDiscardingCancelled(fullSizeDecoderExecutor) {
            val result = work()
            if (!Thread.currentThread().isInterrupted) {
                EventQueue.invokeLater { onResult(result) }
            }
        }
}
