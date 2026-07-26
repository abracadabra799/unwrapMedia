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
    fun `probeVideo picks avg_frame_rate over r_frame_rate when they differ`() {
        // Regression test: ffprobe's csv=p=0 output does not preserve the field order given in
        // -show_entries -- it emits fields in the stream struct's internal order, so for videos
        // where r_frame_rate (a container timebase artifact) differs from avg_frame_rate (the
        // true playback rate), csv put r_frame_rate in the column probeVideo assumed was
        // avg_frame_rate. That mis-paced real playback at the wrong frame rate (e.g. 120fps
        // instead of ~30fps), causing ffmpeg to quadruple-duplicate frames and playback to fall
        // permanently behind schedule. A variable-frame-rate source (60fps decimated to every
        // 6th frame) reproduces the same avg/r_frame_rate mismatch deterministically.
        val video = File.createTempFile("ffmpeg-player-vfr-test-", ".mp4")
        video.deleteOnExit()
        val generate = ProcessBuilder(
            "ffmpeg", "-y", "-f", "lavfi", "-i", "testsrc=duration=3:size=64x48:rate=60",
            "-vf", "select='not(mod(n\\,6))'", "-vsync", "vfr",
            "-c:v", "libx264", "-pix_fmt", "yuv420p", video.absolutePath,
        ).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        generate.waitFor()

        val info = probeVideo(video)

        // r_frame_rate for this fixture is exactly 10.0; avg_frame_rate is ~10.2857 (72/7).
        // Picking up r_frame_rate instead would produce exactly 10.0 here.
        assertTrue(info != null && Math.abs(info.fps - 10.0) > 0.01, "expected fps near 10.2857 (avg_frame_rate), got ${info?.fps}")
        video.delete()
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
