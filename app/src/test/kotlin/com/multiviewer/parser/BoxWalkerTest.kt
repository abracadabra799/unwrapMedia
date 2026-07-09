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

    @Test
    fun `too few bytes for a box header produces a trailing-bytes warning and stops`() {
        val reader = byteReaderOf(byteArrayOf(0x00, 0x00, 0x00)) // only 3 bytes
        val boxes = parseBoxes(reader, 0, reader.length)
        assertEquals(1, boxes.size)
        assertEquals("?", boxes[0].type)
        assertTrue(boxes[0].warnings.single().contains("too short"))
        reader.close()
    }

    @Test
    fun `declared size smaller than header size produces a warning and clamps to the parent end`() {
        val reader = byteReaderOf(
            byteArrayOf(
                0x00, 0x00, 0x00, 0x04, // size = 4, smaller than the 8-byte header
                0x66, 0x72, 0x65, 0x65, // "free"
            )
        )
        val boxes = parseBoxes(reader, 0, reader.length)
        assertEquals(1, boxes.size)
        assertTrue(boxes[0].warnings.single().contains("smaller than header size"))
        assertEquals(8L, boxes[0].size)
        reader.close()
    }

    @Test
    fun `declared size extending past the parent range produces a warning and clamps`() {
        val reader = byteReaderOf(
            byteArrayOf(
                0x00, 0x00, 0x00, 0x64, // size = 100, way past the 8 bytes available
                0x66, 0x72, 0x65, 0x65, // "free"
            )
        )
        val boxes = parseBoxes(reader, 0, reader.length)
        assertEquals(1, boxes.size)
        assertTrue(boxes[0].warnings.single().contains("extends"))
        assertEquals(8L, boxes[0].size)
        reader.close()
    }

    @Test
    fun `truncated 64-bit size header produces a warning and stops`() {
        val reader = byteReaderOf(
            byteArrayOf(
                0x00, 0x00, 0x00, 0x01, // size = 1 -> expects a 64-bit size next
                0x6D, 0x64, 0x61, 0x74, // "mdat"
                0x00, 0x00,             // only 2 of the required 8 bytes present
            )
        )
        val boxes = parseBoxes(reader, 0, reader.length)
        assertEquals(1, boxes.size)
        assertTrue(boxes[0].warnings.single().contains("only"))
        reader.close()
    }
}
