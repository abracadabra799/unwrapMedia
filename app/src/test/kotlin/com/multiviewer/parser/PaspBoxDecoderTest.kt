package com.multiviewer.parser

import kotlin.test.Test
import kotlin.test.assertEquals

class PaspBoxDecoderTest {
    @Test
    fun `decodes hSpacing and vSpacing`() {
        val body = byteArrayOf(
            0x00, 0x00, 0x00, 0x01, // hSpacing = 1
            0x00, 0x00, 0x00, 0x01, // vSpacing = 1
        )
        val reader = byteReaderOf(body)
        val node = PaspBoxDecoder.decode(reader, "pasp", 0, 0, body.size.toLong(), emptyList())
        assertEquals("1", node.fields[0].value)
        assertEquals("1", node.fields[1].value)
        assertEquals("1:1", node.summary)
        reader.close()
    }

    @Test
    fun `box too short for hSpacing and vSpacing returns a warning and no fields`() {
        val reader = byteReaderOf(ByteArray(4))
        val node = PaspBoxDecoder.decode(reader, "pasp", 0, 0, 4, emptyList())
        assertEquals(1, node.warnings.size)
        assertEquals(true, node.fields.isEmpty())
        reader.close()
    }
}
