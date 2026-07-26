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
import java.io.File
import java.util.concurrent.TimeUnit

// Headerless raw pixel dumps (.raw/.rgb/.rgba/.yuv) carry no width/height/format of their own --
// the user supplies these via RawPixelOpenDialog. bytesPerPixel is fractional for YUV 4:2:0
// (1 luma byte/pixel + 0.5 byte/pixel of subsampled chroma).
enum class RawPixelFormat(val label: String, val bytesPerPixel: Double) {
    RGB24("RGB (24-bit)", 3.0),
    RGBA32("RGBA (32-bit)", 4.0),
    YUV420P("YUV 4:2:0 planar (I420)", 1.5),
}

fun expectedRawFileSize(width: Int, height: Int, format: RawPixelFormat): Long =
    (width.toLong() * height.toLong() * format.bytesPerPixel).toLong()

fun decodeRawPixelFile(file: File, width: Int, height: Int, format: RawPixelFormat): ImageBitmap? {
    if (width <= 0 || height <= 0) return null
    return when (format) {
        RawPixelFormat.RGB24 -> decodeRgb24(file, width, height)
        RawPixelFormat.RGBA32 -> decodeRgba32(file, width, height)
        RawPixelFormat.YUV420P -> decodeYuv420p(file, width, height)
    }
}

private fun decodeRgba32(file: File, width: Int, height: Int): ImageBitmap? {
    val expected = width * height * 4
    val bytes = file.readBytes()
    if (bytes.size < expected) return null
    val bitmap = Bitmap().apply {
        allocPixels(ImageInfo(ColorInfo(ColorType.RGBA_8888, ColorAlphaType.UNPREMUL, ColorSpace.sRGB), width, height))
        installPixels(imageInfo, bytes, width * 4)
    }
    return Image.makeFromBitmap(bitmap).toComposeImageBitmap()
}

private fun decodeRgb24(file: File, width: Int, height: Int): ImageBitmap? {
    val expected = width * height * 3
    val bytes = file.readBytes()
    if (bytes.size < expected) return null
    // Skia has no tightly-packed 24-bit-per-pixel ColorType -- RGB_888x expects 4 bytes/pixel with
    // the 4th ignored, so each pixel is expanded by one padding byte before installPixels.
    val expanded = ByteArray(width * height * 4)
    for (i in 0 until width * height) {
        expanded[i * 4] = bytes[i * 3]
        expanded[i * 4 + 1] = bytes[i * 3 + 1]
        expanded[i * 4 + 2] = bytes[i * 3 + 2]
        expanded[i * 4 + 3] = 0xFF.toByte()
    }
    val bitmap = Bitmap().apply {
        allocPixels(ImageInfo(ColorInfo(ColorType.RGB_888X, ColorAlphaType.OPAQUE, ColorSpace.sRGB), width, height))
        installPixels(imageInfo, expanded, width * 4)
    }
    return Image.makeFromBitmap(bitmap).toComposeImageBitmap()
}

// No new dependency for YUV->RGB color conversion -- ffmpeg (already bundled/required elsewhere in
// the app) already does exactly this for raw video frames, so this just points it at the raw file
// directly and reads back a BGRA raw frame the same way FfmpegVideoPlayer already does.
private fun decodeYuv420p(file: File, width: Int, height: Int): ImageBitmap? {
    val expected = (width.toLong() * height.toLong() * 1.5).toLong()
    if (file.length() < expected) return null
    val tempOut = File.createTempFile("raw-yuv-decode-", ".bgra")
    tempOut.deleteOnExit()
    return try {
        val process = ProcessBuilder(
            FfmpegLocator.ffmpegPath(), "-y",
            "-f", "rawvideo", "-pix_fmt", "yuv420p", "-s", "${width}x$height", "-i", file.absolutePath,
            "-f", "rawvideo", "-pix_fmt", "bgra", "-frames:v", "1", tempOut.absolutePath,
        ).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val finished = process.waitFor(8, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            return null
        }
        val outBytes = tempOut.readBytes()
        val expectedOut = width * height * 4
        if (outBytes.size < expectedOut) return null
        val bitmap = Bitmap().apply {
            allocPixels(ImageInfo(ColorInfo(ColorType.BGRA_8888, ColorAlphaType.PREMUL, ColorSpace.sRGB), width, height))
            installPixels(imageInfo, outBytes, width * 4)
        }
        Image.makeFromBitmap(bitmap).toComposeImageBitmap()
    } catch (e: Exception) {
        null
    } finally {
        tempOut.delete()
    }
}
