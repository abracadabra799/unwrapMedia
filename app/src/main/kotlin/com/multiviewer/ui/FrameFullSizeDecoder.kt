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

// [seconds] is relative to the file's start (format start_time). Containers with a seek index keep the
// original fast attempts (measured exact for MP4/MOV, MKV/WebM, MPEG-PS, also with a start offset). In
// MPEG-TS `-ss` before -i lands on another frame or none at all (measured: docs/validation/
// video-frame-fixtures), so one exact absolute-time attempt is used instead -- see exactFrameArgs.
fun frameDecodeAttempts(ffmpeg: String, filePath: String, seconds: Double, info: FrameStreamInfo): List<FrameDecodeAttempt> =
    if (!info.needsExactSeek) {
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
        listOf(FrameDecodeAttempt(exactFrameArgs(ffmpeg, filePath, seconds + info.startTime, info.startTime), ".png"))
    }

// Fast accurate-seek single-frame extraction at native resolution with hardware acceleration.
// Uses fast seek (-ss placed BEFORE -i) to immediately jump to the target timestamp without
// sequentially decoding all preceding frames, combined with JPEG (-q:v 2) for rapid Skia rendering.
object FrameFullSizeDecoder {
    // Stream info per file version: one ffprobe per file instead of one per frame while scrubbing. A failed
    // probe is not cached (a transient lock or timeout must not pin a TS file to the inexact path).
    private val streamInfos = ConcurrentHashMap<String, FrameStreamInfo>()

    private fun streamInfoOf(file: File): FrameStreamInfo {
        val key = "${file.absolutePath}|${file.length()}|${file.lastModified()}"
        streamInfos[key]?.let { return it }
        return probeFrameStreamInfoOrNull(file)?.also { streamInfos[key] = it } ?: FrameStreamInfo(0.0, null)
    }

    private fun decode(file: File, seconds: Double, info: FrameStreamInfo): ImageBitmap? {
        for (attempt in frameDecodeAttempts(FfmpegLocator.ffmpegPath(), file.absolutePath, seconds, info)) {
            if (Thread.currentThread().isInterrupted) return null
            FfmpegImageSnapshotDecoder.decodeSingleFrameToBitmap(attempt.args, tempExtension = attempt.extension, timeoutMs = 5000L)
                ?.let { return it }
        }
        return null
    }

    /** Blocking: the frame at [seconds] from the file's start (format start_time). */
    fun decodeFrameBlocking(file: File, seconds: Double): ImageBitmap? = decode(file, seconds, streamInfoOf(file))

    /** Blocking: the frame at an absolute stream PTS (ffprobe frame=pts_time, as FrameInfo carries it). */
    fun decodeFrameAtPtsBlocking(file: File, ptsSeconds: Double): ImageBitmap? {
        val info = streamInfoOf(file)
        return decode(file, (ptsSeconds - info.startTime).coerceAtLeast(0.0), info)
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
