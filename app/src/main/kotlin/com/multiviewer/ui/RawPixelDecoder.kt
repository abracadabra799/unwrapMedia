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

// Whether byte 0 of a 16-bit RGB565/BGR565 pixel holds the low or high 8 bits of that pixel's
// value -- the only place in this file "byte order" is genuinely ambiguous. The 888/8888 formats
// have no such ambiguity: each channel is already a distinct whole byte in a fixed sequence, and
// that sequence IS the format choice (RGB888 vs BGR888, etc.), not a separate endianness axis.
enum class RawPixelByteOrder(val label: String) {
    LITTLE_ENDIAN("Little-endian"),
    BIG_ENDIAN("Big-endian"),
}

// Headerless raw pixel dumps (.raw/.rgb/.rgba/.yuv) carry no width/height/format of their own --
// the user supplies these via RawPixelOpenDialog. ffmpegPixFmt is null for formats not decoded by
// handing the file straight to ffmpeg: the RGB family goes through Skia directly, and YV12 has no
// ffmpeg -pix_fmt of its own (verified via `ffmpeg -pix_fmts`) so it's byte-reordered into I420
// first (see decodeYv12). NV12/NV21 (the common "YUV420sp" semi-planar layouts, e.g. from Android
// camera capture) differ only in whether the interleaved chroma plane is U-then-V or V-then-U,
// which swaps red/blue if you pick the wrong one, so both are offered explicitly rather than
// guessing.
// Declaration order is also display order in RawPixelOpenDialog's format list -- YUV variants
// listed first since they're what most raw-dump users are looking for (camera/video frame dumps).
enum class RawPixelFormat(val label: String, val ffmpegPixFmt: String?, val needsByteOrder: Boolean = false) {
    YUV420SP_NV12("YUV420sp (NV12, UV)", "nv12"),
    YUV420SP_NV21("YUV420sp (NV21, VU)", "nv21"),
    YUV420P("YUV420p (I420, Y-U-V)", "yuv420p"),
    YV12("YUV420p (YV12, Y-V-U)", null),
    RGB565("RGB565 (16-bit)", null, needsByteOrder = true),
    BGR565("BGR565 (16-bit)", null, needsByteOrder = true),
    RGB888("RGB888 (24-bit)", null),
    BGR888("BGR888 (24-bit)", null),
    RGBA8888("RGBA8888 (32-bit)", null),
    ARGB8888("ARGB8888 (32-bit)", null),
}

// 4:2:0 chroma subsampling covers each plane at ceil(dim/2), not floor(dim/2) -- verified
// directly: a real ffmpeg-generated 5x3 yuv420p frame is 27 bytes (5*3 + 2*ceil(5/2)*ceil(3/2) =
// 15 + 12 = 27), not the 19 bytes floor division would predict.
private fun subsampledChromaDimension(dimension: Int): Int = (dimension + 1) / 2

fun expectedRawFileSize(width: Int, height: Int, format: RawPixelFormat): Long {
    val w = width.toLong()
    val h = height.toLong()
    return when (format) {
        RawPixelFormat.RGB565, RawPixelFormat.BGR565 -> w * h * 2
        RawPixelFormat.RGB888, RawPixelFormat.BGR888 -> w * h * 3
        RawPixelFormat.RGBA8888, RawPixelFormat.ARGB8888 -> w * h * 4
        else -> {
            val chromaPixels = subsampledChromaDimension(width).toLong() * subsampledChromaDimension(height).toLong()
            w * h + 2 * chromaPixels
        }
    }
}

fun decodeRawPixelFile(
    file: File,
    width: Int,
    height: Int,
    format: RawPixelFormat,
    byteOrder: RawPixelByteOrder = RawPixelByteOrder.LITTLE_ENDIAN,
): ImageBitmap? {
    if (width <= 0 || height <= 0) return null
    return when (format) {
        RawPixelFormat.RGB565 -> decode565(file, width, height, swapRedBlue = false, byteOrder)
        RawPixelFormat.BGR565 -> decode565(file, width, height, swapRedBlue = true, byteOrder)
        RawPixelFormat.RGB888 -> decodeRgb888(file, width, height, swapRedBlue = false)
        RawPixelFormat.BGR888 -> decodeRgb888(file, width, height, swapRedBlue = true)
        RawPixelFormat.RGBA8888 -> decodeRgba8888(file, width, height)
        RawPixelFormat.ARGB8888 -> decodeArgb8888(file, width, height)
        RawPixelFormat.YV12 -> decodeYv12(file, width, height)
        else -> {
            val pixFmt = format.ffmpegPixFmt ?: return null
            if (file.length() < expectedRawFileSize(width, height, format)) return null
            decodeYuvFamily(file.readBytes(), width, height, pixFmt)
        }
    }
}

private fun expandedToRgba8888Bitmap(width: Int, height: Int, rgba: ByteArray): ImageBitmap {
    val bitmap = Bitmap().apply {
        allocPixels(ImageInfo(ColorInfo(ColorType.RGBA_8888, ColorAlphaType.UNPREMUL, ColorSpace.sRGB), width, height))
        installPixels(imageInfo, rgba, width * 4)
    }
    return Image.makeFromBitmap(bitmap).toComposeImageBitmap()
}

private fun decodeRgba8888(file: File, width: Int, height: Int): ImageBitmap? {
    val expected = width * height * 4
    val bytes = file.readBytes()
    if (bytes.size < expected) return null
    return expandedToRgba8888Bitmap(width, height, bytes)
}

private fun decodeArgb8888(file: File, width: Int, height: Int): ImageBitmap? {
    val expected = width * height * 4
    val bytes = file.readBytes()
    if (bytes.size < expected) return null
    val reordered = ByteArray(expected)
    for (i in 0 until width * height) {
        val a = bytes[i * 4]
        val r = bytes[i * 4 + 1]
        val g = bytes[i * 4 + 2]
        val b = bytes[i * 4 + 3]
        reordered[i * 4] = r
        reordered[i * 4 + 1] = g
        reordered[i * 4 + 2] = b
        reordered[i * 4 + 3] = a
    }
    return expandedToRgba8888Bitmap(width, height, reordered)
}

private fun decodeRgb888(file: File, width: Int, height: Int, swapRedBlue: Boolean): ImageBitmap? {
    val expected = width * height * 3
    val bytes = file.readBytes()
    if (bytes.size < expected) return null
    // Skia has no tightly-packed 24-bit-per-pixel ColorType, so this expands each pixel to 4
    // bytes (opaque alpha) and reuses the RGBA_8888 path either way.
    val expanded = ByteArray(width * height * 4)
    for (i in 0 until width * height) {
        val c0 = bytes[i * 3]
        val c1 = bytes[i * 3 + 1]
        val c2 = bytes[i * 3 + 2]
        if (swapRedBlue) { // BGR888 input: c0=B, c2=R
            expanded[i * 4] = c2
            expanded[i * 4 + 1] = c1
            expanded[i * 4 + 2] = c0
        } else {
            expanded[i * 4] = c0
            expanded[i * 4 + 1] = c1
            expanded[i * 4 + 2] = c2
        }
        expanded[i * 4 + 3] = 0xFF.toByte()
    }
    return expandedToRgba8888Bitmap(width, height, expanded)
}

// RGB565/BGR565 pack one pixel into 16 bits (5-6-5 bits per channel); which byte holds the low vs
// high 8 bits of that 16-bit value is exactly the "byte order" ambiguity RawPixelByteOrder exists
// for. Each 5/6-bit channel is scaled up to 8 bits by bit replication (v*255/max), the standard
// technique for extending a narrow channel without darkening the top of its range.
private fun decode565(file: File, width: Int, height: Int, swapRedBlue: Boolean, byteOrder: RawPixelByteOrder): ImageBitmap? {
    val expected = width * height * 2
    val bytes = file.readBytes()
    if (bytes.size < expected) return null
    val expanded = ByteArray(width * height * 4)
    for (i in 0 until width * height) {
        val byte0 = bytes[i * 2].toInt() and 0xFF
        val byte1 = bytes[i * 2 + 1].toInt() and 0xFF
        val value = if (byteOrder == RawPixelByteOrder.LITTLE_ENDIAN) (byte1 shl 8) or byte0 else (byte0 shl 8) or byte1
        val top5 = (value shr 11) and 0x1F
        val mid6 = (value shr 5) and 0x3F
        val low5 = value and 0x1F
        val r5 = if (swapRedBlue) low5 else top5
        val b5 = if (swapRedBlue) top5 else low5
        val r8 = (r5 shl 3) or (r5 shr 2)
        val g8 = (mid6 shl 2) or (mid6 shr 4)
        val b8 = (b5 shl 3) or (b5 shr 2)
        expanded[i * 4] = r8.toByte()
        expanded[i * 4 + 1] = g8.toByte()
        expanded[i * 4 + 2] = b8.toByte()
        expanded[i * 4 + 3] = 0xFF.toByte()
    }
    return expandedToRgba8888Bitmap(width, height, expanded)
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
