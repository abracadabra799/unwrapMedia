package com.multiviewer.parser

import java.io.File
import kotlin.test.*

class Av1ConfigDetailsTest {
    private fun decode(bytes: ByteArray, size: Long = bytes.size.toLong()) = byteReaderOf(bytes).use {
        Av1CBoxDecoder.decode(it,"av1C",0,0,size,emptyList())
    }
    @Test fun `real AVIF empty configuration and MP4 sequence OBU retain source ranges`() {
        val avif = assertNotNull(findFirst(parseFile(File("src/test/resources/detail-parsing/default-avif.avif"))) { it.type == "av1C" })
        assertEquals("8", avif.fields.find { it.name == "bit_depth" }?.value)
        assertTrue(avif.children.isEmpty())
        assertTrue(avif.fields.none { it.name == "initial_presentation_delay_minus_one" })
        assertTrue(avif.warnings.isEmpty())
        val mp4 = assertNotNull(findFirst(parseFile(File("src/test/resources/av1-conformance/default-av01.mp4"))) { it.type == "av1C" })
        val obu = mp4.children.single()
        assertEquals(4318L, obu.offset)
        assertEquals(13L, obu.size)
        assertEquals("1", obu.fields.find { it.name == "obu_type" }?.value)
        val payload = obu.fields.first { it.name == "payload_bytes" }
        assertEquals(4320L,payload.offset)
        assertEquals(11L,payload.length)
    }
    @Test fun `optional delay is a sample count and extension IDs decode without payload reads`() {
        val node = decode(hex("81406c1f 2e3802 aabb"))
        assertEquals("12",node.fields.find { it.name == "bit_depth" }?.value)
        assertEquals("15",node.fields.find { it.name == "initial_presentation_delay_minus_one" }?.value)
        assertEquals("16",node.fields.find { it.name == "initial_presentation_delay_samples" }?.value)
        val obu = node.children.single()
        assertEquals("1",obu.fields.find { it.name == "temporal_id" }?.value)
        assertEquals("3",obu.fields.find { it.name == "spatial_id" }?.value)
        assertTrue(node.warnings.isEmpty())
    }
    @Test fun `malformed configuration lengths and versions warn with bounded expansion`() {
        for (suffix in listOf("0a80","0a0201","0e","0880","0a8080808080808080")) {
            assertTrue(decode(hex("81000c00 $suffix")).warnings.isNotEmpty(),suffix)
        }
        assertTrue(decode(hex("82000c00")).warnings.isNotEmpty())
        assertTrue(decode(hex("81000c00"),100).warnings.isNotEmpty())
        val many = decode(hex("81000c00") + ByteArray(600) { if(it%2==0) 0x7a else 0 })
        assertEquals(256,many.children.size)
        assertTrue(many.warnings.isNotEmpty())
        assertEquals("88",many.fields.find { it.name == "unparsed_config_obu_bytes" }?.value)
    }
}
