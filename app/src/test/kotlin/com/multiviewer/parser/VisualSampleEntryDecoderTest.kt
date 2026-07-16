package com.multiviewer.parser

import kotlin.test.Test
import kotlin.test.assertEquals

class VisualSampleEntryDecoderTest {
    @Test
    fun `decodes data_reference_index, width, height and recurses into child boxes`() {
        val body = ByteArray(78 + 8)
        body[7] = 0x01 // data_reference_index = 1
        body[24] = 0x02.toByte(); body[25] = 0x80.toByte() // width = 640
        body[26] = 0x01.toByte(); body[27] = 0xE0.toByte() // height = 480
        // child box at offset 78: size=8, type="free"
        body[78] = 0x00; body[79] = 0x00; body[80] = 0x00; body[81] = 0x08
        body[82] = 'f'.code.toByte(); body[83] = 'r'.code.toByte()
        body[84] = 'e'.code.toByte(); body[85] = 'e'.code.toByte()

        val reader = byteReaderOf(body)
        val node = VisualSampleEntryDecoder.decode(reader, "avc1", 0, 0, body.size.toLong(), emptyList())

        assertEquals("1", node.fields[0].value)
        assertEquals("640", node.fields[1].value)
        assertEquals("480", node.fields[2].value)
        assertEquals("640x480", node.summary)
        assertEquals(listOf("free"), node.children.map { it.type })
        reader.close()
    }

    @Test
    fun `box too short for fixed header returns a warning and no fields`() {
        val reader = byteReaderOf(ByteArray(10))
        val node = VisualSampleEntryDecoder.decode(reader, "avc1", 0, 0, 10, emptyList())
        assertEquals(1, node.warnings.size)
        assertEquals(true, node.fields.isEmpty())
        reader.close()
    }
}
