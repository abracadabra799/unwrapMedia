package com.multiviewer.parser

import kotlin.test.Test
import kotlin.test.assertEquals

class ImirBoxDecoderTest {
    @Test
    fun `decodes axis 0`() {
        val body = byteArrayOf(0x00)
        val reader = byteReaderOf(body)
        val node = ImirBoxDecoder.decode(reader, "imir", 0, 0, body.size.toLong(), emptyList())
        assertEquals("0", node.fields.first { it.name == "axis" }.value)
        reader.close()
    }

    @Test
    fun `decodes axis 1, ignoring reserved high bits`() {
        val body = byteArrayOf(0xFF.toByte())
        val reader = byteReaderOf(body)
        val node = ImirBoxDecoder.decode(reader, "imir", 0, 0, body.size.toLong(), emptyList())
        assertEquals("1", node.fields.first { it.name == "axis" }.value)
        reader.close()
    }
}
