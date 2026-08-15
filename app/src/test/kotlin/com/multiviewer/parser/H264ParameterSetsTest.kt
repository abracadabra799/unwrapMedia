package com.multiviewer.parser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class H264ParameterSetsTest {
    // Real H.264 SPS (25 bytes) and PPS (6 bytes), from the same x264-encoded file, NAL headers
    // included -- every field asserted below was cross-verified by hand against
    // `ffmpeg -bsf:v trace_headers` output (see the design spec).
    private val realSps = byteArrayOf(
        0x67, 0xf4.toByte(), 0x00, 0x0d, 0x91.toByte(), 0x9b.toByte(), 0x28, 0x28,
        0x3f, 0x60, 0x22, 0x00, 0x00, 0x03, 0x00, 0x02,
        0x00, 0x00, 0x03, 0x00, 0x64, 0x1e, 0x28, 0x53.toByte(), 0x2c,
    )
    private val realPps = byteArrayOf(0x68, 0xeb.toByte(), 0xe3.toByte(), 0xc4.toByte(), 0x48, 0x44)

    @Test
    fun `parseH264Sps extracts every curated field correctly from a real SPS`() {
        val sps = parseH264Sps(realSps)
        assertNotNull(sps)
        assertEquals(0, sps.seqParameterSetId)
        assertEquals(244, sps.profileIdc)
        assertEquals(13, sps.levelIdc)
        assertEquals(3, sps.chromaFormatIdc)
        assertEquals(8, sps.bitDepthLuma)
        assertEquals(8, sps.bitDepthChroma)
        assertFalse(sps.scalingMatrixUnsupported)
        assertEquals(0, sps.picOrderCntType)
        assertEquals(4, sps.maxNumRefFrames)
        assertNull(sps.frameCropping) // frame_cropping_flag=0 in this file
        val vui = assertNotNull(sps.vui)
        assertEquals(1, vui.aspectRatioIdc)
        assertNull(vui.sarWidth) // aspect_ratio_idc=1 is not 255 (Extended_SAR), so no SAR fields
        assertNull(vui.videoFullRangeFlag) // video_signal_type_present_flag=0 in this file
    }

    @Test
    fun `parseH264Pps extracts every curated field correctly from a real PPS`() {
        val pps = parseH264Pps(realPps)
        assertNotNull(pps)
        assertEquals(0, pps.picParameterSetId)
        assertEquals(0, pps.seqParameterSetId)
        assertTrue(pps.entropyCodingModeFlag) // CABAC
        assertEquals(true, pps.deblockingFilterControlPresentFlag)
        assertEquals(true, pps.transform8x8ModeFlag)
    }

    @Test
    fun `parseH264Sps returns null for empty input`() {
        assertNull(parseH264Sps(ByteArray(0)))
    }

    @Test
    fun `parseH264Pps returns null for empty input`() {
        assertNull(parseH264Pps(ByteArray(0)))
    }

    @Test
    fun `parseH264Sps returns a partial result with scalingMatrixUnsupported when the scaling matrix flag is set`() {
        // Hand-constructed: same profile_idc=244 (triggers the chroma_format_idc/bit_depth block)
        // as the real SPS, with seq_scaling_matrix_present_flag forced to 1. Traced mechanically
        // (a standalone bit-reader simulation, not just by hand) that this flag lands at byte
        // index 5, bit index 2 (0 = MSB) of the real SPS -- byte[5] = 0x9b = 10011011, flipping
        // bit 2 from 0 to 1 gives 10111011 = 0xBB.
        val truncated = realSps.copyOf(12)
        truncated[5] = 0xBB.toByte()
        val sps = parseH264Sps(truncated)
        assertNotNull(sps)
        assertTrue(sps.scalingMatrixUnsupported)
    }
}
