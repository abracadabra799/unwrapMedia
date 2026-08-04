package com.multiviewer.parser

import kotlin.test.Test
import kotlin.test.assertEquals

class ExifDecoderTest {
    @Test
    fun `decodes IFD0, follows the Exif pointer, and decodes a nested Samsung MakerNote`() {
        // IFD0 (2 entries: Make="SAMSUNG" out-of-line since it's >4 bytes, ExifIFDPointer) ->
        // Exif IFD (1 entry: MakerNote) -> MakerNote blob (1 entry: tag 0x0001). Make must
        // actually say "SAMSUNG" for the MakerNote to be decoded with Samsung's Type2 tag names
        // at all -- see decodeTiff's Make-based dispatch.
        val body = byteArrayOf(
            0x00, 0x00, 0x00, 0x00, // tiffHeaderOffsetField = 0 -> tiffStart = 4
            0x49, 0x49, 0x2a, 0x00, // "II", 42 (little-endian byte order)
            0x08, 0x00, 0x00, 0x00, // IFD0 offset = 8 (relative to tiffStart)
            0x02, 0x00, // IFD0 entry_count = 2
            0x0f, 0x01, 0x02, 0x00, 0x08, 0x00, 0x00, 0x00, 0x26, 0x00, 0x00, 0x00, // Make (0x010F), ASCII, count=8, offset=38
            0x69, 0x87.toByte(), 0x04, 0x00, 0x01, 0x00, 0x00, 0x00, 0x2e, 0x00, 0x00, 0x00, // ExifIFDPointer (0x8769), LONG, count=1, offset=46
            0x00, 0x00, 0x00, 0x00, // IFD0 NextIFDOffset = 0
            0x53, 0x41, 0x4d, 0x53, 0x55, 0x4e, 0x47, 0x00, // "SAMSUNG\0" at offset 38
            0x01, 0x00, // Exif IFD entry_count = 1
            0x7c, 0x92.toByte(), 0x07, 0x00, 0x0e, 0x00, 0x00, 0x00, 0x40, 0x00, 0x00, 0x00, // MakerNote (0x927C), UNDEFINED, count=14, offset=64
            0x00, 0x00, 0x00, 0x00, // Exif IFD NextIFDOffset = 0
            0x01, 0x00, // MakerNote entry_count = 1
            0x01, 0x00, 0x02, 0x00, 0x04, 0x00, 0x00, 0x00, 0x30, 0x31, 0x30, 0x31, // tag 0x0001, ASCII, count=4, value="0101"
        )
        val reader = byteReaderOf(body)
        val ifds = decodeExif(reader, 0, body.size.toLong())

        assertEquals(1, ifds.size)
        val ifd0 = ifds[0]
        assertEquals("IFD0", ifd0.type)
        assertEquals("SAMSUNG", ifd0.fields.first { it.name == "Make" }.value)

        val exifIfd = ifd0.children.first { it.type == "Exif" }
        val makerNote = exifIfd.children.first { it.type == "MakerNote" }
        assertEquals("0101", makerNote.fields.first { it.name == "MakerNoteVersion" }.value)
        reader.close()
    }

    @Test
    fun `a non-Samsung Make does not apply Samsung's MakerNote tag names`() {
        // Same shape as the Samsung test above, but Make="OTHERCO" -- tag 0x0001 in the MakerNote
        // must NOT be labeled "MakerNoteVersion" (or anything else from Samsung's table), since
        // that numeric tag ID means something manufacturer-specific and Samsung's names would be
        // actively wrong here.
        val body = byteArrayOf(
            0x00, 0x00, 0x00, 0x00,
            0x49, 0x49, 0x2a, 0x00,
            0x08, 0x00, 0x00, 0x00,
            0x02, 0x00,
            0x0f, 0x01, 0x02, 0x00, 0x08, 0x00, 0x00, 0x00, 0x26, 0x00, 0x00, 0x00, // Make, count=8, offset=38
            0x69, 0x87.toByte(), 0x04, 0x00, 0x01, 0x00, 0x00, 0x00, 0x2e, 0x00, 0x00, 0x00, // ExifIFDPointer, offset=46
            0x00, 0x00, 0x00, 0x00,
            0x4f, 0x54, 0x48, 0x45, 0x52, 0x43, 0x4f, 0x00, // "OTHERCO\0" at offset 38
            0x01, 0x00,
            0x7c, 0x92.toByte(), 0x07, 0x00, 0x0e, 0x00, 0x00, 0x00, 0x40, 0x00, 0x00, 0x00, // MakerNote, offset=64
            0x00, 0x00, 0x00, 0x00,
            0x01, 0x00,
            0x01, 0x00, 0x02, 0x00, 0x04, 0x00, 0x00, 0x00, 0x30, 0x31, 0x30, 0x31, // tag 0x0001, value="0101"
        )
        val reader = byteReaderOf(body)
        val ifds = decodeExif(reader, 0, body.size.toLong())

        val ifd0 = ifds[0]
        assertEquals("OTHERCO", ifd0.fields.first { it.name == "Make" }.value)
        val exifIfd = ifd0.children.first { it.type == "Exif" }
        val makerNote = exifIfd.children.first { it.type == "MakerNote" }
        assertEquals("0101", makerNote.fields.first { it.name == "Tag 0x0001" }.value)
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
    fun `decodeTiff follows a SubIFDs pointer and finds a preview stored there (camera RAW layout)`() {
        // Camera RAW formats (CR2/NEF/ARW/DNG) are TIFF/EP-based and commonly hang one or more
        // additional resolutions (e.g. a full-size preview) off IFD0 via the SubIFDs tag (0x014A)
        // rather than using NextIFDOffset -- this is a spec-based fixture (TIFF/EP + DNG spec),
        // not verified against a real camera file.
        val tiff = byteArrayOf(
            0x49, 0x49, 0x2a, 0x00, // "II", 42 (little-endian byte order)
            0x08, 0x00, 0x00, 0x00, // IFD0 offset = 8
            0x01, 0x00, // IFD0 entry_count = 1
            0x4a, 0x01, 0x04, 0x00, 0x01, 0x00, 0x00, 0x00, 0x1a, 0x00, 0x00, 0x00, // SubIFDs (0x014A) -> offset 26
            0x00, 0x00, 0x00, 0x00, // IFD0 NextIFDOffset = 0
            0x02, 0x00, // SubIFD0 entry_count = 2
            0x01, 0x02, 0x04, 0x00, 0x01, 0x00, 0x00, 0x00, 0x38, 0x00, 0x00, 0x00, // JPEGInterchangeFormat -> offset 56
            0x02, 0x02, 0x04, 0x00, 0x01, 0x00, 0x00, 0x00, 0x04, 0x00, 0x00, 0x00, // JPEGInterchangeFormatLength = 4
            0x00, 0x00, 0x00, 0x00, // SubIFD0 NextIFDOffset = 0
            0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xd9.toByte(), // preview bytes at offset 56 (4 bytes)
        )
        val reader = byteReaderOf(tiff)
        val ifds = decodeTiff(reader, 0, tiff.size.toLong())

        val subIfd = ifds[0].children.first { it.type == "SubIFD0" }
        val preview = subIfd.children.first { it.type == "ThumbnailImage" }
        assertEquals(56L, preview.offset)
        assertEquals(4L, preview.size)
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

    @Test
    fun `resolves a newly-added IFD0 baseline tag`() {
        val body = byteArrayOf(
            0x00, 0x00, 0x00, 0x00, 0x49, 0x49, 0x2a, 0x00,
            0x08, 0x00, 0x00, 0x00, 0x01, 0x00,
            0x03, 0x01, 0x03, 0x00, 0x01, 0x00, 0x00, 0x00, 0x06, 0x00, 0x00, 0x00, // Compression (0x0103), SHORT, count=1, value=6
            0x00, 0x00, 0x00, 0x00,
        )
        val reader = byteReaderOf(body)
        val ifds = decodeExif(reader, 0, body.size.toLong())
        assertEquals("6", ifds[0].fields.first { it.name == "Compression" }.value)
        reader.close()
    }

    @Test
    fun `resolves a newly-added Exif sub-IFD tag`() {
        val body = byteArrayOf(
            0x00, 0x00, 0x00, 0x00, 0x49, 0x49, 0x2a, 0x00,
            0x08, 0x00, 0x00, 0x00, 0x01, 0x00, 0x69, 0x87.toByte(),
            0x04, 0x00, 0x01, 0x00, 0x00, 0x00, 0x1a, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00,
            0x34, 0xA4.toByte(), 0x02, 0x00, 0x08, 0x00, 0x00, 0x00, 0x2c, 0x00, 0x00, 0x00, // LensModel (0xA434), ASCII, count=8, offset=44 -> absolute 48
            0x00, 0x00, 0x00, 0x00,
            0x35, 0x30, 0x6d, 0x6d, 0x20, 0x66, 0x31, 0x00, // "50mm f1\0" at offset 48
        )
        val reader = byteReaderOf(body)
        val ifds = decodeExif(reader, 0, body.size.toLong())
        val exifIfd = ifds[0].children.first { it.type == "Exif" }
        assertEquals("50mm f1", exifIfd.fields.first { it.name == "LensModel" }.value)
        reader.close()
    }

    @Test
    fun `resolves a newly-added GPS tag`() {
        val body = byteArrayOf(
            0x00, 0x00, 0x00, 0x00, 0x49, 0x49, 0x2a, 0x00,
            0x08, 0x00, 0x00, 0x00, 0x01, 0x00, 0x25, 0x88.toByte(),
            0x04, 0x00, 0x01, 0x00, 0x00, 0x00, 0x1a, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00,
            0x1d, 0x00, 0x02, 0x00, 0x0b, 0x00, 0x00, 0x00, 0x2c, 0x00, 0x00, 0x00, // GPSDateStamp (0x001D), ASCII, count=11, offset=44 -> absolute 48
            0x00, 0x00, 0x00, 0x00,
            0x32, 0x30, 0x32, 0x36, 0x3a, 0x30, 0x38, 0x3a, 0x30, 0x34, 0x00, // "2026:08:04\0" at offset 48
        )
        val reader = byteReaderOf(body)
        val ifds = decodeExif(reader, 0, body.size.toLong())
        val gpsIfd = ifds[0].children.first { it.type == "GPS" }
        assertEquals("2026:08:04", gpsIfd.fields.first { it.name == "GPSDateStamp" }.value)
        reader.close()
    }

    @Test
    fun `resolves a DNG private tag stored in IFD0`() {
        val body = byteArrayOf(
            0x00, 0x00, 0x00, 0x00, 0x49, 0x49, 0x2a, 0x00,
            0x08, 0x00, 0x00, 0x00, 0x01, 0x00,
            0x12, 0xC6.toByte(), 0x01, 0x00, 0x04, 0x00, 0x00, 0x00, 0x01, 0x01, 0x04, 0x00, // DNGVersion (0xC612), BYTE, count=4, value=1.1.4.0
            0x00, 0x00, 0x00, 0x00,
        )
        val reader = byteReaderOf(body)
        val ifds = decodeExif(reader, 0, body.size.toLong())
        assertEquals("01 01 04 00", ifds[0].fields.first { it.name == "DNGVersion" }.value)
        reader.close()
    }

    @Test
    fun `existing tags and the unmapped-tag fallback are unaffected by the table expansion`() {
        // Same fixture as "unrecognized tag falls back to a hex label" above -- re-asserted here
        // to make the no-regression guarantee explicit for this task.
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
}
