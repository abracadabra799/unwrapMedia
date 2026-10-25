package com.multiviewer.parser.conformance.png

import com.multiviewer.parser.conformance.ConformanceStatus.FAIL
import com.multiviewer.parser.conformance.ConformanceStatus.NOT_CHECKED
import com.multiviewer.parser.conformance.ConformanceStatus.PASS
import com.multiviewer.parser.conformance.ConformanceStatus.WARN
import com.multiviewer.parser.conformance.idsWith
import com.multiviewer.parser.conformance.notPassing
import com.multiviewer.parser.conformance.png.SyntheticPng.be16
import com.multiviewer.parser.conformance.png.SyntheticPng.be32
import com.multiviewer.parser.conformance.png.SyntheticPng.check
import com.multiviewer.parser.conformance.png.SyntheticPng.chunk
import com.multiviewer.parser.conformance.png.SyntheticPng.idat
import com.multiviewer.parser.conformance.png.SyntheticPng.iend
import com.multiviewer.parser.conformance.png.SyntheticPng.ihdr
import com.multiviewer.parser.conformance.png.SyntheticPng.png
import com.multiviewer.parser.conformance.png.SyntheticPng.rgb
import com.multiviewer.parser.conformance.statusOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PngStructureRulesTest {
    private fun failsOnly(bytes: ByteArray, vararg ids: String) {
        val r = check(bytes)
        assertNull(r.inspectionError)
        assertEquals(ids.toSet(), r.idsWith(FAIL, WARN), r.notPassing().toString())
    }

    @Test fun `a minimal valid PNG passes every applicable rule`() {
        val r = check(rgb())
        assertEquals(emptySet(), r.idsWith(FAIL, WARN, NOT_CHECKED), r.notPassing().toString())
        assertEquals(PASS, r.overall)
    }

    @Test fun `chunk type bytes and the reserved bit`() {
        failsOnly(rgb(chunk("abcd")), "png3.chunk.type")          // third letter lowercase
        failsOnly(rgb(chunk("ab1d")), "png3.chunk.type")          // '1' outside 41-5A / 61-7A
        assertEquals(PASS, check(rgb(chunk("prVt"))).statusOf("png3.chunk.type")) // private ancillary chunk is fine
    }

    @Test fun `IHDR values and the critical chunk sequence`() {
        failsOnly(png(ihdr(w = 0), idat(), iend()), "png3.ihdr")
        failsOnly(png(ihdr(interlace = 2), idat(), iend()), "png3.ihdr")
        failsOnly(png(ihdr(filter = 1), idat(), iend()), "png3.ihdr")
        failsOnly(png(ihdr(), iend()), "png3.critical")                                     // no IDAT
        failsOnly(png(ihdr(), idat(), chunk("tEXt", "a\u0000b".toByteArray()), idat(), iend()), "png3.critical")
        failsOnly(png(ihdr(), ihdr(), idat(), iend()), "png3.critical")
        failsOnly(png(ihdr(), idat(), chunk("IEND", byteArrayOf(1))), "png3.critical")
        failsOnly(png(chunk("tEXt", "a\u0000b".toByteArray()), ihdr(), idat(), iend()), "png3.ihdr")       // IHDR not first
    }

    @Test fun `PLTE presence, size and entry count`() {
        fun pal(depth: Int, entries: Int) = png(ihdr(depth = depth, colorType = 3), chunk("PLTE", ByteArray(entries * 3)), idat(), iend())
        assertEquals(PASS, check(pal(1, 2)).statusOf("png3.plte"))
        failsOnly(pal(1, 3), "png3.plte")                                                   // 3 entries > 2^1
        failsOnly(png(ihdr(colorType = 3), chunk("PLTE", ByteArray(4)), idat(), iend()), "png3.plte")
        failsOnly(png(ihdr(), idat(), chunk("PLTE", ByteArray(3)), iend()), "png3.critical") // PLTE after IDAT
        assertEquals(PASS, check(png(ihdr(), chunk("PLTE", ByteArray(3)), idat(), iend())).statusOf("png3.plte")) // suggested palette, colour type 2
    }

    @Test fun `ancillary ordering and uniqueness`() {
        failsOnly(png(ihdr(colorType = 3, depth = 8), chunk("PLTE", ByteArray(3)), chunk("gAMA", be32(45455)), idat(), iend()), "png3.order")
        failsOnly(png(ihdr(colorType = 3, depth = 8), chunk("tRNS", byteArrayOf(0)), chunk("PLTE", ByteArray(3)), idat(), iend()), "png3.order")
        failsOnly(rgb(chunk("tIME", be16(2026) + byteArrayOf(10, 25, 12, 0, 0)), chunk("tIME", be16(2026) + byteArrayOf(10, 25, 12, 0, 0))), "png3.multiple")
        assertEquals(PASS, check(rgb(after = listOf(chunk("tIME", be16(2026) + byteArrayOf(10, 25, 12, 0, 0))))).statusOf("png3.order")) // tIME: no constraint
    }

    @Test fun `a truncated file never reports PASS for rules that need every chunk`() {
        val full = rgb(chunk("gAMA", be32(45455)))
        val r = check(full.copyOf(full.size - 20))
        assertEquals(setOf("png3.chunk.layout", "png3.critical"), r.idsWith(FAIL, WARN), r.notPassing().toString())
        assertEquals(NOT_CHECKED, r.statusOf("png3.crc"))
        assertEquals(NOT_CHECKED, r.statusOf("png3.order"))
        assertEquals(NOT_CHECKED, r.statusOf("png3.trailing"))
        assertTrue(r.findings.none { it.status == PASS && it.rule.id !in setOf("png3.signature", "png3.ihdr") }, r.notPassing().toString())
    }
}
