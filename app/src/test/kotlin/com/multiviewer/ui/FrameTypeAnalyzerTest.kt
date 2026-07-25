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
}
