package com.multiviewer.parser.conformance.jpeg

import com.multiviewer.parser.ByteReader
import com.multiviewer.parser.byteReaderOf
import com.multiviewer.parser.conformance.ConformanceStatus.FAIL
import com.multiviewer.parser.conformance.ConformanceStatus.NOT_CHECKED
import com.multiviewer.parser.conformance.ConformanceStatus.PASS
import com.multiviewer.parser.conformance.ConformanceStatus.WARN
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Regression tests for the final whole-branch review (I1, I2, M1-M6). */
class JpegReviewFixesTest {
    private fun u32be(v: Int) = byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())
    private fun box(type: String, payload: Int) = u32be(8 + payload) + type.toByteArray(Charsets.US_ASCII) + ByteArray(payload) { 7 }
    private fun ftyp() = u32be(16) + "ftyp".toByteArray() + "isom".toByteArray() + ByteArray(4)

    private fun le32(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
    private fun le16(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte())

    /** Well-formed Samsung SEF trailer with two field blocks (offsets counted back from SEFH). */
    private fun sefTrailer(): ByteArray {
        fun block(marker: Int, name: String, data: String): ByteArray {
            val n = name.toByteArray() + byteArrayOf(0)
            return le16(0) + le16(marker) + le32(n.size) + n + data.toByteArray()
        }
        val blocks = listOf(block(0x0b01, "A", "hello"), block(0x0b02, "B", "world!!"))
        val total = blocks.sumOf { it.size }
        var remaining = total
        var dir = "SEFH".toByteArray() + le32(0) + le32(blocks.size)
        blocks.forEachIndexed { i, b ->
            dir += le16(0) + le16(0x0b01 + i) + le32(remaining) + le32(b.size); remaining -= b.size
        }
        return blocks.reduce { a, b -> a + b } + dir + le32(dir.size) + "SEFT".toByteArray()
    }

    private fun trailing(bytes: ByteArray) = check(bytes).let { it.findings.single { f -> f.rule.id == "t81.trailing" } }

    @Test fun `I1 a clean SEF trailer explains the bytes but a gap in front of the first block does not`() {
        assertEquals(PASS, trailing(baseline() + sefTrailer()).status)
        val gap = trailing(baseline() + ByteArray(5000) { 0x41 } + sefTrailer())
        assertEquals(WARN, gap.status)
        assertTrue(gap.details.any { it.contains("5000") }, gap.details.toString())
    }

    @Test fun `C1 a SEF trailer with zero directory entries explains nothing`() {
        fun zero(n: Int): ByteArray {
            val dir = "SEFH".toByteArray() + le32(0) + le32(0)
            return ByteArray(n) { 0x55 } + dir + le32(dir.size) + "SEFT".toByteArray()
        }
        val r = trailing(baseline() + zero(100))
        assertEquals(WARN, r.status)
        assertEquals(WARN, trailing(baseline() + zero(0)).status)
        assertEquals(PASS, trailing(baseline() + sefTrailer()).status)
    }

    @Test fun `I2 a second SOI inside the image fails soi`() {
        val r = check(SOI + SOI + dqt() + dht(0, 0) + dht(1, 0) + sof() + sos() + ecs() + EOI)
        assertEquals(FAIL, r.status("t81.soi"))
        assertEquals(FAIL, check(baseline(tables = dqt() + SOI + dht(0, 0) + dht(1, 0))).status("t81.soi"))
    }

    @Test fun `M1 only a structurally valid ISO-BMFF tail is a motion-photo video`() {
        assertEquals(WARN, trailing(baseline() + ftyp() + box("junk", 8)).status)
        assertEquals(WARN, trailing(baseline() + ftyp() + box("moov", 8)).status)        // no mdat
        assertEquals(PASS, trailing(baseline() + ftyp() + box("moov", 8) + box("mdat", 16)).status)
        val cut = baseline() + ftyp() + box("moov", 8) + box("mdat", 16)
        assertEquals(WARN, trailing(cut.copyOf(cut.size - 3)).status)                      // last box cut off
    }

    @Test fun `M2 restart interval that cannot be verified is NOT_CHECKED, not PASS`() {
        // Lossless SOF3 with DRI: the RST count cannot be derived.
        val r = check(baseline(tables = dht(0, 0) + dri(1), frame = sof(0xC3), scan = sos(ss = 1, se = 0) + ecs() + rst(0) + ecs()))
        assertEquals(NOT_CHECKED, r.status("t81.restart"))
    }

    @Test fun `M3 a looping Exif IFD pointer is reported as unreadable, not as missing tags`() {
        val loop = File(javaClass.getResource("/jpeg-conformance/exif-ifd-loop.jpg")!!.toURI())
        val r = ByteReader.open(loop).use { JpegConformanceChecker.check(it) }
        val mand = r.findings.single { it.rule.id == "exif.tags.mandatory" }
        assertTrue(mand.details.none { it.contains("ExifVersion") }, mand.details.toString())
    }

    @Test fun `M3 an out-of-range Exif IFD pointer gives NOT_CHECKED for its tags`() {
        val b = File(javaClass.getResource("/jpeg-conformance/exif-mpf.jpg")!!.toURI()).readBytes()
        // 0th IFD entry 0x8769 (ExifIFDPointer): value is a LONG; point it far outside the APP1.
        val tag = (14..200).first { b[it] == 0x69.toByte() && b[it + 1] == 0x87.toByte() && b[it + 2] == 4.toByte() }
        val p = b.copyOf(); le32(0x7000).copyInto(p, tag + 8)
        val r = byteReaderOf(p).use { JpegConformanceChecker.check(it) }
        assertEquals(FAIL, r.status("exif.ifd.structure"))
        assertEquals(NOT_CHECKED, r.status("exif.tags.mandatory"))
        assertEquals(NOT_CHECKED, r.status("exif.tags.recommended"))
        assertTrue(r.findings.single { it.rule.id == "exif.tags.mandatory" }.details.none { it.contains("ExifVersion") })
    }

    @Test fun `M6 arithmetic and lossless frames pass`() {
        val arith = check(baseline(tables = dqt(), frame = sof(0xC9)))
        assertEquals(emptySet(), arith.ids(FAIL, WARN, NOT_CHECKED), arith.dump().toString())
        val lossless = check(baseline(tables = dht(0, 0), frame = sof(0xC3), scan = sos(ss = 1, se = 0) + ecs()))
        assertEquals(emptySet(), lossless.ids(FAIL, WARN), lossless.dump().toString())
    }

    @Test fun `M6 a 0xFF as the last byte of a chunk-sized scan at the end of the file does not throw`() {
        val s = byteReaderOf(baseline(scan = sos() + ByteArray(65_535) { 0x11 } + byteArrayOf(0xFF.toByte()), tail = ByteArray(0))).use { JpegStreamScanner.scan(it) }
        assertTrue(s.truncated); assertNull(s.eoi)
    }

    private fun mpfFixture() = File(javaClass.getResource("/jpeg-conformance/exif-mpf.jpg")!!.toURI()).readBytes()
    private fun indexOf(b: ByteArray, pat: ByteArray, from: Int = 0): Int = (from..b.size - pat.size).first { i -> pat.indices.all { b[i + it] == pat[it] } }

    @Test fun `M6 MPF negative cases`() {
        val b = mpfFixture()
        val mpf = indexOf(b, "MPF\u0000".toByteArray(Charsets.ISO_8859_1))
        // MP Endian MM while the Exif TIFF header is II -> WARN
        val be = b.copyOf(); byteArrayOf(0x4D, 0x4D, 0, 0x2A, 0, 0, 0, 8).copyInto(be, mpf + 4)
        assertEquals(WARN, byteReaderOf(be).use { JpegConformanceChecker.check(it) }.status("mpf.endian"))
        // MPFVersion 0100 -> 0200
        val ver = b.copyOf(); val v = indexOf(ver, "0100".toByteArray(), mpf); ver[v + 1] = '2'.code.toByte()
        assertEquals(FAIL, byteReaderOf(ver).use { JpegConformanceChecker.check(it) }.status("mpf.version"))
        // tag B001 (NumberOfImages) renamed -> missing mandatory tag
        val idx = b.copyOf(); val t = indexOf(idx, byteArrayOf(0x01, 0xB0.toByte(), 0x04, 0x00), mpf); idx[t] = 0x04
        assertEquals(FAIL, byteReaderOf(idx).use { JpegConformanceChecker.check(it) }.status("mpf.index"))
        // two MP Extensions APP2
        val app2 = indexOf(b, byteArrayOf(0xFF.toByte(), 0xE2.toByte()))
        val len = ((b[app2 + 2].toInt() and 0xFF) shl 8) or (b[app2 + 3].toInt() and 0xFF)
        val dup = b.copyOfRange(0, app2 + 2 + len) + b.copyOfRange(app2, app2 + 2 + len) + b.copyOfRange(app2 + 2 + len, b.size)
        assertEquals(FAIL, byteReaderOf(dup).use { JpegConformanceChecker.check(it) }.status("mpf.count"))
        // first entry's Data Offset must be 0: entry 1 starts at the MP Entry value (after the 3 IFD entries)
        val ent = mpf + 4 + 8 + 2 + 3 * 12 + 4
        val off = b.copyOf(); off[ent + 8] = 1
        assertEquals(FAIL, byteReaderOf(off).use { JpegConformanceChecker.check(it) }.status("mpf.entries"))
    }
}
