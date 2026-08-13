package com.multiviewer.parser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HeicTileGridTest {
    @Test
    fun `decodeGridItemPayload reads a 1x2 grid with 16-bit output dimensions`() {
        // version=0, flags=0 (16-bit fields), rows_minus_one=0 (1 row), columns_minus_one=1 (2 cols),
        // output_width=32 (0x0020), output_height=16 (0x0010).
        val bytes = byteArrayOf(0, 0, 0, 1, 0x00, 0x20, 0x00, 0x10)
        val layout = decodeGridItemPayload(bytes)
        assertEquals(GridLayout(rows = 1, columns = 2, outputWidth = 32, outputHeight = 16), layout)
    }

    @Test
    fun `decodeGridItemPayload reads a 3x1 grid with 32-bit output dimensions`() {
        // version=0, flags=1 (32-bit fields), rows_minus_one=2 (3 rows), columns_minus_one=0 (1 col),
        // output_width=300 (0x0000012C), output_height=200 (0x000000C8).
        val bytes = byteArrayOf(
            0, 1, 2, 0,
            0x00, 0x00, 0x01, 0x2C.toByte(),
            0x00, 0x00, 0x00, 0xC8.toByte(),
        )
        val layout = decodeGridItemPayload(bytes)
        assertEquals(GridLayout(rows = 3, columns = 1, outputWidth = 300, outputHeight = 200), layout)
    }

    @Test
    fun `decodeGridItemPayload returns null for input too short to contain the fixed header`() {
        assertNull(decodeGridItemPayload(byteArrayOf(0, 0, 0)))
    }

    @Test
    fun `decodeGridItemPayload returns null when 32-bit fields are declared but truncated`() {
        assertNull(decodeGridItemPayload(byteArrayOf(0, 1, 0, 0, 0, 0, 0, 0)))
    }
}
