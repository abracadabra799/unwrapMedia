package com.multiviewer.parser

import java.io.File
import kotlin.test.*

class AvcConfigDetailsTest {
    private fun decode(bytes: ByteArray, size: Long = bytes.size.toLong()) = byteReaderOf(bytes).use {
        AvcCBoxDecoder.decode(it, "avcC", 0, 0, size, emptyList())
    }
    @Test fun `real high profile extension exposes chroma and bit depths`() {
        val root = parseFile(File("src/test/resources/detail-parsing/signed-ctts.mp4"))
        val node = assertNotNull(findFirst(root) { it.type == "avcC" })
        assertEquals("1", node.fields.find { it.name == "chroma_format_idc" }?.value)
        assertEquals("8", node.fields.find { it.name == "bit_depth_luma" }?.value)
        assertEquals("8", node.fields.find { it.name == "bit_depth_chroma" }?.value)
        assertEquals("0", node.fields.find { it.name == "num_sps_ext" }?.value)
        assertEquals(4833L, node.fields.first { it.name == "chroma_format_idc" }.offset)
        assertTrue(node.warnings.isEmpty())
    }
    @Test fun `nonzero depths and SPS extension lengths are bounded`() {
        val prefix = hex("0164001f ffe0 00")
        val node = decode(prefix + hex("ff fa fb 01 0002 6d00"))
        assertEquals("3", node.fields.find { it.name == "chroma_format_idc" }?.value)
        assertEquals("10", node.fields.find { it.name == "bit_depth_luma" }?.value)
        assertEquals("11", node.fields.find { it.name == "bit_depth_chroma" }?.value)
        assertEquals("1", node.fields.find { it.name == "num_sps_ext_parsed" }?.value)
        assertTrue(decode(prefix + hex("fd f8")).warnings.isNotEmpty())
        assertTrue(decode(prefix + hex("fd f8 f8 01 0008 6d00")).warnings.isNotEmpty())
        assertTrue(decode(prefix, 1000).warnings.isNotEmpty())
    }
}
