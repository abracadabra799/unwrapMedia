package com.multiviewer.parser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class HevcParameterSetExtractionTest {
    // Synthetic hvcC payload (structure only -- VPS/SPS/PPS contents don't need to be real, valid
    // bitstream since extractHvcCRawParameterSets never parses them, only slices them out by their
    // declared lengths): 23-byte fixed header (length_size_minus1=3 -> length_size=4, num_arrays=3),
    // then one VPS array (1 NAL, 3 bytes), one SPS array (1 NAL, 3 bytes), one PPS array (1 NAL, 2
    // bytes). Array-type bytes 0x20/0x21/0x22 encode array_completeness=0, reserved=0, and
    // nal_unit_type 32/33/34 in the low 6 bits (VPS/SPS/PPS respectively).
    private fun hvcCPayload(): ByteArray = byteArrayOf(
        0x01, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00, 0x1e, 0xf0.toByte(), 0x00, 0xfc.toByte(),
        0xfd.toByte(), 0xf8.toByte(), 0xf8.toByte(), 0x00, 0x00, 0x03, 0x03,
        0x20, 0x00, 0x01, 0x00, 0x03, 0x40, 0xaa.toByte(), 0xbb.toByte(),
        0x21, 0x00, 0x01, 0x00, 0x03, 0x42, 0xcc.toByte(), 0xdd.toByte(),
        0x22, 0x00, 0x01, 0x00, 0x02, 0x44, 0xee.toByte(),
    )

    private fun hvcCBoxNode(payload: ByteArray): Pair<BoxNode, java.io.File> {
        val headerSize = 8
        val header = ByteArray(headerSize) // irrelevant filler, box parsing reads by absolute offset
        val file = fileOf(header + payload)
        val node = BoxNode(type = "hvcC", offset = 0, headerSize = headerSize, size = (headerSize + payload.size).toLong())
        return node to file
    }

    @Test
    fun `extractHvcCRawParameterSets reads length_size and the declared VPS, SPS, and PPS NAL bytes and offsets`() {
        val (node, file) = hvcCBoxNode(hvcCPayload())
        val result = extractHvcCRawParameterSets(file, node)
        assertNotNull(result)
        assertEquals(4, result.lengthSize) // length_size_minus_one=3 -> 3+1=4
        assertEquals(1, result.vpsList.size)
        assertEquals(byteArrayOf(0x40, 0xaa.toByte(), 0xbb.toByte()).toList(), result.vpsList[0].bytes.toList())
        // headerSize=8 + payload index 28 (VPS array: 23-byte fixed header, then type(1)+numNalus(2)
        // +length(2)=5 bytes before the NAL bytes start at payload index 23+5=28) = file offset 36.
        assertEquals(36L, result.vpsList[0].offset)
        assertEquals(1, result.spsList.size)
        assertEquals(byteArrayOf(0x42, 0xcc.toByte(), 0xdd.toByte()).toList(), result.spsList[0].bytes.toList())
        // SPS array starts right after VPS array's 8 bytes (payload index 23+8=31); NAL bytes start
        // 5 bytes into it, at payload index 36 -> file offset 44.
        assertEquals(44L, result.spsList[0].offset)
        assertEquals(1, result.ppsList.size)
        assertEquals(byteArrayOf(0x44, 0xee.toByte()).toList(), result.ppsList[0].bytes.toList())
        // PPS array starts right after SPS array's 8 bytes (payload index 31+8=39); NAL bytes start
        // 5 bytes into it, at payload index 44 -> file offset 52.
        assertEquals(52L, result.ppsList[0].offset)
    }

    @Test
    fun `extractHvcCRawParameterSets returns null when the box is too short for its fixed header`() {
        val (node, file) = hvcCBoxNode(byteArrayOf(0x01, 0x01, 0x00)) // only 3 bytes, needs 23
        assertNull(extractHvcCRawParameterSets(file, node))
    }

    // Length-prefixed samples (hvcC-style, length_size=4): one 3-byte non-VCL NAL (type 39, prefix
    // SEI) followed by a 5-byte VCL NAL (type 20, IDR_W_RADL) whose RBSP starts with a real
    // slice-segment-header prefix (first_slice_segment_in_pic_flag=1,
    // no_output_of_prior_pics_flag=0, slice_pic_parameter_set_id=0 -- same bytes verified in
    // HevcParameterSetsTest's design-spec source file).
    private fun sampleBytes(): ByteArray = byteArrayOf(
        0x00, 0x00, 0x00, 0x03, 0x4e, 0x01, 0xaa.toByte(), // 3-byte non-VCL NAL (type 39)
        0x00, 0x00, 0x00, 0x05, 0x28, 0x01, 0xaf.toByte(), 0x09, 0xa8.toByte(), // 5-byte slice NAL (type 20)
    )

    @Test
    fun `resolveActiveHevcPicParameterSetId skips non-VCL NALs and decodes the first VCL slice header`() {
        val file = fileOf(sampleBytes())
        val picParameterSetId = resolveActiveHevcPicParameterSetId(file, byteOffset = 0, sizeBytes = sampleBytes().size, lengthSize = 4)
        assertEquals(0, picParameterSetId)
    }

    @Test
    fun `resolveActiveHevcPicParameterSetId returns null when no VCL NAL is present in range`() {
        val onlyNonVcl = byteArrayOf(0x00, 0x00, 0x00, 0x03, 0x4e, 0x01, 0xaa.toByte())
        val file = fileOf(onlyNonVcl)
        assertNull(resolveActiveHevcPicParameterSetId(file, byteOffset = 0, sizeBytes = onlyNonVcl.size, lengthSize = 4))
    }

    private val dummyPtl = HevcProfileTierLevel(0, false, 1, 120)

    @Test
    fun `resolveActiveHevcParameterSets looks up the matching PPS, then its SPS, then its VPS`() {
        val vps0 = HevcVps(0, 0, dummyPtl)
        val vps1 = HevcVps(1, 0, dummyPtl)
        val sps0 = HevcSps(0, 1, 0, dummyPtl, 1, 1760, 992, 8, 8, null) // references vps1, not vps0
        val pps0 = HevcPps(0, 0, false, true, true, false, false, true, false, false, true, false, true, false)
        val result = resolveActiveHevcParameterSets(listOf(vps0, vps1), listOf(sps0), listOf(pps0), picParameterSetId = 0)
        assertNotNull(result)
        val (vps, sps, pps) = result
        assertEquals(1, vps?.vpsId)
        assertEquals(0, sps.spsId)
        assertEquals(0, pps.ppsId)
    }

    @Test
    fun `resolveActiveHevcParameterSets returns a null VPS when its id has no match, without failing the whole lookup`() {
        val sps0 = HevcSps(0, 99, 0, dummyPtl, 1, 1760, 992, 8, 8, null) // vpsId 99 doesn't exist
        val pps0 = HevcPps(0, 0, false, true, true, false, false, true, false, false, true, false, true, false)
        val result = resolveActiveHevcParameterSets(emptyList(), listOf(sps0), listOf(pps0), picParameterSetId = 0)
        assertNotNull(result)
        assertNull(result.first)
        assertEquals(0, result.second.spsId)
    }

    @Test
    fun `resolveActiveHevcParameterSets returns null when the pic parameter set id has no match`() {
        assertNull(resolveActiveHevcParameterSets(emptyList(), emptyList(), emptyList(), picParameterSetId = 0))
    }
}
