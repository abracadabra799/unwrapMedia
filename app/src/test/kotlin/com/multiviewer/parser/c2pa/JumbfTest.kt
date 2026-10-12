package com.multiviewer.parser.c2pa

import com.multiviewer.parser.hex
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JumbfTest {
    private val iso = hex("00110010800000aa00389b71")
    private fun u32(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
    private fun box(type: String, payload: ByteArray) = u32(8 + payload.size) + type.toByteArray() + payload
    private fun jumd(fourCc: String, toggles: Int, label: String?, extra: ByteArray = ByteArray(0)) =
        box("jumd", fourCc.toByteArray() + iso + byteArrayOf(toggles.toByte()) + (label?.let { it.toByteArray() + byteArrayOf(0) } ?: ByteArray(0)) + extra)

    @Test
    fun `superbox with description and content`() {
        val bytes = box("jumb", jumd("cbor", 0x03, "c2pa.actions") + box("cbor", hex("a0")))
        val top = parseJumbfBoxes(bytes).single()
        assertEquals("jumb", top.type)
        assertEquals("c2pa.actions", top.label)
        assertEquals("cbor", top.description!!.contentTypeName)
        assertEquals(1, top.children.size)
        val content = top.firstContent("cbor")!!
        assertEquals(hex("a0").toList(), bytes.copyOfRange(content.payloadStart, content.payloadEnd).toList())
        assertTrue(top.warnings.isEmpty())
    }

    @Test
    fun `id, signature and private box toggles`() {
        val extra = u32(7) + ByteArray(32) { 1 } + box("c2sh", ByteArray(16))
        val d = parseJumbfBoxes(box("jumb", jumd("json", 0x02 or 0x04 or 0x08 or 0x10, "x", extra))).single().description!!
        assertEquals("x", d.label)
        assertEquals(7L, d.id)
        assertEquals(32, d.signature!!.size)
        assertEquals("c2sh", d.privateBoxType)
    }

    @Test
    fun `no label toggle and non-standard uuid`() {
        val uuid = hex("40cb0c32bb8a489da70b2ad6f47f4369")
        val bytes = box("jumb", box("jumd", uuid + byteArrayOf(0x01)))
        val d = parseJumbfBoxes(bytes).single().description!!
        assertNull(d.label)
        assertEquals(JUMBF_EMBEDDED_FILE_TYPE, d.contentTypeName)
        assertEquals("01020304-0506-0708-090a-0b0c0d0e0f10", jumbfContentTypeName(hex("0102030405060708090a0b0c0d0e0f10")))
    }

    @Test
    fun `lbox 0 extends to end and lbox 1 uses 64-bit size`() {
        val zero = byteArrayOf(0, 0, 0, 0) + "free".toByteArray() + ByteArray(5)
        assertEquals(13, parseJumbfBoxes(zero).single().size)
        val large = byteArrayOf(0, 0, 0, 1) + "free".toByteArray() + hex("0000000000000014") + ByteArray(4)
        val b = parseJumbfBoxes(large).single()
        assertEquals(16, b.headerSize)
        assertEquals(20, b.size)
    }

    @Test
    fun `truncated box becomes a warning and stops the level`() {
        val bytes = box("free", ByteArray(2)) + u32(100) + "jumb".toByteArray() + ByteArray(4)
        val boxes = parseJumbfBoxes(bytes)
        assertEquals(2, boxes.size)
        assertTrue(boxes[1].warnings.single().contains("past"))
    }

    @Test
    fun `label without terminator and superbox without description warn`() {
        val noNul = box("jumb", box("jumd", "cbor".toByteArray() + iso + byteArrayOf(0x02) + "abc".toByteArray()))
        val top = parseJumbfBoxes(noNul).single()
        assertEquals("abc", top.label)
        assertTrue(top.warnings.any { it.contains("NUL") })
        val noJumd = parseJumbfBoxes(box("jumb", box("cbor", hex("a0")))).single()
        assertNull(noJumd.description)
        assertTrue(noJumd.warnings.any { it.contains("jumd") })
    }

    @Test
    fun `real C jpg store`() {
        val file = File(javaClass.getResource("/c2pa/C.jpg")!!.toURI()).readBytes()
        val store = file.copyOfRange(32, 32 + 45872)
        val top = parseJumbfBoxes(store).single()
        assertEquals("c2pa", top.label)
        assertEquals("c2pa", top.description!!.contentTypeName)
        val manifest = top.children.single()
        assertEquals("contentauth:urn:uuid:b2b1f7fa-b119-4de1-9c0d-c97fbea3f2c3", manifest.label)
        assertEquals("c2ma", manifest.description!!.contentTypeName)
        assertEquals(listOf("c2pa.assertions", "c2pa.claim", "c2pa.signature"), manifest.children.map { it.label })
        val assertions = assertNotNull(manifest.child("c2pa.assertions"))
        assertEquals(
            listOf("c2pa.thumbnail.claim.jpeg", "stds.schema-org.CreativeWork", "c2pa.actions", "c2pa.hash.data"),
            assertions.children.map { it.label },
        )
        val creativeWork = assertions.child("stds.schema-org.CreativeWork")!!
        assertEquals(0x13, creativeWork.description!!.toggles)
        assertEquals("c2sh", creativeWork.description!!.privateBoxType)
        assertEquals(JUMBF_EMBEDDED_FILE_TYPE, assertions.child("c2pa.thumbnail.claim.jpeg")!!.description!!.contentTypeName)
        assertEquals(listOf("bfdb", "bidb"), assertions.child("c2pa.thumbnail.claim.jpeg")!!.children.map { it.type })
        assertTrue(top.warnings.isEmpty())
    }
    @Test
    fun `private box declared beyond description warns`() {
        val badPrivate = u32(100) + "c2sh".toByteArray()
        val top = parseJumbfBoxes(box("jumb", jumd("json", 0x12, "x", badPrivate))).single()
        assertTrue(top.warnings.any { it.contains("private") })
    }

    @Test
    fun `superbox depth limit terminates expansion with warning`() {
        var data = box("free", ByteArray(0))
        repeat(40) { data = box("jumb", jumd("c2pa", 3, "c2pa") + data) }
        var node = parseJumbfBoxes(data).single()
        repeat(32) { node = node.children.single() }
        assertTrue(node.warnings.any { it.contains("nesting") })
        assertTrue(node.children.isEmpty())
    }
}
