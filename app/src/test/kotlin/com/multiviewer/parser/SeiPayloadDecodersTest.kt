package com.multiviewer.parser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class SeiPayloadDecodersTest {
    private fun sei(type: Int, payload: ByteArray) = SeiMessage(type, payload.size, payload)

    @Test
    fun `mastering display uses G B R order and SEI units`() {
        val md = assertNotNull(decodeMasteringDisplayPayload(hex("33c286c41d4c0bb884d03e803d134042 00989680 00000032")))
        assertEquals(0.68, md.redX, 1e-9); assertEquals(0.32, md.redY, 1e-9)
        assertEquals(0.265, md.greenX, 1e-9); assertEquals(0.69, md.greenY, 1e-9)
        assertEquals(0.15, md.blueX, 1e-9); assertEquals(0.06, md.blueY, 1e-9)
        assertEquals(0.3127, md.whiteX, 1e-9); assertEquals(0.329, md.whiteY, 1e-9)
        assertEquals(1000.0, md.maxLuminance, 1e-9); assertEquals(0.005, md.minLuminance, 1e-9)
        assertEquals(0.00002, md.chromaStep); assertEquals(0.0001, md.maxLuminanceStep)
    }

    @Test
    fun `short mastering display payload is malformed`() {
        assertIs<DecodedMetadata.Malformed>(decodeSeiPayload(VideoCodec.HEVC, sei(137, ByteArray(10))))
    }

    @Test
    fun `content light level`() {
        val d = assertIs<DecodedMetadata.LightLevel>(decodeSeiPayload(VideoCodec.H264, sei(144, hex("03e80190"))))
        assertEquals(ContentLightLevel(1000, 400), d.value)
    }

    @Test
    fun `T35 HDR10plus payload decodes to Hdr10Plus`() {
        val t35 = hex("b5003c0001040140001f401f401194073a00fa240800002800285000c8c80321900fa2580c82d01772f82713184650004fa12c0d2c963840")
        val d = assertIs<DecodedMetadata.Hdr10Plus>(decodeSeiPayload(VideoCodec.HEVC, sei(4, t35)))
        assertEquals(1000L, d.value.targetedSystemDisplayMaximumLuminance)
    }

    @Test
    fun `other T35 payload keeps country and provider`() {
        val d = assertIs<DecodedMetadata.ItuT35>(decodeSeiPayload(VideoCodec.HEVC, sei(4, hex("b5 0031 47413934"))))
        assertEquals(0xB5, d.countryCode)
        assertEquals(0x31, d.providerCode)
    }

    @Test
    fun `truncated HDR10plus header is malformed rather than generic T35`() {
        val t35 = hex("b5003c0001040140")
        assertIs<DecodedMetadata.Malformed>(decodeSeiPayload(VideoCodec.HEVC, sei(4, t35)))
    }

    @Test
    fun `user data unregistered formats the x265 UUID and ASCII`() {
        val payload = hex("2ca2de09b51747dbbb55a4fe7fc2fc4e") + "x265 (build 216)".toByteArray() + byteArrayOf(0x00)
        val d = assertIs<DecodedMetadata.UserDataUnregistered>(decodeSeiPayload(VideoCodec.HEVC, sei(5, payload)))
        assertEquals("2ca2de09-b517-47db-bb55-a4fe7fc2fc4e", d.uuid)
        assertEquals("x265 (build 216).", d.asciiPreview)
    }

    @Test
    fun `recovery point differs per codec`() {
        // H.264: recovery_frame_cnt ue(v)=0 ('1'), exact_match=1, broken_link=0, changing_slice_group_idc=00 -> 1 1 0 00 + pad
        val h264 = assertIs<DecodedMetadata.RecoveryPoint>(decodeSeiPayload(VideoCodec.H264, sei(6, byteArrayOf(0b11000000.toByte()))))
        assertEquals(DecodedMetadata.RecoveryPoint(0, exactMatch = true, brokenLink = false), h264)
        // HEVC: recovery_poc_cnt se(v)=-1 (codeNum 2 = '011'), exact_match=0, broken_link=1 -> 011 0 1 + pad
        val hevc = assertIs<DecodedMetadata.RecoveryPoint>(decodeSeiPayload(VideoCodec.HEVC, sei(6, byteArrayOf(0b01101000.toByte()))))
        assertEquals(DecodedMetadata.RecoveryPoint(-1, exactMatch = false, brokenLink = true), hevc)
    }

    @Test
    fun `alternative transfer and unknown types`() {
        assertEquals(DecodedMetadata.AlternativeTransfer(18), decodeSeiPayload(VideoCodec.HEVC, sei(147, byteArrayOf(18))))
        assertEquals(DecodedMetadata.PresenceOnly, decodeSeiPayload(VideoCodec.HEVC, sei(1, byteArrayOf(1, 2))))
        assertEquals("pic_timing", seiPayloadTypeName(1))
        assertEquals("mastering_display_colour_volume", seiPayloadTypeName(137))
        assertEquals("sei_payload_250", seiPayloadTypeName(250))
    }

    @Test
    fun `malformed message stays malformed`() {
        assertIs<DecodedMetadata.Malformed>(decodeSeiPayload(VideoCodec.HEVC, SeiMessage(137, 24, ByteArray(2), "cut")))
    }
}
