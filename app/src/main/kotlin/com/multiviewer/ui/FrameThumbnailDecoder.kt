package com.multiviewer.ui

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Image
import java.awt.EventQueue
import java.io.File
import java.util.concurrent.TimeUnit

private const val THUMBNAIL_DECODE_WIDTH_PX = 120
private const val BATCH_TIMEOUT_MS = 15000L

// Pure, unit-testable: decides what (if anything) needs fetching, given the currently-visible
// frame index range and what's already cached or has an in-flight request. Expands the visible
// range by prefetchMargin on both sides for smoother scrolling (thumbnails just off-screen are
// ready before they're scrolled into view), then clamps to the valid [0, frameCount - 1] index
// range. Returns the span from the lowest to the highest MISSING index in that expanded range --
// not necessarily minimal if the missing indices aren't contiguous (may re-request a few already-
// cached frames in between), which keeps this simple: one batch ffmpeg call per trigger rather
// than splitting into multiple sub-ranges around gaps.
fun missingThumbnailRange(
    visibleRange: IntRange,
    prefetchMargin: Int,
    frameCount: Int,
    alreadyCachedOrPending: Set<Int>,
): IntRange? {
    if (visibleRange.isEmpty() || frameCount <= 0) return null
    val expandedFirst = (visibleRange.first - prefetchMargin).coerceIn(0, frameCount - 1)
    val expandedLast = (visibleRange.last + prefetchMargin).coerceIn(0, frameCount - 1)
    val missing = (expandedFirst..expandedLast).filter { it !in alreadyCachedOrPending }
    if (missing.isEmpty()) return null
    return missing.min()..missing.max()
}

// Reuses the same "run ffmpeg -> read output -> Skia decode -> cleanup" shape
// FfmpegImageSnapshotDecoder.decodeSingleFrameToBitmap already establishes, extended to a batch of
// N sequential frames from one accurate-seek point instead of one frame -- verified directly that
// -ss placed AFTER -i (accurate seek) combined with -frames:v <count> produces exactly that many
// sequential frames starting at the seek point, in presentation order, in one ffmpeg call.
object FrameThumbnailDecoder {
    fun decodeRangeAsync(file: File, startIndex: Int, startPtsSeconds: Double, count: Int, onResult: (Map<Int, ImageBitmap>) -> Unit) {
        Thread {
            val bitmaps = decodeRangeToBitmaps(file, startPtsSeconds, count)
            val result = bitmaps.mapIndexed { offset, bitmap -> (startIndex + offset) to bitmap }.toMap()
            EventQueue.invokeLater { onResult(result) }
        }.apply { isDaemon = true }.start()
    }

    // Frames that fail to decode (or fewer output files than requested, e.g. near end of file)
    // are simply absent from the returned list rather than represented as null entries -- the
    // caller's index mapping (decodeRangeAsync above) then naturally omits those indices from the
    // result map instead of caching a null placeholder for them.
    private fun decodeRangeToBitmaps(file: File, startPtsSeconds: Double, count: Int): List<ImageBitmap> {
        val tempDir = try {
            File.createTempFile("frame-thumbnails-", "").apply {
                delete()
                mkdir()
            }
        } catch (e: Exception) {
            return emptyList()
        }
        return try {
            val process = ProcessBuilder(
                FfmpegLocator.ffmpegPath(), "-y", "-i", file.absolutePath,
                "-ss", startPtsSeconds.toString(),
                "-frames:v", count.toString(),
                "-vf", "scale=$THUMBNAIL_DECODE_WIDTH_PX:-1",
                "-vsync", "0",
                File(tempDir, "thumb_%05d.png").absolutePath,
            ).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .also { FfmpegLocator.configureEnvironment(it) }
                .start()
            val finished = process.waitFor(BATCH_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()
                return emptyList()
            }
            tempDir.listFiles { f -> f.name.startsWith("thumb_") }
                ?.sortedBy { it.name }
                ?.mapNotNull { pngFile ->
                    try {
                        Image.makeFromEncoded(pngFile.readBytes()).toComposeImageBitmap()
                    } catch (e: Exception) {
                        null
                    }
                }
                ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
