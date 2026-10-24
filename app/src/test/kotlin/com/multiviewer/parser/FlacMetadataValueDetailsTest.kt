package com.multiviewer.parser

import java.io.File
import kotlin.test.*

class FlacMetadataValueDetailsTest {
    private fun value(n: BoxNode, name: String) = assertNotNull(n.fields.find { it.name == name }, name)
    private fun block(type: Int, payload: ByteArray): BoxNode {
        val n = payload.size
        val bytes = hex("664c6143") + byteArrayOf((128 or type).toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte()) + payload
        return byteReaderOf(bytes).use { parseFlacBlocks(it, 0, it.length)[1] }
    }
    private fun actual() = ByteReader.open(File("src/test/resources/flac-metadata/metadata.flac")).use { parseFlacBlocks(it, 0, it.length) }
    private fun le(n: Int) = byteArrayOf(n.toByte(), (n ushr 8).toByte(), (n ushr 16).toByte(), (n ushr 24).toByte())
    @Test fun `real pictures expose palette count data extent and URI`() {
        val pictures = actual().filter { it.type == "PICTURE" }
        val picture = pictures[0]
        assertEquals("0", value(picture, "num_colors").value)
        assertEquals("96", value(picture, "data_length").value)
        assertEquals(737L, value(picture, "data_length").offset)
        assertEquals(741L, value(picture, "picture_data").offset)
        assertEquals(96L, value(picture, "picture_data").length)
        assertEquals(ValueInterpretation.UNINTERPRETED, value(picture, "picture_data").interpretation)
        assertEquals("표지", value(picture, "description").value)
        val uri = value(pictures[1], "picture_uri")
        assertEquals("https://example.invalid/cover.png", uri.value)
        assertEquals(888L, uri.offset)
        assertEquals(33L, uri.length)
        assertEquals(ValueInterpretation.PARSED, detailInterpretation(pictures[1]))
    }
    @Test fun `real tags expose counts lengths duplicate values and exact value ranges`() {
        val comment = actual().first { it.type == "VORBIS_COMMENT" }
        assertEquals("13", value(comment, "vendor_length").value)
        assertEquals("4", value(comment, "comment_count").value)
        assertEquals("17", value(comment, "comment[1].length").value)
        val title = value(comment, "TITLE")
        assertEquals("검증 FLAC", title.value)
        assertEquals(102L, title.offset)
        assertEquals(11L, title.length)
        assertEquals(listOf("First", "Second"), comment.fields.filter { it.name == "ARTIST" }.map { it.value })
    }
    @Test fun `picture lengths cannot consume subsequent metadata and malformed text stays explicit`() {
        val bytes = File("src/test/resources/flac-metadata/metadata.flac").readBytes()
        val picture = bytes.copyOfRange(694, 837)
        for (length in picture.indices) assertEquals(ValueInterpretation.UNPARSABLE, detailInterpretation(block(6, picture.copyOf(length))), "length=$length")
        val badData = picture.copyOf().also { hex("ffffffff").copyInto(it,43) }
        assertEquals(ValueInterpretation.UNPARSABLE, detailInterpretation(block(6, badData)))
        val badMime = picture.copyOf().also { it[8] = 0xff.toByte() }
        assertEquals(ValueInterpretation.UNPARSABLE, value(block(6, badMime), "mime").interpretation)
        val badDescription = picture.copyOf().also { it[21] = 0xff.toByte() }
        assertEquals(ValueInterpretation.UNPARSABLE, value(block(6, badDescription), "description").interpretation)
        assertEquals(ValueInterpretation.UNINTERPRETED, detailInterpretation(block(6, picture + byteArrayOf(1))))
    }
    @Test fun `Vorbis malformed counts strings and field syntax cannot appear parsed`() {
        val invalid = listOf(
            hex("00000000ffffffff"),
            hex("ffffffff"),
            le(2) + hex("c328") + le(0),
            le(0) + le(1) + le(3) + hex("413dff"),
            le(0) + le(1) + le(3) + "BAD".toByteArray(),
            le(0) + le(1) + le(3) + hex("013d41"),
        )
        invalid.forEach { assertEquals(ValueInterpretation.UNPARSABLE, detailInterpretation(block(4, it))) }
        val trailing = block(4, le(0) + le(0) + byteArrayOf(1))
        assertEquals(ValueInterpretation.UNINTERPRETED, detailInterpretation(trailing))
        assertEquals("4294967295", value(block(4, hex("00000000ffffffff")), "comment_count").value)
    }
    @Test fun `large text and comment counts remain bounded with explicit omitted bytes`() {
        val text = "TITLE=".toByteArray() + ByteArray(70_000) { 'x'.code.toByte() }
        val large = block(4, le(0) + le(1) + le(text.size) + text)
        assertEquals(ValueInterpretation.UNINTERPRETED, detailInterpretation(large))
        assertTrue(large.fields.sumOf { it.value.length } < 1000)
        val record = le(3) + "A=B".toByteArray()
        val many = block(4, le(0) + le(5000) + ByteArray(record.size * 5000) { record[it % record.size] })
        assertEquals(ValueInterpretation.UNINTERPRETED, detailInterpretation(many))
        assertEquals(4096, many.fields.count { it.name == "A" })
        assertNotNull(many.fields.find { it.name == "unparsed_bytes" })
    }
}
