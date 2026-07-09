package com.multiviewer.parser

import kotlin.test.Test
import kotlin.test.assertEquals

class FtypBoxDecoderTest {
    @Test
    fun `decodes major brand, minor version, and compatible brands`() {
        val reader = byteReaderOf(
            byteArrayOf(
                0x68, 0x65, 0x69, 0x63, // major_brand "heic"
                0x00, 0x00, 0x00, 0x00, // minor_version 0
                0x6D, 0x69, 0x66, 0x31, // compatible_brand "mif1"
                0x68, 0x65, 0x69, 0x63, // compatible_brand "heic"
            )
        )
        val node = FtypBoxDecoder.decode(reader, "ftyp", 0, 0, 16, emptyList())
        assertEquals("heic", node.fields[0].value)
        assertEquals("0", node.fields[1].value)
        assertEquals(listOf("mif1", "heic"), node.fields.drop(2).map { it.value })
        assertEquals("heic, 2 compatible brand(s)", node.summary)
        reader.close()
    }
}
