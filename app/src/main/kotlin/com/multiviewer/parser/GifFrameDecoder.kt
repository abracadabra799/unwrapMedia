package com.multiviewer.parser

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Codec
import org.jetbrains.skia.Data
import org.jetbrains.skia.Image
import java.io.File

const val MAX_GIF_FRAMES = 500

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
fun decodeGifAnimation(file: File, maxFrames: Int = MAX_GIF_FRAMES): GifAnimationData? {
    return try {
        val codec = Codec.makeFromData(Data.makeFromBytes(file.readBytes()))
        val totalFrameCount = codec.frameCount
        val framesInfo = codec.framesInfo
        val decodedCount = minOf(totalFrameCount, maxFrames).coerceAtLeast(1)

        val frames = mutableListOf<ImageBitmap>()
        val durationsMs = mutableListOf<Int>()
        for (index in 0 until decodedCount) {
            val bitmap = Bitmap()
            bitmap.allocPixels(codec.imageInfo)
            codec.readPixels(bitmap, index)
            frames.add(Image.makeFromBitmap(bitmap).toComposeImageBitmap())
            durationsMs.add(if (index < framesInfo.size) framesInfo[index].duration else 0)
        }

        GifAnimationData(
            frames = frames,
            durationsMs = durationsMs,
            loopCount = codec.repetitionCount,
            totalFrameCount = totalFrameCount,
            truncated = totalFrameCount > decodedCount,
        )
    } catch (e: Exception) {
        null
    }
}
