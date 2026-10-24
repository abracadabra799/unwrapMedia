package com.multiviewer.parser

import java.io.File
import kotlin.test.*

class SampleDetailsDecoderTest {
    private fun decode(type: String, data: ByteArray): BoxNode {
        registerAllDecoders()
        return byteReaderOf(data).use { BoxRegistry.decoderFor(type).decode(it, type, 0, 0, data.size.toLong(), emptyList()) }
    }
    @Test fun `real dependency flags are exposed as four columns`() {
        registerAllDecoders()
        ByteReader.open(File("src/test/resources/hdr/av1_hdr.mp4")).use { reader ->
            val node = BoxRegistry.decoderFor("sdtp").decode(reader, "sdtp", 16694, 8, 22, emptyList())
            val table = assertNotNull(node.table)
            assertEquals(10L, table.entryCount)
            assertEquals(listOf(0L, 2L, 0L, 0L), readTableRow(reader, table, 0))
            assertEquals(listOf("is_leading", "sample_depends_on", "sample_is_depended_on", "sample_has_redundancy"), table.columns)
        }
    }
    @Test fun `real roll group yields a lazy signed table`() {
        registerAllDecoders()
        ByteReader.open(File("src/test/resources/detail-parsing/default-avc-aac.mp4")).use { reader ->
            val node = BoxRegistry.decoderFor("sgpd").decode(reader, "sgpd", 1383, 8, 26, emptyList())
            assertEquals("roll", node.fields.firstOrNull { it.name == "grouping_type" }?.value)
            assertEquals(1L, assertNotNull(node.table).entryCount)
            assertEquals(1407L, node.table!!.entriesStart)
            assertEquals(listOf(-1L), readTableRow(reader, node.table!!, 0))
            assertTrue(node.warnings.isEmpty())
        }
    }
    @Test fun `version zero and version two roll headers are decoded`() {
        val zero = decode("sgpd", hex("00000000 726f6c6c 00000001 ffff"))
        assertEquals(1L, assertNotNull(zero.table).entryCount)
        val two = decode("sgpd", hex("02000000 70726f6c 00000002 00000007 00000001 0002"))
        assertEquals("7", two.fields.firstOrNull { it.name == "default_sample_description_index" }?.value)
        assertEquals(20L, assertNotNull(two.table).entriesStart)
    }
    @Test fun `variable roll descriptions expose signed values and source offsets`() {
        val node = decode("sgpd", hex("01000000 726f6c6c 00000000 00000002 00000002 ffff 00000002 0002"))
        assertEquals(listOf("-1", "2"), node.children.map { it.fields.single { f -> f.name == "roll_distance" }.value })
        assertEquals(listOf(20L, 26L), node.children.map { it.offset })
        assertTrue(node.warnings.isEmpty())
    }
    @Test fun `truncated headers and descriptions warn and unknown group is explicit`() {
        for (n in 0 until 4) assertTrue(decode("sdtp", ByteArray(n)).warnings.isNotEmpty())
        for (n in 0 until 12) assertTrue(decode("sgpd", ByteArray(n)).warnings.isNotEmpty())
        assertTrue(decode("sgpd", hex("01000000 726f6c6c 00000000 00000001 00000020 ff")).warnings.isNotEmpty())
        assertTrue(decode("sgpd", hex("03000000 726f6c6c 00000002 00000001 ffff")).warnings.isNotEmpty())
        val unknown = decode("sgpd", hex("00000000 78787878 00000001 01020304"))
        assertTrue(unknown.fields.any { it.name == "parsing_status" })
        assertTrue(unknown.children.isEmpty())
    }
    @Test fun `table decoding preserves all dependency bits and rejects out of range rows`() {
        val body = hex("00000000 e4")
        val node = decode("sdtp", body)
        byteReaderOf(body).use { reader ->
            val table = assertNotNull(node.table)
            assertEquals(listOf(3L, 2L, 1L, 0L), readTableRow(reader, table, 0))
            assertFailsWith<IllegalArgumentException> { readTableRow(reader, table, 1) }
            val unsigned = TableData(listOf("raw"), listOf(1), 4, 1)
            assertEquals(listOf(228L), readTableRow(reader, unsigned, 0))
        }
        val json = com.multiviewer.cli.buildDumpJson(File("sample.mp4"), node)
        assertTrue(json.contains("SDTP_BITS"))
        val ordinary = node.copy(table = TableData(listOf("raw"), listOf(1), 4, 1))
        assertFalse(com.multiviewer.cli.buildDumpJson(File("sample.mp4"), ordinary).contains("rowEncoding"))
    }

}
