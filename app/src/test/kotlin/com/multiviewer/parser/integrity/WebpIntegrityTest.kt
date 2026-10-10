package com.multiviewer.parser.integrity

import kotlin.test.Test
import kotlin.test.assertEquals

class WebpIntegrityTest {
    @Test
    fun `valid simple webp passes and declares its bitstream size`() {
        val r = checkBytes(webpBytes(), "webp")
        listOf("webp.riff", "webp.chunks", "webp.image").forEach { assertEquals(CheckStatus.PASS, r.item(it).status, it) }
        assertEquals(CheckStatus.SKIP, r.item("webp.canvas").status)
        assertEquals(1, r.declaredWidth)
        assertEquals(1, r.declaredHeight)
    }

    @Test
    fun `riff size larger than the file fails`() {
        assertEquals(CheckStatus.FAIL, checkBytes(webpBytes(riffSizeDelta = 10), "webp").item("webp.riff").status)
    }

    @Test
    fun `trailing bytes warn`() {
        assertEquals(CheckStatus.WARN, checkBytes(webpBytes(trailing = byteArrayOf(1, 2)), "webp").item("webp.riff").status)
    }

    @Test
    fun `truncated chunk fails`() {
        val full = webpBytes()
        val r = checkBytes(full.copyOf(full.size - 3), "webp")
        assertEquals(CheckStatus.FAIL, r.item("webp.chunks").status)
    }

    @Test
    fun `vp8x canvas is compared with the bitstream`() {
        assertEquals(CheckStatus.PASS, checkBytes(webpBytes(vp8xCanvas = 1 to 1), "webp").item("webp.canvas").status)
        assertEquals(CheckStatus.WARN, checkBytes(webpBytes(vp8xCanvas = 4 to 4), "webp").item("webp.canvas").status)
    }
}
