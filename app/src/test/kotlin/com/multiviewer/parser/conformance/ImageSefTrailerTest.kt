package com.multiviewer.parser.conformance

import com.multiviewer.parser.conformance.ConformanceStatus.FAIL
import com.multiviewer.parser.conformance.ConformanceStatus.PASS
import com.multiviewer.parser.conformance.ConformanceStatus.WARN
import com.multiviewer.parser.conformance.gif.SyntheticGif
import com.multiviewer.parser.conformance.png.SyntheticPng
import com.multiviewer.parser.conformance.webp.SyntheticWebp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Final-review C1: a SEF trailer explains trailing bytes only when its entries tile [end of image, SEFH). */
class ImageSefTrailerTest {
    private fun le32(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
    private fun le16(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte())

    /** Well-formed SEF trailer with two field blocks; [gapBefore] bytes in front of the first block, [gapMid] between. */
    private fun sef(gapBefore: Int = 0, gapMid: Int = 0): ByteArray {
        fun block(marker: Int, name: String, data: String): ByteArray {
            val n = name.toByteArray() + byteArrayOf(0)
            return le16(0) + le16(marker) + le32(n.size) + n + data.toByteArray()
        }
        val b1 = block(0x0b01, "A", "hello"); val b2 = block(0x0b02, "B", "world!!")
        val body = ByteArray(gapBefore) { 0x41 } + b1 + ByteArray(gapMid) { 0x42 } + b2
        val s1 = gapBefore; val s2 = gapBefore + b1.size + gapMid
        var dir = "SEFH".toByteArray() + le32(0) + le32(2)
        dir += le16(0) + le16(0x0b01) + le32(body.size - s1) + le32(b1.size)
        dir += le16(0) + le16(0x0b02) + le32(body.size - s2) + le32(b2.size)
        return body + dir + le32(dir.size) + "SEFT".toByteArray()
    }

    /** SEFH declaring 0 entries, preceded by [n] arbitrary bytes: nothing is explained. */
    private fun sefZero(n: Int): ByteArray {
        val dir = "SEFH".toByteArray() + le32(0) + le32(0)
        return ByteArray(n) { 0x55 } + dir + le32(dir.size) + "SEFT".toByteArray()
    }

    private val png = SyntheticPng.rgb()
    private val webpBase get() = SyntheticWebp.riff(SyntheticWebp.vp8())
    private val gifBase get() = SyntheticGif.gif(SyntheticGif.header(), SyntheticGif.image(), SyntheticGif.TRAILER)

    private fun webp(tail: ByteArray) = webpBase + tail
    private fun status(r: ConformanceReport, id: String) = r.findings.single { it.rule.id == id }.status

    @Test fun `PNG - clean SEF trailer explains the bytes, gaps and empty directories do not`() {
        fun st(b: ByteArray) = status(SyntheticPng.check(b), "png3.trailing")
        assertEquals(PASS, st(png + sef()))
        assertEquals(FAIL, st(png + sefZero(100)))
        assertEquals(FAIL, st(png + sefZero(0)))
        assertEquals(FAIL, st(png + sef(gapBefore = 24)))
        assertEquals(FAIL, st(png + sef(gapMid = 24)))
    }

    @Test fun `WebP - clean SEF trailer explains the bytes, gaps and empty directories do not`() {
        fun st(tail: ByteArray) = status(SyntheticWebp.check(webp(tail)), "rfc9649.trailing")
        assertEquals(PASS, st(sef()))
        assertEquals(WARN, st(sefZero(100)))
        assertEquals(WARN, st(sef(gapBefore = 24)))
        assertEquals(WARN, st(sef(gapMid = 24)))
    }

    @Test fun `GIF - clean SEF trailer explains the bytes, gaps and empty directories do not`() {
        fun st(tail: ByteArray) = status(SyntheticGif.check(gifBase + tail), "gif89a.trailing")
        assertEquals(PASS, st(sef()))
        assertEquals(WARN, st(sefZero(100)))
        assertEquals(WARN, st(sef(gapBefore = 24)))
        assertEquals(WARN, st(sef(gapMid = 24)))
    }

    @Test fun `the unexplained range is reported`() {
        val f = SyntheticPng.check(png + sefZero(100)).findings.single { it.rule.id == "png3.trailing" }
        assertTrue(f.details.any { it.contains("SEF") && it.contains("100") }, f.details.toString())
    }
}
