package com.multiviewer.parser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ApvPbuTest {
    // Real access-unit bytes from openapv's test/bitstream/qp_D.apv (first access unit), verified
    // byte-identical whether read from the raw .apv file or a real ffmpeg-remuxed apv1 MP4 sample --
    // see docs/superpowers/plans/2026-08-16-apv-codec-support.md's Technical Foundation section.
    private val realAccessUnitPrefix = hexToBytes(
        "00095f7c6150763100095f2601000100217b40000f00000870220000000000" +
            "400002000000000ab900140000000006b300000216000001dc333333009ddd" +
            "9073",
    )

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { i -> ((Character.digit(hex[i * 2], 16) shl 4) + Character.digit(hex[i * 2 + 1], 16)).toByte() }

    @Test
    fun `findApvPrimaryFramePbuPayload locates the primary-frame PBU and returns its frame() payload`() {
        val payload = findApvPrimaryFramePbuPayload(realAccessUnitPrefix)

        assertNotNull(payload)
        // The frame() payload starts right after the 4-byte pbu_header, i.e. at access-unit byte 16
        // (4-byte length + 4-byte 'aPv1' signature + 4-byte pbu_size + 4-byte pbu_header = 16).
        // Its first byte is profile_idc = 0x21 = 33.
        assertEquals(0x21, payload[0].toInt() and 0xFF)
    }

    @Test
    fun `findApvPrimaryFramePbuPayload returns null for truncated input`() {
        assertNull(findApvPrimaryFramePbuPayload(realAccessUnitPrefix.copyOfRange(0, 10)))
    }

    @Test
    fun `findApvPrimaryFramePbuPayload returns null when no primary-frame PBU is present`() {
        // Same leading length + signature, but pbu_type changed from 1 to 2 (non-primary frame) at
        // access-unit byte 12 -- no primary-frame PBU exists in this input.
        val mutated = realAccessUnitPrefix.copyOf()
        mutated[12] = 2
        assertNull(findApvPrimaryFramePbuPayload(mutated))
    }
}
