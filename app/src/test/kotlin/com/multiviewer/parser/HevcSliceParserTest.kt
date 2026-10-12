package com.multiviewer.parser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class HevcSliceParserTest {

    @Test
    fun `parses IDR slice header with slice_qp_delta and computes final slice QP`() {
        // Build an IDR slice NAL unit
        // NAL Header: forbidden_zero_bit=0, nal_unit_type=19 (IDR_W_RADL, 6 bits: 010011 -> 0x26), nuh_layer_id=0, nuh_temporal_id_plus1=1
        // First 2 bytes: 0x26, 0x01
        // Payload:
        // first_slice_segment_in_pic_flag = 1 (1 bit)
        // no_output_of_prior_pics_flag = 0 (1 bit)
        // slice_pic_parameter_set_id = 0 (ue: '1')
        // slice_type = 2 (I-Slice, ue: '011')
        // slice_qp_delta = +4 (se: 4 -> codeNum 8 -> '0001000')
        val bitWriter = TestBitWriter()
        bitWriter.u(1, 1) // first_slice_segment_in_pic_flag
        bitWriter.u(1, 0) // no_output_of_prior_pics_flag
        bitWriter.u(1, 1) // slice_pic_parameter_set_id = 0 (ue: '1')
        bitWriter.u(3, 3) // slice_type = 2 (I-Slice, ue: '011')
        bitWriter.u(7, 8) // slice_qp_delta = +4 (se: codeNum 8 -> '0001000')

        val nalBytes = byteArrayOf(0x26, 0x01) + bitWriter.toBytes()

        val pps = HevcPps(
            ppsId = 0, spsId = 0, dependentSliceSegmentsEnabledFlag = false,
            signDataHidingEnabledFlag = false, cabacInitPresentFlag = false,
            constrainedIntraPredFlag = false, transformSkipEnabledFlag = false,
            cuQpDeltaEnabledFlag = false, weightedPredFlag = false,
            weightedBipredFlag = false, tilesEnabledFlag = false,
            entropyCodingSyncEnabledFlag = false, deblockingFilterControlPresentFlag = false,
            ppsDeblockingFilterDisabledFlag = null, initQpMinus26 = -2, // base = 26 - 2 = 24
        )

        val sliceInfo = parseHevcIdrSliceHeader(nalBytes, pps)
        assertNotNull(sliceInfo)
        assertEquals("I-Slice", sliceInfo.sliceType)
        assertEquals(4, sliceInfo.sliceQpDelta)
        assertEquals(28, sliceInfo.sliceQp) // 26 + (-2) + 4 = 28
        assertTrue(sliceInfo.isIdr)
    }

    @Test
    fun `returns null for non-VCL NAL unit`() {
        // NAL type 32 (VPS: 32 << 1 = 64 = 0x40)
        val nalBytes = byteArrayOf(0x40, 0x01, 0x00)
        val sliceInfo = parseHevcIdrSliceHeader(nalBytes, null)
        assertEquals(null, sliceInfo)
    }

    @Test
    fun `probeHevcFrameSliceQpWithFfmpeg returns slice info for real fixture`() {
        val resource = javaClass.getResource("/hdr/hevc_hev1.mp4")
        if (resource != null) {
            val file = java.io.File(resource.toURI())
            val sliceInfo = probeHevcFrameSliceQpWithFfmpeg(file, 0.0)
            assertNotNull(sliceInfo)
            assertTrue(sliceInfo.sliceQp in 0..51)
        }
    }
}
