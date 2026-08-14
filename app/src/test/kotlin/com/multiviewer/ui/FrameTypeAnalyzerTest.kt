package com.multiviewer.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FrameTypeAnalyzerTest {
    @Test
    fun `probeFrameTypes reads type, size, and timestamp for every frame of a real synthetic video`() {
        val video = File.createTempFile("frame-type-analyzer-test-", ".mp4")
        video.deleteOnExit()
        val generate = ProcessBuilder(
            "ffmpeg", "-y", "-f", "lavfi", "-i", "testsrc=duration=2:size=64x48:rate=10",
            video.absolutePath,
        ).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        generate.waitFor()

        val frames = probeFrameTypes(video)

        assertTrue(frames != null && frames.size == 20, "Expected 20 frames (2s at 10fps), got ${frames?.size}")
        assertEquals(0, frames!![0].index)
        assertEquals('I', frames[0].type)
        assertTrue(frames[0].sizeBytes > 0)
        assertEquals(0.0, frames[0].ptsSeconds)
        assertTrue(frames[0].byteOffset != null && frames[0].byteOffset!! >= 0, "Expected a non-null byte offset, got ${frames[0].byteOffset}")
        assertEquals(19, frames[19].index)
        assertTrue(frames.any { it.type == 'P' }, "Expected at least one P frame")
        assertTrue(frames.any { it.type == 'B' }, "Expected at least one B frame")
        video.delete()
    }

    @Test
    fun `probeFrameTypes returns null for a nonexistent file`() {
        assertNull(probeFrameTypes(File("/nonexistent/path/does-not-exist.mp4")))
    }

    private val frames = listOf(
        FrameInfo(index = 0, type = 'I', sizeBytes = 1000, ptsSeconds = 0.0),
        FrameInfo(index = 1, type = 'P', sizeBytes = 500, ptsSeconds = 0.04),
        FrameInfo(index = 2, type = 'P', sizeBytes = 400, ptsSeconds = 0.08),
        FrameInfo(index = 3, type = 'P', sizeBytes = 450, ptsSeconds = 0.12),
    )

    @Test
    fun `currentFrameIndex returns -1 before playback has started`() {
        assertEquals(-1, currentFrameIndex(frames, 0.0))
    }

    @Test
    fun `currentFrameIndex returns the last frame whose pts has passed`() {
        assertEquals(1, currentFrameIndex(frames, 0.06))
    }

    @Test
    fun `currentFrameIndex returns the last frame when playback is past the final pts`() {
        assertEquals(3, currentFrameIndex(frames, 999.0))
    }

    @Test
    fun `currentFrameIndex returns -1 for an empty frame list`() {
        assertEquals(-1, currentFrameIndex(emptyList(), 5.0))
    }

    // I P P B P (indices 0-4), a second GOP starting at index 5 (I P P) -- covers a frame that
    // IS its own keyframe, several frames at increasing distance within the same GOP, and a frame
    // in a LATER GOP correctly ignoring the earlier one's keyframe.
    private val multiGopFrames = listOf(
        FrameInfo(index = 0, type = 'I', sizeBytes = 1000, ptsSeconds = 0.0),
        FrameInfo(index = 1, type = 'P', sizeBytes = 500, ptsSeconds = 0.04),
        FrameInfo(index = 2, type = 'P', sizeBytes = 400, ptsSeconds = 0.08),
        FrameInfo(index = 3, type = 'B', sizeBytes = 100, ptsSeconds = 0.12),
        FrameInfo(index = 4, type = 'P', sizeBytes = 450, ptsSeconds = 0.16),
        FrameInfo(index = 5, type = 'I', sizeBytes = 1200, ptsSeconds = 0.20),
        FrameInfo(index = 6, type = 'P', sizeBytes = 300, ptsSeconds = 0.24),
        FrameInfo(index = 7, type = 'P', sizeBytes = 350, ptsSeconds = 0.28),
    )

    @Test
    fun `gopPositionOf returns distance 0 for a frame that is itself a keyframe`() {
        assertEquals(GopPosition(keyframeIndex = 0, distanceFromKeyframe = 0), gopPositionOf(multiGopFrames, 0))
        assertEquals(GopPosition(keyframeIndex = 5, distanceFromKeyframe = 0), gopPositionOf(multiGopFrames, 5))
    }

    @Test
    fun `gopPositionOf returns the correct distance within the first GOP`() {
        assertEquals(GopPosition(keyframeIndex = 0, distanceFromKeyframe = 3), gopPositionOf(multiGopFrames, 3))
    }

    @Test
    fun `gopPositionOf uses the nearest PRECEDING keyframe, not an earlier one, once a new GOP starts`() {
        assertEquals(GopPosition(keyframeIndex = 5, distanceFromKeyframe = 2), gopPositionOf(multiGopFrames, 7))
    }

    @Test
    fun `gopPositionOf returns null for an out-of-bounds index`() {
        assertNull(gopPositionOf(multiGopFrames, -1))
        assertNull(gopPositionOf(multiGopFrames, multiGopFrames.size))
    }
}
