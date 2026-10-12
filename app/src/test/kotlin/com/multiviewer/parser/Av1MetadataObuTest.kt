package com.multiviewer.parser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class Av1MetadataObuTest {
    @Test
    fun `real SVT-AV1 HDR_CLL`() {
        // OBU 2a 06 | payload 01 03e8 0190 80
        val (type, decoded) = decodeAv1MetadataPayload(hex("01 03e8 0190 80"))
        assertEquals(1L, type)
        assertEquals(ContentLightLevel(1000, 400), assertIs<DecodedMetadata.LightLevel>(decoded).value)
    }

    @Test
    fun `real SVT-AV1 HDR_MDCV uses R G B order and AV1 fixed point`() {
        val (type, decoded) = decodeAv1MetadataPayload(hex("02 ae1451ec 43d7b0a4 26660f5c 500d5439 0003e800 00000052 80"))
        assertEquals(2L, type)
        val md = assertIs<DecodedMetadata.Mastering>(decoded).value
        assertEquals(44564 / 65536.0, md.redX, 1e-12)
        assertEquals(17367 / 65536.0, md.greenX, 1e-12)
        assertEquals(9830 / 65536.0, md.blueX, 1e-12)
        assertEquals(20493 / 65536.0, md.whiteX, 1e-12)
        assertEquals(1000.0, md.maxLuminance, 1e-12)
        assertEquals(82 / 16384.0, md.minLuminance, 1e-12)
        assertEquals(1 / 256.0, md.maxLuminanceStep)
    }

    @Test
    fun `ITU_T_T35 with HDR10plus`() {
        val t35 = hex("b5003c0001040140001f401f401194073a00fa240800002800285000c8c80321900fa2580c82d01772f82713184650004fa12c0d2c963840")
        val (type, decoded) = decodeAv1MetadataPayload(hex("04") + t35 + hex("80"))
        assertEquals(4L, type)
        assertIs<DecodedMetadata.Hdr10Plus>(decoded)
    }

    @Test
    fun `short MDCV is malformed and other types are presence-only`() {
        assertIs<DecodedMetadata.Malformed>(decodeAv1MetadataPayload(hex("02 ae14")).second)
        assertEquals(DecodedMetadata.PresenceOnly, decodeAv1MetadataPayload(hex("05 00")).second)
        assertEquals("TIMECODE", av1MetadataTypeName(5))
        assertEquals("metadata_type_9", av1MetadataTypeName(9))
    }
}
