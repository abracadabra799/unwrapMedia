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
// the user supplies these via RawPixelOpenDialog. ffmpegPixFmt is null for formats not decoded by
// handing the file straight to ffmpeg: RGB24/RGBA32 go through Skia directly, and YV12 has no
// ffmpeg -pix_fmt of its own (verified via `ffmpeg -pix_fmts`) so it's byte-reordered into I420
// first (see decodeYv12). NV12/NV21 (the common "YUV420sp" semi-planar layouts, e.g. from Android
// camera capture) differ only in whether the interleaved chroma plane is U-then-V or V-then-U,
// which swaps red/blue if you pick the wrong one, so both are offered explicitly rather than
// guessing.
enum class RawPixelFormat(val label: String, val ffmpegPixFmt: String?) {
    RGB24("RGB (24-bit)", null),
    RGBA32("RGBA (32-bit)", null),
    YUV420P("YUV 4:2:0 planar (I420, Y-U-V)", "yuv420p"),
    YV12("YUV 4:2:0 planar (YV12, Y-V-U)", null),
    YUV420SP_NV12("YUV 4:2:0 semi-planar (NV12, UV)", "nv12"),
    YUV420SP_NV21("YUV 4:2:0 semi-planar (NV21, VU)", "nv21"),
}

// 4:2:0 chroma subsampling covers each plane at ceil(dim/2), not floor(dim/2) -- verified
// directly: a real ffmpeg-generated 5x3 yuv420p frame is 27 bytes (5*3 + 2*ceil(5/2)*ceil(3/2) =
// 15 + 12 = 27), not the 19 bytes floor division would predict. This also means a flat
// bytes-per-pixel multiplier (e.g. "1.5x") is only exact for even width/height, so
// expectedRawFileSize computes the chroma plane size explicitly instead.
private fun subsampledChromaDimension(dimension: Int): Int = (dimension + 1) / 2

fun expectedRawFileSize(width: Int, height: Int, format: RawPixelFormat): Long {
    val w = width.toLong()
    val h = height.toLong()
    return when (format) {
        RawPixelFormat.RGB24 -> w * h * 3
        RawPixelFormat.RGBA32 -> w * h * 4
        else -> {
            val chromaPixels = subsampledChromaDimension(width).toLong() * subsampledChromaDimension(height).toLong()
            w * h + 2 * chromaPixels
        }
    }
}

fun decodeRawPixelFile(file: File, width: Int, height: Int, format: RawPixelFormat): ImageBitmap? {
    if (width <= 0 || height <= 0) return null
    return when (format) {
        RawPixelFormat.RGB24 -> decodeRgb24(file, width, height)
        RawPixelFormat.RGBA32 -> decodeRgba32(file, width, height)
        RawPixelFormat.YV12 -> decodeYv12(file, width, height)
        else -> {
            val pixFmt = format.ffmpegPixFmt ?: return null
            if (file.length() < expectedRawFileSize(width, height, format)) return null
            decodeYuvFamily(file.readBytes(), width, height, pixFmt)
        }
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

// YV12 is Y, then V, then U (each a full/quarter-size plane); I420/yuv420p -- the only 4:2:0
// planar format ffmpeg's rawvideo demuxer actually understands by name -- is Y, then U, then V.
// Swapping the two chroma plane blocks converts one into the other; the pixel values themselves
// aren't touched, so this is exact, not an approximation.
private fun decodeYv12(file: File, width: Int, height: Int): ImageBitmap? {
    val ySize = width * height
    val chromaSize = subsampledChromaDimension(width) * subsampledChromaDimension(height)
    val expected = ySize + chromaSize * 2
    val bytes = file.readBytes()
    if (bytes.size < expected) return null
    val reordered = ByteArray(expected)
    System.arraycopy(bytes, 0, reordered, 0, ySize)
    System.arraycopy(bytes, ySize + chromaSize, reordered, ySize, chromaSize) // U: YV12's 3rd plane -> I420's 2nd
    System.arraycopy(bytes, ySize, reordered, ySize + chromaSize, chromaSize) // V: YV12's 2nd plane -> I420's 3rd
    return decodeYuvFamily(reordered, width, height, "yuv420p")
}

// No new dependency for YUV->RGB color conversion -- ffmpeg (already bundled/required elsewhere in
// the app) already does exactly this for raw video frames, so this writes the (possibly
// reordered) bytes to a temp file, points ffmpeg at it, and reads back a BGRA raw frame the same
// way FfmpegVideoPlayer already does. Covers YUV420P/YV12 as well as the NV12/NV21 semi-planar
// layouts -- only the -pix_fmt name (and, for YV12, the chroma plane order) differs.
private fun decodeYuvFamily(bytes: ByteArray, width: Int, height: Int, ffmpegPixFmt: String): ImageBitmap? {
    val tempIn = File.createTempFile("raw-yuv-input-", ".raw")
    val tempOut = File.createTempFile("raw-yuv-decode-", ".bgra")
    tempIn.deleteOnExit()
    tempOut.deleteOnExit()
    return try {
        tempIn.writeBytes(bytes)
        val process = ProcessBuilder(
            FfmpegLocator.ffmpegPath(), "-y",
            "-f", "rawvideo", "-pix_fmt", ffmpegPixFmt, "-s", "${width}x$height", "-i", tempIn.absolutePath,
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
        tempIn.delete()
        tempOut.delete()
    }
}
