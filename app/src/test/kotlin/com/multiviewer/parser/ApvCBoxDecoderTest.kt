package com.multiviewer.parser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ApvCBoxDecoderTest {
    // Real apvC box payload extracted from a real ffmpeg-remuxed apv1 MP4 (ffmpeg -i qp_D.apv -c
    // copy out.mp4) during planning -- see docs/superpowers/plans/2026-08-16-apv-codec-support.md's
    // Technical Foundation section for how these exact byte offsets were confirmed (direct
    // value-search, not assumed from documentation).
    private fun realApvCPayload(): ByteArray = ByteArray(22) { i ->
        val hex = "000000000101010101217b0200000f00000008702200"
        ((Character.digit(hex[i * 2], 16) shl 4) + Character.digit(hex[i * 2 + 1], 16)).toByte()
    }

    // Mirrors Av1CBoxDecoderTest's exact decode() helper shape: fileOf() + ByteReader.open(),
    // headerSize-byte zero-padding prefix, offset=0.
    private fun decode(payload: ByteArray): BoxNode {
        val headerSize = 8
        val file = fileOf(ByteArray(headerSize) + payload)
        val reader = ByteReader.open(file)
        return ApvCBoxDecoder.decode(reader, "apvC", offset = 0, headerSize = headerSize, size = (headerSize + payload.size).toLong(), warnings = emptyList())
    }

    private fun fieldValue(node: BoxNode, name: String): String? = node.fields.find { it.name == name }?.value

    @Test
    fun `decode extracts profile level band and frame dimensions from a real apvC box`() {
        val node = decode(realApvCPayload())

        assertEquals("33", fieldValue(node, "profile_idc"))
        assertEquals("123", fieldValue(node, "level_idc"))
        assertEquals("2", fieldValue(node, "band_idc"))
        assertEquals("3840", fieldValue(node, "frame_width"))
        assertEquals("2160", fieldValue(node, "frame_height"))
        assertEquals("2", fieldValue(node, "chroma_format_idc"))
        assertEquals("10", fieldValue(node, "bit_depth"))
    }

    @Test
    fun `decode adds a warning and no fields when the box is too short`() {
        val node = decode(realApvCPayload().copyOfRange(0, 10)) // 10 bytes, needs at least 21
        assertTrue(node.warnings.isNotEmpty())
        assertTrue(node.fields.isEmpty())
    }
}
