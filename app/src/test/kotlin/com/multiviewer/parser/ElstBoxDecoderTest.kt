package com.multiviewer.parser

import kotlin.test.Test
import kotlin.test.assertEquals

class ElstBoxDecoderTest {
    @Test
    fun `version 0 entry decodes with signed media_time and fixed-point media_rate`() {
        val body = byteArrayOf(
            0x00, 0x00, 0x00, 0x00,             // version=0, flags=0
            0x00, 0x00, 0x00, 0x01,             // entry_count = 1
            0x00, 0x00, 0x03, 0xE8.toByte(),    // segment_duration = 1000
            0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), // media_time = -1 (empty edit)
            0x00, 0x01,                         // media_rate_integer = 1
            0x00, 0x00,                         // media_rate_fraction = 0
        )
        val reader = byteReaderOf(body)
        val node = ElstBoxDecoder.decode(reader, "elst", 0, 0, body.size.toLong(), emptyList())

        assertEquals("1000", node.fields[0].value) // segment_duration
        assertEquals("-1", node.fields[1].value)    // media_time
        assertEquals("1.0", node.fields[2].value)   // media_rate
        assertEquals("1 edit", node.summary)
        assertEquals(true, node.warnings.isEmpty())
        reader.close()
    }

    @Test
    fun `version 1 entry uses 8-byte duration and media_time fields`() {
        val body = byteArrayOf(
            0x01, 0x00, 0x00, 0x00,             // version=1, flags=0
            0x00, 0x00, 0x00, 0x01,             // entry_count = 1
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x03, 0xE8.toByte(), // segment_duration = 1000
            0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(),
            0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), // media_time = -1
            0x00, 0x01,                         // media_rate_integer = 1
            0x00, 0x00,                         // media_rate_fraction = 0
        )
        val reader = byteReaderOf(body)
        val node = ElstBoxDecoder.decode(reader, "elst", 0, 0, body.size.toLong(), emptyList())

        assertEquals("1000", node.fields[0].value)
        assertEquals("-1", node.fields[1].value)
        assertEquals("1.0", node.fields[2].value)
        reader.close()
    }

    @Test
    fun `declared entry_count larger than available bytes truncates with a warning`() {
        val body = byteArrayOf(
            0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x02,             // entry_count = 2 (only 1 fits)
            0x00, 0x00, 0x03, 0xE8.toByte(),
            0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(),
            0x00, 0x01,
            0x00, 0x00,
        )
        val reader = byteReaderOf(body)
        val node = ElstBoxDecoder.decode(reader, "elst", 0, 0, body.size.toLong(), emptyList())
        assertEquals(1, node.warnings.size)
        assertEquals(3, node.fields.size) // only the 1 entry that fit
        reader.close()
    }
}
