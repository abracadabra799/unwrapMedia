package com.multiviewer.parser

import java.io.File
import kotlin.test.*

class FlacCueSheetDetailsTest {
    private fun nodes(file: String) = ByteReader.open(File("src/test/resources/flac-metadata/$file")).use { parseFlacBlocks(it, 0, it.length) }
    private fun value(node: BoxNode, name: String) = assertNotNull(node.fields.find { it.name == name }, name)
    private fun cue(payload: ByteArray): BoxNode {
        val length = payload.size
        val bytes = hex("664c6143") + byteArrayOf(0x85.toByte(), (length ushr 16).toByte(), (length ushr 8).toByte(), length.toByte()) + payload
        return byteReaderOf(bytes).use { parseFlacBlocks(it, 0, it.length).first { it.type == "CUESHEET" } }
    }
    @Test fun `actual CD cuesheet exposes catalog tracks indices and exact source spans`() {
        val node = nodes("metadata.flac").first { it.type == "CUESHEET" }
        assertEquals("1234567890123", value(node, "media_catalog_number").value)
        assertEquals("88200", value(node, "lead_in_samples").value)
        assertEquals("true", value(node, "is_cd").value)
        assertEquals("3", value(node, "track_count").value)
        assertEquals(3, node.children.size)
        val first = node.children.first()
        assertEquals(546L, first.offset)
        assertEquals("USAAA2600001", value(first, "isrc").value)
        assertEquals("true", value(first, "pre_emphasis").value)
        val secondIndex = first.children[1]
        assertEquals("588", value(secondIndex, "sample_offset").value)
        assertEquals(594L, value(secondIndex, "sample_offset").offset)
        assertEquals(8L, value(secondIndex, "sample_offset").length)
        assertEquals("170", value(node.children.last(), "track_number").value)
        assertEquals(ValueInterpretation.PARSED, detailInterpretation(node))
    }
    @Test fun `non CD tracks retain data type and sample positions without sector rounding`() {
        val node = nodes("non-cd.flac").first { it.type == "CUESHEET" }
        assertEquals("false", value(node, "is_cd").value)
        val first = node.children.first()
        assertEquals("17", value(first, "sample_offset").value)
        assertEquals("1", value(first, "track_type").value)
        assertEquals("3", value(first.children.single(), "sample_offset").value)
        assertEquals("255", value(node.children.last(), "track_number").value)
    }
    @Test fun `unsigned sample offsets do not become negative`() {
        val payload = ByteArray(432)
        payload[395] = 1
        hex("ffffffffffffffff").copyInto(payload, 396)
        payload[404] = 255.toByte()
        assertEquals("18446744073709551615", value(cue(payload).children.single(), "sample_offset").value)
    }
    @Test fun `all truncated prefixes fail without consuming a following block`() {
        val all = File("src/test/resources/flac-metadata/metadata.flac").readBytes()
        val payload = all.copyOfRange(150, 690)
        for (length in payload.indices) {
            val node = cue(payload.copyOf(length))
            assertEquals(ValueInterpretation.UNPARSABLE, detailInterpretation(node), "length=$length")
            fun ranges(n: BoxNode) {
                for (f in n.fields) assertTrue(f.offset >= n.offset && f.offset + f.length <= n.offset + n.size, "${f.name}: length=$length")
                n.children.forEach { child ->
                    assertTrue(child.offset >= n.offset && child.offset + child.size <= n.offset + n.size)
                    ranges(child)
                }
            }
            ranges(node)
        }
        val bytes = hex("664c6143 05000000 81000004 00000000")
        byteReaderOf(bytes).use { r ->
            val parsed = parseFlacBlocks(r, 0, r.length)
            assertEquals(ValueInterpretation.UNPARSABLE, detailInterpretation(parsed[1]))
            assertEquals("PADDING", parsed[2].type)
            assertEquals(8L, parsed[2].offset)
        }
    }
    @Test fun `large cuesheet stops expanding and labels remaining bytes`() {
        val header = ByteArray(396).also { it[395] = 20 }
        val track = ByteArray(36 + 255 * 12).also { it[8] = 1; it[35] = 255.toByte() }
        val node = cue(header + ByteArray(track.size * 20) { track[it % track.size] })
        assertEquals(ValueInterpretation.UNINTERPRETED, detailInterpretation(node))
        assertTrue(node.children.sumOf { 1 + it.children.size } <= 4096)
        assertTrue(node.fields.any { it.name == "unparsed_bytes" } || node.children.last().fields.any { it.name == "unparsed_bytes" })
    }
}
