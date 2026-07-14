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

    @Test
    fun `plain QuickTime-style meta box (no version-flags) has its first child at the payload start`() {
        // meta box: 8-byte header, size 20 (8 header + 12 payload)
        // payload is just one child: hdlr, size 12 (8-byte header + 4 dummy payload bytes)
        val body = byteArrayOf(
            0x00, 0x00, 0x00, 0x0C, 0x68, 0x64, 0x6C, 0x72, // child "hdlr", size 12
            0x00, 0x00, 0x00, 0x00, // dummy payload to reach size 12
        )
        val reader = byteReaderOf(
            byteArrayOf(0x00, 0x00, 0x00, 0x14, 0x6D, 0x65, 0x74, 0x61) + body // "meta", size 20
        )
        val node = MetaBoxDecoder.decode(reader, "meta", 0, 8, 20, emptyList())
        assertEquals(1, node.children.size)
        assertEquals("hdlr", node.children[0].type)
        assertEquals(8L, node.children[0].offset)
        reader.close()
    }

    @Test
    fun `ISO-style meta box with version-flags skips 4 bytes before the first child`() {
        // meta box payload: 4-byte version/flags, then one child hdlr, size 12
        val body = byteArrayOf(
            0x00, 0x00, 0x00, 0x00, // version/flags
            0x00, 0x00, 0x00, 0x0C, 0x68, 0x64, 0x6C, 0x72, // child "hdlr", size 12
            0x00, 0x00, 0x00, 0x00, // dummy payload to reach size 12
        )
        val reader = byteReaderOf(
            byteArrayOf(0x00, 0x00, 0x00, 0x18, 0x6D, 0x65, 0x74, 0x61) + body // "meta", size 24
        )
        val node = MetaBoxDecoder.decode(reader, "meta", 0, 8, 24, emptyList())
        assertEquals(1, node.children.size)
        assertEquals("hdlr", node.children[0].type)
        assertEquals(12L, node.children[0].offset)
        reader.close()
    }

    @Test
    fun `too short to peek a fourcc defaults to FullBox behavior without crashing`() {
        // meta box, size 8 (header only, empty payload) — nothing to peek, must not throw
        val reader = byteReaderOf(byteArrayOf(0x00, 0x00, 0x00, 0x08, 0x6D, 0x65, 0x74, 0x61))
        val node = MetaBoxDecoder.decode(reader, "meta", 0, 8, 8, emptyList())
        assertEquals(0, node.children.size)
        reader.close()
    }
}
