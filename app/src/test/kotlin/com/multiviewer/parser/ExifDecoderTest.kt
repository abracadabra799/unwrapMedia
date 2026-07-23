package com.multiviewer.parser

import kotlin.test.Test
import kotlin.test.assertEquals

class ExifDecoderTest {
    @Test
    fun `decodes IFD0, follows the Exif pointer, and decodes a nested Samsung MakerNote`() {
        val body = byteArrayOf(
            0x00, 0x00, 0x00, 0x00, 0x49, 0x49, 0x2a, 0x00,
            0x08, 0x00, 0x00, 0x00, 0x02, 0x00, 0x0f, 0x01,
            0x02, 0x00, 0x04, 0x00, 0x00, 0x00, 0x41, 0x42,
            0x43, 0x00, 0x69, 0x87.toByte(), 0x04, 0x00, 0x01, 0x00,
            0x00, 0x00, 0x26, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x01, 0x00, 0x7c, 0x92.toByte(), 0x07, 0x00,
            0x0e, 0x00, 0x00, 0x00, 0x38, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00,
            0x02, 0x00, 0x04, 0x00, 0x00, 0x00, 0x30, 0x31,
            0x30, 0x31,
        )
        val reader = byteReaderOf(body)
        val ifds = decodeExif(reader, 0, body.size.toLong())

        assertEquals(1, ifds.size)
        val ifd0 = ifds[0]
        assertEquals("IFD0", ifd0.type)
        assertEquals("ABC", ifd0.fields.first { it.name == "Make" }.value)

        val exifIfd = ifd0.children.first { it.type == "Exif" }
        val makerNote = exifIfd.children.first { it.type == "MakerNote" }
        assertEquals("0101", makerNote.fields.first { it.name == "Version" }.value)
        reader.close()
    }

    @Test
    fun `follows the GPS pointer and decodes a GPS tag`() {
        val body = byteArrayOf(
            0x00, 0x00, 0x00, 0x00, 0x49, 0x49, 0x2a, 0x00,
            0x08, 0x00, 0x00, 0x00, 0x01, 0x00, 0x25, 0x88.toByte(),
            0x04, 0x00, 0x01, 0x00, 0x00, 0x00, 0x1a, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00,
            0x01, 0x00, 0x02, 0x00, 0x02, 0x00, 0x00, 0x00,
            0x4e, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        )
        val reader = byteReaderOf(body)
        val ifds = decodeExif(reader, 0, body.size.toLong())

        val ifd0 = ifds[0]
        val gpsIfd = ifd0.children.first { it.type == "GPS" }
        assertEquals("N", gpsIfd.fields.first { it.name == "GPSLatitudeRef" }.value)
        reader.close()
    }

    @Test
    fun `unrecognized tag falls back to a hex label`() {
        val body = byteArrayOf(
            0x00, 0x00, 0x00, 0x00, 0x49, 0x49, 0x2a, 0x00,
            0x08, 0x00, 0x00, 0x00, 0x01, 0x00,
            0x34, 0x12, 0x04, 0x00, 0x01, 0x00, 0x00, 0x00, 0x2a, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00,
        )
        val reader = byteReaderOf(body)
        val ifds = decodeExif(reader, 0, body.size.toLong())
        assertEquals("42", ifds[0].fields.first { it.name == "Tag 0x1234" }.value)
        reader.close()
    }

    @Test
    fun `out-of-line value offset past the end of the item is treated as out of bounds, not a crash`() {
        val body = byteArrayOf(
            0x00, 0x00, 0x00, 0x00, 0x49, 0x49, 0x2a, 0x00,
            0x08, 0x00, 0x00, 0x00, 0x01, 0x00,
            0x0f, 0x01, 0x02, 0x00, 0x0a, 0x00, 0x00, 0x00, 0xff.toByte(), 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00,
        )
        val reader = byteReaderOf(body)
        val ifds = decodeExif(reader, 0, body.size.toLong())
        assertEquals("(out of bounds)", ifds[0].fields.first { it.name == "Make" }.value)
        reader.close()
    }

    @Test
    fun `decodeTiff decodes a standalone TIFF blob with no HEIF offset wrapper`() {
        val tiff = byteArrayOf(
            0x49, 0x49, 0x2a, 0x00, 0x08, 0x00, 0x00, 0x00,
            0x01, 0x00, 0x0f, 0x01, 0x02, 0x00, 0x04, 0x00,
            0x00, 0x00, 0x41, 0x42, 0x43, 0x00, 0x00, 0x00,
            0x00, 0x00,
        )
        val reader = byteReaderOf(tiff)
        val ifds = decodeTiff(reader, 0, tiff.size.toLong())

        assertEquals(1, ifds.size)
        assertEquals("IFD0", ifds[0].type)
        assertEquals("ABC", ifds[0].fields.first { it.name == "Make" }.value)
        reader.close()
    }

    @Test
    fun `decodeTiff follows NextIFDOffset to IFD1 and extracts a ThumbnailImage node from JPEGInterchangeFormat tags`() {
        val tiff = byteArrayOf(
            0x49, 0x49, 0x2a, 0x00, // "II", 42 (little-endian byte order)
            0x08, 0x00, 0x00, 0x00, // IFD0 offset = 8
            0x00, 0x00, // IFD0 entry_count = 0
            0x0e, 0x00, 0x00, 0x00, // IFD0 NextIFDOffset = 14
            0x02, 0x00, // IFD1 entry_count = 2
            0x01, 0x02, 0x04, 0x00, 0x01, 0x00, 0x00, 0x00, 0x2c, 0x00, 0x00, 0x00, // JPEGInterchangeFormat (0x0201) = 44
            0x02, 0x02, 0x04, 0x00, 0x01, 0x00, 0x00, 0x00, 0x04, 0x00, 0x00, 0x00, // JPEGInterchangeFormatLength (0x0202) = 4
            0x00, 0x00, 0x00, 0x00, // IFD1 NextIFDOffset = 0
            0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xd9.toByte(), // thumbnail bytes at offset 44 (4 bytes)
        )
        val reader = byteReaderOf(tiff)
        val ifds = decodeTiff(reader, 0, tiff.size.toLong())

        assertEquals(2, ifds.size)
        assertEquals("IFD0", ifds[0].type)
        assertEquals("IFD1", ifds[1].type)
        val thumbnail = ifds[1].children.first { it.type == "ThumbnailImage" }
        assertEquals(44L, thumbnail.offset)
        assertEquals(4L, thumbnail.size)
        reader.close()
    }

    @Test
    fun `an IFD1 with only one of the two JPEGInterchangeFormat tags produces no ThumbnailImage node`() {
        val tiff = byteArrayOf(
            0x49, 0x49, 0x2a, 0x00, // "II", 42 (little-endian byte order)
            0x08, 0x00, 0x00, 0x00, // IFD0 offset = 8
            0x00, 0x00, // IFD0 entry_count = 0
            0x0e, 0x00, 0x00, 0x00, // IFD0 NextIFDOffset = 14
            0x01, 0x00, // IFD1 entry_count = 1
            0x01, 0x02, 0x04, 0x00, 0x01, 0x00, 0x00, 0x00, 0x64, 0x00, 0x00, 0x00, // JPEGInterchangeFormat only (no Length tag)
            0x00, 0x00, 0x00, 0x00, // IFD1 NextIFDOffset = 0
        )
        val reader = byteReaderOf(tiff)
        val ifds = decodeTiff(reader, 0, tiff.size.toLong())

        assertEquals(2, ifds.size)
        assertEquals(true, ifds[1].children.none { it.type == "ThumbnailImage" })
        reader.close()
    }
}
