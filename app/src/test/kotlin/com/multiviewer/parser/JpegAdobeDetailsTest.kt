package com.multiviewer.parser

import java.io.File
import kotlin.test.*

class JpegAdobeDetailsTest {
    private fun segment(payload: ByteArray): BoxNode {
        val length = payload.size + 2
        val bytes = hex("ffd8 ffee") + byteArrayOf((length shr 8).toByte(), length.toByte()) + payload + hex("ffd9")
        return byteReaderOf(bytes).use { parseJpegSegments(it, 0, bytes.size.toLong()).first { n -> n.type == "APP14" } }
    }
    @Test fun `real CMYK JPEG exposes Adobe version and flags with original ranges`() {
        ByteReader.open(File("src/test/resources/detail-parsing/cmyk-adobe.jpg")).use { reader ->
            val node = parseJpegSegments(reader, 0, reader.length).first { it.type == "APP14" }
            val version = assertNotNull(node.fields.find { it.name == "version" })
            assertEquals("100", version.value)
            assertEquals(11L, version.offset)
            assertEquals(2L, version.length)
            assertEquals("0", node.fields.find { it.name == "flags0" }?.value)
            assertEquals("0", node.fields.find { it.name == "flags1" }?.value)
            assertEquals("0", node.fields.first().value)
            assertTrue(node.warnings.isEmpty())
        }
    }
    @Test fun `nonzero Adobe flags decode in big endian and truncated headers warn`() {
        val node = segment(hex("41646f6265 0065 1234 abcd 02"))
        assertEquals("4660", node.fields.find { it.name == "flags0" }?.value)
        assertEquals("43981", node.fields.find { it.name == "flags1" }?.value)
        assertEquals("2", node.fields.first { it.name == "color_transform" }.value)
        assertTrue(segment(hex("41646f6265 0064")).warnings.isNotEmpty())
        val unknown = segment("Other".toByteArray())
        assertTrue(unknown.fields.isEmpty())
        assertTrue(unknown.warnings.isEmpty())
    }
}
