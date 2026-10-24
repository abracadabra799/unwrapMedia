package com.multiviewer.parser

import java.io.File
import kotlin.test.*

class InfeDetailsTest {
    private fun decode(bytes: ByteArray, size: Long = bytes.size.toLong()) = byteReaderOf(bytes).use {
        InfeBoxDecoder.decode(it, "infe", 0, 0, size, emptyList())
    }
    @Test fun `real HEIC exposes hidden flag without fabricating absent encoding`() {
        val root = parseFile(File("src/test/resources/c2pa/s26-motion-photo.heic"))
        val first = assertNotNull(findFirst(root) { it.type == "infe" })
        assertEquals("true", first.fields.find { it.name == "hidden_item" }?.value)
        assertEquals(3014054L, first.fields.first { it.name == "hidden_item" }.offset)
        val mime = assertNotNull(findFirst(root) { it.type == "infe" && it.fields.any { f -> f.name == "item_type" && f.value == "mime" } })
        assertEquals("application/rdf+xml", mime.fields.first { it.name == "content_type" }.value)
        assertTrue(mime.fields.none { it.name == "content_encoding" })
        assertTrue(mime.warnings.isEmpty())
    }
    @Test fun `MIME encoding and version three URI retain byte ranges`() {
        val mime = decode(hex("02000000 00320000 6d696d65 00 746578742f706c61696e00 677a697000"))
        val encoding = assertNotNull(mime.fields.find { it.name == "content_encoding" })
        assertEquals("gzip", encoding.value)
        assertEquals(24L, encoding.offset)
        assertEquals(4L, encoding.length)
        val uri = decode(hex("03000001 ffffffff 0000 75726920 00 75726e3a7465737400"))
        assertEquals("4294967295", uri.fields.first { it.name == "item_ID" }.value)
        assertEquals("urn:test", uri.fields.find { it.name == "item_uri_type" }?.value)
    }
    @Test fun `unknown versions and truncated strings or file ranges warn`() {
        assertTrue(decode(hex("04000000 00000001 0000 68766331 00")).warnings.isNotEmpty())
        assertTrue(decode(hex("02000000 00010000 68766331 616263")).warnings.isNotEmpty())
        assertTrue(decode(hex("02000000 00010000 68766331 00"), 1000).warnings.isNotEmpty())
        val large = decode(hex("02000000 00010000 68766331") + ByteArray(70000) { 65 })
        assertTrue(large.warnings.isNotEmpty())
        assertTrue(large.fields.none { it.name == "item_name" })
        assertEquals(65536L, large.fields.first { it.name == "item_name_preview" }.length)
    }
}
