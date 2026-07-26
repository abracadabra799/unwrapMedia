package com.multiviewer.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StreamCodecDetailsTest {
    @Test
    fun `probeStreamDetails extracts video codec fields from a real synthetic video`() {
        val video = File.createTempFile("stream-codec-details-test-", ".mp4")
        video.deleteOnExit()
        val generate = ProcessBuilder(
            "ffmpeg", "-y", "-f", "lavfi", "-i", "testsrc=duration=2:size=64x48:rate=10",
            video.absolutePath,
        ).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        generate.waitFor()

        val details = probeStreamDetails(video)

        assertTrue(details != null)
        val videoFields = details!!.videoFields.associate { it.label to it.value }
        assertEquals("High 4:4:4 Predictive", videoFields["Profile"])
        assertEquals("10", videoFields["Level"])
        assertEquals("4:4:4", videoFields["Chroma Subsampling"])
        assertEquals("8 bit", videoFields["Bit Depth"])
        assertEquals("Constant", videoFields["Frame Rate Mode"])
        assertTrue(videoFields["Bit Rate"]?.contains("Kbps") == true, "Expected a Kbps bit rate, got ${videoFields["Bit Rate"]}")
        assertEquals("0:00:02.000", videoFields["Duration"])
        assertEquals("20", videoFields["Frame Count"])
        assertTrue(details.audioFields.isEmpty(), "This synthetic video has no audio stream")
        video.delete()
    }

    @Test
    fun `probeStreamDetails returns null for a nonexistent file`() {
        assertNull(probeStreamDetails(File("/nonexistent/path/does-not-exist.mp4")))
    }
}
