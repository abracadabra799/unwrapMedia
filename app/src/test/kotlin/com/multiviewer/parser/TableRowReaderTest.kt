package com.multiviewer.parser

import kotlin.test.Test
import kotlin.test.assertEquals

class TableRowReaderTest {
    @Test
    fun `reads a two-field row at the correct file offset`() {
        val body = byteArrayOf(
            0x00, 0x00, 0x00, 0x0A, 0x00, 0x00, 0x00, 0x14, // row 0: (10, 20)
            0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x1E, // row 1: (1, 30)
        )
        val reader = byteReaderOf(body)
        assertEquals(listOf(10L, 20L), readTableRow(reader, entriesStart = 0, fieldWidths = listOf(4, 4), rowIndex = 0))
        assertEquals(listOf(1L, 30L), readTableRow(reader, entriesStart = 0, fieldWidths = listOf(4, 4), rowIndex = 1))
        reader.close()
    }

    @Test
    fun `reads a single 8-byte-wide field row (like co64 chunk offsets)`() {
        val body = byteArrayOf(
            0x00, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, // row 0: 65536
        )
        val reader = byteReaderOf(body)
        assertEquals(listOf(65536L), readTableRow(reader, entriesStart = 0, fieldWidths = listOf(8), rowIndex = 0))
        reader.close()
    }
}
