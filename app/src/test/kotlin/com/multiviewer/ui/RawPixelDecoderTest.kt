package com.multiviewer.ui

import androidx.compose.ui.graphics.asSkiaBitmap
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RawPixelDecoderTest {
    @Test
    fun `decodes an RGBA32 dump with the exact pixel values`() {
        val width = 2
        val height = 2
        // Four distinct pixels: red, green, blue, white -- each RGBA (4 bytes).
        val bytes = byteArrayOf(
            0xFF.toByte(), 0x00, 0x00, 0xFF.toByte(),
            0x00, 0xFF.toByte(), 0x00, 0xFF.toByte(),
            0x00, 0x00, 0xFF.toByte(), 0xFF.toByte(),
            0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(),
        )
        val file = File.createTempFile("raw-pixel-rgba-test-", ".rgba")
        file.deleteOnExit()
        file.writeBytes(bytes)

        val bitmap = decodeRawPixelFile(file, width, height, RawPixelFormat.RGBA32)

        assertTrue(bitmap != null)
        val skiaBitmap = bitmap.asSkiaBitmap()
        assertEquals(0xFFFF0000.toInt(), skiaBitmap.getColor(0, 0)) // red, ARGB
        assertEquals(0xFF00FF00.toInt(), skiaBitmap.getColor(1, 0)) // green
        assertEquals(0xFF0000FF.toInt(), skiaBitmap.getColor(0, 1)) // blue
        assertEquals(0xFFFFFFFF.toInt(), skiaBitmap.getColor(1, 1)) // white
        file.delete()
    }

    @Test
    fun `decodes an RGB24 dump (no alpha channel) with the exact pixel values`() {
        val width = 2
        val height = 1
        val bytes = byteArrayOf(
            0xFF.toByte(), 0x80.toByte(), 0x00, // orange-ish
            0x10, 0x20, 0x30,
        )
        val file = File.createTempFile("raw-pixel-rgb-test-", ".rgb")
        file.deleteOnExit()
        file.writeBytes(bytes)

        val bitmap = decodeRawPixelFile(file, width, height, RawPixelFormat.RGB24)

        assertTrue(bitmap != null)
        val skiaBitmap = bitmap.asSkiaBitmap()
        assertEquals(0xFFFF8000.toInt(), skiaBitmap.getColor(0, 0))
        assertEquals(0xFF102030.toInt(), skiaBitmap.getColor(1, 0))
        file.delete()
    }

    @Test
    fun `returns null when the file is smaller than the declared dimensions require`() {
        val file = File.createTempFile("raw-pixel-too-small-", ".rgba")
        file.deleteOnExit()
        file.writeBytes(ByteArray(4)) // one pixel's worth, but we ask for 4x4

        val bitmap = decodeRawPixelFile(file, 4, 4, RawPixelFormat.RGBA32)

        assertNull(bitmap)
        file.delete()
    }

    @Test
    fun `decodes a real ffmpeg-generated YUV420p dump into a recognizably red image`() {
        val width = 16
        val height = 16
        val yuvFile = File.createTempFile("raw-pixel-yuv-test-", ".yuv")
        yuvFile.deleteOnExit()
        val generate = ProcessBuilder(
            "ffmpeg", "-y", "-f", "lavfi", "-i", "color=red:size=${width}x$height",
            "-pix_fmt", "yuv420p", "-frames:v", "1", "-f", "rawvideo", yuvFile.absolutePath,
        ).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        generate.waitFor()

        val bitmap = decodeRawPixelFile(yuvFile, width, height, RawPixelFormat.YUV420P)

        assertTrue(bitmap != null)
        val color = bitmap.asSkiaBitmap().getColor(width / 2, height / 2)
        val red = (color shr 16) and 0xFF
        val green = (color shr 8) and 0xFF
        val blue = color and 0xFF
        assertTrue(red > 200 && green < 60 && blue < 60, "expected a clearly red pixel, got r=$red g=$green b=$blue")
        yuvFile.delete()
    }

    @Test
    fun `decodes real ffmpeg-generated NV12 and NV21 dumps into a recognizably blue image`() {
        val width = 16
        val height = 16
        for (format in listOf(RawPixelFormat.YUV420SP_NV12, RawPixelFormat.YUV420SP_NV21)) {
            val yuvFile = File.createTempFile("raw-pixel-${format.ffmpegPixFmt}-test-", ".yuv")
            yuvFile.deleteOnExit()
            val generate = ProcessBuilder(
                "ffmpeg", "-y", "-f", "lavfi", "-i", "color=blue:size=${width}x$height",
                "-pix_fmt", format.ffmpegPixFmt!!, "-frames:v", "1", "-f", "rawvideo", yuvFile.absolutePath,
            ).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start()
            generate.waitFor()

            val bitmap = decodeRawPixelFile(yuvFile, width, height, format)

            assertTrue(bitmap != null, "expected ${format.ffmpegPixFmt} to decode")
            val color = bitmap.asSkiaBitmap().getColor(width / 2, height / 2)
            val red = (color shr 16) and 0xFF
            val green = (color shr 8) and 0xFF
            val blue = color and 0xFF
            assertTrue(
                blue > 200 && red < 60 && green < 60,
                "expected a clearly blue pixel for ${format.ffmpegPixFmt}, got r=$red g=$green b=$blue",
            )
            yuvFile.delete()
        }
    }

    @Test
    fun `NV12 and NV21 produce different colors from the same bytes -- the chroma order actually matters`() {
        val width = 16
        val height = 16
        val nv12File = File.createTempFile("raw-pixel-nv12-crosscheck-", ".yuv")
        nv12File.deleteOnExit()
        val generate = ProcessBuilder(
            "ffmpeg", "-y", "-f", "lavfi", "-i", "color=blue:size=${width}x$height",
            "-pix_fmt", "nv12", "-frames:v", "1", "-f", "rawvideo", nv12File.absolutePath,
        ).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        generate.waitFor()

        // Decode the same NV12-encoded bytes once correctly (as NV12) and once deliberately
        // mislabeled (as NV21, which swaps the U/V interleave order) -- if the format selection
        // weren't actually wired through to ffmpeg's -pix_fmt, these would decode identically.
        val correct = decodeRawPixelFile(nv12File, width, height, RawPixelFormat.YUV420SP_NV12)
        val mislabeled = decodeRawPixelFile(nv12File, width, height, RawPixelFormat.YUV420SP_NV21)

        assertTrue(correct != null && mislabeled != null)
        val correctColor = correct.asSkiaBitmap().getColor(width / 2, height / 2)
        val mislabeledColor = mislabeled.asSkiaBitmap().getColor(width / 2, height / 2)
        assertTrue(correctColor != mislabeledColor, "NV12 and NV21 decodes of the same bytes should differ")
        nv12File.delete()
    }

    @Test
    fun `decodes a YV12 dump (Y-V-U plane order) into a recognizably green image`() {
        // ffmpeg's rawvideo demuxer has no "yv12" -pix_fmt of its own (only yuv420p, i.e. I420,
        // Y-U-V order) -- so this builds a real YV12 fixture by taking an actual ffmpeg-generated
        // I420 frame and swapping its two chroma plane blocks, the exact inverse of what
        // decodeRawPixelFile's YV12 path is supposed to do internally.
        val width = 16
        val height = 16
        val i420File = File.createTempFile("raw-pixel-yv12-source-", ".yuv")
        i420File.deleteOnExit()
        val generate = ProcessBuilder(
            "ffmpeg", "-y", "-f", "lavfi", "-i", "color=green:size=${width}x$height",
            "-pix_fmt", "yuv420p", "-frames:v", "1", "-f", "rawvideo", i420File.absolutePath,
        ).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        generate.waitFor()
        val i420Bytes = i420File.readBytes()
        val ySize = width * height
        val chromaSize = (width / 2) * (height / 2)
        val yv12Bytes = ByteArray(i420Bytes.size)
        System.arraycopy(i420Bytes, 0, yv12Bytes, 0, ySize)
        System.arraycopy(i420Bytes, ySize, yv12Bytes, ySize + chromaSize, chromaSize) // I420's U -> YV12's 3rd plane
        System.arraycopy(i420Bytes, ySize + chromaSize, yv12Bytes, ySize, chromaSize) // I420's V -> YV12's 2nd plane
        val yv12File = File.createTempFile("raw-pixel-yv12-test-", ".yuv")
        yv12File.deleteOnExit()
        yv12File.writeBytes(yv12Bytes)

        val bitmap = decodeRawPixelFile(yv12File, width, height, RawPixelFormat.YV12)

        assertTrue(bitmap != null)
        val color = bitmap.asSkiaBitmap().getColor(width / 2, height / 2)
        val red = (color shr 16) and 0xFF
        val green = (color shr 8) and 0xFF
        val blue = color and 0xFF
        // ffmpeg's lavfi "green" is the web-standard (0,128,0), not lime (0,255,0).
        assertTrue(green > 100 && red < 60 && blue < 60, "expected a clearly green pixel, got r=$red g=$green b=$blue")
        i420File.delete()
        yv12File.delete()
    }

    @Test
    fun `expectedRawFileSize accounts for YUV420p's fractional bytes-per-pixel`() {
        assertEquals(24L, expectedRawFileSize(width = 4, height = 4, format = RawPixelFormat.YUV420P))
        assertEquals(48L, expectedRawFileSize(width = 4, height = 4, format = RawPixelFormat.RGB24))
        assertEquals(64L, expectedRawFileSize(width = 4, height = 4, format = RawPixelFormat.RGBA32))
    }

    @Test
    fun `expectedRawFileSize uses ceiling division for odd-dimension chroma planes, matching real ffmpeg output`() {
        // Verified directly: `ffmpeg -f lavfi -i color=red:size=5x3 -pix_fmt yuv420p -f rawvideo`
        // produces a 27-byte frame (15 luma + 2*ceil(5/2)*ceil(3/2) = 15+12), not the 19 bytes a
        // floor-division chroma size would predict.
        assertEquals(27L, expectedRawFileSize(width = 5, height = 3, format = RawPixelFormat.YUV420P))
        assertEquals(27L, expectedRawFileSize(width = 5, height = 3, format = RawPixelFormat.YV12))
        assertEquals(27L, expectedRawFileSize(width = 5, height = 3, format = RawPixelFormat.YUV420SP_NV12))
    }

    @Test
    fun `decodes a real ffmpeg-generated odd-resolution YUV420p dump`() {
        val width = 15
        val height = 9
        val yuvFile = File.createTempFile("raw-pixel-odd-res-test-", ".yuv")
        yuvFile.deleteOnExit()
        val generate = ProcessBuilder(
            "ffmpeg", "-y", "-f", "lavfi", "-i", "color=red:size=${width}x$height",
            "-pix_fmt", "yuv420p", "-frames:v", "1", "-f", "rawvideo", yuvFile.absolutePath,
        ).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        generate.waitFor()
        assertEquals(expectedRawFileSize(width, height, RawPixelFormat.YUV420P), yuvFile.length())

        val bitmap = decodeRawPixelFile(yuvFile, width, height, RawPixelFormat.YUV420P)

        assertTrue(bitmap != null)
        val color = bitmap.asSkiaBitmap().getColor(width / 2, height / 2)
        val red = (color shr 16) and 0xFF
        val green = (color shr 8) and 0xFF
        val blue = color and 0xFF
        assertTrue(red > 200 && green < 60 && blue < 60, "expected a clearly red pixel, got r=$red g=$green b=$blue")
        yuvFile.delete()
    }
}
