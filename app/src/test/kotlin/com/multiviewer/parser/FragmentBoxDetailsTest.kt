package com.multiviewer.parser

import java.io.File
import kotlin.test.*

class FragmentBoxDetailsTest {
    private fun decode(type: String, body: ByteArray): BoxNode {
        registerAllDecoders()
        return byteReaderOf(body).use { BoxRegistry.decoderFor(type).decode(it, type, 0, 0, body.size.toLong(), emptyList()) }
    }
    @Test fun `real fragment tree exposes header fields and lazy sample sizes`() {
        val file = File("src/test/resources/detail-parsing/fragmented-avc.mp4")
        val root = parseFile(file)
        fun box(type: String) = assertNotNull(findFirst(root) { it.type == type })
        assertEquals("1", box("mfhd").fields.firstOrNull { it.name == "sequence_number" }?.value)
        assertEquals("1", box("trex").fields.firstOrNull { it.name == "track_ID" }?.value)
        assertEquals("2048", box("tfhd").fields.firstOrNull { it.name == "default_sample_duration" }?.value)
        assertEquals("0", box("tfdt").fields.firstOrNull { it.name == "baseMediaDecodeTime" }?.value)
        val trun = box("trun")
        assertEquals("120", trun.fields.firstOrNull { it.name == "data_offset" }?.value)
        val table = assertNotNull(trun.table)
        ByteReader.open(file).use { reader ->
            assertEquals(listOf(1980L), readTableRow(reader, table, 0))
            assertEquals(listOf(510L), readTableRow(reader, table, 1))
        }
    }
    @Test fun `trun version one offsets are signed while version zero is unsigned`() {
        val bytes = hex("01000801 00000001 fffffff8 fffffffe")
        val node = decode("trun", bytes)
        assertEquals("-8", node.fields.firstOrNull { it.name == "data_offset" }?.value)
        byteReaderOf(bytes).use { reader -> assertEquals(listOf(-2L), readTableRow(reader, assertNotNull(node.table), 0)) }
        val zero = bytes.copyOf().also { it[0] = 0 }
        byteReaderOf(zero).use { reader -> assertEquals(listOf(4294967294L), readTableRow(reader, assertNotNull(decode("trun", zero).table), 0)) }
    }
    @Test fun `decode time retains uint64 values and optional fields are bounded`() {
        assertEquals("18446744073709551615", decode("tfdt", hex("01000000 ffffffffffffffff")).fields.firstOrNull { it.name == "baseMediaDecodeTime" }?.value)
        for ((type, body) in listOf("mfhd" to "00000000", "trex" to "00000000", "tfhd" to "00000001 00000001", "tfdt" to "01000000 00000000", "trun" to "00000200 00000002 00000010")) {
            assertTrue(decode(type, hex(body)).warnings.isNotEmpty(), type)
        }
        assertTrue(decode("tfhd", hex("01000000 00000001")).warnings.isNotEmpty())
    }
}
