package com.multiviewer.parser

import kotlin.test.Test
import kotlin.test.assertEquals

class IrotBoxDecoderTest {
    @Test
    fun `decodes a 90-degree rotation`() {
        val body = byteArrayOf(0x01)
        val reader = byteReaderOf(body)
        val node = IrotBoxDecoder.decode(reader, "irot", 0, 0, body.size.toLong(), emptyList())
        assertEquals("1", node.fields.first { it.name == "angle" }.value)
        assertEquals("90°", node.summary)
        reader.close()
    }

    @Test
    fun `ignores reserved high bits, keeping only the low 2-bit angle`() {
        val body = byteArrayOf(0xFB.toByte()) // reserved bits all 1, angle bits = 11 (3)
        val reader = byteReaderOf(body)
        val node = IrotBoxDecoder.decode(reader, "irot", 0, 0, body.size.toLong(), emptyList())
        assertEquals("3", node.fields.first { it.name == "angle" }.value)
        assertEquals("270°", node.summary)
        reader.close()
    }
}
