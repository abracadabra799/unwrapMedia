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

/** Converts a validated AV2 frame to the same Compose bitmap type used by the existing players. */
fun Av2DecodedFrame.toImageBitmap(): ImageBitmap {
    val rgba = yuv420ToRgba(this)
    val bitmap = Bitmap().apply {
        allocPixels(ImageInfo(ColorInfo(ColorType.RGBA_8888, ColorAlphaType.UNPREMUL, ColorSpace.sRGB), format.width, format.height))
        installPixels(imageInfo, rgba, format.width * 4)
    }
    return Image.makeFromBitmap(bitmap).toComposeImageBitmap()
}
