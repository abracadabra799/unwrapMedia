package com.multiviewer.parser

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Codec
import org.jetbrains.skia.Data
import java.io.File

const val MAX_GIF_FRAMES = 500
private const val MAX_GIF_DECODED_BYTES = 32L * 1024 * 1024

data class GifAnimationData(
    val frames: List<ImageBitmap>,
    val durationsMs: List<Int>,
    val loopCount: Int,
    val totalFrameCount: Int,
    val truncated: Boolean,
)

// Decodes every frame of an animated GIF (or the single frame of a static one) via Skia's
// multi-frame Codec API, which -- unlike Image.makeFromEncoded's single-frame decode used
// elsewhere in this file for the static fallback path -- handles GIF's per-frame disposal method
// and transparency compositing internally, so frame N's pixels already reflect whatever the GIF's
// own compositing rules say frame N should look like. Each frame gets its own freshly allocated
// Bitmap (no buffer reuse across frames) so there's no risk of one frame's pixel storage being
// mutated out from under an already-captured ImageBitmap by a later loop iteration. Returns null
// if the file can't be decoded as an image at all (corrupt bytes, wrong format) -- callers should
// fall back to the existing single-frame decodePrimaryBitmapAndHistogram path in that case.
fun decodeGifAnimation(
    file: File,
    maxFrames: Int = MAX_GIF_FRAMES,
    maxDecodedBytes: Long = MAX_GIF_DECODED_BYTES,
): GifAnimationData? {
    if (maxFrames <= 0 || maxDecodedBytes <= 0) return null
    val frames = mutableListOf<ImageBitmap>()
    var delivered = false
    return try {
        if (Thread.currentThread().isInterrupted) throw InterruptedException("GIF decode cancelled")
        Data.makeFromBytes(file.readBytes()).use { data ->
            Codec.makeFromData(data).use { codec ->
                val totalFrameCount = codec.frameCount
                val framesInfo = codec.framesInfo
                val info = codec.imageInfo
                val frameBytes = info.width.toLong() * info.height * 4
                if (frameBytes <= 0 || frameBytes > maxDecodedBytes) return null
                val decodedCount = minOf(totalFrameCount.coerceAtLeast(1).toLong(), maxFrames.toLong(), maxDecodedBytes / frameBytes).toInt()
                val durationsMs = mutableListOf<Int>()
                for (index in 0 until decodedCount) {
                    if (Thread.currentThread().isInterrupted) throw InterruptedException("GIF decode cancelled")
                    val bitmap = Bitmap()
                    try {
                        if (!bitmap.allocPixels(info)) { bitmap.close(); return null }
                        codec.readPixels(bitmap, index)
                        // Transfer ownership directly to Compose, avoiding a second full-frame copy.
                        frames.add(bitmap.asComposeImageBitmap())
                    } catch (e: Exception) {
                        bitmap.close()
                        throw e
                    }
                    durationsMs.add(if (index < framesInfo.size) framesInfo[index].duration else 0)
                }
                GifAnimationData(frames, durationsMs, codec.repetitionCount, totalFrameCount, totalFrameCount > decodedCount)
                    .also { delivered = true }
            }
        }
    } catch (e: InterruptedException) {
        throw e
    } catch (e: Exception) {
        null
    } finally {
        if (!delivered) frames.forEach { it.asSkiaBitmap().close() }
    }
}
