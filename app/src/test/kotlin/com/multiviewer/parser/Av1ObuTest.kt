package com.multiviewer.parser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Av1ObuTest {
    @Test
    fun `parseObuHeader decodes a Sequence Header OBU header with no extension`() {
        // Real byte from a libsvtav1 encode: obu_forbidden_bit=0, obu_type=1 (OBU_SEQUENCE_HEADER),
        // obu_extension_flag=0, obu_has_size_field=1, obu_reserved_1bit=0 -> 0 0001 0 1 0 = 0x0a.
        val reader = byteReaderOf(byteArrayOf(0x0a))
        val header = parseObuHeader(reader, 0)
        assertEquals(1, header.obuType)
        assertFalse(header.extensionFlag)
        assertTrue(header.hasSizeField)
        assertEquals(1, header.headerSize)
    }

    @Test
    fun `parseObuHeader accounts for the extra byte when extension_flag is set`() {
        // obu_type=6 (OBU_FRAME), obu_extension_flag=1, obu_has_size_field=1 -> 0 0110 1 1 0 = 0x36.
        val reader = byteReaderOf(byteArrayOf(0x36, 0xAA.toByte()))
        val header = parseObuHeader(reader, 0)
        assertEquals(6, header.obuType)
        assertTrue(header.extensionFlag)
        assertTrue(header.hasSizeField)
        assertEquals(2, header.headerSize)
    }

    @Test
    fun `readLeb128 decodes a real single-byte value`() {
        // Real byte from the same encode: leb128-encoded obu_size=11, single byte (MSB clear).
        val reader = byteReaderOf(byteArrayOf(0x0b))
        val (value, nextPos) = readLeb128(reader, 0)
        assertEquals(11L, value)
        assertEquals(1L, nextPos)
    }

    @Test
    fun `readLeb128 decodes a multi-byte value`() {
        // 300 = 0b1_0010_1100. leb128 groups 7 bits at a time, LSB group first, continuation bit
        // set on every byte but the last: group0 = 300 and 0x7F = 0x2C, continuation set -> 0xAC;
        // group1 = 300 shr 7 = 2, no continuation -> 0x02.
        val reader = byteReaderOf(byteArrayOf(0xAC.toByte(), 0x02))
        val (value, nextPos) = readLeb128(reader, 0)
        assertEquals(300L, value)
        assertEquals(2L, nextPos)
    }

    @Test
    fun `readLeb128 starts reading at the given position, not always at 0`() {
        val reader = byteReaderOf(byteArrayOf(0xFF.toByte(), 0x0b, 0x00))
        val (value, nextPos) = readLeb128(reader, 1)
        assertEquals(11L, value)
        assertEquals(2L, nextPos)
    }
}
