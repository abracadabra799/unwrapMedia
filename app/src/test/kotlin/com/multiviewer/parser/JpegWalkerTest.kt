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
    fun `a byte that is not 0xFF where a marker is expected produces a warning and stops`() {
        val bytes = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0x00, 0x01)
        val reader = byteReaderOf(bytes)
        val segments = parseJpegSegments(reader, 0, bytes.size.toLong())

        assertEquals(listOf("SOI", "?"), segments.map { it.type })
        assertTrue(segments[1].warnings.isNotEmpty())
        reader.close()
    }
}
