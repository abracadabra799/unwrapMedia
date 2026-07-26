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
    fun `expectedRawFileSize accounts for YUV420p's fractional bytes-per-pixel`() {
        assertEquals(24L, expectedRawFileSize(width = 4, height = 4, format = RawPixelFormat.YUV420P))
        assertEquals(48L, expectedRawFileSize(width = 4, height = 4, format = RawPixelFormat.RGB24))
        assertEquals(64L, expectedRawFileSize(width = 4, height = 4, format = RawPixelFormat.RGBA32))
    }
}
