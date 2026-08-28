package com.multiviewer.ui

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Image
import java.awt.EventQueue
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private const val THUMBNAIL_DECODE_WIDTH_PX = 120
private const val BATCH_TIMEOUT_MS = 15000L
private const val THUMB_FILENAME_PREFIX = "thumb_"
private const val THUMB_FILENAME_SUFFIX = ".jpg"

// Thread pool for thumbnail batch decoding -- limits concurrency to prevent overloading the CPU
// while reusing worker threads across filmstrip scroll events.
private val thumbnailDecoderExecutor = Executors.newFixedThreadPool(4) { runnable ->
    Thread(runnable).apply { isDaemon = true }
}

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

// High-speed streaming thumbnail extractor using fast seek (-ss before -i) and GPU hardware acceleration.
// Uses high-quality JPEG output (-q:v 2) for fast encoding & Skia decoding, and polls the output directory
// to display each thumbnail progressively in real-time as it is decoded rather than waiting for the whole batch.
object FrameThumbnailDecoder {
    fun decodeRangeAsync(
        file: File,
        startIndex: Int,
        startPtsSeconds: Double,
        count: Int,
        onProgress: (Map<Int, ImageBitmap>) -> Unit,
    ) {
        thumbnailDecoderExecutor.submit {
            decodeRangeStreaming(file, startIndex, startPtsSeconds, count) { partialMap ->
                EventQueue.invokeLater { onProgress(partialMap) }
            }
        }
    }

    // Keyed by global frame index (startIndex + offset), parsed from each output file's own "thumb_%05d"
    // number. As ffmpeg writes each frame's JPEG, it is immediately decoded to ImageBitmap, emitted to
    // the UI, and deleted from disk for minimal latency and memory footprint.
    private fun decodeRangeStreaming(
        file: File,
        startIndex: Int,
        startPtsSeconds: Double,
        count: Int,
        onBatchDecoded: (Map<Int, ImageBitmap>) -> Unit,
    ) {
        val tempDir = try {
            Files.createTempDirectory("frame-thumbnails-").toFile().apply { deleteOnExit() }
        } catch (e: Exception) {
            return
        }

        try {
            val process = ProcessBuilder(
                FfmpegLocator.ffmpegPath(), "-y",
                "-hwaccel", "auto",
                "-ss", startPtsSeconds.toString(),
                "-i", file.absolutePath,
                "-frames:v", count.toString(),
                "-vf", "scale=$THUMBNAIL_DECODE_WIDTH_PX:-1",
                "-q:v", "2",
                "-vsync", "0",
                File(tempDir, "$THUMB_FILENAME_PREFIX%05d$THUMB_FILENAME_SUFFIX").absolutePath,
            ).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .also { FfmpegLocator.configureEnvironment(it) }
                .start()
                .also { com.multiviewer.util.ProcessManager.register(it) }

            val processedOffsets = mutableSetOf<Int>()
            val startTime = System.currentTimeMillis()

            fun scanAndEmitNewThumbnails() {
                val files = tempDir.listFiles { f -> f.name.startsWith(THUMB_FILENAME_PREFIX) && f.name.endsWith(THUMB_FILENAME_SUFFIX) }
                    ?: return
                val batch = mutableMapOf<Int, ImageBitmap>()
                for (thumbFile in files) {
                    val offset = thumbFile.name.removePrefix(THUMB_FILENAME_PREFIX).removeSuffix(THUMB_FILENAME_SUFFIX)
                        .toIntOrNull()?.minus(1) ?: continue
                    if (offset in processedOffsets) continue
                    if (thumbFile.length() <= 0) continue
                    try {
                        val bytes = thumbFile.readBytes()
                        if (bytes.isNotEmpty()) {
                            val bitmap = Image.makeFromEncoded(bytes).toComposeImageBitmap()
                            processedOffsets.add(offset)
                            batch[startIndex + offset] = bitmap
                            thumbFile.delete()
                        }
                    } catch (e: Exception) {
                        // File may still be being written by ffmpeg, retry on next poll tick
                    }
                }
                if (batch.isNotEmpty()) {
                    onBatchDecoded(batch)
                }
            }

            while (process.isAlive) {
                scanAndEmitNewThumbnails()
                if (System.currentTimeMillis() - startTime > BATCH_TIMEOUT_MS) {
                    com.multiviewer.util.ProcessManager.terminate(process)
                    break
                }
                try {
                    Thread.sleep(25)
                } catch (e: InterruptedException) {
                    com.multiviewer.util.ProcessManager.terminate(process)
                    break
                }
            }

            process.waitFor(2000, TimeUnit.MILLISECONDS)
            com.multiviewer.util.ProcessManager.unregister(process)

            // Final sweep to pick up any remaining frames finished just before process exit
            scanAndEmitNewThumbnails()
        } catch (e: Exception) {
            // ignore
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
