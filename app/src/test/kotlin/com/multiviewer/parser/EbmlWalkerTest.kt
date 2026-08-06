package com.multiviewer.parser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EbmlWalkerTest {
    @Test
    fun `parses a 4-byte-ID master element containing a 2-byte-ID string leaf`() {
        // EBML header (ID 0x1A45DFA3, 4 bytes) containing a DocType (ID 0x4282, 2 bytes) of "webm".
        val reader = byteReaderOf(
            byteArrayOf(
                0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte(), // EBML element ID (4 bytes)
                0x87.toByte(), // size = 7 (1-byte VINT)
                0x42, 0x82.toByte(), // DocType element ID (2 bytes)
                0x84.toByte(), // size = 4 (1-byte VINT)
                0x77, 0x65, 0x62, 0x6D, // "webm"
            )
        )
        val elements = parseEbmlElements(reader, 0, reader.length)

        assertEquals(1, elements.size)
        assertEquals("EBML", elements[0].type)
        assertEquals(0L, elements[0].offset)
        assertEquals(5, elements[0].headerSize)
        assertEquals(12L, elements[0].size)

        assertEquals(1, elements[0].children.size)
        val docType = elements[0].children[0]
        assertEquals("DocType", docType.type)
        assertEquals(5L, docType.offset)
        assertEquals(3, docType.headerSize)
        assertEquals(7L, docType.size)
        assertEquals("webm", docType.fields.single { it.name == "value" }.value)
        reader.close()
    }

    @Test
    fun `decodes a known 1-byte-ID uint element to its numeric value`() {
        // TrackType (ID 0x83, 1 byte) with a 1-byte value of 1 (video).
        val reader = byteReaderOf(byteArrayOf(0x83.toByte(), 0x81.toByte(), 0x01))
        val elements = parseEbmlElements(reader, 0, reader.length)

        assertEquals(1, elements.size)
        assertEquals("TrackType", elements[0].type)
        assertEquals(2, elements[0].headerSize)
        assertEquals(3L, elements[0].size)
        assertEquals("1", elements[0].fields.single { it.name == "value" }.value)
        reader.close()
    }

    @Test
    fun `a known master element recurses into its children`() {
        // Video (ID 0xE0, 1 byte) containing PixelWidth (ID 0xB0, 1 byte) = 640.
        val reader = byteReaderOf(
            byteArrayOf(
                0xE0.toByte(), 0x84.toByte(), // Video, size = 4
                0xB0.toByte(), 0x82.toByte(), 0x02, 0x80.toByte(), // PixelWidth, size = 2, value = 640
            )
        )
        val elements = parseEbmlElements(reader, 0, reader.length)

        assertEquals(1, elements.size)
        assertEquals("Video", elements[0].type)
        assertEquals(1, elements[0].children.size)
        val pixelWidth = elements[0].children[0]
        assertEquals("PixelWidth", pixelWidth.type)
        assertEquals("640", pixelWidth.fields.single { it.name == "value" }.value)
        reader.close()
    }

    @Test
    fun `an element ID not in the known table falls back to an unlabeled numeric name with no children or fields`() {
        val reader = byteReaderOf(byteArrayOf(0x9B.toByte(), 0x82.toByte(), 0xAA.toByte(), 0xBB.toByte()))
        val elements = parseEbmlElements(reader, 0, reader.length)

        assertEquals(1, elements.size)
        assertEquals("0x9B", elements[0].type)
        assertTrue(elements[0].children.isEmpty())
        assertTrue(elements[0].fields.isEmpty())
        reader.close()
    }

    @Test
    fun `an unknown-size element extends to the end of its parent range`() {
        // TrackType (ID 0x83, 1 byte) with the "unknown size" marker (0xFF = all value bits 1),
        // followed by 1 remaining byte in the given range.
        val reader = byteReaderOf(byteArrayOf(0x83.toByte(), 0xFF.toByte(), 0x05))
        val elements = parseEbmlElements(reader, 0, reader.length)

        assertEquals(1, elements.size)
        assertEquals(3L, elements[0].size)
        assertEquals("5", elements[0].fields.single { it.name == "value" }.value)
        reader.close()
    }

    @Test
    fun `declared size extending past the parent range produces a warning and clamps`() {
        val reader = byteReaderOf(byteArrayOf(0x83.toByte(), 0x8A.toByte(), 0x05))
        val elements = parseEbmlElements(reader, 0, reader.length)

        assertEquals(1, elements.size)
        assertTrue(elements[0].warnings.single().contains("extends"))
        assertEquals(3L, elements[0].size)
        reader.close()
    }

    @Test
    fun `too few bytes for an element header produces a trailing-bytes warning and stops`() {
        val reader = byteReaderOf(byteArrayOf(0x83.toByte()))
        val elements = parseEbmlElements(reader, 0, reader.length)

        assertEquals(1, elements.size)
        assertEquals("?", elements[0].type)
        assertTrue(elements[0].warnings.single().contains("too short"))
        reader.close()
    }

    @Test
    fun `two sibling elements parse back to back`() {
        val reader = byteReaderOf(
            byteArrayOf(
                0x83.toByte(), 0x81.toByte(), 0x01, // TrackType = 1
                0x83.toByte(), 0x81.toByte(), 0x02, // TrackType = 2
            )
        )
        val elements = parseEbmlElements(reader, 0, reader.length)

        assertEquals(2, elements.size)
        assertEquals(0L, elements[0].offset)
        assertEquals(3L, elements[1].offset)
        assertEquals("1", elements[0].fields.single { it.name == "value" }.value)
        assertEquals("2", elements[1].fields.single { it.name == "value" }.value)
        reader.close()
    }

    @Test
    fun `SimpleBlock (raw frame data) is shown as a byte count, not misdecoded as a numeric value`() {
        // Regression test: SimpleBlock/Block hold raw compressed frame data that can be many KB --
        // decoding it as a UINT (as an earlier version of this table did) produced a meaningless
        // wrapped-around number and wastefully read every payload byte just to compute it. Real
        // frame bytes here (not actually valid VP9/VP8, just non-zero filler) to catch any
        // accidental attempt to interpret them.
        val reader = byteReaderOf(
            byteArrayOf(0xA3.toByte(), 0x84.toByte(), 0x11, 0x22, 0x33, 0x44) // SimpleBlock, size = 4
        )
        val elements = parseEbmlElements(reader, 0, reader.length)

        assertEquals(1, elements.size)
        assertEquals("SimpleBlock", elements[0].type)
        assertTrue(elements[0].fields.isEmpty())
        assertEquals("4 byte(s)", elements[0].summary)
        reader.close()
    }

    @Test
    fun `DateUTC decodes to a formatted date string, not a raw nanosecond integer`() {
        // DateUTC (ID 0x4461, 2 bytes), size=8, value=1_000_000_000 ns after 2001-01-01T00:00:00Z
        // (0x3B9ACA00), i.e. 2001-01-01T00:00:01Z.
        val reader = byteReaderOf(
            byteArrayOf(
                0x44, 0x61.toByte(), // DateUTC element ID (2 bytes)
                0x88.toByte(), // size = 8 (1-byte VINT)
                0x00, 0x00, 0x00, 0x00, 0x3B, 0x9A.toByte(), 0xCA.toByte(), 0x00,
            )
        )
        val elements = parseEbmlElements(reader, 0, reader.length)

        assertEquals(1, elements.size)
        assertEquals("DateUTC", elements[0].type)
        assertEquals("2001-01-01T00:00:01", elements[0].fields.single { it.name == "value" }.value)
        assertEquals("2001-01-01T00:00:01", elements[0].summary)
        reader.close()
    }

    @Test
    fun `a DateUTC of 0 shows as not set, matching the MP4 zero-timestamp convention`() {
        val reader = byteReaderOf(
            byteArrayOf(
                0x44, 0x61.toByte(),
                0x88.toByte(),
                0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            )
        )
        val elements = parseEbmlElements(reader, 0, reader.length)

        assertEquals("0 (not set)", elements[0].fields.single { it.name == "value" }.value)
        reader.close()
    }
}
