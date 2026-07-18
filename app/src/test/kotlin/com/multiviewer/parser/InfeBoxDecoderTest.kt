package com.multiviewer.parser

import kotlin.test.Test
import kotlin.test.assertEquals

class InfeBoxDecoderTest {
    @Test
    fun `version 2 decodes item_ID, item_protection_index, item_type and item_name`() {
        val body = byteArrayOf(0x02, 0x00, 0x00, 0x00) + // version=2, flags=0
            byteArrayOf(0x00, 0x01) +                     // item_ID = 1 (2 bytes)
            byteArrayOf(0x00, 0x00) +                     // item_protection_index = 0
            "hvc1".toByteArray() +                        // item_type
            "Image".toByteArray() + byteArrayOf(0)         // item_name, null-terminated
        val reader = byteReaderOf(body)
        val node = InfeBoxDecoder.decode(reader, "infe", 0, 0, body.size.toLong(), emptyList())

        assertEquals("1", node.fields[0].value)
        assertEquals("0", node.fields[1].value)
        assertEquals("hvc1", node.fields[2].value)
        assertEquals("Image", node.fields[3].value)
        assertEquals("hvc1: Image", node.summary)
        reader.close()
    }

    @Test
    fun `version below 2 is unsupported and returns a warning with no fields`() {
        val body = byteArrayOf(0x00, 0x00, 0x00, 0x00)
        val reader = byteReaderOf(body)
        val node = InfeBoxDecoder.decode(reader, "infe", 0, 0, body.size.toLong(), emptyList())
        assertEquals(1, node.warnings.size)
        assertEquals(true, node.fields.isEmpty())
        reader.close()
    }

    @Test
    fun `box too short for version 2 fixed fields returns a warning and no fields`() {
        val body = byteArrayOf(0x02, 0x00, 0x00, 0x00, 0x00, 0x00)
        val reader = byteReaderOf(body)
        val node = InfeBoxDecoder.decode(reader, "infe", 0, 0, body.size.toLong(), emptyList())
        assertEquals(1, node.warnings.size)
        assertEquals(true, node.fields.isEmpty())
        reader.close()
    }

    @Test
    fun `mime item_type reads content_type after item_name`() {
        val body = byteArrayOf(0x02, 0x00, 0x00, 0x00) + // version=2, flags=0
            byteArrayOf(0x00, 0x32) +                     // item_ID = 50
            byteArrayOf(0x00, 0x00) +                     // item_protection_index = 0
            "mime".toByteArray() +                        // item_type
            byteArrayOf(0) +                              // item_name = "" (empty, null-terminated)
            "application/rdf+xml".toByteArray() + byteArrayOf(0) // content_type, null-terminated
        val reader = byteReaderOf(body)
        val node = InfeBoxDecoder.decode(reader, "infe", 0, 0, body.size.toLong(), emptyList())

        assertEquals("50", node.fields[0].value)
        assertEquals("mime", node.fields[2].value)
        assertEquals("", node.fields[3].value)
        assertEquals("application/rdf+xml", node.fields[4].value)
        reader.close()
    }
}
