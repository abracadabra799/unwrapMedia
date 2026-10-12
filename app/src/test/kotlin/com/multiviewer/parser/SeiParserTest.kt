package com.multiviewer.parser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SeiParserTest {
    @Test
    fun `real x265 CLL SEI splits into one message`() {
        val messages = splitSeiMessages(hex("4e01 90 04 03e8 0190 80"), nalHeaderSize = 2)
        val m = messages.single()
        assertEquals(144, m.payloadType)
        assertEquals(4, m.payloadSize)
        assertEquals(hex("03e80190").toList(), m.payload.toList())
        assertNull(m.malformedReason)
    }

    @Test
    fun `emulation prevention is removed before splitting`() {
        val nal = hex("4e01 89 18 33c286c41d4c0bb884d03e803d134042 0098968000000300328000")
        val m = splitSeiMessages(nal, 2).single()
        assertEquals(137, m.payloadType)
        assertEquals(24, m.payload.size)
        assertEquals(hex("0098968000000032").toList(), m.payload.copyOfRange(16, 24).toList())
    }

    @Test
    fun `two messages in one NAL`() {
        val nal = hex("4e01 8918 33c286c41d4c0bb884d03e803d134042 009896800000030032 9004 03e80190 80")
        assertEquals(listOf(137, 144), splitSeiMessages(nal, 2).map { it.payloadType })
    }

    @Test
    fun `H264 one-byte NAL header`() {
        val m = splitSeiMessages(hex("06 90 04 03e8 0190 80"), nalHeaderSize = 1).single()
        assertEquals(144, m.payloadType)
    }

    @Test
    fun `0xFF-extended payloadSize`() {
        val nal = hex("4e01 05 ff2d") + ByteArray(300) { 0x41 } + hex("80")
        val m = splitSeiMessages(nal, 2).single()
        assertEquals(5, m.payloadType)
        assertEquals(300, m.payloadSize)
    }

    @Test
    fun `payload capped but declared size kept`() {
        val nal = hex("4e01 05 ff2d") + ByteArray(300) { 0x41 } + hex("80")
        val m = splitSeiMessages(nal, 2, maxPayloadBytes = 16).single()
        assertEquals(300, m.payloadSize)
        assertEquals(16, m.payload.size)
    }

    @Test
    fun `size past the NAL end is reported as malformed and stops`() {
        val m = splitSeiMessages(hex("4e01 89 18 33c2"), 2).single()
        assertEquals(137, m.payloadType)
        assertNotNull(m.malformedReason)
    }

    @Test
    fun `NAL with only a header yields nothing`() {
        assertEquals(emptyList(), splitSeiMessages(hex("4e01"), 2))
    }
}
