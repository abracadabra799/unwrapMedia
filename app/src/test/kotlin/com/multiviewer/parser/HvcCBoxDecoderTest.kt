package com.multiviewer.parser

import kotlin.test.Test
import kotlin.test.assertEquals

class HvcCBoxDecoderTest {
    private val validBody = byteArrayOf(
        0x01,                            // configuration_version
        0x01,                            // profile_space=0, tier=0, profile_idc=1
        0x60, 0x00, 0x00, 0x00,          // general_profile_compatibility_flags
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, // general_constraint_indicator_flags
        0x5D,                            // general_level_idc = 93
        0xF0.toByte(), 0x00,             // reserved + min_spatial_segmentation_idc
        0xFC.toByte(),                   // parallelismType
        0xFC.toByte(),                   // chroma_format_idc
        0xF8.toByte(),                   // bit_depth_luma_minus8
        0xF8.toByte(),                   // bit_depth_chroma_minus8
        0x00, 0x00,                      // avgFrameRate
        0x0F,                            // lengthSizeMinusOne bits -> length_size = 4
        0x02,                            // num_arrays = 2
        // array 1: SPS (type 33), 1 NAL of length 3
        0xA1.toByte(), 0x00, 0x01, 0x00, 0x03, 0x42, 0x01, 0x02,
        // array 2: PPS (type 34), 1 NAL of length 2
        0xA2.toByte(), 0x00, 0x01, 0x00, 0x02, 0x44, 0x01,
    )

    @Test
    fun `decodes profile, level, length_size and counts one SPS and one PPS across arrays`() {
        val reader = byteReaderOf(validBody)
        val node = HvcCBoxDecoder.decode(reader, "hvcC", 0, 0, validBody.size.toLong(), emptyList())

        assertEquals("1", node.fields[0].value) // configuration_version
        assertEquals("1", node.fields[1].value) // general_profile_idc
        assertEquals("93", node.fields[2].value) // general_level_idc
        assertEquals("4", node.fields[3].value) // length_size
        assertEquals("2", node.fields[4].value) // num_arrays
        assertEquals("0", node.fields[5].value) // num_vps
        assertEquals("1", node.fields[6].value) // num_sps
        assertEquals("1", node.fields[7].value) // num_pps
        assertEquals("profile=1, level=93, 1 SPS, 1 PPS", node.summary)
        assertEquals(true, node.warnings.isEmpty())
        reader.close()
    }

    @Test
    fun `declared array count larger than available data truncates with a warning`() {
        val body = validBody.copyOf()
        body[22] = 0x03 // declare 3 arrays but only 2 are present
        val reader = byteReaderOf(body)
        val node = HvcCBoxDecoder.decode(reader, "hvcC", 0, 0, body.size.toLong(), emptyList())
        assertEquals(1, node.warnings.size)
        assertEquals("1", node.fields[6].value) // num_sps still counted from the 2 arrays found
        reader.close()
    }

    @Test
    fun `box too short for fixed header returns a warning and no fields`() {
        val reader = byteReaderOf(ByteArray(10))
        val node = HvcCBoxDecoder.decode(reader, "hvcC", 0, 0, 10, emptyList())
        assertEquals(1, node.warnings.size)
        assertEquals(true, node.fields.isEmpty())
        reader.close()
    }
}
