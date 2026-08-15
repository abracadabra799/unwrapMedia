package com.multiviewer.parser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class H264ParameterSetExtractionTest {
    // Synthetic avcC payload (structure only -- SPS/PPS contents don't need to be real, valid
    // bitstream since extractAvcCRawParameterSets never parses them, only slices them out by
    // their declared lengths): configuration_version=1, avc_profile_indication=100,
    // profile_compatibility=0, avc_level_indication=30, length_size_minus_one=3 (-> length_size=4),
    // num_sps=1 (declared in the low 5 bits), one 3-byte SPS, num_pps=1, one 2-byte PPS.
    private fun avcCPayload(): ByteArray = byteArrayOf(
        0x01, 0x64, 0x00, 0x1e, 0xFF.toByte(), 0xE1.toByte(),
        0x00, 0x03, 0x67, 0xAA.toByte(), 0xBB.toByte(), // num_sps=1 implied by 0xE1 low 5 bits; one SPS, length=3
        0x01, 0x00, 0x02, 0x68, 0xCC.toByte(), // num_pps=1, one PPS, length=2
    )

    private fun avcCBoxNode(payload: ByteArray): Pair<BoxNode, java.io.File> {
        val headerSize = 8
        val header = ByteArray(headerSize) // irrelevant filler, box parsing reads by absolute offset
        val file = fileOf(header + payload)
        val node = BoxNode(type = "avcC", offset = 0, headerSize = headerSize, size = (headerSize + payload.size).toLong())
        return node to file
    }

    @Test
    fun `extractAvcCRawParameterSets reads length_size and the declared SPS and PPS byte ranges`() {
        val (node, file) = avcCBoxNode(avcCPayload())
        val result = extractAvcCRawParameterSets(file, node)
        assertNotNull(result)
        assertEquals(4, result.lengthSize) // length_size_minus_one=3 -> 3+1=4
        assertEquals(1, result.spsList.size)
        assertEquals(byteArrayOf(0x67, 0xAA.toByte(), 0xBB.toByte()).toList(), result.spsList[0].toList())
        assertEquals(1, result.ppsList.size)
        assertEquals(byteArrayOf(0x68, 0xCC.toByte()).toList(), result.ppsList[0].toList())
    }

    @Test
    fun `extractAvcCRawParameterSets returns null when the box is too short for its fixed header`() {
        val (node, file) = avcCBoxNode(byteArrayOf(0x01, 0x64, 0x00)) // only 3 bytes, needs 6
        assertNull(extractAvcCRawParameterSets(file, node))
    }

    // Length-prefixed samples (avcC-style, length_size=4): one 5-byte non-VCL NAL (type 6, SEI)
    // followed by a 6-byte VCL NAL (type 5, IDR slice) whose RBSP starts with a real slice-header
    // prefix (first_mb_in_slice=0, slice_type=7, pic_parameter_set_id=0 -- same bytes verified in
    // BitReaderTest/H264ParameterSetsTest).
    private fun sampleBytes(): ByteArray = byteArrayOf(
        0x00, 0x00, 0x00, 0x03, 0x06, 0xAA.toByte(), 0xBB.toByte(), // 3-byte SEI NAL (type 6)
        0x00, 0x00, 0x00, 0x04, 0x65, 0x88.toByte(), 0x84.toByte(), 0x00, // 4-byte slice NAL (type 5)
    )

    @Test
    fun `resolveActivePicParameterSetId skips non-VCL NALs and decodes the first VCL slice header`() {
        val file = fileOf(sampleBytes())
        val picParameterSetId = resolveActivePicParameterSetId(file, byteOffset = 0, sizeBytes = sampleBytes().size, lengthSize = 4)
        assertEquals(0, picParameterSetId)
    }

    @Test
    fun `resolveActivePicParameterSetId returns null when no VCL NAL is present in range`() {
        val onlyNonVcl = byteArrayOf(0x00, 0x00, 0x00, 0x03, 0x06, 0xAA.toByte(), 0xBB.toByte())
        val file = fileOf(onlyNonVcl)
        assertNull(resolveActivePicParameterSetId(file, byteOffset = 0, sizeBytes = onlyNonVcl.size, lengthSize = 4))
    }

    @Test
    fun `resolveActiveParameterSets looks up the matching PPS then its matching SPS`() {
        val sps0 = H264Sps(0, 66, 30, 1, 8, 8, 0, 1, null, null)
        val sps1 = H264Sps(1, 66, 30, 1, 8, 8, 0, 1, null, null)
        val pps0 = H264Pps(0, 1, true, true, true) // references sps1, not sps0
        val result = resolveActiveParameterSets(listOf(sps0, sps1), listOf(pps0), picParameterSetId = 0)
        assertNotNull(result)
        assertEquals(1, result.first.seqParameterSetId)
        assertEquals(0, result.second.picParameterSetId)
    }

    @Test
    fun `resolveActiveParameterSets returns null when the pic parameter set id has no match`() {
        assertNull(resolveActiveParameterSets(emptyList(), emptyList(), picParameterSetId = 0))
    }
}
