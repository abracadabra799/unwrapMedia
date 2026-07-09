package com.multiviewer.parser

import kotlin.test.Test
import kotlin.test.assertEquals

class ContainerBoxDecoderTest {
    @Test
    fun `recurses into children starting right after the box header`() {
        BoxRegistry.register("box1", ContainerBoxDecoder())
        val reader = byteReaderOf(
            byteArrayOf(
                0x00, 0x00, 0x00, 0x18, 0x62, 0x6F, 0x78, 0x31, // "box1", size 24 (8 header + 16 children)
                0x00, 0x00, 0x00, 0x08, 0x66, 0x72, 0x65, 0x65, // child "free", size 8
                0x00, 0x00, 0x00, 0x08, 0x73, 0x6B, 0x69, 0x70, // child "skip", size 8
            )
        )
        val boxes = parseBoxes(reader, 0, reader.length)
        assertEquals(1, boxes.size)
        assertEquals(listOf("free", "skip"), boxes[0].children.map { it.type })
        reader.close()
    }

    @Test
    fun `summarize option reports child count as the summary`() {
        BoxRegistry.register("box2", ContainerBoxDecoder(summarize = true))
        val reader = byteReaderOf(
            byteArrayOf(
                0x00, 0x00, 0x00, 0x10, 0x62, 0x6F, 0x78, 0x32, // "box2", size 16
                0x00, 0x00, 0x00, 0x08, 0x66, 0x72, 0x65, 0x65, // child "free", size 8
            )
        )
        val boxes = parseBoxes(reader, 0, reader.length)
        assertEquals("1 entries", boxes[0].summary)
        reader.close()
    }
}
