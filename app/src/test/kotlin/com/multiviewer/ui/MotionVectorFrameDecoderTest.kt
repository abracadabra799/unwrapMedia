package com.multiviewer.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class MotionVectorFrameDecoderTest {
    @Test
    fun `buildMotionVectorFfmpegArgs produces the accurate-seek plus codecview command in order`() {
        val args = buildMotionVectorFfmpegArgs("/usr/bin/ffmpeg", "/tmp/video.mp4", 1.5)
        assertEquals(
            listOf(
                "/usr/bin/ffmpeg", "-y", "-i", "/tmp/video.mp4",
                "-ss", "1.5",
                "-flags2", "+export_mvs",
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
}
