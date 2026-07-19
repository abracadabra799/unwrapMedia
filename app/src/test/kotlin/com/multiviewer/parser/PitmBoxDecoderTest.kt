package com.multiviewer.parser

import kotlin.test.Test
import kotlin.test.assertEquals

class PitmBoxDecoderTest {
    @Test
    fun `version 0 decodes a 2-byte primary_item_ID`() {
        val body = byteArrayOf(0x00, 0x00, 0x00, 0x00) + // version=0, flags=0
            byteArrayOf(0x00, 0x29) // primary_item_ID = 41
        val reader = byteReaderOf(body)
        val node = PitmBoxDecoder.decode(reader, "pitm", 0, 0, body.size.toLong(), emptyList())

        assertEquals("primary_item_ID", node.fields[0].name)
        assertEquals("41", node.fields[0].value)
        assertEquals("primary_item_ID=41", node.summary)
        reader.close()
    }

    @Test
    fun `version 1 decodes a 4-byte primary_item_ID`() {
        val body = byteArrayOf(0x01, 0x00, 0x00, 0x00) + // version=1, flags=0
            byteArrayOf(0x00, 0x00, 0x01, 0x2C) // primary_item_ID = 300
        val reader = byteReaderOf(body)
        val node = PitmBoxDecoder.decode(reader, "pitm", 0, 0, body.size.toLong(), emptyList())

        assertEquals("300", node.fields[0].value)
        reader.close()
    }

    @Test
    fun `box too short for primary_item_ID returns a warning and no fields`() {
        val body = byteArrayOf(0x00, 0x00, 0x00, 0x00)
        val reader = byteReaderOf(body)
        val node = PitmBoxDecoder.decode(reader, "pitm", 0, 0, body.size.toLong(), emptyList())

        assertEquals(1, node.warnings.size)
        assertEquals(true, node.fields.isEmpty())
        reader.close()
    }
}
