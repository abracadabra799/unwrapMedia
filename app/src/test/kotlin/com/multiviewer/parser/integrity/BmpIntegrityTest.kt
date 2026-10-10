package com.multiviewer.parser.integrity

import kotlin.test.Test
import kotlin.test.assertEquals

class BmpIntegrityTest {
    @Test
    fun `valid bmp passes`() {
        val r = checkBytes(bmpBytes(), "bmp")
        listOf("bmp.size", "bmp.offset", "bmp.pixels").forEach { assertEquals(CheckStatus.PASS, r.item(it).status, it) }
        assertEquals(2, r.declaredWidth)
        assertEquals(2, r.declaredHeight)
    }

    @Test
    fun `short pixel array fails`() {
        val r = checkBytes(bmpBytes(pixelBytes = 10), "bmp")
        assertEquals(CheckStatus.FAIL, r.item("bmp.pixels").status)
        assertEquals(54L, r.item("bmp.pixels").offset)
    }

    @Test
    fun `bfSize larger than the file fails, smaller warns`() {
        assertEquals(CheckStatus.FAIL, checkBytes(bmpBytes(bfSizeOverride = 1000), "bmp").item("bmp.size").status)
        assertEquals(CheckStatus.WARN, checkBytes(bmpBytes(bfSizeOverride = 60), "bmp").item("bmp.size").status)
    }
}
