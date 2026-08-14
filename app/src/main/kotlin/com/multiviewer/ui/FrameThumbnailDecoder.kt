package com.multiviewer.ui

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Image
import java.awt.EventQueue
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

private const val THUMBNAIL_DECODE_WIDTH_PX = 120
private const val BATCH_TIMEOUT_MS = 15000L
private const val THUMB_FILENAME_PREFIX = "thumb_"
private const val THUMB_FILENAME_SUFFIX = ".png"

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
            val offsetToBitmap = decodeRangeToBitmaps(file, startPtsSeconds, count)
            val result = offsetToBitmap.mapKeys { (offset, _) -> startIndex + offset }
            EventQueue.invokeLater { onResult(result) }
        }.apply { isDaemon = true }.start()
    }

    // Keyed by OFFSET FROM startIndex (0-based), parsed from each output file's own "thumb_%05d"
    // number rather than from its position in a post-filter list -- if a file in the MIDDLE of the
    // batch fails to decode (corrupt/truncated PNG), a position-based index would silently shift
    // every later frame's offset down by one, mislabeling it as an earlier frame. Deriving the
    // offset from the filename itself means a mid-batch failure just leaves a gap at its own
    // correct offset instead of corrupting every offset after it. ffmpeg's %05d sequence starts at
    // 1, so offset = parsed number - 1.
    private fun decodeRangeToBitmaps(file: File, startPtsSeconds: Double, count: Int): Map<Int, ImageBitmap> {
        val tempDir = try {
            Files.createTempDirectory("frame-thumbnails-").toFile().apply { deleteOnExit() }
        } catch (e: Exception) {
            return emptyMap()
        }
        return try {
            val process = ProcessBuilder(
                FfmpegLocator.ffmpegPath(), "-y", "-i", file.absolutePath,
                "-ss", startPtsSeconds.toString(),
                "-frames:v", count.toString(),
                "-vf", "scale=$THUMBNAIL_DECODE_WIDTH_PX:-1",
                "-vsync", "0",
                File(tempDir, "$THUMB_FILENAME_PREFIX%05d$THUMB_FILENAME_SUFFIX").absolutePath,
            ).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .also { FfmpegLocator.configureEnvironment(it) }
                .start()
            val finished = process.waitFor(BATCH_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()
                return emptyMap()
            }
            if (process.exitValue() != 0) return emptyMap()
            tempDir.listFiles { f -> f.name.startsWith(THUMB_FILENAME_PREFIX) }
                ?.mapNotNull { pngFile ->
                    pngFile.deleteOnExit()
                    val offset = pngFile.name.removePrefix(THUMB_FILENAME_PREFIX).removeSuffix(THUMB_FILENAME_SUFFIX)
                        .toIntOrNull()?.minus(1) ?: return@mapNotNull null
                    try {
                        offset to Image.makeFromEncoded(pngFile.readBytes()).toComposeImageBitmap()
                    } catch (e: Exception) {
                        null
                    }
                }
                ?.toMap()
                ?: emptyMap()
        } catch (e: Exception) {
            emptyMap()
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
