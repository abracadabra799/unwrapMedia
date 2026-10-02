package com.multiviewer.parser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class Av2ObuTest {
    @Test
    fun `AV2 OBU header decodes type and temporal layer from its first byte`() {
        // extension=0, type=1 (Sequence Header), temporal layer=2.
        val result = parseAv2ObuHeader(byteReaderOf(byteArrayOf(0x06)), 0, 1)

        val parsed = assertIs<Av2ParseResult.Value<Av2ObuHeader>>(result).value
        assertEquals(1, parsed.obuType)
        assertEquals("OBU_SEQUENCE_HEADER", av2ObuTypeName(parsed.obuType))
        assertEquals(2, parsed.temporalLayerId)
        assertEquals(0, parsed.embeddedLayerId)
        assertEquals(0, parsed.extendedLayerId)
        assertEquals(1, parsed.headerSize)
    }

    @Test
    fun `AV2 OBU header decodes embedded and extended layer IDs from its extension byte`() {
        // extension=1, type=16 (Layer Configuration Record), temporal=3; embedded=5, extended=17.
        val result = parseAv2ObuHeader(byteReaderOf(byteArrayOf(0xc3.toByte(), 0xb1.toByte())), 0, 2)

        val parsed = assertIs<Av2ParseResult.Value<Av2ObuHeader>>(result).value
        assertEquals(16, parsed.obuType)
        assertEquals(3, parsed.temporalLayerId)
        assertEquals(5, parsed.embeddedLayerId)
        assertEquals(17, parsed.extendedLayerId)
        assertEquals(2, parsed.headerSize)
    }

    @Test
    fun `AV2 OBU header reports a truncated extension byte`() {
        val result = parseAv2ObuHeader(byteReaderOf(byteArrayOf(0x80.toByte())), 0, 1)

        assertIs<Av2ParseResult.Error>(result)
    }

    @Test
    fun `bounded AV2 leb128 decodes one and multiple byte values`() {
        val reader = byteReaderOf(byteArrayOf(0x0b, 0xac.toByte(), 0x02))

        val first = assertIs<Av2ParseResult.Value<Long>>(readAv2Leb128(reader, 0, 3))
        val second = assertIs<Av2ParseResult.Value<Long>>(readAv2Leb128(reader, first.nextOffset, 3))

        assertEquals(11L, first.value)
        assertEquals(1L, first.nextOffset)
        assertEquals(300L, second.value)
        assertEquals(3L, second.nextOffset)
    }

    @Test
    fun `bounded AV2 leb128 rejects an unterminated value at the configured boundary`() {
        val result = readAv2Leb128(byteReaderOf(byteArrayOf(0x81.toByte(), 0x01)), 0, 1)

        assertIs<Av2ParseResult.Error>(result)
    }

    @Test
    fun `bounded AV2 leb128 rejects a ninth byte and preserves unknown OBU type`() {
        val overflow = readAv2Leb128(byteReaderOf(ByteArray(9) { 0x80.toByte() }), 0, 9)

        assertIs<Av2ParseResult.Error>(overflow)
        assertEquals("unknown(30)", av2ObuTypeName(30))
    }
}
