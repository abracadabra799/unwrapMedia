package com.multiviewer.parser

import kotlin.test.Test
import kotlin.test.assertEquals

class AudioSampleEntryDecoderTest {
    @Test
    fun `decodes data_reference_index, channelcount, samplesize, samplerate and recurses into children`() {
        val body = ByteArray(28 + 8)
        body[7] = 0x01 // data_reference_index = 1
        body[16] = 0x00; body[17] = 0x02 // channelcount = 2
        body[18] = 0x00; body[19] = 0x10 // samplesize = 16
        body[24] = 0xAC.toByte(); body[25] = 0x44; body[26] = 0x00; body[27] = 0x00 // samplerate = 44100.0 (0xAC440000 / 65536)
        // child box at offset 28: size=8, type="esds"
        body[28] = 0x00; body[29] = 0x00; body[30] = 0x00; body[31] = 0x08
        body[32] = 'e'.code.toByte(); body[33] = 's'.code.toByte()
        body[34] = 'd'.code.toByte(); body[35] = 's'.code.toByte()

        val reader = byteReaderOf(body)
        val node = AudioSampleEntryDecoder.decode(reader, "mp4a", 0, 0, body.size.toLong(), emptyList())

        assertEquals("1", node.fields[0].value)
        assertEquals("2", node.fields[1].value)
        assertEquals("16", node.fields[2].value)
        assertEquals("44100.0", node.fields[3].value)
        assertEquals("2ch, 44100Hz", node.summary)
        assertEquals(listOf("esds"), node.children.map { it.type })
        reader.close()
    }

    @Test
    fun `box too short for fixed header returns a warning and no fields`() {
        val reader = byteReaderOf(ByteArray(10))
        val node = AudioSampleEntryDecoder.decode(reader, "mp4a", 0, 0, 10, emptyList())
        assertEquals(1, node.warnings.size)
        assertEquals(true, node.fields.isEmpty())
        reader.close()
    }
}
