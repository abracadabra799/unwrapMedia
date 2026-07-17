package com.multiviewer.parser

import kotlin.test.Test
import kotlin.test.assertEquals

class ColrBoxDecoderTest {
    @Test
    fun `nclx colour_type decodes primaries, transfer, matrix and full_range_flag`() {
        val body = byteArrayOf(
            'n'.code.toByte(), 'c'.code.toByte(), 'l'.code.toByte(), 'x'.code.toByte(), // colour_type = "nclx"
            0x00, 0x01, // colour_primaries = 1
            0x00, 0x01, // transfer_characteristics = 1
            0x00, 0x01, // matrix_coefficients = 1
            0x80.toByte(), // full_range_flag = true (top bit set)
        )
        val reader = byteReaderOf(body)
        val node = ColrBoxDecoder.decode(reader, "colr", 0, 0, body.size.toLong(), emptyList())

        assertEquals("nclx", node.fields[0].value)
        assertEquals("1", node.fields[1].value)
        assertEquals("1", node.fields[2].value)
        assertEquals("1", node.fields[3].value)
        assertEquals("true", node.fields[4].value)
        assertEquals("nclx: 1/1/1", node.summary)
        reader.close()
    }

    @Test
    fun `non-nclx colour_type surfaces only colour_type and summarizes remaining bytes as an ICC profile`() {
        val body = byteArrayOf(
            'r'.code.toByte(), 'I'.code.toByte(), 'C'.code.toByte(), 'C'.code.toByte(), // colour_type = "rICC"
            0x01, 0x02, 0x03, 0x04, // arbitrary ICC profile bytes
        )
        val reader = byteReaderOf(body)
        val node = ColrBoxDecoder.decode(reader, "colr", 0, 0, body.size.toLong(), emptyList())

        assertEquals(1, node.fields.size)
        assertEquals("rICC", node.fields[0].value)
        assertEquals("ICC profile (4 bytes)", node.summary)
        reader.close()
    }

    @Test
    fun `box too short for colour_type returns a warning and no fields`() {
        val reader = byteReaderOf(ByteArray(2))
        val node = ColrBoxDecoder.decode(reader, "colr", 0, 0, 2, emptyList())
        assertEquals(1, node.warnings.size)
        assertEquals(true, node.fields.isEmpty())
        reader.close()
    }
}
