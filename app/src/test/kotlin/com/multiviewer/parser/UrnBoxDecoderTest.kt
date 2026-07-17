package com.multiviewer.parser

import kotlin.test.Test
import kotlin.test.assertEquals

class UrnBoxDecoderTest {
    @Test
    fun `decodes name and location as two consecutive null-terminated strings`() {
        val body = byteArrayOf(0x00, 0x00, 0x00, 0x00) +
            "urn:example".toByteArray() + byteArrayOf(0) + "http://example.com/asset".toByteArray() + byteArrayOf(0)
        val reader = byteReaderOf(body)
        val node = UrnBoxDecoder.decode(reader, "urn ", 0, 0, body.size.toLong(), emptyList())
        assertEquals("urn:example", node.fields[0].value)
        assertEquals("http://example.com/asset", node.fields[1].value)
        assertEquals("urn:example: http://example.com/asset", node.summary)
        reader.close()
    }

    @Test
    fun `box too short for FullBox header returns a warning and no fields`() {
        val reader = byteReaderOf(ByteArray(2))
        val node = UrnBoxDecoder.decode(reader, "urn ", 0, 0, 2, emptyList())
        assertEquals(1, node.warnings.size)
        assertEquals(true, node.fields.isEmpty())
        reader.close()
    }
}
