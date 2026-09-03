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
    fun `formatMmSsMs renders minutes, seconds, and milliseconds`() {
        assertEquals("1:03.133", formatMmSsMs(63.1332))
        assertEquals("0:00.000", formatMmSsMs(0.0))
        assertEquals("0:59.999", formatMmSsMs(59.9994)) // truncates, doesn't round up into 1:00.000
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
        assertTrue(info != null && info.duration >= 1.9, "Expected duration >= 1.9, got ${info?.duration}")
        video.delete()
    }

    @Test
    fun `probeVideo correctly extracts duration for WebM, FLV, AVI, WMV videos where stream duration may be NA`() {
        val webm = File.createTempFile("test-probe-", ".webm")
        webm.deleteOnExit()
        ProcessBuilder("ffmpeg", "-y", "-f", "lavfi", "-i", "testsrc=duration=2:size=64x48:rate=10", webm.absolutePath)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start().waitFor()
        val webmInfo = probeVideo(webm)
        assertTrue(webmInfo != null && webmInfo.duration >= 1.9, "WebM duration expected >= 1.9, got ${webmInfo?.duration}")

        val flv = File.createTempFile("test-probe-", ".flv")
        flv.deleteOnExit()
        ProcessBuilder("ffmpeg", "-y", "-f", "lavfi", "-i", "testsrc=duration=2:size=64x48:rate=10", flv.absolutePath)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start().waitFor()
        val flvInfo = probeVideo(flv)
        assertTrue(flvInfo != null && flvInfo.duration >= 1.9, "FLV duration expected >= 1.9, got ${flvInfo?.duration}")

        val avi = File.createTempFile("test-probe-", ".avi")
        avi.deleteOnExit()
        ProcessBuilder("ffmpeg", "-y", "-f", "lavfi", "-i", "testsrc=duration=2:size=64x48:rate=10", avi.absolutePath)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start().waitFor()
        val aviInfo = probeVideo(avi)
        assertTrue(aviInfo != null && aviInfo.duration >= 1.9, "AVI duration expected >= 1.9, got ${aviInfo?.duration}")

        val wmv = File.createTempFile("test-probe-", ".wmv")
        wmv.deleteOnExit()
        ProcessBuilder("ffmpeg", "-y", "-f", "lavfi", "-i", "testsrc=duration=2:size=64x48:rate=10", wmv.absolutePath)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start().waitFor()
        val wmvInfo = probeVideo(wmv)
        assertTrue(wmvInfo != null && wmvInfo.duration >= 1.9, "WMV duration expected >= 1.9, got ${wmvInfo?.duration}")

        webm.delete()
        flv.delete()
        avi.delete()
        wmv.delete()
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
    fun `parseFfmpegOutputDimensionsLine matches real device footage lacking a SAR bracket`() {
        // Regression test: this exact line (captured from a real iPhone-shot MOV with a non-90
        // -180-270-degree rotation transform, ffmpeg 8.1.2) has no "[SAR ...]" suffix after the
        // WxH -- the previous regex required one and silently never matched, falling back to
        // probeVideo()'s predicted (and here, wrong) dimensions, which misaligned every raw frame
        // boundary into a scrambled image.
        val line = "  Stream #0:0(und): Video: rawvideo (BGRA / 0x41524742), " +
            "bgra(pc, gbr/smpte432/bt709, progressive), 1308x1744, q=2-31, 4375436 kb/s, 59.94 fps, 59.94 tbn (default)"
        assertEquals(1308 to 1744, parseFfmpegOutputDimensionsLine(line))
    }

    @Test
    fun `parseFfmpegOutputDimensionsLine still matches when a SAR bracket is present`() {
        val line = "Stream #0:0(und): Video: rawvideo (BGRA / 0x41524742), bgra(...), 480x640 [SAR 1:1 DAR 3:4], ..."
        assertEquals(480 to 640, parseFfmpegOutputDimensionsLine(line))
    }

    @Test
    fun `parseFfmpegOutputDimensionsLine does not false-positive on an input stream's FourCC hex literal alone`() {
        val line = "  Stream #0:1(und): Video: hevc (Rext) (hvc1 / 0x31637668), gray(pc, smpte170m/smpte432/bt709), 256x192, 6 kb/s"
        assertNull(parseFfmpegOutputDimensionsLine(line))
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

    @Test
    fun `ffmpegPipeArgs requests passthrough frame timing, not ffmpeg's own frame-rate conversion`() {
        // Some device footage (verified against a real Samsung Motion Photo clip: r_frame_rate
        // 120/1 vs avg_frame_rate ~30.3) declares a container frame-rate far above its real content
        // rate. Without this flag, ffmpeg's default CFR behavior silently duplicates each real
        // decoded frame ~4x to fill that declared rate -- the piped frame count (measured: 372)
        // no longer matches probeFrameTimestamps' real per-frame count (measured: 94), so the
        // reader loop keeps reading/pacing ~278 extra duplicate frames using the fallback duration
        // after the real per-frame durations list is exhausted, well past when playedSeconds
        // already reached info.duration -- the video visibly keeps playing for several extra
        // seconds after its own progress bar reads 100%. "-fps_mode passthrough" (ffmpeg's current
        // name for legacy "-vsync 0") outputs decoded frames as-is, with no duplication/dropping,
        // matching probeFrameTimestamps' real frame count exactly (verified: 94 == 94).
        val args = ffmpegPipeArgs("ffmpeg", "/some/file.mp4", emptyList())

        val fpsModeIndex = args.indexOf("-fps_mode")
        assertTrue(fpsModeIndex >= 0, "expected -fps_mode flag in ffmpeg pipe args, got $args")
        assertEquals("passthrough", args.getOrNull(fpsModeIndex + 1))
    }

    @Test
    fun `shouldSkipFrame is false while cumulative lag is below the frame's own budget`() {
        assertTrue(!shouldSkipFrame(cumulativeLagMillis = 0L, budgetMillis = 33L))
        assertTrue(!shouldSkipFrame(cumulativeLagMillis = 32L, budgetMillis = 33L))
    }

    @Test
    fun `shouldSkipFrame is true once cumulative lag reaches or exceeds the frame's own budget`() {
        assertTrue(shouldSkipFrame(cumulativeLagMillis = 33L, budgetMillis = 33L))
        assertTrue(shouldSkipFrame(cumulativeLagMillis = 100L, budgetMillis = 33L))
    }

    @Test
    fun `laggedAfterFrame adds no lag when processing finished within budget`() {
        assertEquals(0L, laggedAfterFrame(cumulativeLagMillis = 0L, budgetMillis = 33L, elapsedMillis = 20L))
        assertEquals(0L, laggedAfterFrame(cumulativeLagMillis = 0L, budgetMillis = 33L, elapsedMillis = 33L))
    }

    @Test
    fun `laggedAfterFrame accumulates the overrun when processing exceeded budget`() {
        assertEquals(7L, laggedAfterFrame(cumulativeLagMillis = 0L, budgetMillis = 33L, elapsedMillis = 40L))
        assertEquals(17L, laggedAfterFrame(cumulativeLagMillis = 10L, budgetMillis = 33L, elapsedMillis = 40L))
    }

    @Test
    fun `laggedAfterSkip pays down exactly one frame's budget worth of lag`() {
        assertEquals(7L, laggedAfterSkip(cumulativeLagMillis = 40L, budgetMillis = 33L))
        assertEquals(0L, laggedAfterSkip(cumulativeLagMillis = 33L, budgetMillis = 33L))
    }

    @Test
    fun `sustained per-frame overrun does not grow cumulative lag unboundedly once skip catch-up applies`() {
        // Regression test for the root cause of "video plays much longer than its real duration"
        // (measured: raw BGRA frame read + Skia bitmap construction alone already exceeds a 4K30
        // frame's 33ms budget, at ~40ms/frame). Without a catch-up mechanism, cumulativeLagMillis
        // would grow by (elapsed - budget) on every single frame forever, so total playback wall
        // time keeps drifting further from real duration for as long as the video plays. With
        // skip-based catch-up wired in, lag is paid back down whenever it reaches a full frame's
        // budget, so it stays bounded instead of growing linearly across the whole video.
        val budgetMillis = 33L
        val perFrameElapsedMillis = 40L // ~1.2x budget, matching the measured 4K30 ratio
        var cumulativeLagMillis = 0L
        var skippedCount = 0
        repeat(300) { // 10 seconds worth of frames at ~30fps
            if (shouldSkipFrame(cumulativeLagMillis, budgetMillis)) {
                skippedCount++
                cumulativeLagMillis = laggedAfterSkip(cumulativeLagMillis, budgetMillis)
            } else {
                cumulativeLagMillis = laggedAfterFrame(cumulativeLagMillis, budgetMillis, perFrameElapsedMillis)
            }
        }
        assertTrue(skippedCount > 0, "expected some frames to be skipped under sustained overrun")
        assertTrue(
            cumulativeLagMillis < budgetMillis * 10,
            "cumulative lag should stay bounded across 300 frames of sustained overrun, got ${cumulativeLagMillis}ms",
        )
    }

    @Test
    fun `no frames are skipped when processing always finishes within budget`() {
        val budgetMillis = 33L
        var cumulativeLagMillis = 0L
        var skippedCount = 0
        repeat(100) {
            if (shouldSkipFrame(cumulativeLagMillis, budgetMillis)) {
                skippedCount++
                cumulativeLagMillis = laggedAfterSkip(cumulativeLagMillis, budgetMillis)
            } else {
                cumulativeLagMillis = laggedAfterFrame(cumulativeLagMillis, budgetMillis, elapsedMillis = 10L)
            }
        }
        assertEquals(0, skippedCount)
        assertEquals(0L, cumulativeLagMillis)
    }
}
