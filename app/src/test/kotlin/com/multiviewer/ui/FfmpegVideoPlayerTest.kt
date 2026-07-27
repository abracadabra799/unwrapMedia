package com.multiviewer.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FfmpegVideoPlayerTest {
    @Test
    fun `probeFrameTimestamps reads one pts_time per frame from a real video`() {
        val video = File.createTempFile("ffmpeg-pts-test-", ".mp4")
        video.deleteOnExit()
        ProcessBuilder(
            "ffmpeg", "-y", "-f", "lavfi", "-i", "testsrc=duration=3:size=320x240:rate=24",
            "-c:v", "libx264", "-pix_fmt", "yuv420p", "-g", "12", "-bf", "2", video.absolutePath,
        ).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start().waitFor()

        val timestamps = probeFrameTimestamps(video)

        assertEquals(72, timestamps?.size) // 3s * 24fps
        assertEquals(0.0, timestamps?.first())
        assertEquals(timestamps, timestamps?.sorted()) // presentation order, not decode order
        video.delete()
    }

    @Test
    fun `probeFrameTimestamps returns null for a nonexistent file`() {
        assertNull(probeFrameTimestamps(File("/nonexistent/path/does-not-exist.mp4")))
    }

    @Test
    fun `frameDurationsSeconds derives each duration from the gap to the next timestamp`() {
        val durations = frameDurationsSeconds(listOf(0.0, 0.1, 0.25, 0.3), fallbackSeconds = 0.05)

        assertEquals(4, durations.size)
        assertTrue(Math.abs(durations[0] - 0.1) < 1e-9)
        assertTrue(Math.abs(durations[1] - 0.15) < 1e-9)
        assertTrue(Math.abs(durations[2] - 0.05) < 1e-9)
        assertEquals(0.05, durations[3]) // last frame has no "next" timestamp -- uses the fallback exactly
    }

    @Test
    fun `frameDurationsSeconds sums to real elapsed playback time on an actually variable-rate clip`() {
        // select every 3rd frame of a 30fps source, so consecutive kept frames are NOT evenly
        // spaced by a single flat duration -- reproduces genuine VFR content, not just a lower
        // flat rate.
        val video = File.createTempFile("ffmpeg-vfr-durations-test-", ".mp4")
        video.deleteOnExit()
        ProcessBuilder(
            "ffmpeg", "-y", "-f", "lavfi", "-i", "testsrc=duration=3:size=320x240:rate=30",
            "-vf", "select='not(mod(n\\,3))'", "-vsync", "vfr",
            "-c:v", "libx264", "-pix_fmt", "yuv420p", video.absolutePath,
        ).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start().waitFor()

        val timestamps = probeFrameTimestamps(video)!!
        val durations = frameDurationsSeconds(timestamps, fallbackSeconds = 0.1)

        assertTrue(Math.abs(durations.sum() - 3.0) < 0.05, "expected total near 3.0s, got ${durations.sum()}")
        video.delete()
    }

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
    fun `watchForActualDimensions reads ffmpeg's own reported output size from real stderr output`() {
        // Regression test: ffmpeg's stream description line for a rawvideo BGRA output includes a
        // FourCC hex literal ("0x41524742") *before* the actual WxH later on the same line -- a
        // naive \d+x\d+ search matches that hex literal ("0" x "41524742") instead of the real
        // dimensions. This spawns the exact kind of process FfmpegVideoPlayer's DisposableEffect
        // does and verifies the real output dimensions are extracted correctly, not the FourCC.
        val video = File.createTempFile("ffmpeg-dims-test-", ".mp4")
        video.deleteOnExit()
        ProcessBuilder(
            "ffmpeg", "-y", "-f", "lavfi", "-i", "testsrc=duration=1:size=96x64:rate=5",
            "-c:v", "libx264", "-pix_fmt", "yuv420p", video.absolutePath,
        ).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start().waitFor()

        val process = ProcessBuilder(
            "ffmpeg", "-i", video.absolutePath, "-f", "rawvideo", "-pix_fmt", "bgra", "-an", "-",
        ).start()
        process.inputStream.close() // don't need the pixel data itself for this test

        val dimensions = watchForActualDimensions(process, fallback = -1 to -1).get(5, java.util.concurrent.TimeUnit.SECONDS)

        assertEquals(96 to 64, dimensions)
        process.destroyForcibly()
        video.delete()
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
