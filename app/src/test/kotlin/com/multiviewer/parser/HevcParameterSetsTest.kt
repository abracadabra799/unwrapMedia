package com.multiviewer.parser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HevcParameterSetsTest {
    // Real HEVC VPS/SPS/PPS, from a locally-recorded HEVC (Main) file, NAL headers included --
    // every field asserted below was cross-verified by hand against `ffmpeg -bsf:v trace_headers`
    // output (see the design spec).
    private val realVps = byteArrayOf(
        0x40, 0x01, 0x0c, 0x01, 0xff.toByte(), 0xff.toByte(), 0x01, 0x60,
        0x00, 0x00, 0x03, 0x00, 0xb0.toByte(), 0x00, 0x00, 0x03,
        0x00, 0x00, 0x03, 0x00, 0x78, 0xac.toByte(), 0x09, 0x00,
    )
    private val realSps = byteArrayOf(
        0x42, 0x01, 0x01, 0x01, 0x60, 0x00, 0x00, 0x03,
        0x00, 0xb0.toByte(), 0x00, 0x00, 0x03, 0x00, 0x00, 0x03,
        0x00, 0x78, 0xa0.toByte(), 0x03, 0x70, 0x80.toByte(), 0x3e, 0x1c,
        0xb2.toByte(), 0xe5.toByte(), 0xae.toByte(), 0xe4.toByte(), 0xc9.toByte(), 0x2e, 0xa6.toByte(), 0xe0.toByte(),
        0xa0.toByte(), 0xc0.toByte(), 0xa0.toByte(), 0x5d, 0xa1.toByte(), 0x42, 0x50, 0x00,
    )
    private val realPps = byteArrayOf(
        0x44, 0x01, 0xc1.toByte(), 0xe3.toByte(), 0x0f, 0x09, 0x41, 0xef.toByte(),
        0x61, 0x28, 0x00,
    )

    @Test
    fun `parseHevcVps extracts every curated field correctly from a real VPS`() {
        val vps = parseHevcVps(realVps)
        assertNotNull(vps)
        assertEquals(0, vps.vpsId)
        assertEquals(0, vps.maxSubLayersMinus1)
        assertFalse(vps.ptlUnsupported)
        val ptl = assertNotNull(vps.ptl)
        assertEquals(0, ptl.generalProfileSpace)
        assertFalse(ptl.generalTierFlag)
        assertEquals(1, ptl.generalProfileIdc)
        assertEquals(120, ptl.generalLevelIdc)
    }

    @Test
    fun `parseHevcSps extracts every curated field correctly from a real SPS`() {
        val sps = parseHevcSps(realSps)
        assertNotNull(sps)
        assertEquals(0, sps.spsId)
        assertEquals(0, sps.vpsId)
        assertEquals(0, sps.maxSubLayersMinus1)
        assertEquals(0, sps.ptl.generalProfileSpace)
        assertFalse(sps.ptl.generalTierFlag)
        assertEquals(1, sps.ptl.generalProfileIdc)
        assertEquals(120, sps.ptl.generalLevelIdc)
        assertEquals(1, sps.chromaFormatIdc)
        assertEquals(1760, sps.picWidth)
        assertEquals(992, sps.picHeight)
        assertEquals(8, sps.bitDepthLuma)
        assertEquals(8, sps.bitDepthChroma)
        val vui = assertNotNull(sps.vui)
        assertEquals(true, vui.videoFullRangeFlag)
        assertEquals(5, vui.colourPrimaries)
        assertEquals(6, vui.transferCharacteristics)
        assertEquals(5, vui.matrixCoefficients)
    }

    @Test
    fun `parseHevcPps extracts every curated field correctly from a real PPS`() {
        val pps = parseHevcPps(realPps)
        assertNotNull(pps)
        assertEquals(0, pps.ppsId)
        assertEquals(0, pps.spsId)
        assertFalse(pps.dependentSliceSegmentsEnabledFlag)
        assertTrue(pps.signDataHidingEnabledFlag)
        assertTrue(pps.cabacInitPresentFlag)
        assertFalse(pps.constrainedIntraPredFlag)
        assertFalse(pps.transformSkipEnabledFlag)
        assertTrue(pps.cuQpDeltaEnabledFlag)
        assertFalse(pps.weightedPredFlag)
        assertFalse(pps.weightedBipredFlag)
        assertTrue(pps.tilesEnabledFlag)
        assertFalse(pps.entropyCodingSyncEnabledFlag)
        assertTrue(pps.deblockingFilterControlPresentFlag)
        assertEquals(false, pps.ppsDeblockingFilterDisabledFlag)
    }

    @Test
    fun `parseHevcVps returns null for empty input`() {
        assertNull(parseHevcVps(ByteArray(0)))
    }

    @Test
    fun `parseHevcSps returns null for empty input`() {
        assertNull(parseHevcSps(ByteArray(0)))
    }

    @Test
    fun `parseHevcPps returns null for empty input`() {
        assertNull(parseHevcPps(ByteArray(0)))
    }

    @Test
    fun `parseHevcVps returns a partial result with ptlUnsupported when max_sub_layers_minus1 is nonzero`() {
        // Hand-constructed from the real VPS: vps_max_sub_layers_minus1 is a 3-bit field starting
        // at bit 12 relative to the VPS payload (after the 2-byte NAL header), which is bits 4-6 of
        // byte index 3 (0xf4001 header consumes bytes 0-1; payload byte 2 = index 2, byte 3 = index
        // 3). Traced mechanically: byte[3] = 0x01 = 00000001. The field's top bit is byte[3] bit 4
        // (0-indexed from MSB), worth 1 << (7-4) = 0x08. Setting that bit changes byte[3] to 0x09 =
        // 00001001, making vps_max_sub_layers_minus1 = bits 4-6 = "100" = 4 (was "000" = 0).
        val mutated = realVps.copyOf()
        mutated[3] = (mutated[3].toInt() or 0x08).toByte()
        val vps = parseHevcVps(mutated)
        assertNotNull(vps)
        assertEquals(4, vps.maxSubLayersMinus1)
        assertTrue(vps.ptlUnsupported)
        assertNull(vps.ptl)
    }
}
