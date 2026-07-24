package com.multiviewer.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FfmpegVideoPlayerTest {
    @Test
    fun `parseFrameRate reads a simple integer fraction`() {
        assertEquals(30.0, parseFrameRate("30/1"))
    }

    @Test
    fun `parseFrameRate reads a non-integer NTSC-style fraction`() {
        val result = parseFrameRate("24000/1001")
        assertTrue(result != null && Math.abs(result - 23.976) < 0.001)
    }

    @Test
    fun `parseFrameRate returns null for a zero-over-zero fraction`() {
        assertNull(parseFrameRate("0/0"))
    }

    @Test
    fun `parseFrameRate returns null for a malformed string`() {
        assertNull(parseFrameRate("not-a-fraction"))
    }

    @Test
    fun `probeVideo reads width, height, and fps from a real synthetic video`() {
        val video = File.createTempFile("ffmpeg-player-probe-test-", ".mp4")
        video.deleteOnExit()
        val generate = ProcessBuilder(
            "ffmpeg", "-y", "-f", "lavfi", "-i", "testsrc=duration=2:size=64x48:rate=10",
            video.absolutePath,
        ).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        generate.waitFor()

        val info = probeVideo(video)

        assertEquals(64, info?.width)
        assertEquals(48, info?.height)
        assertEquals(10.0, info?.fps)
        video.delete()
    }

    @Test
    fun `probeVideo returns null for a nonexistent file`() {
        assertNull(probeVideo(File("/nonexistent/path/does-not-exist.mp4")))
    }

    @Test
    fun `raw BGRA frames can be read from ffmpeg's stdout at the exact expected byte size`() {
        val video = File.createTempFile("ffmpeg-player-frames-test-", ".mp4")
        video.deleteOnExit()
        val generate = ProcessBuilder(
            "ffmpeg", "-y", "-f", "lavfi", "-i", "testsrc=duration=2:size=64x48:rate=10",
            video.absolutePath,
        ).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        generate.waitFor()

        val info = probeVideo(video)!!
        val frameSize = info.width * info.height * 4
        val process = ProcessBuilder(
            "ffmpeg", "-i", video.absolutePath,
            "-f", "rawvideo", "-pix_fmt", "bgra", "-an",
            "-r", info.fps.toString(), "-",
        ).redirectError(ProcessBuilder.Redirect.DISCARD).start()

        val input = process.inputStream
        val buffer = ByteArray(frameSize)
        var framesRead = 0
        repeat(3) {
            var offset = 0
            while (offset < frameSize) {
                val read = input.read(buffer, offset, frameSize - offset)
                if (read < 0) return@repeat
                offset += read
            }
            framesRead++
        }
        process.destroyForcibly()
        video.delete()

        assertEquals(3, framesRead)
    }
}
