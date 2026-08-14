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
}
