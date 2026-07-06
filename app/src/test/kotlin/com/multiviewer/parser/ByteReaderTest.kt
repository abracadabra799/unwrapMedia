package com.multiviewer.parser

import kotlin.test.Test
import kotlin.test.assertEquals

class ByteReaderTest {
    @Test
    fun `reads big-endian integers and fourcc at given offsets`() {
        val reader = byteReaderOf(
            byteArrayOf(
                0x00, 0x00, 0x00, 0x18, // uint32 = 24
                0x66, 0x74, 0x79, 0x70, // "ftyp"
                0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x2A, // uint64 = 42
                0x01, 0x02, // uint16 = 258
                0x7F,       // uint8 = 127
            )
        )
        assertEquals(24L, reader.readUInt32(0))
        assertEquals("ftyp", reader.readFourCC(4))
        assertEquals(42L, reader.readUInt64(8))
        assertEquals(258, reader.readUInt16(16))
        assertEquals(127, reader.readUInt8(18))
        assertEquals(19L, reader.length)
        reader.close()
    }

    @Test
    fun `reads a byte range`() {
        val reader = byteReaderOf(byteArrayOf(1, 2, 3, 4, 5))
        val bytes = reader.readBytes(1, 3)
        assertEquals(listOf<Byte>(2, 3, 4), bytes.toList())
        reader.close()
    }
}
