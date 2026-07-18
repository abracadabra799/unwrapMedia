package com.multiviewer.parser

import kotlin.test.Test
import kotlin.test.assertEquals

class IlocBoxDecoderTest {
    @Test
    fun `decodes construction_method 0, 1 and 2 with correct extent resolution`() {
        val body = byteArrayOf(
            0x01, 0x00, 0x00, 0x00,             // version=1, flags=0
            0x44,                               // offset_size=4, length_size=4
            0x00,                               // base_offset_size=0, index_size=0
            0x00, 0x03,                         // item_count = 3
            // item 1: construction_method=0, base_offset=0, extent=(1000, 500)
            0x00, 0x01,                         // item_ID = 1
            0x00, 0x00,                         // construction_method = 0
            0x00, 0x01,                         // data_reference_index = 1
            0x00, 0x01,                         // extent_count = 1
            0x00, 0x00, 0x03, 0xe8.toByte(),    // extent_offset = 1000
            0x00, 0x00, 0x01, 0xf4.toByte(),    // extent_length = 500
            // item 2: construction_method=1, base_offset=0, extent=(10, 20)
            0x00, 0x02,
            0x00, 0x01,
            0x00, 0x01,
            0x00, 0x01,
            0x00, 0x00, 0x00, 0x0a,
            0x00, 0x00, 0x00, 0x14,
            // item 3: construction_method=2, base_offset=0, extent=(5, 5)
            0x00, 0x03,
            0x00, 0x02,
            0x00, 0x01,
            0x00, 0x01,
            0x00, 0x00, 0x00, 0x05,
            0x00, 0x00, 0x00, 0x05,
        )
        val reader = byteReaderOf(body)
        val node = IlocBoxDecoder.decode(reader, "iloc", 0, 0, body.size.toLong(), emptyList())

        assertEquals(3, node.children.size)
        assertEquals("item_1", node.children[0].type)
        assertEquals("0", node.children[0].fields[0].value)
        assertEquals("1000", node.children[0].children[0].fields.first { it.name == "offset" }.value)
        assertEquals("500", node.children[0].children[0].fields.first { it.name == "length" }.value)

        assertEquals("item_2", node.children[1].type)
        assertEquals("1", node.children[1].fields[0].value)
        assertEquals("10", node.children[1].children[0].fields.first { it.name == "idat_relative_offset" }.value)

        assertEquals("item_3", node.children[2].type)
        assertEquals(1, node.warnings.size)
        assertEquals("0", node.children[2].children[0].fields.first { it.name == "base_offset" }.value)
        assertEquals("5", node.children[2].children[0].fields.first { it.name == "extent_offset" }.value)

        assertEquals("3 items", node.summary)
        reader.close()
    }

    @Test
    fun `declared item_count larger than available data truncates with a warning`() {
        val body = byteArrayOf(
            0x01, 0x00, 0x00, 0x00,
            0x44,
            0x00,
            0x00, 0x02,                         // item_count = 2 (only 1 fits)
            0x00, 0x01,
            0x00, 0x00,
            0x00, 0x01,
            0x00, 0x01,
            0x00, 0x00, 0x00, 0x64.toByte(),
            0x00, 0x00, 0x00, 0x32,
        )
        val reader = byteReaderOf(body)
        val node = IlocBoxDecoder.decode(reader, "iloc", 0, 0, body.size.toLong(), emptyList())
        assertEquals(1, node.children.size)
        assertEquals(1, node.warnings.size)
        reader.close()
    }

    @Test
    fun `box too short for FullBox header and size fields returns a warning and no children`() {
        val reader = byteReaderOf(ByteArray(4))
        val node = IlocBoxDecoder.decode(reader, "iloc", 0, 0, 4, emptyList())
        assertEquals(1, node.warnings.size)
        assertEquals(true, node.children.isEmpty())
        reader.close()
    }

    @Test
    fun `unsupported offset_size bails out with a warning instead of crashing`() {
        val body = byteArrayOf(
            0x01, 0x00, 0x00, 0x00,             // version=1, flags=0
            0x24,                               // offset_size=2 (unsupported), length_size=4
            0x00,                               // base_offset_size=0, index_size=0
            0x00, 0x00,                         // item_count = 0
        )
        val reader = byteReaderOf(body)
        val node = IlocBoxDecoder.decode(reader, "iloc", 0, 0, body.size.toLong(), emptyList())
        assertEquals(1, node.warnings.size)
        assertEquals(true, node.children.isEmpty())
        reader.close()
    }
}
