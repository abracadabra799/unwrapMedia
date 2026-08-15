package com.multiviewer.parser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class Av1ParameterSetExtractionTest {
    // Real av1C payload (17 bytes) captured from a libsvtav1-encoded 320x240 MP4: 4-byte
    // AV1CodecConfigurationRecord fixed header (marker=1, version=1, seq_profile=0,
    // seq_level_idx_0=0, seq_tier_0=0, high_bitdepth=0, twelve_bit=0, monochrome=0,
    // chroma_subsampling_x=1, chroma_subsampling_y=1, chroma_sample_position=0,
    // initial_presentation_delay_present=0), then configOBUs: a single Sequence Header OBU
    // (obu_type=1, no extension, has_size_field=1, leb128 obu_size=11) with an 11-byte payload.
    private fun av1CPayload(): ByteArray = byteArrayOf(
        0x81.toByte(), 0x00, 0x0c, 0x00, // fixed header
        0x0a, 0x0b, // OBU header (type=1, has_size_field=1) + leb128 size=11
        0x02, 0x00, 0x00, 0x05, 0x61, 0xe7.toByte(), 0xfd.toByte(), 0xe0.toByte(), 0x17, 0xc0.toByte(), 0x02,
    )

    private fun av1CBoxNode(payload: ByteArray): Pair<BoxNode, java.io.File> {
        val headerSize = 8
        val header = ByteArray(headerSize) // irrelevant filler, box parsing reads by absolute offset
        val file = fileOf(header + payload)
        val node = BoxNode(type = "av1C", offset = 0, headerSize = headerSize, size = (headerSize + payload.size).toLong())
        return node to file
    }

    @Test
    fun `extractAv1CRawSequenceHeader finds the Sequence Header OBU payload and its file offset`() {
        val (node, file) = av1CBoxNode(av1CPayload())
        val result = extractAv1CRawSequenceHeader(file, node)
        assertNotNull(result)
        val expectedPayload = byteArrayOf(
            0x02, 0x00, 0x00, 0x05, 0x61, 0xe7.toByte(), 0xfd.toByte(), 0xe0.toByte(), 0x17, 0xc0.toByte(), 0x02,
        )
        assertEquals(expectedPayload.toList(), result.bytes.toList())
        // headerSize=8 + payload index 6 (seq header payload starts right after the 2-byte OBU
        // header+leb128-size at payload index 4-5, itself right after the 4-byte fixed header) = 14.
        assertEquals(14L, result.offset)
    }

    @Test
    fun `extractAv1CRawSequenceHeader returns null when the box is too short for its fixed header`() {
        val (node, file) = av1CBoxNode(byteArrayOf(0x81.toByte(), 0x00, 0x0c)) // only 3 bytes, needs 4
        assertNull(extractAv1CRawSequenceHeader(file, node))
    }

    @Test
    fun `extractAv1CRawSequenceHeader returns null when no Sequence Header OBU is present`() {
        // configOBUs contains only a Temporal Delimiter OBU: obu_type=2, has_size_field=1,
        // obu_reserved_1bit=0 -> 0 0010 0 1 0 = 0x12, followed by leb128 obu_size=0.
        val payload = byteArrayOf(0x81.toByte(), 0x00, 0x0c, 0x00, 0x12, 0x00)
        val (node, file) = av1CBoxNode(payload)
        assertNull(extractAv1CRawSequenceHeader(file, node))
    }
}
