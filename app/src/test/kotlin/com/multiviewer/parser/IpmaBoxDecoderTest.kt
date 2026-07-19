package com.multiviewer.parser

import kotlin.test.Test
import kotlin.test.assertEquals

class IpmaBoxDecoderTest {
    @Test
    fun `version 0, flags 0 decodes 2-byte item_IDs and 1-byte property associations`() {
        val body = byteArrayOf(0x00, 0x00, 0x00, 0x00) + // version=0, flags=0 (1-byte associations)
            byteArrayOf(0x00, 0x00, 0x00, 0x02) +         // entry_count = 2
            // entry: item_ID=1, association_count=2, associations=[1, 2]
            byteArrayOf(0x00, 0x01, 0x02, 0x01, 0x02) +
            // entry: item_ID=41, association_count=1, associations=[3]
            byteArrayOf(0x00, 0x29, 0x01, 0x03)
        val reader = byteReaderOf(body)
        val node = IpmaBoxDecoder.decode(reader, "ipma", 0, 0, body.size.toLong(), emptyList())

        assertEquals(2, node.children.size)
        assertEquals("item_1", node.children[0].type)
        assertEquals(listOf("1", "2"), node.children[0].fields.map { it.value })
        assertEquals("item_41", node.children[1].type)
        assertEquals(listOf("3"), node.children[1].fields.map { it.value })
        assertEquals("2 entries", node.summary)
        reader.close()
    }

    @Test
    fun `version 1, flags 1 decodes 4-byte item_IDs and masks the essential bit from 2-byte associations`() {
        val body = byteArrayOf(0x01, 0x00, 0x00, 0x01) + // version=1, flags=1 (2-byte associations)
            byteArrayOf(0x00, 0x00, 0x00, 0x01) +         // entry_count = 1
            // entry: item_ID=300, association_count=1, association=[essential=1, property_index=5]
            byteArrayOf(0x00, 0x00, 0x01, 0x2C, 0x01, 0x80.toByte(), 0x05)
        val reader = byteReaderOf(body)
        val node = IpmaBoxDecoder.decode(reader, "ipma", 0, 0, body.size.toLong(), emptyList())

        assertEquals(1, node.children.size)
        assertEquals("item_300", node.children[0].type)
        assertEquals("5", node.children[0].fields[0].value)
        reader.close()
    }

    @Test
    fun `declared entry_count larger than available data truncates with a warning`() {
        val body = byteArrayOf(0x00, 0x00, 0x00, 0x00) + // version=0, flags=0
            byteArrayOf(0x00, 0x00, 0x00, 0x02) +         // entry_count = 2 (only 1 present)
            byteArrayOf(0x00, 0x01, 0x01, 0x01)           // entry: item_ID=1, association_count=1, associations=[1]
        val reader = byteReaderOf(body)
        val node = IpmaBoxDecoder.decode(reader, "ipma", 0, 0, body.size.toLong(), emptyList())

        assertEquals(1, node.warnings.size)
        assertEquals(1, node.children.size)
        reader.close()
    }
}
