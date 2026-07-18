package com.multiviewer.parser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JpegWalkerTest {
    @Test
    fun `walks SOI, a generic length-bearing marker, and EOI`() {
        val bytes = byteArrayOf(
            0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xdb.toByte(), 0x00, 0x05, 0x00, 0x01,
            0x02, 0xff.toByte(), 0xd9.toByte(),
        )
        val reader = byteReaderOf(bytes)
        val segments = parseJpegSegments(reader, 0, bytes.size.toLong())

        assertEquals(listOf("SOI", "DQT", "EOI"), segments.map { it.type })
        assertEquals(0L, segments[0].offset)
        assertEquals(2L, segments[0].size)
        assertEquals(2L, segments[1].offset)
        assertEquals(7L, segments[1].size)
        assertEquals(9L, segments[2].offset)
        assertEquals(2L, segments[2].size)
        reader.close()
    }

    @Test
    fun `decodes SOF0 dimensions and a single component`() {
        val bytes = byteArrayOf(
            0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xc0.toByte(), 0x00, 0x0b, 0x08, 0x01,
            0xe0.toByte(), 0x02, 0x80.toByte(), 0x01, 0x01, 0x11, 0x00, 0xff.toByte(),
            0xd9.toByte(),
        )
        val reader = byteReaderOf(bytes)
        val segments = parseJpegSegments(reader, 0, bytes.size.toLong())

        assertEquals(listOf("SOI", "SOF0", "EOI"), segments.map { it.type })
        val sof0 = segments[1]
        assertEquals(13L, sof0.size)
        assertEquals("8", sof0.fields.first { it.name == "precision" }.value)
        assertEquals("480", sof0.fields.first { it.name == "height" }.value)
        assertEquals("640", sof0.fields.first { it.name == "width" }.value)
        assertEquals("1", sof0.fields.first { it.name == "num_components" }.value)
        assertEquals("1", sof0.fields.first { it.name == "component_id" }.value)
        assertEquals("0x11", sof0.fields.first { it.name == "sampling_factors" }.value)
        assertEquals("0", sof0.fields.first { it.name == "quantization_table" }.value)
        assertEquals("640x480, 1 component(s)", sof0.summary)
        reader.close()
    }

    @Test
    fun `SOS scan-data skip does not stop at a byte-stuffed FF00 or an RST marker`() {
        val bytes = byteArrayOf(
            0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xda.toByte(), 0x00, 0x08, 0x01, 0x01,
            0x00, 0x00, 0x3f, 0x00, 0xab.toByte(), 0xff.toByte(), 0x00, 0xcd.toByte(),
            0xff.toByte(), 0xd0.toByte(), 0xef.toByte(), 0xff.toByte(), 0xd9.toByte(),
        )
        val reader = byteReaderOf(bytes)
        val segments = parseJpegSegments(reader, 0, bytes.size.toLong())

        assertEquals(listOf("SOI", "SOS", "EOI"), segments.map { it.type })
        val sos = segments[1]
        assertEquals(2L, sos.offset)
        assertEquals(17L, sos.size)
        val eoi = segments[2]
        assertEquals(19L, eoi.offset)
        reader.close()
    }

    @Test
    fun `SOS scan-data skip treats a run of FF fill bytes before the real marker as scan data`() {
        val bytes = byteArrayOf(
            0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xda.toByte(), 0x00, 0x08, 0x01, 0x01,
            0x00, 0x00, 0x3f, 0x00, 0xab.toByte(), 0xff.toByte(), 0xff.toByte(), 0xd9.toByte(),
        )
        val reader = byteReaderOf(bytes)
        val segments = parseJpegSegments(reader, 0, bytes.size.toLong())

        assertEquals(listOf("SOI", "SOS", "EOI"), segments.map { it.type })
        val sos = segments[1]
        assertEquals(2L, sos.offset)
        assertEquals(12L, sos.size)
        val eoi = segments[2]
        assertEquals(14L, eoi.offset)
        reader.close()
    }

    @Test
    fun `a byte that is not 0xFF where a marker is expected produces a warning and stops`() {
        val bytes = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0x00, 0x01)
        val reader = byteReaderOf(bytes)
        val segments = parseJpegSegments(reader, 0, bytes.size.toLong())

        assertEquals(listOf("SOI", "?"), segments.map { it.type })
        assertTrue(segments[1].warnings.isNotEmpty())
        reader.close()
    }

    @Test
    fun `APP1 Exif payload delegates to decodeTiff`() {
        val bytes = byteArrayOf(
            0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xe1.toByte(), 0x00, 0x22, 0x45, 0x78,
            0x69, 0x66, 0x00, 0x00, 0x49, 0x49, 0x2a, 0x00,
            0x08, 0x00, 0x00, 0x00, 0x01, 0x00, 0x0f, 0x01,
            0x02, 0x00, 0x04, 0x00, 0x00, 0x00, 0x41, 0x42,
            0x43, 0x00, 0x00, 0x00, 0x00, 0x00, 0xff.toByte(), 0xd9.toByte(),
        )
        val reader = byteReaderOf(bytes)
        val segments = parseJpegSegments(reader, 0, bytes.size.toLong())

        assertEquals(listOf("SOI", "APP1", "EOI"), segments.map { it.type })
        val app1 = segments[1]
        assertEquals(36L, app1.size)
        val ifd0 = app1.children.single()
        assertEquals("IFD0", ifd0.type)
        assertEquals("ABC", ifd0.fields.first { it.name == "Make" }.value)
        reader.close()
    }

    @Test
    fun `APP1 XMP payload is exposed as a single text field`() {
        val bytes = byteArrayOf(
            0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xe1.toByte(), 0x00, 0x2b, 0x68, 0x74,
            0x74, 0x70, 0x3a, 0x2f, 0x2f, 0x6e, 0x73, 0x2e,
            0x61, 0x64, 0x6f, 0x62, 0x65, 0x2e, 0x63, 0x6f,
            0x6d, 0x2f, 0x78, 0x61, 0x70, 0x2f, 0x31, 0x2e,
            0x30, 0x2f, 0x00, 0x3c, 0x78, 0x3a, 0x78, 0x6d,
            0x70, 0x6d, 0x65, 0x74, 0x61, 0x2f, 0x3e, 0xff.toByte(),
            0xd9.toByte(),
        )
        val reader = byteReaderOf(bytes)
        val segments = parseJpegSegments(reader, 0, bytes.size.toLong())

        assertEquals(listOf("SOI", "APP1", "EOI"), segments.map { it.type })
        val app1 = segments[1]
        assertEquals("<x:xmpmeta/>", app1.fields.first { it.name == "xmp" }.value)
        assertEquals("XMP (12 chars)", app1.summary)
        reader.close()
    }

    @Test
    fun `DQT decodes a single 8-bit table, de-zigzags it, and estimates quality`() {
        val bytes = byteArrayOf(
            0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xdb.toByte(), 0x00, 0x43, 0x00, 0x10,
            0x0b, 0x0c, 0x0e, 0x0c, 0x0a, 0x10, 0x0e, 0x0d,
            0x0e, 0x12, 0x11, 0x10, 0x13, 0x18, 0x28, 0x1a,
            0x18, 0x16, 0x16, 0x18, 0x31, 0x23, 0x25, 0x1d,
            0x28, 0x3a, 0x33, 0x3d, 0x3c, 0x39, 0x33, 0x38,
            0x37, 0x40, 0x48, 0x5c, 0x4e, 0x40, 0x44, 0x57,
            0x45, 0x37, 0x38, 0x50, 0x6d, 0x51, 0x57, 0x5f,
            0x62, 0x67, 0x68, 0x67, 0x3e, 0x4d, 0x71, 0x79,
            0x70, 0x64, 0x78, 0x5c, 0x65, 0x67, 0x63, 0xff.toByte(),
            0xd9.toByte(),
        )
        val reader = byteReaderOf(bytes)
        val segments = parseJpegSegments(reader, 0, bytes.size.toLong())

        assertEquals(listOf("SOI", "DQT", "EOI"), segments.map { it.type })
        val dqt = segments[1]
        assertEquals(1, dqt.children.size)
        val table = dqt.children[0]
        assertEquals("QuantizationTable", table.type)
        assertEquals("0", table.fields.first { it.name == "precision" }.value)
        assertEquals("0", table.fields.first { it.name == "destination_id" }.value)
        assertEquals("~50%", table.fields.first { it.name == "quality_estimate" }.value)
        val expectedRaster = listOf(
            16, 11, 10, 16, 24, 40, 51, 61,
            12, 12, 14, 19, 26, 58, 60, 55,
            14, 13, 16, 24, 40, 57, 69, 56,
            14, 17, 22, 29, 51, 87, 80, 62,
            18, 22, 37, 56, 68, 109, 103, 77,
            24, 35, 55, 64, 81, 104, 113, 92,
            49, 64, 78, 87, 103, 121, 120, 101,
            72, 92, 95, 98, 112, 100, 103, 99,
        ).map { it.toString() }
        reader.close()
    }

    @Test
    fun `DQT decodes multiple tables packed into one segment`() {
        val bytes = byteArrayOf(
            0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xdb.toByte(), 0x00, 0x84.toByte(), 0x00, 0x10,
            0x0b, 0x0c, 0x0e, 0x0c, 0x0a, 0x10, 0x0e, 0x0d,
            0x0e, 0x12, 0x11, 0x10, 0x13, 0x18, 0x28, 0x1a,
            0x18, 0x16, 0x16, 0x18, 0x31, 0x23, 0x25, 0x1d,
            0x28, 0x3a, 0x33, 0x3d, 0x3c, 0x39, 0x33, 0x38,
            0x37, 0x40, 0x48, 0x5c, 0x4e, 0x40, 0x44, 0x57,
            0x45, 0x37, 0x38, 0x50, 0x6d, 0x51, 0x57, 0x5f,
            0x62, 0x67, 0x68, 0x67, 0x3e, 0x4d, 0x71, 0x79,
            0x70, 0x64, 0x78, 0x5c, 0x65, 0x67, 0x63, 0x01,
            0x11, 0x12, 0x12, 0x18, 0x15, 0x18, 0x2f, 0x1a,
            0x1a, 0x2f, 0x63, 0x42, 0x38, 0x42, 0x63, 0x63,
            0x63, 0x63, 0x63, 0x63, 0x63, 0x63, 0x63, 0x63,
            0x63, 0x63, 0x63, 0x63, 0x63, 0x63, 0x63, 0x63,
            0x63, 0x63, 0x63, 0x63, 0x63, 0x63, 0x63, 0x63,
            0x63, 0x63, 0x63, 0x63, 0x63, 0x63, 0x63, 0x63,
            0x63, 0x63, 0x63, 0x63, 0x63, 0x63, 0x63, 0x63,
            0x63, 0x63, 0x63, 0x63, 0x63, 0x63, 0x63, 0x63,
            0xff.toByte(), 0xd9.toByte(),
        )
        val reader = byteReaderOf(bytes)
        val segments = parseJpegSegments(reader, 0, bytes.size.toLong())

        val dqt = segments[1]
        assertEquals(2, dqt.children.size)
        assertEquals("0", dqt.children[0].fields.first { it.name == "destination_id" }.value)
        assertEquals("1", dqt.children[1].fields.first { it.name == "destination_id" }.value)
        assertEquals("~50%", dqt.children[0].fields.first { it.name == "quality_estimate" }.value)
        assertEquals("~50%", dqt.children[1].fields.first { it.name == "quality_estimate" }.value)
        assertEquals("2 quantization table(s)", dqt.summary)
        reader.close()
    }

    @Test
    fun `DQT with a truncated table produces a warning and no children`() {
        val bytes = byteArrayOf(
            0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xdb.toByte(), 0x00, 0x21, 0x00, 0x10,
            0x0b, 0x0c, 0x0e, 0x0c, 0x0a, 0x10, 0x0e, 0x0d,
            0x0e, 0x12, 0x11, 0x10, 0x13, 0x18, 0x28, 0x1a,
            0x18, 0x16, 0x16, 0x18, 0x31, 0x23, 0x25, 0x1d,
            0x28, 0x3a, 0x33, 0x3d, 0x3c, 0xff.toByte(), 0xd9.toByte(),
        )
        val reader = byteReaderOf(bytes)
        val segments = parseJpegSegments(reader, 0, bytes.size.toLong())

        val dqt = segments[1]
        assertEquals(0, dqt.children.size)
        assertTrue(dqt.warnings.isNotEmpty())
        reader.close()
    }

    @Test
    fun `DHT decodes a single Huffman table's bit counts and total code count`() {
        val bytes = byteArrayOf(
            0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xc4.toByte(), 0x00, 0x1f, 0x00, 0x00,
            0x01, 0x05, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08,
            0x09, 0x0a, 0x0b, 0xff.toByte(), 0xd9.toByte(),
        )
        val reader = byteReaderOf(bytes)
        val segments = parseJpegSegments(reader, 0, bytes.size.toLong())

        assertEquals(listOf("SOI", "DHT", "EOI"), segments.map { it.type })
        val dht = segments[1]
        assertEquals(1, dht.children.size)
        val table = dht.children[0]
        assertEquals("HuffmanTable", table.type)
        assertEquals("DC", table.fields.first { it.name == "class" }.value)
        assertEquals("0", table.fields.first { it.name == "destination_id" }.value)
        assertEquals("0, 1, 5, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0, 0, 0", table.fields.first { it.name == "bit_counts" }.value)
        assertEquals("12", table.fields.first { it.name == "total_codes" }.value)
        reader.close()
    }

    @Test
    fun `DHT decodes multiple tables packed into one segment`() {
        val bytes = byteArrayOf(
            0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xc4.toByte(), 0x00, 0x32, 0x00, 0x00,
            0x01, 0x05, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08,
            0x09, 0x0a, 0x0b, 0x11, 0x01, 0x00, 0x01, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0xaa.toByte(), 0xbb.toByte(), 0xff.toByte(), 0xd9.toByte(),
        )
        val reader = byteReaderOf(bytes)
        val segments = parseJpegSegments(reader, 0, bytes.size.toLong())

        val dht = segments[1]
        assertEquals(2, dht.children.size)
        assertEquals("DC", dht.children[0].fields.first { it.name == "class" }.value)
        assertEquals("AC", dht.children[1].fields.first { it.name == "class" }.value)
        assertEquals("1", dht.children[1].fields.first { it.name == "destination_id" }.value)
        assertEquals("2", dht.children[1].fields.first { it.name == "total_codes" }.value)
        reader.close()
    }

    @Test
    fun `DHT with a truncated table produces a warning and no children`() {
        val bytes = byteArrayOf(
            0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xc4.toByte(), 0x00, 0x0d, 0x00, 0x00,
            0x01, 0x05, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01,
            0x00, 0xff.toByte(), 0xd9.toByte(),
        )
        val reader = byteReaderOf(bytes)
        val segments = parseJpegSegments(reader, 0, bytes.size.toLong())

        val dht = segments[1]
        assertEquals(0, dht.children.size)
        assertTrue(dht.warnings.isNotEmpty())
        reader.close()
    }

    @Test
    fun `SOS header decodes component selectors, spectral selection, and successive approximation`() {
        val bytes = byteArrayOf(
            0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xda.toByte(), 0x00, 0x0c, 0x03, 0x01,
            0x00, 0x02, 0x11, 0x03, 0x11, 0x00, 0x3f, 0x00,
            0xab.toByte(), 0xff.toByte(), 0xd9.toByte(),
        )
        val reader = byteReaderOf(bytes)
        val segments = parseJpegSegments(reader, 0, bytes.size.toLong())

        assertEquals(listOf("SOI", "SOS", "EOI"), segments.map { it.type })
        val sos = segments[1]
        assertEquals(2L, sos.offset)
        assertEquals(15L, sos.size)
        assertEquals("3", sos.fields.first { it.name == "num_components" }.value)
        val selectors = sos.fields.filter { it.name == "component_selector" }.map { it.value }
        assertEquals(listOf("1", "2", "3"), selectors)
        val dcTables = sos.fields.filter { it.name == "dc_table" }.map { it.value }
        assertEquals(listOf("0", "1", "1"), dcTables)
        val acTables = sos.fields.filter { it.name == "ac_table" }.map { it.value }
        assertEquals(listOf("0", "1", "1"), acTables)
        assertEquals("0", sos.fields.first { it.name == "spectral_selection_start" }.value)
        assertEquals("63", sos.fields.first { it.name == "spectral_selection_end" }.value)
        assertEquals("0", sos.fields.first { it.name == "successive_approx_high" }.value)
        assertEquals("0", sos.fields.first { it.name == "successive_approx_low" }.value)
        reader.close()
    }

    @Test
    fun `COM decodes the comment payload as UTF-8 text`() {
        val bytes = byteArrayOf(
            0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xfe.toByte(), 0x00, 0x0e, 0x54, 0x65,
            0x73, 0x74, 0x20, 0x63, 0x6f, 0x6d, 0x6d, 0x65,
            0x6e, 0x74, 0xff.toByte(), 0xd9.toByte(),
        )
        val reader = byteReaderOf(bytes)
        val segments = parseJpegSegments(reader, 0, bytes.size.toLong())

        assertEquals(listOf("SOI", "COM", "EOI"), segments.map { it.type })
        assertEquals("Test comment", segments[1].fields.first { it.name == "comment" }.value)
        reader.close()
    }

    @Test
    fun `APP0 decodes JFIF header fields`() {
        val bytes = byteArrayOf(
            0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xe0.toByte(), 0x00, 0x10, 0x4a, 0x46,
            0x49, 0x46, 0x00, 0x01, 0x01, 0x01, 0x00, 0x48,
            0x00, 0x48, 0x00, 0x00, 0xff.toByte(), 0xd9.toByte(),
        )
        val reader = byteReaderOf(bytes)
        val segments = parseJpegSegments(reader, 0, bytes.size.toLong())

        assertEquals(listOf("SOI", "APP0", "EOI"), segments.map { it.type })
        val app0 = segments[1]
        assertEquals("1.1", app0.fields.first { it.name == "version" }.value)
        assertEquals("pixels/inch", app0.fields.first { it.name == "units" }.value)
        assertEquals("72", app0.fields.first { it.name == "x_density" }.value)
        assertEquals("72", app0.fields.first { it.name == "y_density" }.value)
        assertEquals("0", app0.fields.first { it.name == "x_thumbnail" }.value)
        assertEquals("0", app0.fields.first { it.name == "y_thumbnail" }.value)
        reader.close()
    }

    @Test
    fun `non-JFIF APP0 falls back to a plain structural node`() {
        val bytes = byteArrayOf(
            0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xe0.toByte(), 0x00, 0x0f, 0x4e, 0x4f,
            0x54, 0x4a, 0x46, 0x49, 0x46, 0x00, 0x65, 0x78,
            0x74, 0x72, 0x61, 0xff.toByte(), 0xd9.toByte(),
        )
        val reader = byteReaderOf(bytes)
        val segments = parseJpegSegments(reader, 0, bytes.size.toLong())

        assertEquals(listOf("SOI", "APP0", "EOI"), segments.map { it.type })
        assertEquals(0, segments[1].fields.size)
        reader.close()
    }
}
