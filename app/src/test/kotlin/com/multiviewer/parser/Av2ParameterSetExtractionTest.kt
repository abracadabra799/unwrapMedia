package com.multiviewer.parser

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class Av2ParameterSetExtractionTest {
    @Test
    fun `extract finds a Sequence Header payload and its exact file range`() {
        val payload = byteArrayOf(0, 0, 3, 0x04, 0x55, 0xaa.toByte())
        val file = fileOf(ByteArray(8) + payload)
        val node = BoxNode("av2C", 0, 8, (8 + payload.size).toLong())

        val raw = extractAv2CRawSequenceHeader(file, node)

        requireNotNull(raw)
        assertEquals(byteArrayOf(0x55, 0xaa.toByte()).toList(), raw.bytes.toList())
        assertEquals(12L, raw.offset)
    }

    @Test
    fun `extract returns null when AV2 configuration lacks or corrupts a Sequence Header`() {
        val absent = fileOf(ByteArray(8) + byteArrayOf(0, 0, 1, 0x08))
        val malformed = fileOf(ByteArray(8) + byteArrayOf(0, 0, 2, 0x04))

        assertNull(extractAv2CRawSequenceHeader(absent, BoxNode("av2C", 0, 8, absent.length())))
        assertNull(extractAv2CRawSequenceHeader(malformed, BoxNode("av2C", 0, 8, malformed.length())))
    }
}
