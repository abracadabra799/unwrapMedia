package com.multiviewer.parser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Av1CBoxDecoderTest {
    // Same real av1C fixed header used in Av1ParameterSetExtractionTest -- see that file's comment
    // for the full field-by-field derivation. configOBUs content doesn't matter here; the decoder
    // only reads the first 4 bytes.
    private fun realAv1CPayload(): ByteArray = byteArrayOf(
        0x81.toByte(), 0x00, 0x0c, 0x00,
        0x0a, 0x0b, 0x02, 0x00, 0x00, 0x05, 0x61, 0xe7.toByte(), 0xfd.toByte(), 0xe0.toByte(), 0x17, 0xc0.toByte(), 0x02,
    )

    private fun decode(payload: ByteArray): BoxNode {
        val headerSize = 8
        val file = fileOf(ByteArray(headerSize) + payload)
        val reader = ByteReader.open(file)
        return Av1CBoxDecoder.decode(reader, "av1C", offset = 0, headerSize = headerSize, size = (headerSize + payload.size).toLong(), warnings = emptyList())
    }

    private fun fieldValue(node: BoxNode, name: String): String? = node.fields.find { it.name == name }?.value

    @Test
    fun `decode reads every fixed-header field from a real av1C payload`() {
        val node = decode(realAv1CPayload())
        assertEquals("1", fieldValue(node, "marker"))
        assertEquals("1", fieldValue(node, "version"))
        assertEquals("0", fieldValue(node, "seq_profile"))
        assertEquals("0", fieldValue(node, "seq_level_idx_0"))
        assertEquals("0", fieldValue(node, "seq_tier_0"))
        assertEquals("0", fieldValue(node, "high_bitdepth"))
        assertEquals("0", fieldValue(node, "twelve_bit"))
        assertEquals("0", fieldValue(node, "monochrome"))
        assertEquals("1", fieldValue(node, "chroma_subsampling_x"))
        assertEquals("1", fieldValue(node, "chroma_subsampling_y"))
        assertEquals("0", fieldValue(node, "chroma_sample_position"))
        assertEquals("0", fieldValue(node, "initial_presentation_delay_present"))
    }

    @Test
    fun `decode adds a warning and no fields when the box is too short for its fixed header`() {
        val node = decode(byteArrayOf(0x81.toByte(), 0x00, 0x0c)) // only 3 bytes, needs 4
        assertTrue(node.warnings.any { it.contains("too short") })
        assertTrue(node.fields.isEmpty())
    }
}
