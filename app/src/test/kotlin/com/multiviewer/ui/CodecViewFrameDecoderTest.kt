package com.multiviewer.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CodecViewFrameDecoderTest {
    @Test
    fun `buildCodecViewFfmpegArgs requests both side-data exports before -i for motion vectors mode`() {
        val args = buildCodecViewFfmpegArgs("/usr/bin/ffmpeg", "/tmp/video.mp4", 1.5, CodecViewMode.MOTION_VECTORS)
        assertEquals(
            listOf(
                "/usr/bin/ffmpeg", "-y",
                "-flags2", "+export_mvs",
                "-export_side_data", "venc_params",
                "-i", "/tmp/video.mp4",
                "-ss", "1.5",
                "-vf", "codecview=mv=pf+bf+bb",
                "-frames:v", "1", "-update", "1",
            ),
            args,
        )
    }

    @Test
    fun `buildCodecViewFfmpegArgs uses the qp filter for QP heatmap mode, same side-data exports`() {
        val args = buildCodecViewFfmpegArgs("/usr/bin/ffmpeg", "/tmp/video.mp4", 1.5, CodecViewMode.QP_HEATMAP)
        assertEquals(
            listOf(
                "/usr/bin/ffmpeg", "-y",
                "-flags2", "+export_mvs",
                "-export_side_data", "venc_params",
                "-i", "/tmp/video.mp4",
                "-ss", "1.5",
                "-vf", "codecview=qp=1",
                "-frames:v", "1", "-update", "1",
            ),
            args,
        )
    }

    @Test
    fun `buildCodecViewFfmpegArgs formats an integer-valued pts seconds correctly`() {
        val args = buildCodecViewFfmpegArgs("/usr/bin/ffmpeg", "/tmp/video.mp4", 2.0, CodecViewMode.MOTION_VECTORS)
        assertEquals("2.0", args[args.indexOf("-ss") + 1])
    }

    @Test
    fun `codecViewSupportedFor is true only for h264, independently per mode`() {
        assertTrue(codecViewSupportedFor(CodecViewMode.MOTION_VECTORS, "h264"))
        assertTrue(codecViewSupportedFor(CodecViewMode.QP_HEATMAP, "h264"))
        assertFalse(codecViewSupportedFor(CodecViewMode.MOTION_VECTORS, "hevc"))
        assertFalse(codecViewSupportedFor(CodecViewMode.QP_HEATMAP, "hevc"))
        assertFalse(codecViewSupportedFor(CodecViewMode.MOTION_VECTORS, null))
        assertFalse(codecViewSupportedFor(CodecViewMode.QP_HEATMAP, null))
    }
}
