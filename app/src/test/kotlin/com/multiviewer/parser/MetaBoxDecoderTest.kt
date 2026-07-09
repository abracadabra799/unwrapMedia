package com.multiviewer.parser

import kotlin.test.Test
import kotlin.test.assertEquals

class MetaBoxDecoderTest {
    @Test
    fun `meta box skips 4 bytes of version and flags before recursing into children`() {
        BoxRegistry.register("meta", ContainerBoxDecoder(childOffsetInPayload = 4))
        val reader = byteReaderOf(
            byteArrayOf(
                0x00, 0x00, 0x00, 0x14, 0x6D, 0x65, 0x74, 0x61, // "meta", size 20
                0x00, 0x00, 0x00, 0x00,                         // version/flags
                0x00, 0x00, 0x00, 0x08, 0x68, 0x64, 0x6C, 0x72, // child "hdlr", size 8
            )
        )
        val boxes = parseBoxes(reader, 0, reader.length)
        assertEquals(1, boxes[0].children.size)
        assertEquals("hdlr", boxes[0].children[0].type)
        assertEquals(12L, boxes[0].children[0].offset)
        reader.close()
    }
}
