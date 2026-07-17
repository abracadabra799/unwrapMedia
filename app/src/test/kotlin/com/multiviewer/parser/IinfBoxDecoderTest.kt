package com.multiviewer.parser

import kotlin.test.Test
import kotlin.test.assertEquals

class IinfBoxDecoderTest {
    @Test
    fun `version 0 uses a 2-byte entry_count and recurses into infe children`() {
        val body = byteArrayOf(
            0x00, 0x00, 0x00, 0x00, // version=0, flags=0
            0x00, 0x01,             // entry_count = 1 (2 bytes)
            0x00, 0x00, 0x00, 0x08, 0x69, 0x6E, 0x66, 0x65, // child "infe", size 8
        )
        val reader = byteReaderOf(body)
        val node = IinfBoxDecoder.decode(reader, "iinf", 0, 0, body.size.toLong(), emptyList())
        assertEquals(1, node.children.size)
        assertEquals("infe", node.children[0].type)
        assertEquals("1 item", node.summary)
        reader.close()
    }

    @Test
    fun `version 1 uses a 4-byte entry_count`() {
        val body = byteArrayOf(
            0x01, 0x00, 0x00, 0x00, // version=1, flags=0
            0x00, 0x00, 0x00, 0x01, // entry_count = 1 (4 bytes)
            0x00, 0x00, 0x00, 0x08, 0x69, 0x6E, 0x66, 0x65, // child "infe", size 8
        )
        val reader = byteReaderOf(body)
        val node = IinfBoxDecoder.decode(reader, "iinf", 0, 0, body.size.toLong(), emptyList())
        assertEquals(1, node.children.size)
        assertEquals("1 item", node.summary)
        reader.close()
    }

    @Test
    fun `box too short for a FullBox header returns a warning and no children`() {
        val reader = byteReaderOf(ByteArray(2))
        val node = IinfBoxDecoder.decode(reader, "iinf", 0, 0, 2, emptyList())
        assertEquals(1, node.warnings.size)
        assertEquals(true, node.children.isEmpty())
        reader.close()
    }
}
