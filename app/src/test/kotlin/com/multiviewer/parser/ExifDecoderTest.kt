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
            0x03, 0x01, 0x03, 0x00, 0x01, 0x00, 0x00, 0x00, 0x05, 0x00, 0x00, 0x00, // Compression (0x0103), SHORT, count=1, value=5 (not in Task 2's enum table -- this test is about name resolution only)
            0x00, 0x00, 0x00, 0x00,
        )
        val reader = byteReaderOf(body)
        val ifds = decodeExif(reader, 0, body.size.toLong())
        assertEquals("5", ifds[0].fields.first { it.name == "Compression" }.value)
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

    @Test
    fun `translates Orientation into a human-readable label`() {
        val body = byteArrayOf(
            0x00, 0x00, 0x00, 0x00, 0x49, 0x49, 0x2a, 0x00,
            0x08, 0x00, 0x00, 0x00, 0x01, 0x00,
            0x12, 0x01, 0x03, 0x00, 0x01, 0x00, 0x00, 0x00, 0x06, 0x00, 0x00, 0x00, // Orientation (0x0112), SHORT, count=1, value=6
            0x00, 0x00, 0x00, 0x00,
        )
        val reader = byteReaderOf(body)
        val ifds = decodeExif(reader, 0, body.size.toLong())
        assertEquals("Rotate 90 CW", ifds[0].fields.first { it.name == "Orientation" }.value)
        reader.close()
    }

    @Test
    fun `translates ExposureProgram into a human-readable label`() {
        val body = byteArrayOf(
            0x00, 0x00, 0x00, 0x00, 0x49, 0x49, 0x2a, 0x00,
            0x08, 0x00, 0x00, 0x00, 0x01, 0x00, 0x69, 0x87.toByte(),
            0x04, 0x00, 0x01, 0x00, 0x00, 0x00, 0x1a, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00,
            0x22, 0x88.toByte(), 0x03, 0x00, 0x01, 0x00, 0x00, 0x00, 0x02, 0x00, 0x00, 0x00, // ExposureProgram (0x8822), SHORT, count=1, value=2
            0x00, 0x00, 0x00, 0x00,
        )
        val reader = byteReaderOf(body)
        val ifds = decodeExif(reader, 0, body.size.toLong())
        val exifIfd = ifds[0].children.first { it.type == "Exif" }
        assertEquals("Normal program", exifIfd.fields.first { it.name == "ExposureProgram" }.value)
        reader.close()
    }

    @Test
    fun `formats FNumber as an f-stop instead of a raw fraction`() {
        val body = byteArrayOf(
            0x00, 0x00, 0x00, 0x00, 0x49, 0x49, 0x2a, 0x00,
            0x08, 0x00, 0x00, 0x00, 0x01, 0x00, 0x69, 0x87.toByte(),
            0x04, 0x00, 0x01, 0x00, 0x00, 0x00, 0x1a, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00,
            0x9d.toByte(), 0x82.toByte(), 0x05, 0x00, 0x01, 0x00, 0x00, 0x00, 0x2c, 0x00, 0x00, 0x00, // FNumber (0x829D), RATIONAL, count=1, offset=44 -> absolute 48
            0x00, 0x00, 0x00, 0x00,
            0x1c, 0x00, 0x00, 0x00, 0x0a, 0x00, 0x00, 0x00, // 28/10 at offset 48
        )
        val reader = byteReaderOf(body)
        val ifds = decodeExif(reader, 0, body.size.toLong())
        val exifIfd = ifds[0].children.first { it.type == "Exif" }
        assertEquals("f/2.8", exifIfd.fields.first { it.name == "FNumber" }.value)
        reader.close()
    }

    @Test
    fun `formats a sub-one-second ExposureTime as a fraction with a unit suffix`() {
        val body = byteArrayOf(
            0x00, 0x00, 0x00, 0x00, 0x49, 0x49, 0x2a, 0x00,
            0x08, 0x00, 0x00, 0x00, 0x01, 0x00, 0x69, 0x87.toByte(),
            0x04, 0x00, 0x01, 0x00, 0x00, 0x00, 0x1a, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00,
            0x9a.toByte(), 0x82.toByte(), 0x05, 0x00, 0x01, 0x00, 0x00, 0x00, 0x2c, 0x00, 0x00, 0x00, // ExposureTime (0x829A), RATIONAL, count=1, offset=44 -> absolute 48
            0x00, 0x00, 0x00, 0x00,
            0x01, 0x00, 0x00, 0x00, 0x7d, 0x00, 0x00, 0x00, // 1/125 at offset 48
        )
        val reader = byteReaderOf(body)
        val ifds = decodeExif(reader, 0, body.size.toLong())
        val exifIfd = ifds[0].children.first { it.type == "Exif" }
        assertEquals("1/125s", exifIfd.fields.first { it.name == "ExposureTime" }.value)
        reader.close()
    }

    @Test
    fun `a tag with no value-interpretation entry still shows the raw formatted value`() {
        // ISOSpeedRatings (0x8827) has a tag name but no enum/rational entry in this task's
        // tables -- must still show the plain formatted number, not throw or show blank.
        val body = byteArrayOf(
            0x00, 0x00, 0x00, 0x00, 0x49, 0x49, 0x2a, 0x00,
            0x08, 0x00, 0x00, 0x00, 0x01, 0x00, 0x69, 0x87.toByte(),
            0x04, 0x00, 0x01, 0x00, 0x00, 0x00, 0x1a, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00,
            0x27, 0x88.toByte(), 0x03, 0x00, 0x01, 0x00, 0x00, 0x00, 0x64, 0x00, 0x00, 0x00, // ISOSpeedRatings (0x8827), SHORT, count=1, value=100
            0x00, 0x00, 0x00, 0x00,
        )
        val reader = byteReaderOf(body)
        val ifds = decodeExif(reader, 0, body.size.toLong())
        val exifIfd = ifds[0].children.first { it.type == "Exif" }
        assertEquals("100", exifIfd.fields.first { it.name == "ISOSpeedRatings" }.value)
        reader.close()
    }

    @Test
    fun `decodeTiff follows NextIFDOffset across 3 pages (IFD0, IFD1, IFD2)`() {
        val tiff = byteArrayOf(
            0x49, 0x49, 0x2a, 0x00, // "II", 42
            0x08, 0x00, 0x00, 0x00, // IFD0 offset = 8
            // IFD0 at offset 8 (entry_count = 1, next = 26)
            0x01, 0x00,
            0x00, 0x01, 0x03, 0x00, 0x01, 0x00, 0x00, 0x00, 0x80.toByte(), 0x02, 0x00, 0x00, // ImageWidth = 640
            0x1a, 0x00, 0x00, 0x00, // NextIFDOffset = 26
            // IFD1 at offset 26 (entry_count = 1, next = 44)
            0x01, 0x00,
            0x00, 0x01, 0x03, 0x00, 0x01, 0x00, 0x00, 0x00, 0x40, 0x01, 0x00, 0x00, // ImageWidth = 320
            0x2c, 0x00, 0x00, 0x00, // NextIFDOffset = 44
            // IFD2 at offset 44 (entry_count = 1, next = 0)
            0x01, 0x00,
            0x00, 0x01, 0x03, 0x00, 0x01, 0x00, 0x00, 0x00, 0xa0.toByte(), 0x00, 0x00, 0x00, // ImageWidth = 160
            0x00, 0x00, 0x00, 0x00, // NextIFDOffset = 0
        )
        val reader = byteReaderOf(tiff)
        val ifds = decodeTiff(reader, 0, tiff.size.toLong())

        assertEquals(3, ifds.size)
        assertEquals("IFD0", ifds[0].type)
        assertEquals("IFD1", ifds[1].type)
        assertEquals("IFD2", ifds[2].type)
        assertEquals("640", ifds[0].fields.first { it.name == "ImageWidth" }.value)
        assertEquals("320", ifds[1].fields.first { it.name == "ImageWidth" }.value)
        assertEquals("160", ifds[2].fields.first { it.name == "ImageWidth" }.value)
        reader.close()
    }

    @Test
    fun `decodeTiff extracts ImageData (Strips) child node from StripOffsets and StripByteCounts`() {
        val tiff = byteArrayOf(
            0x49, 0x49, 0x2a, 0x00, // "II", 42
            0x08, 0x00, 0x00, 0x00, // IFD0 offset = 8
            0x02, 0x00, // entry_count = 2
            0x11, 0x01, 0x04, 0x00, 0x01, 0x00, 0x00, 0x00, 0x26, 0x00, 0x00, 0x00, // StripOffsets (0x0111), count=1, value=38
            0x17, 0x01, 0x04, 0x00, 0x01, 0x00, 0x00, 0x00, 0x10, 0x00, 0x00, 0x00, // StripByteCounts (0x0117), count=1, value=16
            0x00, 0x00, 0x00, 0x00, // next = 0
            // Strip data payload (16 bytes at offset 38)
            0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0a, 0x0b, 0x0c, 0x0d, 0x0e, 0x0f,
        )
        val reader = byteReaderOf(tiff)
        val ifds = decodeTiff(reader, 0, tiff.size.toLong())

        val stripNode = ifds[0].children.first { it.type == "ImageData (Strips)" }
        assertEquals(38L, stripNode.offset)
        assertEquals(16L, stripNode.size)
        assertEquals("1", stripNode.fields.first { it.name == "Strip Count" }.value)
        assertEquals("16 bytes (16 B)", stripNode.fields.first { it.name == "Total Payload Size" }.value)
        reader.close()
    }

    @Test
    fun `decodeTiff extracts ICCProfile, XMP, PhotoshopIRB, and GeoTIFF child nodes`() {
        val tiff = byteArrayOf(
            0x49, 0x49, 0x2a, 0x00, // "II", 42
            0x08, 0x00, 0x00, 0x00, // IFD0 offset = 8
            0x04, 0x00, // entry_count = 4
            0x73, 0x87.toByte(), 0x07, 0x00, 0x08, 0x00, 0x00, 0x00, 0x3e, 0x00, 0x00, 0x00, // ICCProfile (0x8773), count=8, offset=62
            0xbc.toByte(), 0x02, 0x02, 0x00, 0x08, 0x00, 0x00, 0x00, 0x46, 0x00, 0x00, 0x00, // XMP (0x02BC), count=8, offset=70
            0x49, 0x86.toByte(), 0x07, 0x00, 0x08, 0x00, 0x00, 0x00, 0x4e, 0x00, 0x00, 0x00, // Photoshop (0x8649), count=8, offset=78
            0xaf.toByte(), 0x87.toByte(), 0x03, 0x00, 0x04, 0x00, 0x00, 0x00, 0x56, 0x00, 0x00, 0x00, // GeoKeyDirectory (0x87AF), count=4, offset=86
            0x00, 0x00, 0x00, 0x00, // next = 0
            // Payload blocks
            0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, // ICC at 62
            0x3c, 0x78, 0x6d, 0x70, 0x2f, 0x3e, 0x00, 0x00, // XMP at 70
            0x38, 0x42, 0x49, 0x4d, 0x04, 0x04, 0x00, 0x00, // 8BIM at 78
            0x01, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, // GeoKeys at 86
        )
        val reader = byteReaderOf(tiff)
        val ifds = decodeTiff(reader, 0, tiff.size.toLong())

        assertEquals(true, ifds[0].children.any { it.type == "ICC Color Profile" })
        assertEquals(true, ifds[0].children.any { it.type == "XMP Metadata (XML Packet)" })
        assertEquals(true, ifds[0].children.any { it.type == "Photoshop IRB / IPTC" })
        assertEquals(true, ifds[0].children.any { it.type == "GeoTIFF Metadata" })
        reader.close()
    }
}
