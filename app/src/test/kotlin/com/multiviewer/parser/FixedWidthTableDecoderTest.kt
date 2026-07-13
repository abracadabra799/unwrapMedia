package com.multiviewer.parser

import kotlin.test.Test
import kotlin.test.assertEquals

class FixedWidthTableDecoderTest {
    @Test
    fun `decodes entry_count and rows, summarizing instead of exposing rows as children`() {
        val decoder = FixedWidthTableDecoder(listOf("sample_count", "sample_delta"), listOf(4, 4))
        val body = byteArrayOf(
            0x00, 0x00, 0x00, 0x00, // version/flags
            0x00, 0x00, 0x00, 0x02, // entry_count = 2
            0x00, 0x00, 0x00, 0x0A, 0x00, 0x00, 0x00, 0x14, // row 1: (10, 20)
            0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x1E, // row 2: (1, 30)
        )
        val reader = byteReaderOf(body)
        val node = decoder.decode(reader, "stts", 0, 0, body.size.toLong(), emptyList())
        assertEquals("2 entries", node.summary)
        assertEquals(listOf("sample_count", "sample_delta"), node.table?.columns)
        assertEquals(listOf(listOf(10L, 20L), listOf(1L, 30L)), node.table?.rows)
        assertEquals(true, node.children.isEmpty())
        reader.close()
    }

    @Test
    fun `declared entry_count larger than available bytes truncates with a warning`() {
        val decoder = FixedWidthTableDecoder(listOf("chunk_offset"), listOf(4))
        val body = byteArrayOf(
            0x00, 0x00, 0x00, 0x00, // version/flags
            0x00, 0x00, 0x00, 0x64, // entry_count = 100 (way more than available)
            0x00, 0x00, 0x00, 0x01, // only 1 entry actually fits
        )
        val reader = byteReaderOf(body)
        val node = decoder.decode(reader, "stco", 0, 0, body.size.toLong(), emptyList())
        assertEquals(1, node.table?.rows?.size)
        assertEquals(1, node.warnings.size)
        reader.close()
    }
}
