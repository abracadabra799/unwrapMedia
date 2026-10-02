package com.multiviewer.ui

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorInfo
import org.jetbrains.skia.ColorSpace
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import kotlin.math.floor

/** Downscales 8-bit planar 4:2:0 data before RGBA expansion to bound display-frame memory. */
fun Av2DecodedFrame.toDisplayFrame(maxEdge: Int = 1280): Av2DecodedFrame {
    require(maxEdge > 0) { "Maximum display edge must be positive" }
    require(format.bitDepth == 8) { "Only 8-bit Y4M conversion is supported" }
    val width = format.width
    val height = format.height
    if (maxOf(width, height) <= maxEdge) return this

    val scale = maxEdge.toDouble() / maxOf(width, height)
    val outWidth = maxOf(1, floor(width * scale).toInt())
    val outHeight = maxOf(1, floor(height * scale).toInt())
    val srcLumaSize = width.toLong() * height
    val srcChromaWidth = (width + 1) / 2
    val srcChromaHeight = (height + 1) / 2
    val srcChromaSize = srcChromaWidth.toLong() * srcChromaHeight
    require(srcLumaSize + srcChromaSize * 2 <= yuv420Payload.size) { "Y4M frame payload is truncated" }

    val outChromaWidth = (outWidth + 1) / 2
    val outChromaHeight = (outHeight + 1) / 2
    val outLumaSize = outWidth * outHeight
    val outChromaSize = outChromaWidth * outChromaHeight
    val scaled = ByteArray(outLumaSize + outChromaSize * 2)
    for (y in 0 until outHeight) {
        val sourceY = (y.toLong() * height / outHeight).toInt()
        for (x in 0 until outWidth) {
            val sourceX = (x.toLong() * width / outWidth).toInt()
            scaled[y * outWidth + x] = yuv420Payload[sourceY * width + sourceX]
        }
    }
    for (plane in 0..1) {
        val sourcePlaneStart = (srcLumaSize + plane * srcChromaSize).toInt()
        val targetPlaneStart = outLumaSize + plane * outChromaSize
        for (y in 0 until outChromaHeight) {
            val sourceY = (y.toLong() * srcChromaHeight / outChromaHeight).toInt()
            for (x in 0 until outChromaWidth) {
                val sourceX = (x.toLong() * srcChromaWidth / outChromaWidth).toInt()
                scaled[targetPlaneStart + y * outChromaWidth + x] =
                    yuv420Payload[sourcePlaneStart + sourceY * srcChromaWidth + sourceX]
            }
        }
    }
    return copy(
        format = format.copy(width = outWidth, height = outHeight),
        yuv420Payload = scaled,
    )
}

/** Converts a validated AV2 frame to the same Compose bitmap type used by the existing players. */
fun Av2DecodedFrame.toImageBitmap(): ImageBitmap {
    val displayFrame = toDisplayFrame()
    val rgba = yuv420ToRgba(displayFrame)
    val bitmap = Bitmap().apply {
        allocPixels(ImageInfo(ColorInfo(ColorType.RGBA_8888, ColorAlphaType.UNPREMUL, ColorSpace.sRGB), displayFrame.format.width, displayFrame.format.height))
        installPixels(imageInfo, rgba, displayFrame.format.width * 4)
    }
    return Image.makeFromBitmap(bitmap).toComposeImageBitmap()
}
