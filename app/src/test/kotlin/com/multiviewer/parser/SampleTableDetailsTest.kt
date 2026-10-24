package com.multiviewer.parser

import java.io.File
import kotlin.test.*

class SampleTableDetailsTest {
    @Test fun `real ctts version one displays negative composition offsets`() {
        val file = File("src/test/resources/detail-parsing/signed-ctts.mp4")
        val ctts = assertNotNull(findFirst(parseFile(file)) { it.type == "ctts" })
        assertEquals("1", ctts.fields.find { it.name == "version" }?.value)
        val count = assertNotNull(ctts.fields.find { it.name == "entry_count" })
        assertEquals("4", count.value)
        assertEquals(4929L, count.offset)
        ByteReader.open(file).use { reader ->
            assertEquals(listOf(2L, -2048L), readTableRow(reader, assertNotNull(ctts.table), 2))
        }
    }
    @Test fun `ctts version zero keeps unsigned values and unknown versions are not guessed`() {
        registerAllDecoders()
        val bytes = hex("00000000 00000001 00000002 fffff800")
        byteReaderOf(bytes).use { reader ->
            val node = BoxRegistry.decoderFor("ctts").decode(reader, "ctts", 0, 0, bytes.size.toLong(), emptyList())
            assertEquals(listOf(2L, 4294965248L), readTableRow(reader, assertNotNull(node.table), 0))
        }
        bytes[0] = 2
        byteReaderOf(bytes).use { reader ->
            val node = BoxRegistry.decoderFor("ctts").decode(reader, "ctts", 0, 0, bytes.size.toLong(), emptyList())
            assertNull(node.table)
            assertTrue(node.warnings.isNotEmpty())
        }
    }
    @Test fun `fixed table headers remain visible when rows truncate and file bounds warn`() {
        val bytes = hex("00000000 00000002 00000001 00000020")
        val decoder = FixedWidthTableDecoder(listOf("sample_count", "sample_delta"), listOf(4,4))
        byteReaderOf(bytes).use { reader ->
            val node = decoder.decode(reader, "stts", 0, 0, bytes.size.toLong(), emptyList())
            assertEquals("2", node.fields.find { it.name == "entry_count" }?.value)
            assertEquals(1L, node.table?.entryCount)
            assertTrue(node.warnings.isNotEmpty())
            assertTrue(decoder.decode(reader, "stts", 0, 0, 1000, emptyList()).warnings.isNotEmpty())
        }
    }
}
