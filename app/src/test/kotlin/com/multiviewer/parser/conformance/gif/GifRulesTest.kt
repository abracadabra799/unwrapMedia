package com.multiviewer.parser.conformance.gif

import com.multiviewer.parser.conformance.ConformanceStatus.FAIL
import com.multiviewer.parser.conformance.ConformanceStatus.NOT_APPLICABLE
import com.multiviewer.parser.conformance.ConformanceStatus.NOT_CHECKED
import com.multiviewer.parser.conformance.ConformanceStatus.PASS
import com.multiviewer.parser.conformance.ConformanceStatus.WARN
import com.multiviewer.parser.conformance.gif.SyntheticGif.TRAILER
import com.multiviewer.parser.conformance.gif.SyntheticGif.app
import com.multiviewer.parser.conformance.gif.SyntheticGif.check
import com.multiviewer.parser.conformance.gif.SyntheticGif.comment
import com.multiviewer.parser.conformance.gif.SyntheticGif.gce
import com.multiviewer.parser.conformance.gif.SyntheticGif.gif
import com.multiviewer.parser.conformance.gif.SyntheticGif.header
import com.multiviewer.parser.conformance.gif.SyntheticGif.image
import com.multiviewer.parser.conformance.gif.SyntheticGif.plainText
import com.multiviewer.parser.conformance.idsWith
import com.multiviewer.parser.conformance.notPassing
import com.multiviewer.parser.conformance.statusOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GifRulesTest {
    private fun failsOnly(bytes: ByteArray, vararg ids: String) {
        val r = check(bytes)
        assertNull(r.inspectionError)
        assertEquals(ids.toSet(), r.idsWith(FAIL, WARN), r.notPassing().toString())
    }

    @Test fun `87a still image and 89a animation pass`() {
        listOf(gif(header("87a"), image(), TRAILER), gif(header(), app(), gce(0x04), image(), comment(), gce(0x08), image(1, 1, 2, 2), TRAILER)).forEach { b ->
            val r = check(b)
            assertEquals(emptySet(), r.idsWith(FAIL, WARN, NOT_CHECKED), r.notPassing().toString())
            assertEquals(PASS, r.overall)
        }
        assertEquals(NOT_APPLICABLE, check(gif(header("87a"), image(), TRAILER)).statusOf("gif89a.version.earliest"))
        // A palette-only stream (Header, Logical Screen, Trailer) is allowed by the grammar.
        assertEquals(emptySet(), check(gif(header("87a"), TRAILER)).idsWith(FAIL, WARN))
    }

    @Test fun `version labels`() {
        failsOnly(gif(header("87a"), comment(), image(), TRAILER), "gif89a.version")
        failsOnly(gif(header("87a", packed = 0x88), image(), TRAILER), "gif89a.version")          // LSD Sort Flag needs 89a
        failsOnly(gif(header(), image(), TRAILER), "gif89a.version.earliest")
        assertEquals(PASS, check(gif(header(), image(packed = 0x20), TRAILER)).statusOf("gif89a.version.earliest")) // ID Sort Flag
    }

    @Test fun `Logical Screen, background index and colour tables`() {
        failsOnly(header("89a").copyOf(10), "gif89a.lsd", "gif89a.trailer")
        failsOnly(header("89a").copyOf(16), "gif89a.lsd", "gif89a.trailer")                    // 6-byte GCT cut off
        failsOnly(gif(header("87a", packed = 0x00, background = 3), image(), TRAILER), "gif89a.lsd.background")
        failsOnly(gif(header("87a"), image(packed = 0x02), TRAILER), "gif89a.lct.size")
        assertEquals(PASS, check(gif(header("87a"), image(packed = 0x81), TRAILER)).statusOf("gif89a.lct.size"))
    }

    @Test fun `block grammar, extensions and GCE`() {
        failsOnly(gif(header("87a"), byteArrayOf(0x55), image(), TRAILER), "gif89a.blocks", "gif89a.trailer")
        failsOnly(gif(header(), byteArrayOf(0x21, 0xF9.toByte(), 4, 0, 0)), "gif89a.blocks", "gif89a.trailer") // sub-blocks cut off
        failsOnly(gif(header(), gce(size = 3), image(), TRAILER), "gif89a.gce")
        failsOnly(gif(header(), gce(), gce(), image(), TRAILER), "gif89a.gce")
        failsOnly(gif(header(), image(), gce(), TRAILER), "gif89a.gce")                         // nothing for it to control
        assertEquals(PASS, check(gif(header(), gce(), comment(), image(), TRAILER)).statusOf("gif89a.gce")) // other extensions may sit between
        failsOnly(gif(header(), app(size = 10), image(), TRAILER), "gif89a.extension")
        failsOnly(gif(header(), app("NETSC\u0001PE2.0"), image(), TRAILER), "gif89a.extension")
        failsOnly(gif(header(), plainText(size = 13), TRAILER), "gif89a.extension")
        val unknown = check(gif(header(), byteArrayOf(0x21, 0x99.toByte(), 1, 0, 0), image(), TRAILER))
        assertEquals(PASS, unknown.statusOf("gif89a.blocks"))
        assertTrue(unknown.findings.single { it.rule.id == "gif89a.blocks" }.details.single().contains("0x99"))
    }

    @Test fun `reserved fields, image bounds and LZW`() {
        failsOnly(gif(header(), gce(0x20), image(), TRAILER), "gif89a.reserved")
        failsOnly(gif(header(), gce(0x10), image(), TRAILER), "gif89a.reserved")                  // disposal 4
        failsOnly(gif(header("87a"), image(packed = 0x08), TRAILER), "gif89a.reserved")
        failsOnly(gif(header("87a"), image(x = 1), TRAILER), "gif89a.image.bounds")
        failsOnly(gif(header("87a"), image(lzw = 1), TRAILER), "gif89a.lzw")
        failsOnly(gif(header("87a"), image(data = ByteArray(0)), TRAILER), "gif89a.lzw")
        failsOnly(gif(header("87a"), image(data = byteArrayOf(0x2D, 0)), TRAILER), "gif89a.lzw.clear") // first code 5 (EOI)
    }

    @Test fun `M1 an unreadable first LZW code is NOT_CHECKED, never PASS`() {
        // min code size 8 -> 9-bit first code, but only one data byte
        val r = check(gif(header("87a"), image(lzw = 8, data = byteArrayOf(0)), TRAILER))
        assertEquals(NOT_CHECKED, r.statusOf("gif89a.lzw.clear"))
        assertEquals(emptySet(), r.idsWith(FAIL, WARN), r.notPassing().toString())
        // a readable image elsewhere that is wrong still WARNs
        assertEquals(WARN, check(gif(header("87a"), image(lzw = 8, data = byteArrayOf(0)), image(data = byteArrayOf(0x2D, 0)), TRAILER)).statusOf("gif89a.lzw.clear"))
    }

    @Test fun `M2 the scanner keeps extension bytes only for the labels the rules read`() {
        val bytes = gif(header(), gce(), comment("abc"), plainText(), app(), image(), TRAILER)
        val exts = com.multiviewer.parser.byteReaderOf(bytes).use { GifContext(it).extensions }
        fun first(label: Int) = exts.single { it.label == label }
        assertEquals(4, first(0xF9).firstSize)
        assertTrue(first(0xF9).first.isNotEmpty() && first(0xFF).first.isNotEmpty())
        assertEquals(0, first(0xFE).first.size)
        assertEquals(0, first(0x01).first.size)
        assertEquals(3, first(0xFE).firstSize)
        assertEquals(12, first(0x01).firstSize)
    }

    @Test fun `trailer and data after it`() {
        val noTrailer = check(gif(header(), gce(), image()))
        assertEquals(setOf("gif89a.trailer"), noTrailer.idsWith(FAIL, WARN), noTrailer.notPassing().toString())
        assertEquals(NOT_CHECKED, noTrailer.statusOf("gif89a.image.bounds"))                    // only the blocks read were checked
        assertEquals(NOT_CHECKED, noTrailer.statusOf("gif89a.trailing"))
        val trailing = check(gif(header("87a"), image(), TRAILER, ByteArray(5)))
        assertEquals(setOf("gif89a.trailing"), trailing.idsWith(FAIL, WARN))
        assertEquals(WARN, trailing.overall)
    }
}
