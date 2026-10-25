package com.multiviewer.parser.conformance.png

import com.multiviewer.parser.conformance.ConformanceStatus.FAIL
import com.multiviewer.parser.conformance.ConformanceStatus.NOT_APPLICABLE
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

class PngAncillaryRulesTest {
    private fun failsOnly(bytes: ByteArray, vararg ids: String) {
        val r = check(bytes)
        assertNull(r.inspectionError)
        assertEquals(ids.toSet(), r.idsWith(FAIL, WARN), r.notPassing().toString())
    }

    @Test fun `ancillary field values`() {
        failsOnly(rgb(chunk("sRGB", byteArrayOf(4))), "png3.ancillary")
        failsOnly(rgb(chunk("cICP", byteArrayOf(1, 13, 1, 1))), "png3.ancillary")              // Matrix Coefficients must be 0
        failsOnly(rgb(chunk("mDCV", ByteArray(24))), "png3.ancillary")                          // without cICP
        assertEquals(PASS, check(rgb(chunk("cICP", byteArrayOf(9, 16, 0, 1)), chunk("mDCV", ByteArray(24)), chunk("cLLI", ByteArray(8)))).statusOf("png3.ancillary"))
        failsOnly(rgb(chunk("sBIT", byteArrayOf(8, 0, 8))), "png3.ancillary")
        failsOnly(rgb(chunk("sBIT", byteArrayOf(8, 9, 8))), "png3.ancillary")
        failsOnly(rgb(chunk("pHYs", be32(1) + be32(1) + byteArrayOf(2))), "png3.ancillary")
        failsOnly(rgb(after = listOf(chunk("tIME", be16(2026) + byteArrayOf(13, 1, 0, 0, 0)))), "png3.ancillary")
        failsOnly(png(ihdr(colorType = 3), chunk("PLTE", ByteArray(6)), chunk("hIST", ByteArray(2)), idat(), iend()), "png3.ancillary")
        failsOnly(png(ihdr(colorType = 3), chunk("PLTE", ByteArray(6)), chunk("bKGD", byteArrayOf(2)), idat(), iend()), "png3.ancillary")
        fun splt(name: String, depth: Int, vararg freq: Int) =
            chunk("sPLT", name.toByteArray() + byteArrayOf(0, depth.toByte()) + freq.fold(ByteArray(0)) { a, f -> a + ByteArray(4) + be16(f) })
        assertEquals(PASS, check(rgb(splt("p", 8, 9, 3))).statusOf("png3.ancillary"))
        failsOnly(rgb(splt("p", 8, 3, 9)), "png3.ancillary")                                    // not decreasing
        failsOnly(rgb(splt("p", 4, 3)), "png3.ancillary")                                       // depth 4
        failsOnly(rgb(splt("p", 8, 3), splt("p", 8, 3)), "png3.ancillary")                      // duplicate names
    }

    @Test fun `tRNS and the unused high bits of 16-bit sample fields`() {
        failsOnly(rgb(chunk("tRNS", ByteArray(2))), "png3.trns")                                // colour type 2 needs 6 bytes
        failsOnly(png(ihdr(colorType = 3), chunk("PLTE", ByteArray(3)), chunk("tRNS", ByteArray(2)), idat(), iend()), "png3.trns")
        failsOnly(rgb(chunk("tRNS", be16(0x100) + be16(0) + be16(0))), "png3.unused_bits")
        assertEquals(NOT_APPLICABLE, check(png(ihdr(depth = 16), chunk("bKGD", be16(0xFFFF) + be16(0) + be16(0)), idat(), iend())).statusOf("png3.unused_bits"))
    }

    @Test fun `keywords, text fields and zlib headers`() {
        failsOnly(rgb(chunk("tEXt", ByteArray(80) { 0x41 })), "png3.keyword")                   // no separator within 79 bytes
        failsOnly(rgb(chunk("tEXt", "a\u007Fb\u0000x".toByteArray())), "png3.keyword")
        failsOnly(rgb(chunk("tEXt", "Title\u0000a\u0000b".toByteArray())), "png3.text")
        failsOnly(rgb(chunk("zTXt", "Title\u0000".toByteArray() + byteArrayOf(1) + SyntheticPng.zlib())), "png3.text")
        failsOnly(rgb(chunk("iTXt", "Title\u0000".toByteArray() + byteArrayOf(0, 0, 0, 0) + byteArrayOf(0xC3.toByte(), 0x28))), "png3.text") // invalid UTF-8
        failsOnly(rgb(chunk("iTXt", "Title\u0000".toByteArray() + byteArrayOf(0, 1, 0, 0) + "x".toByteArray())), "png3.text")         // uncompressed, method 1
        val cinfo8 = byteArrayOf(0x88.toByte(), (31 - (0x88 * 256) % 31).toByte()) + SyntheticPng.zlib().copyOfRange(2, SyntheticPng.zlib().size)
        failsOnly(png(ihdr(), idat(cinfo8), iend()), "png3.compression", "zlib.header")
        failsOnly(png(ihdr(), idat(byteArrayOf(0x78)), iend()), "zlib.header")                  // shorter than CMF+FLG
        // The 2-byte header may span IDAT chunks.
        val z = SyntheticPng.zlib()
        assertEquals(PASS, check(png(ihdr(), idat(z.copyOf(1)), idat(z.copyOfRange(1, z.size)), iend())).statusOf("zlib.header"))
    }
}
