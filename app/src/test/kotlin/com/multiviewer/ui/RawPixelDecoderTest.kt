package com.multiviewer.ui

import androidx.compose.ui.graphics.asSkiaBitmap
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RawPixelDecoderTest {
    @Test
    fun `decodes an RGBA8888 dump with the exact pixel values`() {
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

        val bitmap = decodeRawPixelFile(file, width, height, RawPixelFormat.RGBA8888)

        assertTrue(bitmap != null)
        val skiaBitmap = bitmap.asSkiaBitmap()
        assertEquals(0xFFFF0000.toInt(), skiaBitmap.getColor(0, 0)) // red, ARGB
        assertEquals(0xFF00FF00.toInt(), skiaBitmap.getColor(1, 0)) // green
        assertEquals(0xFF0000FF.toInt(), skiaBitmap.getColor(0, 1)) // blue
        assertEquals(0xFFFFFFFF.toInt(), skiaBitmap.getColor(1, 1)) // white
        file.delete()
    }

    @Test
    fun `decodes an RGB888 dump (no alpha channel) with the exact pixel values`() {
        val width = 2
        val height = 1
        val bytes = byteArrayOf(
            0xFF.toByte(), 0x80.toByte(), 0x00, // orange-ish
            0x10, 0x20, 0x30,
        )
        val file = File.createTempFile("raw-pixel-rgb-test-", ".rgb")
        file.deleteOnExit()
        file.writeBytes(bytes)

        val bitmap = decodeRawPixelFile(file, width, height, RawPixelFormat.RGB888)

        assertTrue(bitmap != null)
        val skiaBitmap = bitmap.asSkiaBitmap()
        assertEquals(0xFFFF8000.toInt(), skiaBitmap.getColor(0, 0))
        assertEquals(0xFF102030.toInt(), skiaBitmap.getColor(1, 0))
        file.delete()
    }

    @Test
    fun `decodes a BGR888 dump (channel order swapped from RGB888)`() {
        val width = 2
        val height = 1
        val bytes = byteArrayOf(
            0x00, 0x80.toByte(), 0xFF.toByte(), // stored B,G,R -> should read back as orange-ish (R=FF,G=80,B=00)
            0x30, 0x20, 0x10,
        )
        val file = File.createTempFile("raw-pixel-bgr888-test-", ".rgb")
        file.deleteOnExit()
        file.writeBytes(bytes)

        val bitmap = decodeRawPixelFile(file, width, height, RawPixelFormat.BGR888)

        assertTrue(bitmap != null)
        val skiaBitmap = bitmap.asSkiaBitmap()
        assertEquals(0xFFFF8000.toInt(), skiaBitmap.getColor(0, 0))
        assertEquals(0xFF102030.toInt(), skiaBitmap.getColor(1, 0))
        file.delete()
    }

    @Test
    fun `decodes an ARGB8888 dump (alpha-first channel order)`() {
        val width = 2
        val height = 1
        val bytes = byteArrayOf(
            0x80.toByte(), 0xFF.toByte(), 0x00, 0x00, // A=80, R=FF, G=00, B=00 -> half-alpha red
            0xFF.toByte(), 0x00, 0xFF.toByte(), 0x00, // A=FF, R=00, G=FF, B=00 -> opaque green
        )
        val file = File.createTempFile("raw-pixel-argb8888-test-", ".rgba")
        file.deleteOnExit()
        file.writeBytes(bytes)

        val bitmap = decodeRawPixelFile(file, width, height, RawPixelFormat.ARGB8888)

        assertTrue(bitmap != null)
        val skiaBitmap = bitmap.asSkiaBitmap()
        assertEquals(0x80FF0000.toInt(), skiaBitmap.getColor(0, 0))
        assertEquals(0xFF00FF00.toInt(), skiaBitmap.getColor(1, 0))
        file.delete()
    }

    @Test
    fun `decodes RGB565 with the exact expected 8-bit-per-channel values after bit-replication scaling`() {
        // Pure red in RGB565 is bits 11111 000000 00000 = 0xF800. Little-endian on disk: low byte
        // first (0x00, 0xF8). 5-bit 0x1F scales to 8-bit via bit replication: (31<<3)|(31>>2) = 255.
        val width = 1
        val height = 1
        val bytes = byteArrayOf(0x00, 0xF8.toByte())
        val file = File.createTempFile("raw-pixel-rgb565-test-", ".raw")
        file.deleteOnExit()
        file.writeBytes(bytes)

        val bitmap = decodeRawPixelFile(file, width, height, RawPixelFormat.RGB565, RawPixelByteOrder.LITTLE_ENDIAN)

        assertTrue(bitmap != null)
        assertEquals(0xFFFF0000.toInt(), bitmap.asSkiaBitmap().getColor(0, 0))
        file.delete()
    }

    @Test
    fun `RGB565 and BGR565 decode the same bytes to swapped red-blue channels`() {
        // Pure blue in RGB565 is bits 00000 000000 11111 = 0x001F -> little-endian bytes (0x1F, 0x00).
        val width = 1
        val height = 1
        val bytes = byteArrayOf(0x1F, 0x00)
        val file = File.createTempFile("raw-pixel-565-swap-test-", ".raw")
        file.deleteOnExit()
        file.writeBytes(bytes)

        val asRgb = decodeRawPixelFile(file, width, height, RawPixelFormat.RGB565, RawPixelByteOrder.LITTLE_ENDIAN)
        val asBgr = decodeRawPixelFile(file, width, height, RawPixelFormat.BGR565, RawPixelByteOrder.LITTLE_ENDIAN)

        assertTrue(asRgb != null && asBgr != null)
        assertEquals(0xFF0000FF.toInt(), asRgb.asSkiaBitmap().getColor(0, 0)) // RGB565: low bits = blue
        assertEquals(0xFFFF0000.toInt(), asBgr.asSkiaBitmap().getColor(0, 0)) // BGR565: low bits = red
        file.delete()
    }

    @Test
    fun `RGB565 little-endian and big-endian byte order produce different results for the same bytes`() {
        // 0xF800 (pure red) little-endian on disk is (0x00, 0xF8); read as big-endian instead, the
        // 16-bit value becomes 0x00F8 (a dim, mostly-blue-and-green value), not red -- proving byte
        // order is actually threaded through, not silently ignored.
        val width = 1
        val height = 1
        val bytes = byteArrayOf(0x00, 0xF8.toByte())
        val file = File.createTempFile("raw-pixel-565-endian-test-", ".raw")
        file.deleteOnExit()
        file.writeBytes(bytes)

        val little = decodeRawPixelFile(file, width, height, RawPixelFormat.RGB565, RawPixelByteOrder.LITTLE_ENDIAN)
        val big = decodeRawPixelFile(file, width, height, RawPixelFormat.RGB565, RawPixelByteOrder.BIG_ENDIAN)

        assertTrue(little != null && big != null)
        assertTrue(
            little.asSkiaBitmap().getColor(0, 0) != big.asSkiaBitmap().getColor(0, 0),
            "expected little-endian and big-endian reads of the same bytes to differ",
        )
        file.delete()
    }

    @Test
    fun `returns null when the file is smaller than the declared dimensions require`() {
        val file = File.createTempFile("raw-pixel-too-small-", ".rgba")
        file.deleteOnExit()
        file.writeBytes(ByteArray(4)) // one pixel's worth, but we ask for 4x4

        val bitmap = decodeRawPixelFile(file, 4, 4, RawPixelFormat.RGBA8888)

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
        assertEquals(48L, expectedRawFileSize(width = 4, height = 4, format = RawPixelFormat.RGB888))
        assertEquals(64L, expectedRawFileSize(width = 4, height = 4, format = RawPixelFormat.RGBA8888))
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

    @Test
    fun `rawPixelFrameCount counts whole frames and ignores a trailing partial frame`() {
        // Each RGBA8888 frame at 4x4 is 64 bytes.
        assertEquals(3, rawPixelFrameCount(fileLength = 192, width = 4, height = 4, format = RawPixelFormat.RGBA8888))
        assertEquals(1, rawPixelFrameCount(fileLength = 64, width = 4, height = 4, format = RawPixelFormat.RGBA8888))
        assertEquals(1, rawPixelFrameCount(fileLength = 100, width = 4, height = 4, format = RawPixelFormat.RGBA8888)) // 36 trailing bytes ignored
        assertEquals(0, rawPixelFrameCount(fileLength = 32, width = 4, height = 4, format = RawPixelFormat.RGBA8888)) // less than one frame
    }

    @Test
    fun `decodes each frame of a concatenated multi-frame RGBA8888 dump independently`() {
        val width = 2
        val height = 2
        // Three concatenated frames: solid red, solid green, solid blue.
        fun solidFrame(r: Byte, g: Byte, b: Byte): ByteArray {
            val pixel = byteArrayOf(r, g, b, 0xFF.toByte())
            return pixel + pixel + pixel + pixel
        }
        val red = 0xFF.toByte()
        val zero = 0x00.toByte()
        val bytes = solidFrame(red, zero, zero) + solidFrame(zero, red, zero) + solidFrame(zero, zero, red)
        val file = File.createTempFile("raw-pixel-multiframe-test-", ".rgba")
        file.deleteOnExit()
        file.writeBytes(bytes)

        assertEquals(3, rawPixelFrameCount(file.length(), width, height, RawPixelFormat.RGBA8888))

        val frame0 = decodeRawPixelFile(file, width, height, RawPixelFormat.RGBA8888, frameIndex = 0)
        val frame1 = decodeRawPixelFile(file, width, height, RawPixelFormat.RGBA8888, frameIndex = 1)
        val frame2 = decodeRawPixelFile(file, width, height, RawPixelFormat.RGBA8888, frameIndex = 2)

        assertTrue(frame0 != null && frame1 != null && frame2 != null)
        assertEquals(0xFFFF0000.toInt(), frame0.asSkiaBitmap().getColor(0, 0))
        assertEquals(0xFF00FF00.toInt(), frame1.asSkiaBitmap().getColor(0, 0))
        assertEquals(0xFF0000FF.toInt(), frame2.asSkiaBitmap().getColor(0, 0))
        file.delete()
    }

    @Test
    fun `defaultRawPixelFormat picks NV12 for a nv12 extension`() {
        assertEquals(RawPixelFormat.YUV420SP_NV12, defaultRawPixelFormat("nv12"))
    }

    @Test
    fun `defaultRawPixelFormat picks NV21 for a nv21 extension`() {
        assertEquals(RawPixelFormat.YUV420SP_NV21, defaultRawPixelFormat("nv21"))
    }

    @Test
    fun `defaultRawPixelFormat is case-insensitive`() {
        assertEquals(RawPixelFormat.YUV420SP_NV21, defaultRawPixelFormat("NV21"))
    }

    @Test
    fun `defaultRawPixelFormat falls back to the first enum entry for any other extension`() {
        assertEquals(RawPixelFormat.entries.first(), defaultRawPixelFormat("raw"))
        assertEquals(RawPixelFormat.entries.first(), defaultRawPixelFormat("yuv"))
        assertEquals(RawPixelFormat.entries.first(), defaultRawPixelFormat("rgba"))
    }

    @Test
    fun `decodeRawPixelFile returns null for a frame index past the end of the sequence`() {
        val width = 2
        val height = 2
        val onePixel = byteArrayOf(0xFF.toByte(), 0, 0, 0xFF.toByte())
        val bytes = onePixel + onePixel + onePixel + onePixel // exactly one frame's worth
        val file = File.createTempFile("raw-pixel-oob-frame-test-", ".rgba")
        file.deleteOnExit()
        file.writeBytes(bytes)

        assertTrue(decodeRawPixelFile(file, width, height, RawPixelFormat.RGBA8888, frameIndex = 0) != null)
        assertNull(decodeRawPixelFile(file, width, height, RawPixelFormat.RGBA8888, frameIndex = 1))
        file.delete()
    }

    @Test
    fun `parseResolutionFromFilename extracts resolution from standard cross separated filenames`() {
        assertEquals(Pair(1920, 1080), parseResolutionFromFilename("video_1920x1080.yuv"))
        assertEquals(Pair(3840, 2160), parseResolutionFromFilename("dump_3840X2160_nv12.raw"))
        assertEquals(Pair(1280, 720), parseResolutionFromFilename("test_1280*720.rgb"))
        assertEquals(Pair(640, 480), parseResolutionFromFilename("sample_640×480.rgba"))
        assertEquals(Pair(1000, 564), parseResolutionFromFilename("1000x564.nv12"))
    }

    @Test
    fun `parseResolutionFromFilename extracts resolution from width and height tagged filenames`() {
        assertEquals(Pair(1920, 1080), parseResolutionFromFilename("frame_w1920_h1080.raw"))
        assertEquals(Pair(1920, 1080), parseResolutionFromFilename("W1920H1080.bin"))
        assertEquals(Pair(1280, 720), parseResolutionFromFilename("out_width_1280_height_720.yuv"))
    }

    @Test
    fun `parseResolutionFromFilename extracts resolution from underscore or dash separated filenames`() {
        assertEquals(Pair(1920, 1080), parseResolutionFromFilename("camera_1920_1080.nv21"))
        assertEquals(Pair(3840, 2160), parseResolutionFromFilename("dump-3840-2160.raw"))
        assertEquals(Pair(1280, 720), parseResolutionFromFilename("test_1280_720_30fps.yuv"))
    }

    @Test
    fun `parseResolutionFromFilename rejects dates and non-resolution numbers`() {
        assertNull(parseResolutionFromFilename("IMG_20260831_120000.raw"))
        assertNull(parseResolutionFromFilename("photo_2026_08_31.raw"))
        assertNull(parseResolutionFromFilename("capture_12_05.yuv"))
        assertNull(parseResolutionFromFilename("test_video.raw"))
    }

    @Test
    fun `detectRawPixelFormat recognizes format tokens in filename and extensions`() {
        assertEquals(RawPixelFormat.YUV420SP_NV12, detectRawPixelFormat(File("frame_1920x1080_nv12.raw")))
        assertEquals(RawPixelFormat.YUV420SP_NV21, detectRawPixelFormat(File("dump_1920x1080_nv21.bin")))
        assertEquals(RawPixelFormat.YV12, detectRawPixelFormat(File("test_1280x720_yv12.yuv")))
        assertEquals(RawPixelFormat.YUV420P, detectRawPixelFormat(File("camera_1920x1080_i420.raw")))
        assertEquals(RawPixelFormat.RGBA8888, detectRawPixelFormat(File("screen_3840x2160_rgba.raw")))
        assertEquals(RawPixelFormat.RGB565, detectRawPixelFormat(File("display_640x480_rgb565.bin")))
    }

    @Test
    fun `parseFpsFromFilename extracts frame rate accurately`() {
        assertEquals("30", parseFpsFromFilename("video_1920x1080_30fps.yuv"))
        assertEquals("60", parseFpsFromFilename("dump_3840x2160_60fps_nv12.raw"))
        assertEquals("24.0", parseFpsFromFilename("film_1920x1080_24.0fps.yuv"))
        assertNull(parseFpsFromFilename("test_1920x1080.raw"))
    }
}
