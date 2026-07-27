package com.multiviewer.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ValueInspectorPanelTest {
    @Test
    fun `combineBytesAsLong reads little-endian bytes with the low-order byte first`() {
        val bytes = byteArrayOf(0x01, 0x00, 0x00, 0x00)
        assertEquals(1L, combineBytesAsLong(bytes, 4, littleEndian = true))
    }

    @Test
    fun `combineBytesAsLong reads big-endian bytes with the high-order byte first`() {
        val bytes = byteArrayOf(0x00, 0x00, 0x00, 0x01)
        assertEquals(1L, combineBytesAsLong(bytes, 4, littleEndian = false))
    }

    @Test
    fun `combineBytesAsLong returns the same raw bits regardless of endianness for a palindromic value`() {
        // 0x12345678 little-endian bytes, read big-endian, produce a different (but equally valid)
        // raw bit pattern -- this just pins down that the two endiannesses genuinely disagree, so a
        // caller relying on the toggle actually sees different values.
        val bytes = byteArrayOf(0x78, 0x56, 0x34, 0x12)
        assertEquals(0x12345678L, combineBytesAsLong(bytes, 4, littleEndian = true))
        assertEquals(0x78563412L, combineBytesAsLong(bytes, 4, littleEndian = false))
    }

    @Test
    fun `combineBytesAsLong returns null when fewer bytes are available than requested`() {
        val bytes = byteArrayOf(0x01, 0x02)
        assertNull(combineBytesAsLong(bytes, 4, littleEndian = true))
    }

    @Test
    fun `combineBytesAsLong reinterpreted as Int32 correctly sign-extends a negative value`() {
        // 0xFFFFFFFF little-endian -> raw bits all 1s -> Int32 must read as -1, not a huge positive
        // Long (proving callers should reinterpret via .toInt(), not just print the Long directly).
        val bytes = byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())
        val raw = combineBytesAsLong(bytes, 4, littleEndian = true)
        assertEquals(-1, raw?.toInt())
        assertEquals(0xFFFFFFFFL, raw?.and(0xFFFFFFFFL))
    }

    @Test
    fun `combineBytesAsLong for 8 bytes preserves the full 64-bit raw pattern including the sign bit`() {
        val bytes = byteArrayOf(0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x80.toByte())
        // Little-endian: byte[7]=0x80 is the most significant byte -> sign bit set -> negative Long.
        val raw = combineBytesAsLong(bytes, 8, littleEndian = true)
        assertEquals(Long.MIN_VALUE, raw)
    }
}
