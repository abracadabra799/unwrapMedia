package com.multiviewer.parser

import kotlin.test.Test
import kotlin.test.assertEquals

class UrlBoxDecoderTest {
    @Test
    fun `self-contained flag produces no fields`() {
        val body = byteArrayOf(0x00, 0x00, 0x00, 0x01) // version=0, flags=1 (self-contained)
        val reader = byteReaderOf(body)
        val node = UrlBoxDecoder.decode(reader, "url ", 0, 0, body.size.toLong(), emptyList())
        assertEquals(true, node.fields.isEmpty())
        assertEquals("self-contained", node.summary)
        reader.close()
    }

    @Test
    fun `non-self-contained flag decodes the location string`() {
        val body = byteArrayOf(0x00, 0x00, 0x00, 0x00) + "file.mp4".toByteArray() + byteArrayOf(0)
        val reader = byteReaderOf(body)
        val node = UrlBoxDecoder.decode(reader, "url ", 0, 0, body.size.toLong(), emptyList())
        assertEquals("file.mp4", node.fields[0].value)
        reader.close()
    }

    @Test
    fun `box too short for FullBox header returns a warning and no fields`() {
        val reader = byteReaderOf(ByteArray(2))
        val node = UrlBoxDecoder.decode(reader, "url ", 0, 0, 2, emptyList())
        assertEquals(1, node.warnings.size)
        assertEquals(true, node.fields.isEmpty())
        reader.close()
    }
}
