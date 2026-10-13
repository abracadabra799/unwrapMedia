package com.multiviewer.parser

import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IsoGainmapMetadataTest {
    // The 61-byte ISO 21496-1 payload S26 writes (identical in the JPEG gain map image's APP2 and the
    // HEIC tmap item) -- dumped from app/src/test/resources/c2pa/s26-*.
    private val s26Payload = hex(
        "0000000040" + "0000000000000001" + "0093333300400000" +
            "0000000000000001" + "0093333300400000" + "0000000100000001" +
            "0000000000000001" + "0000000000000001",
    )

    @Test
    fun `decodes the real S26 payload`() {
        val m = assertNotNull(parseIsoGainmapMetadata(s26Payload))
        assertEquals(0, m.minimumVersion)
        assertEquals(0, m.writerVersion)
        assertFalse(m.isMultichannel)
        assertTrue(m.useBaseColourSpace)
        assertFalse(m.backwardDirection)
        assertFalse(m.commonDenominator)
        assertEquals(0.0, m.baseHdrHeadroom)
        assertEquals(2.3, m.alternateHdrHeadroom, 1e-6)
        val c = m.channels.single()
        assertEquals(0.0, c.gainMapMin)
        assertEquals(2.3, c.gainMapMax, 1e-6)
        assertEquals(1.0, c.gamma)
        assertEquals(0.0, c.baseOffset)
        assertEquals(0.0, c.alternateOffset)
    }

    @Test
    fun `maps to the hdrgm-style parameters the S26 XMP also carries`() {
        val p = parseIsoGainmapMetadata(s26Payload)!!.toParsedParameters()
        assertEquals(0.0, p.gainMapMin)
        assertEquals(2.3, p.gainMapMax!!, 1e-6)
        assertEquals(1.0, p.gamma)
        assertEquals(0.0, p.offsetSdr)
        assertEquals(0.0, p.offsetHdr)
        assertEquals(0.0, p.hdrCapacityMin)
        assertEquals(2.3, p.hdrCapacityMax!!, 1e-6)
        assertEquals(false, p.baseRenditionIsHdr)
        assertEquals(Math.pow(2.0, 2.3), p.linearMaxBoost!!, 1e-6)
    }

    private fun payload(flags: Int, body: ByteBuffer.() -> Unit): ByteArray {
        val buf = ByteBuffer.allocate(256)
        buf.putShort(0).putShort(0).put(flags.toByte())
        buf.body()
        return buf.array().copyOf(buf.position())
    }

    @Test
    fun `common denominator with three channels`() {
        val bytes = payload(0x80 or 0x08) {
            putInt(1000); putInt(0); putInt(3000)
            repeat(3) { ch -> putInt(-500 * ch); putInt(3000); putInt(1000); putInt(15); putInt(15) }
        }
        val m = assertNotNull(parseIsoGainmapMetadata(bytes))
        assertTrue(m.isMultichannel)
        assertTrue(m.commonDenominator)
        assertEquals(3.0, m.alternateHdrHeadroom)
        assertEquals(3, m.channels.size)
        assertEquals(-1.0, m.channels[2].gainMapMin)
        assertEquals(0.015, m.channels[1].baseOffset, 1e-9)
    }

    @Test
    fun `backward direction swaps the base and alternate pairs`() {
        val bytes = payload(0x04 or 0x08) {
            putInt(64); putInt(128); putInt(0)
            putInt(0); putInt(128); putInt(64); putInt(2); putInt(1)
        }
        val p = parseIsoGainmapMetadata(bytes)!!.toParsedParameters()
        assertEquals(true, p.baseRenditionIsHdr)
        assertEquals(0.0, p.hdrCapacityMin) // alternate (SDR) headroom
        assertEquals(2.0, p.hdrCapacityMax) // base (HDR) headroom
        assertEquals(1.0 / 64, p.offsetSdr) // alternate offset
        assertEquals(2.0 / 64, p.offsetHdr) // base offset
    }

    @Test
    fun `truncated, version-only, zero denominator and unknown versions are rejected`() {
        assertNull(parseIsoGainmapMetadata(s26Payload.copyOf(40)))
        assertNull(parseIsoGainmapMetadata(hex("00000000"))) // primary image's version-only block
        assertNull(parseIsoGainmapMetadata(byteArrayOf(0, 1) + s26Payload.copyOfRange(2, s26Payload.size)))
        val zeroDen = s26Payload.copyOf().also { it[12] = 0 } // base_hdr_headroom denominator 1 -> 0
        assertNull(parseIsoGainmapMetadata(zeroDen))
    }

    @Test
    fun `version-only block is recognised`() {
        assertTrue(isIsoGainmapVersionBlock(hex("00000000")))
        assertFalse(isIsoGainmapVersionBlock(s26Payload))
    }
}
