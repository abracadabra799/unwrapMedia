package com.multiviewer.parser.conformance.jpeg

import com.multiviewer.parser.byteReaderOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JpegStreamScannerTest {
    private fun scan(b: ByteArray) = byteReaderOf(b).use { JpegStreamScanner.scan(it) }

    @Test fun `walks markers to EOI and records the entropy data`() {
        val b = baseline()
        val s = scan(b)
        assertEquals(listOf(0xD8, 0xE0, 0xDB, 0xC4, 0xC4, 0xC0, 0xDA, 0xD9), s.segments.map { it.marker })
        assertEquals(b.size.toLong(), s.end)
        assertEquals(1, s.entropy.size)
        assertEquals(0L, s.entropy[0].restartCount)
        assertTrue(s.markerProblems.isEmpty() && s.lengthProblems.isEmpty() && !s.truncated)
    }

    @Test fun `counts restart markers, skips stuffed bytes and fill bytes, finds out-of-order RST`() {
        val data = ecs() + byteArrayOf(0xFF.toByte(), 0) + rst(0) + ecs() + rst(1) + ecs() + rst(3) + ecs()
        val s = scan(baseline(scan = sos() + data, tail = byteArrayOf(0xFF.toByte(), 0xFF.toByte()) + EOI))
        assertEquals(3L, s.entropy[0].restartCount)
        assertEquals(s.entropy[0].start + 4 + 2 + 2 + 4 + 2 + 4, s.entropy[0].restartOutOfOrder)
        assertTrue(s.eoi != null)
    }

    @Test fun `truncated scan data and bad lengths are reported, never thrown`() {
        val cut = baseline().let { it.copyOf(it.size - 2) }
        val t = scan(cut)
        assertTrue(t.truncated); assertNull(t.eoi)
        val bad = SOI + byteArrayOf(0xFF.toByte(), 0xE1.toByte(), 0x7F, 0x00)
        val l = scan(bad)
        assertEquals(1, l.lengthProblems.size); assertTrue(l.truncated)
        val garbage = scan(SOI + byteArrayOf(0x12, 0x34))
        assertEquals(1, garbage.markerProblems.size)
    }

    @Test fun `entropy data crossing the 64 KiB chunk boundary is scanned`() {
        val big = ByteArray(70_000) { 0x11 }.also { it[65_535] = 0xFF.toByte(); it[65_536] = 0 }
        val s = scan(baseline(scan = sos() + big + rst(0) + ecs()))
        assertEquals(1L, s.entropy[0].restartCount)
        assertTrue(s.eoi != null)
    }
}
