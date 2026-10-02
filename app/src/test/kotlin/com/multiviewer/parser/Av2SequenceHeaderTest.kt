package com.multiviewer.parser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class Av2SequenceHeaderTest {
    private fun bits(vararg parts: String): ByteArray {
        val source = parts.joinToString("")
        return ByteArray((source.length + 7) / 8) { index ->
            source.substring(index * 8, minOf(index * 8 + 8, source.length)).padEnd(8, '0').toInt(2).toByte()
        }
    }

    @Test
    fun `parse AV2 sequence header exposes direct stream metadata`() {
        val payload = bits(
            "1", "00011", "0", "00100", "1", "1", "1", // id, profile, single, level, tier, chroma 420, 10-bit
            "000", "0", "00", "000", "1", // lcr, still, layers, monotonic output
            "1001", "1000", "1001111111", "101100111", "0", // 640x360, no crop
        )

        val header = parseAv2SequenceHeader(payload)

        requireNotNull(header)
        assertEquals(0, header.sequenceHeaderId)
        assertEquals(3, header.profile)
        assertEquals(4, header.level)
        assertEquals(1, header.tier)
        assertEquals(640, header.maxFrameWidth)
        assertEquals(360, header.maxFrameHeight)
        assertEquals(10, header.bitDepth)
        assertEquals(1, header.chromaSubsamplingX)
        assertEquals(1, header.chromaSubsamplingY)
        assertEquals(true, header.monotonicOutputOrder)
    }

    @Test
    fun `parse AV2 sequence header rejects empty and reserved chroma data`() {
        assertNull(parseAv2SequenceHeader(ByteArray(0)))
        assertNull(parseAv2SequenceHeader(bits("1", "00000", "1", "00000", "00100")))
    }
}
