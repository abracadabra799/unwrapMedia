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
}
