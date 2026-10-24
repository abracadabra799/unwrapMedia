package com.multiviewer.parser

import java.io.File
import kotlin.test.*

class HevcConfigDetailsTest {
    @Test fun `real default hev1 and HDR10 expose chroma bit depth and temporal configuration`() {
        for ((path, depth, compatibility) in listOf(
            Triple("hevc-conformance/default-hev1.mp4", "8", "0x60000000"),
            Triple("video-frame/hdr10.mp4", "10", "0x20000000"),
        )) {
            val node = assertNotNull(findFirst(parseFile(File("src/test/resources/$path"))) { it.type == "hvcC" })
            fun value(name: String) = node.fields.find { it.name == name }?.value
            assertEquals(depth, value("bit_depth_luma"))
            assertEquals(depth, value("bit_depth_chroma"))
            assertEquals("1", value("chroma_format_idc"))
            assertEquals("1", value("num_temporal_layers"))
            assertEquals("true", value("temporal_id_nested"))
            assertEquals(compatibility, value("general_profile_compatibility_flags"))
            assertEquals("0x900000000000", value("general_constraint_indicator_flags"))
        }
    }
    @Test fun `nonzero fixed bitfields preserve source values and offsets`() {
        val bytes = hex("01 e1 ffffffff ffffffffffff 5d f123 fe ff fb fc 1234 af 00")
        byteReaderOf(bytes).use { reader ->
            val node = HvcCBoxDecoder.decode(reader, "hvcC", 0, 0, bytes.size.toLong(), emptyList())
            fun value(name: String) = node.fields.find { it.name == name }?.value
            assertEquals("3", value("general_profile_space"))
            assertEquals("1", value("general_tier_flag"))
            assertEquals("291", value("min_spatial_segmentation_idc"))
            assertEquals("2", value("parallelism_type"))
            assertEquals("3", value("chroma_format_idc"))
            assertEquals("11", value("bit_depth_luma"))
            assertEquals("12", value("bit_depth_chroma"))
            assertEquals("4660", value("avg_frame_rate"))
            assertEquals("2", value("constant_frame_rate"))
            assertEquals("5", value("num_temporal_layers"))
            assertEquals(19L, node.fields.first { it.name == "avg_frame_rate" }.offset)
        }
    }
    @Test fun `truncated NAL arrays and invalid file ranges produce warnings`() {
        val bytes = hex("01 01 60000000 900000000000 1e f000 fc fd f8 f8 0000 0f 01 a1 0001 0010 4201")
        byteReaderOf(bytes).use { reader ->
            assertTrue(HvcCBoxDecoder.decode(reader, "hvcC", 0, 0, bytes.size.toLong(), emptyList()).warnings.isNotEmpty())
            assertTrue(HvcCBoxDecoder.decode(reader, "hvcC", 0, 0, 1000, emptyList()).warnings.isNotEmpty())
        }
    }
}
