package com.multiviewer.ui

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorInfo
import org.jetbrains.skia.ColorSpace
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo

// Raw 32-bit pixels -> Compose bitmap with exactly one native copy. The previous chain
// (allocPixels + installPixels + Image.makeFromBitmap + toComposeImageBitmap) allocated and copied
// the frame four times -- ~32MB of native churn per 1080p frame during playback, released only when
// the GC got around to the Skia wrappers. installPixels copies `pixels`, so the caller may reuse
// its buffer for the next frame; setImmutable lets Skia draw the bitmap without copying it again.
fun pixelBufferToImageBitmap(
    width: Int,
    height: Int,
    pixels: ByteArray,
    colorType: ColorType,
    alphaType: ColorAlphaType,
): ImageBitmap {
    val info = ImageInfo(ColorInfo(colorType, alphaType, ColorSpace.sRGB), width, height)
    val bitmap = Bitmap()
    check(bitmap.installPixels(info, pixels, width * 4)) { "installPixels failed for ${width}x$height" }
    bitmap.setImmutable()
    return bitmap.asComposeImageBitmap()
}
