package com.multiviewer.parser.conformance.isobmff

import com.multiviewer.parser.ByteReader
import com.multiviewer.parser.conformance.ConformanceReport
import com.multiviewer.parser.conformance.ConformanceStatus.FAIL
import com.multiviewer.parser.conformance.ConformanceStatus.NOT_APPLICABLE
import com.multiviewer.parser.conformance.ConformanceStatus.NOT_CHECKED
import com.multiviewer.parser.conformance.ConformanceStatus.PASS
import com.multiviewer.parser.conformance.ConformanceStatus.WARN
import com.multiviewer.parser.fileOf
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IsoBmffSampleRulesTest {
    private val sampleIds = listOf(
        "bmff.stbl.sample_count", "bmff.stsc.order", "bmff.stsc.coverage", "bmff.ctts.count", "bmff.stss.order",
        "bmff.sample.location", "bmff.mdat.present", "bmff.duration.consistency",
    )
    private val allIds = sampleIds + "bmff.elst.entries" + "bmff.fragmented"

    private fun check(bytes: ByteArray) = ByteReader.open(fileOf(bytes)).use { IsoBmffConformanceChecker.check(it) }
    private fun check(spec: MovieSpec) = check(buildMovie(spec))
    private fun ConformanceReport.finding(id: String) = findings.single { it.rule.id == id }
    private fun ConformanceReport.status(id: String) = finding(id).status

    @Test
    fun `default movie passes every sample and edit rule with the right scope`() {
        val r = check(MovieSpec())
        assertNull(r.inspectionError)
        allIds.forEach { id -> assertEquals(PASS, r.status(id), "$id ${r.finding(id).details}") }
        sampleIds.forEach { id -> assertEquals("샘플 테이블 / Sample tables", r.finding(id).rule.scope, id) }
        assertEquals("편집 목록 / Edit lists", r.finding("bmff.elst.entries").rule.scope)
        assertTrue(r.findings.all { it.rule.source == "ISO/IEC 14496-12 (공개 자료 기반, 원문 미대조)" })
    }

    @Test
    fun `stts total different from stsz count fails sample count`() {
        assertEquals(FAIL, check(MovieSpec(sttsCounts = listOf(2))).status("bmff.stbl.sample_count"))
    }

    @Test
    fun `stsc first chunk must start at 1`() {
        assertEquals(FAIL, check(MovieSpec(stsc = listOf(Triple(2L, 3L, 1L)))).status("bmff.stsc.order"))
    }

    @Test
    fun `stsc covering 2 of 3 samples fails coverage`() {
        val r = check(MovieSpec(stsc = listOf(Triple(1L, 2L, 1L))))
        assertEquals(PASS, r.status("bmff.stsc.order"))
        assertEquals(FAIL, r.status("bmff.stsc.coverage"))
        assertEquals(NOT_CHECKED, r.status("bmff.sample.location"))
    }

    @Test
    fun `stsc sample description index beyond stsd fails order`() {
        assertEquals(FAIL, check(MovieSpec(stsc = listOf(Triple(1L, 3L, 2L)))).status("bmff.stsc.order"))
    }

    @Test
    fun `stsc samples per chunk 0 and non increasing first chunk fail order`() {
        assertEquals(FAIL, check(MovieSpec(stsc = listOf(Triple(1L, 0L, 1L)))).status("bmff.stsc.order"))
        assertEquals(FAIL, check(MovieSpec(stsc = listOf(Triple(1L, 3L, 1L), Triple(1L, 3L, 1L)))).status("bmff.stsc.order"))
    }

    @Test
    fun `ctts total different from sample count fails`() {
        assertEquals(FAIL, check(MovieSpec(ctts = listOf(2L to 0L))).status("bmff.ctts.count"))
        assertEquals(PASS, check(MovieSpec(ctts = listOf(3L to 100L))).status("bmff.ctts.count"))
    }

    @Test
    fun `ctts version 0 negative offset fails in ISO only`() {
        assertEquals(FAIL, check(MovieSpec(ctts = listOf(3L to 0xFFFFFF9CL))).status("bmff.ctts.count"))
    }

    @Test
    fun `stss must be increasing, at least 1 and within sample count`() {
        listOf(listOf(2L, 1L), listOf(4L), listOf(0L)).forEach { stss ->
            assertEquals(FAIL, check(MovieSpec(stss = stss)).status("bmff.stss.order"), stss.toString())
        }
        assertEquals(PASS, check(MovieSpec(stss = null)).status("bmff.stss.order"))
    }

    @Test
    fun `chunk offset past end of file fails sample location`() {
        val f = check(MovieSpec(chunkOffsetDelta = 10_000)).finding("bmff.sample.location")
        assertEquals(FAIL, f.status)
        assertTrue(f.details.any { it.contains("파일") }, f.details.toString())
    }

    @Test
    fun `chunk in the mdat header warns sample location`() {
        val f = check(MovieSpec(chunkOffsetDelta = -8)).finding("bmff.sample.location")
        assertEquals(WARN, f.status, f.details.toString())
        assertTrue(f.details.any { it.contains("mdat") })
    }

    @Test
    fun `missing mdat fails mdat present and sample location`() {
        val r = check(MovieSpec(dropBox = "mdat"))
        assertNull(r.inspectionError)
        assertEquals(FAIL, r.status("bmff.mdat.present"))
        assertEquals(FAIL, r.status("bmff.sample.location"))
    }

    @Test
    fun `external data reference makes sample location not applicable`() {
        val external = fullBox("url ", 0, 0, "http://x/a.mp4\u0000".toByteArray())
        val r = check(MovieSpec(drefEntry = external))
        assertEquals(NOT_APPLICABLE, r.status("bmff.sample.location"))
        assertEquals(PASS, r.status("bmff.mdat.present"))
        val noMdat = check(MovieSpec(drefEntry = external, dropBox = "mdat"))
        assertEquals(NOT_APPLICABLE, noMdat.status("bmff.mdat.present"))
        assertEquals(NOT_APPLICABLE, noMdat.status("bmff.sample.location"))
    }

    @Test
    fun `fixed sample size stsz and stz2 are expanded`() {
        val fixed = fullBox("stsz", 0, 0, be32(10), be32(3))
        val r = check(MovieSpec(replaceBox = mapOf("stsz" to fixed)))
        listOf("bmff.stbl.sample_count", "bmff.stsc.coverage", "bmff.sample.location")
            .forEach { assertEquals(PASS, r.status(it), "$it ${r.finding(it).details}") }
        val stz2 = box("stz2", ByteArray(4), byteArrayOf(0, 0, 0, 8), be32(3), byteArrayOf(10, 10, 10))
        val z = check(MovieSpec(replaceBox = mapOf("stsz" to stz2)))
        listOf("bmff.stbl.sample_count", "bmff.sample.location").forEach { assertEquals(PASS, z.status(it), "$it ${z.finding(it).details}") }
        val big = box("stz2", ByteArray(4), byteArrayOf(0, 0, 0, 16), be32(3), be16(10), be16(10), be16(60000))
        assertEquals(FAIL, check(MovieSpec(replaceBox = mapOf("stsz" to big))).status("bmff.sample.location"))
        val nibble = box("stz2", ByteArray(4), byteArrayOf(0, 0, 0, 4), be32(3), byteArrayOf(0xAA.toByte(), 0xA0.toByte()))
        assertEquals(PASS, check(MovieSpec(replaceBox = mapOf("stsz" to nibble))).status("bmff.sample.location"))
        val badField = box("stz2", ByteArray(4), byteArrayOf(0, 0, 0, 12), be32(3), ByteArray(6))
        assertEquals(FAIL, check(MovieSpec(replaceBox = mapOf("stsz" to badField))).status("bmff.stbl.sample_count"))
    }

    @Test
    fun `co64 chunk offsets are read`() {
        // ftyp is 24 bytes and mdat precedes moov, so the first sample is at 24 + 8 regardless of the moov size.
        val co64 = fullBox("co64", 0, 0, be32(1), be32(0), be32(32))
        val r = check(MovieSpec(replaceBox = mapOf("stco" to co64)))
        assertEquals(PASS, r.status("bmff.sample.location"), r.finding("bmff.sample.location").details.toString())
        val past = fullBox("co64", 0, 0, be32(1), be32(1), be32(32))
        assertEquals(FAIL, check(MovieSpec(replaceBox = mapOf("stco" to past))).status("bmff.sample.location"))
    }

    @Test
    fun `edit list entries`() {
        assertEquals(PASS, check(MovieSpec(elst = listOf(Triple(300L, -1L, 1), Triple(300L, 0L, 1)))).status("bmff.elst.entries"))
        assertEquals(FAIL, check(MovieSpec(elst = listOf(Triple(300L, -2L, 1)))).status("bmff.elst.entries"))
        assertEquals(FAIL, check(MovieSpec(elst = listOf(Triple(300L, 0L, 2)))).status("bmff.elst.entries"))
        assertEquals(FAIL, check(MovieSpec(elst = listOf(Triple(300L, 300L, 1)))).status("bmff.elst.entries"))
        assertEquals(PASS, check(MovieSpec(elst = listOf(Triple(300L, 299L, 0)))).status("bmff.elst.entries"))
    }

    @Test
    fun `edit list entry count larger than the box fails`() {
        val elst = fullBox("elst", 0, 0, be32(5), be32(300), be32(0), be16(1), be16(0))
        val bytes = buildMovie(MovieSpec(elst = listOf(Triple(300L, 0L, 1))))
        val good = fullBox("elst", 0, 0, be32(1), be32(300), be32(0), be16(1), be16(0))
        val at = indexOf(bytes, good)
        val bad = bytes.copyOf()
        elst.copyInto(bad, at)
        assertEquals(FAIL, check(bad).status("bmff.elst.entries"))
    }

    @Test
    fun `elst version 1 with fraction rate fails`() {
        val v1 = fullBox("elst", 1, 0, be32(1), be32(0), be32(300), be32(0), be32(0), be16(1), be16(0x8000))
        val bytes = buildMovie(MovieSpec(elst = listOf(Triple(300L, 0L, 1))))
        val good = box("edts", fullBox("elst", 0, 0, be32(1), be32(300), be32(0), be16(1), be16(0)))
        val at = indexOf(bytes, good)
        val replaced = bytes.copyOfRange(0, at) + box("edts", v1) + bytes.copyOfRange(at + good.size, bytes.size)
        // fix trak/moov sizes (+8 bytes)
        val tree = ByteReader.open(fileOf(bytes)).use { IsoBmffBoxTree.walk(it) }
        val moov = tree.top.single { it.type == "moov" }
        val trak = moov.children.single { it.type == "trak" }
        be32(moov.size + 8).copyInto(replaced, moov.offset.toInt())
        be32(trak.size + 8).copyInto(replaced, trak.offset.toInt())
        val f = check(replaced).finding("bmff.elst.entries")
        assertEquals(FAIL, f.status, f.details.toString())
        assertTrue(f.details.any { it.contains("media_rate_fraction") })
    }

    @Test
    fun `tkhd duration off by 50 ticks warns without edts and is not applicable with edts`() {
        val f = check(MovieSpec(tkhdDurationDelta = 50)).finding("bmff.duration.consistency")
        assertEquals(WARN, f.status, f.details.toString())
        assertEquals(PASS, check(MovieSpec(tkhdDurationDelta = 1)).status("bmff.duration.consistency"))
        assertEquals(NOT_APPLICABLE, check(MovieSpec(tkhdDurationDelta = 50, elst = listOf(Triple(350L, 0L, 1))))
            .status("bmff.duration.consistency"))
    }

    @Test
    fun `timescale 0 never divides`() {
        val r = check(MovieSpec(mediaTimescale = 0))
        assertNull(r.inspectionError)
        assertEquals(NOT_CHECKED, r.status("bmff.duration.consistency"))
        val m = check(MovieSpec(movieTimescale = 0))
        assertNull(m.inspectionError)
        assertEquals(NOT_CHECKED, m.status("bmff.duration.consistency"))
    }

    @Test
    fun `fragmented movie passes fragmented and skips sample rules`() {
        val r = check(MovieSpec(extraMoov = listOf(box("mvex", fullBox("trex", 0, 0, ByteArray(20))))))
        val f = r.finding("bmff.fragmented")
        assertEquals(PASS, f.status)
        assertTrue(f.details.any { it.contains("mvex") })
        sampleIds.forEach { id -> assertEquals(NOT_APPLICABLE, r.status(id), id) }
        val moof = box("moof", fullBox("mfhd", 0, 0, be32(1)))
        val m = check(MovieSpec(trailing = moof))
        assertTrue(m.finding("bmff.fragmented").details.any { it.contains("moof") })
        sampleIds.forEach { id -> assertEquals(NOT_APPLICABLE, m.status(id), id) }
    }

    @Test
    fun `table cap is reported as not checked, not as a failure`() {
        val stsz = fullBox("stsz", 0, 0, be32(0), be32(2_000_001))
        val bytes = buildMovie(MovieSpec(replaceBox = mapOf("stsz" to stsz)))
        val r = check(bytes)
        assertNull(r.inspectionError)
        val f = r.finding("bmff.stbl.sample_count")
        assertEquals(NOT_CHECKED, f.status)
        assertTrue(f.details.any { it.contains("표 항목 상한 초과") }, f.details.toString())
        assertEquals(NOT_CHECKED, r.status("bmff.sample.location"))
        ByteReader.open(fileOf(bytes)).use { reader ->
            val tree = IsoBmffBoxTree.walk(reader)
            val ctx = BmffContext(tree, reader, BmffProfile.ISO)
            val box = ctx.allBoxes.single { it.type == "stsz" }
            val t = IsoBmffSampleRules.table(ctx, box, countAt = 8, entryBits = 32)
            assertTrue(t is IsoBmffSampleRules.Table.Skip && t.reason.contains("표 항목 상한 초과"), t.toString())
        }
    }

    @Test
    fun `stsz entry count larger than the box fails sample count`() {
        val stsz = fullBox("stsz", 0, 0, be32(0), be32(4), be32(10), be32(10), be32(10))
        assertEquals(FAIL, check(MovieSpec(replaceBox = mapOf("stsz" to stsz))).status("bmff.stbl.sample_count"))
    }

    private fun fixture(name: String) = File(javaClass.getResource("/isobmff-conformance/$name")!!.toURI())

    @Test
    fun `ffmpeg chapter fixtures pass the sample and edit rules`() {
        listOf("chapters.mp4", "chapters.m4a").forEach { name ->
            val r = ByteReader.open(fixture(name)).use { IsoBmffConformanceChecker.check(it) }
            assertNull(r.inspectionError, name)
            (allIds - "bmff.duration.consistency").forEach { id ->
                assertEquals(PASS, r.status(id), "$name $id ${r.finding(id).details}")
            }
            val d = r.finding("bmff.duration.consistency")
            assertTrue(d.status == PASS || d.status == NOT_APPLICABLE, "$name ${d.details}")
        }
    }

    private fun indexOf(hay: ByteArray, needle: ByteArray): Int {
        for (i in 0..hay.size - needle.size) if ((needle.indices).all { hay[i + it] == needle[it] }) return i
        error("not found")
    }
}
