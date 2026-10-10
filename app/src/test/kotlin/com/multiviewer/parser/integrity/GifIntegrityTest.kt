package com.multiviewer.parser.integrity

import kotlin.test.Test
import kotlin.test.assertEquals

class GifIntegrityTest {
    @Test
    fun `valid gif passes`() {
        val r = checkBytes(gifBytes(), "gif")
        listOf("gif.lsd", "gif.frames", "gif.blocks", "gif.trailer", "gif.trailing").forEach {
            assertEquals(CheckStatus.PASS, r.item(it).status, it)
        }
        assertEquals(2, r.declaredWidth)
        assertEquals(1, r.declaredHeight)
    }

    @Test
    fun `missing trailer fails`() {
        assertEquals(CheckStatus.FAIL, checkBytes(gifBytes(withTrailer = false), "gif").item("gif.trailer").status)
    }

    @Test
    fun `truncated sub-block chain fails blocks and trailer`() {
        val r = checkBytes(gifBytes(truncateImageData = true), "gif")
        assertEquals(CheckStatus.FAIL, r.item("gif.blocks").status)
        assertEquals(CheckStatus.FAIL, r.item("gif.trailer").status)
    }

    @Test
    fun `bytes after trailer warn`() {
        assertEquals(CheckStatus.WARN, checkBytes(gifBytes(trailing = byteArrayOf(9, 9)), "gif").item("gif.trailing").status)
    }
}
