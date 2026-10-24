package com.multiviewer.parser

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CountedBoxDetailsTest {
    private fun decode(type: String, body: ByteArray): BoxNode {
        registerAllDecoders()
        return byteReaderOf(body).use { BoxRegistry.decoderFor(type).decode(it, type, 0, 0, body.size.toLong(), emptyList()) }
    }
    @Test fun `sample descriptions and data references expose fullbox header and entry count`() {
        for (type in listOf("stsd", "dref")) {
            val node = decode(type, hex("00000000 00000001 0000000c 75726c20 00000001"))
            assertEquals("1", node.fields.firstOrNull { it.name == "entry_count" }?.value)
            assertEquals("0", node.fields.firstOrNull { it.name == "version" }?.value)
            assertEquals("url ", node.children.single().type)
            assertTrue(node.warnings.isEmpty())
            assertTrue(decode(type, hex("00000000 00000002")).warnings.isNotEmpty())
            for (n in 0 until 8) assertTrue(decode(type, ByteArray(n)).warnings.isNotEmpty())
        }
    }
    @Test fun `real sample group mapping is visible and read lazily`() {
        registerAllDecoders()
        ByteReader.open(File("src/test/resources/detail-parsing/default-avc-aac.mp4")).use { reader ->
            val node = BoxRegistry.decoderFor("sbgp").decode(reader, "sbgp", 1409, 8, 28, emptyList())
            assertEquals("roll", node.fields.firstOrNull { it.name == "grouping_type" }?.value)
            val table = assertNotNull(node.table)
            assertEquals(1L, table.entryCount)
            assertEquals(listOf(20L, 1L), readTableRow(reader, table.entriesStart, table.fieldWidths, 0))
            assertTrue(node.warnings.isEmpty())
        }
    }
    @Test fun `version one grouping parameter and truncated entries are bounded`() {
        val node = decode("sbgp", hex("01000000 726f6c6c 00000007 00000002 00000014 00000001"))
        assertEquals("7", node.fields.firstOrNull { it.name == "grouping_type_parameter" }?.value)
        assertEquals(1L, assertNotNull(node.table).entryCount)
        assertTrue(node.warnings.isNotEmpty())
        assertTrue(decode("sbgp", hex("02000000 726f6c6c 00000000")).warnings.isNotEmpty())
        for (n in 0 until 12) assertTrue(decode("sbgp", ByteArray(n)).warnings.isNotEmpty())
    }
}
