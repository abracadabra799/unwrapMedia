package com.multiviewer.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MotionVectorFrameDecoderTest {
    @Test
    fun `buildMotionVectorFfmpegArgs places -flags2 before -i and -ss after -i`() {
        // -flags2 +export_mvs is a per-input decoder option and only takes effect placed before
        // -i (verified against a real ffmpeg build: placed after -i it's silently ignored and no
        // motion vectors are exported); -ss placed after -i is ffmpeg's accurate seek. Both
        // requirements hold simultaneously and this is the exact command shape that was confirmed
        // to produce visible motion vector arrows.
        val args = buildMotionVectorFfmpegArgs("/usr/bin/ffmpeg", "/tmp/video.mp4", 1.5)
        assertEquals(
            listOf(
                "/usr/bin/ffmpeg", "-y",
                "-flags2", "+export_mvs",
                "-i", "/tmp/video.mp4",
                "-ss", "1.5",
                "-vf", "codecview=mv=pf+bf+bb",
                "-frames:v", "1", "-update", "1",
            ),
            args,
        )
    }

    @Test
    fun `buildMotionVectorFfmpegArgs formats an integer-valued pts seconds correctly`() {
        val args = buildMotionVectorFfmpegArgs("/usr/bin/ffmpeg", "/tmp/video.mp4", 2.0)
        assertEquals("2.0", args[args.indexOf("-ss") + 1])
    }

    @Test
    fun `motionVectorsSupportedFor is true only for h264`() {
        assertTrue(motionVectorsSupportedFor("h264"))
        assertFalse(motionVectorsSupportedFor("hevc"))
        assertFalse(motionVectorsSupportedFor("vp9"))
        assertFalse(motionVectorsSupportedFor(null))
    }
}
