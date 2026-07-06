package com.multiviewer.parser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BoxWalkerTest {
    @Test
    fun `parses a simple 32-bit size box`() {
        val reader = byteReaderOf(
            byteArrayOf(
                0x00, 0x00, 0x00, 0x08, // size = 8
                0x66, 0x72, 0x65, 0x65, // "free"
            )
        )
        val boxes = parseBoxes(reader, 0, reader.length)
        assertEquals(1, boxes.size)
        assertEquals("free", boxes[0].type)
        assertEquals(0L, boxes[0].offset)
        assertEquals(8, boxes[0].headerSize)
        assertEquals(8L, boxes[0].size)
        reader.close()
    }

    @Test
    fun `parses a 64-bit extended size box (size field is 1)`() {
        val reader = byteReaderOf(
            byteArrayOf(
                0x00, 0x00, 0x00, 0x01, // size = 1 -> read 64-bit size next
                0x6D, 0x64, 0x61, 0x74, // "mdat"
                0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x10, // largesize = 16
            )
        )
        val boxes = parseBoxes(reader, 0, reader.length)
        assertEquals(1, boxes.size)
        assertEquals("mdat", boxes[0].type)
        assertEquals(16, boxes[0].headerSize)
        assertEquals(16L, boxes[0].size)
        reader.close()
    }

    @Test
    fun `size 0 means the box extends to the end of the parsed range`() {
        val reader = byteReaderOf(
            byteArrayOf(
                0x00, 0x00, 0x00, 0x00, // size = 0 -> extends to end
                0x6D, 0x64, 0x61, 0x74, // "mdat"
                0x11, 0x22, 0x33,       // 3 bytes of payload
            )
        )
        val boxes = parseBoxes(reader, 0, reader.length)
        assertEquals(1, boxes.size)
        assertEquals(11L, boxes[0].size)
        reader.close()
    }

    @Test
    fun `parses two sibling boxes back to back`() {
        val reader = byteReaderOf(
            byteArrayOf(
                0x00, 0x00, 0x00, 0x08, 0x66, 0x72, 0x65, 0x65, // "free", size 8
                0x00, 0x00, 0x00, 0x08, 0x73, 0x6B, 0x69, 0x70, // "skip", size 8
            )
        )
        val boxes = parseBoxes(reader, 0, reader.length)
        assertEquals(listOf("free", "skip"), boxes.map { it.type })
        assertEquals(0L, boxes[0].offset)
        assertEquals(8L, boxes[1].offset)
        reader.close()
    }

    @Test
    fun `unknown box types fall back to a leaf node with no children`() {
        val reader = byteReaderOf(
            byteArrayOf(0x00, 0x00, 0x00, 0x08, 0x66, 0x72, 0x65, 0x65)
        )
        val boxes = parseBoxes(reader, 0, reader.length)
        assertTrue(boxes[0].children.isEmpty())
        assertTrue(boxes[0].fields.isEmpty())
        reader.close()
    }
}
