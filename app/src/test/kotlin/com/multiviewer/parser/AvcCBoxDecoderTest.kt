package com.multiviewer.parser

import kotlin.test.Test
import kotlin.test.assertEquals

class AvcCBoxDecoderTest {
    @Test
    fun `decodes profile, level, length_size and counts one SPS and one PPS`() {
        val body = byteArrayOf(
            0x01,                   // configuration_version
            0x64,                   // avc_profile_indication = 100
            0x00,                   // profile_compatibility
            0x1F,                   // avc_level_indication = 31
            0xFF.toByte(),          // lengthSizeMinusOne bits -> length_size = 4
            0xE1.toByte(),          // numSps bits -> declared 1 SPS
            0x00, 0x04,             // sps_length = 4
            0x67, 0x64, 0x00, 0x1F, // sps bytes (not decoded)
            0x01,                   // num_pps = 1
            0x00, 0x02,             // pps_length = 2
            0x68, 0xCE.toByte(),    // pps bytes (not decoded)
        )
        val reader = byteReaderOf(body)
        val node = AvcCBoxDecoder.decode(reader, "avcC", 0, 0, body.size.toLong(), emptyList())

        assertEquals("1", node.fields[0].value) // configuration_version
        assertEquals("100", node.fields[1].value) // avc_profile_indication
        assertEquals("0", node.fields[2].value) // profile_compatibility
        assertEquals("31", node.fields[3].value) // avc_level_indication
        assertEquals("4", node.fields[4].value) // length_size
        assertEquals("1", node.fields[5].value) // num_sps
        assertEquals("1", node.fields[6].value) // num_pps
        assertEquals("profile=100, level=31, 1 SPS, 1 PPS", node.summary)
        assertEquals(true, node.warnings.isEmpty())
        reader.close()
    }

    @Test
    fun `declared SPS count larger than available data truncates with a warning`() {
        val body = byteArrayOf(
            0x01, 0x64, 0x00, 0x1F,
            0xFF.toByte(),
            0xE2.toByte(),          // numSps bits -> declared 2 SPS (only 1 fits)
            0x00, 0x04,
            0x67, 0x64, 0x00, 0x1F,
            0x01,
            0x00, 0x02,
            0x68, 0xCE.toByte(),
        )
        val reader = byteReaderOf(body)
        val node = AvcCBoxDecoder.decode(reader, "avcC", 0, 0, body.size.toLong(), emptyList())
        assertEquals(1, node.warnings.size)
        assertEquals("1", node.fields[5].value) // num_sps found despite declaring 2
        reader.close()
    }

    @Test
    fun `box too short for fixed header returns a warning and no fields`() {
        val reader = byteReaderOf(ByteArray(3))
        val node = AvcCBoxDecoder.decode(reader, "avcC", 0, 0, 3, emptyList())
        assertEquals(1, node.warnings.size)
        assertEquals(true, node.fields.isEmpty())
        reader.close()
    }
}
