package com.multiviewer.parser.integrity

import kotlin.test.Test
import kotlin.test.assertEquals

class HeifIntegrityTest {
    @Test
    fun `valid single-item heif passes and declares the ispe size`() {
        val r = checkBytes(heifBytes(), "heic")
        assertEquals("HEIF", r.format)
        listOf("heif.ftyp", "heif.meta", "heif.primary", "heif.iloc").forEach { assertEquals(CheckStatus.PASS, r.item(it).status, it) }
        assertEquals(64, r.declaredWidth)
        assertEquals(48, r.declaredHeight)
    }

    @Test
    fun `extent past end of file fails`() {
        val r = checkBytes(heifBytes(extentShift = 100), "heic")
        assertEquals(CheckStatus.FAIL, r.item("heif.iloc").status)
    }

    @Test
    fun `truncated file fails the iloc check`() {
        val full = heifBytes()
        assertEquals(CheckStatus.FAIL, checkBytes(full.copyOf(full.size - 8), "heic").item("heif.iloc").status)
    }

    @Test
    fun `undeclared primary item fails`() {
        assertEquals(CheckStatus.FAIL, checkBytes(heifBytes(primaryId = 7), "heic").item("heif.primary").status)
    }

    @Test
    fun `complete grid passes and declares its output size`() {
        val r = checkBytes(heifGridBytes(tileRefs = 2), "heic")
        assertEquals(CheckStatus.PASS, r.item("heif.grid").status, r.items.toString())
        assertEquals(128, r.declaredWidth)
        assertEquals(64, r.declaredHeight)
    }

    @Test
    fun `grid with missing tile references fails`() {
        assertEquals(CheckStatus.FAIL, checkBytes(heifGridBytes(tileRefs = 1), "heic").item("heif.grid").status)
    }

    @Test
    fun `heif motion photo with exposed top-level ftyp and moov without mpvd fails ftyp check`() {
        val base = heifBytes()
        val extraFtyp = box("ftyp", "mp42".toByteArray(Charsets.US_ASCII) + be32(0) + "mp42isom".toByteArray(Charsets.US_ASCII))
        val moov = box("moov", ByteArray(16))
        val r = checkBytes(base + extraFtyp + moov, "heic")
        val ftypItems = r.items.filter { it.id == "heif.ftyp" }
        assertEquals(2, ftypItems.size)
        assertEquals(CheckStatus.FAIL, ftypItems.last().status)
        assertEquals(CheckStatus.FAIL, r.overall)
    }

    @Test
    fun `heif with corrupted sefd trailer fails structure check`() {
        val base = heifBytes()
        val brokenSefd = box("sefd", ByteArray(16) + "SEFT".toByteArray(Charsets.US_ASCII))
        val r = checkBytes(base + brokenSefd, "heic")
        assertEquals(CheckStatus.FAIL, r.item("heif.sefd").status)
        assertEquals(CheckStatus.FAIL, r.overall)
    }
}
