package com.multiviewer.parser.conformance.isobmff

import com.multiviewer.parser.ByteReader
import com.multiviewer.parser.conformance.ConformanceReport
import com.multiviewer.parser.conformance.ConformanceStatus.FAIL
import com.multiviewer.parser.conformance.ConformanceStatus.NOT_APPLICABLE
import com.multiviewer.parser.conformance.ConformanceStatus.NOT_CHECKED
import com.multiviewer.parser.conformance.ConformanceStatus.PASS
import com.multiviewer.parser.conformance.ConformanceStatus.WARN
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Real ffmpeg-default files and byte-level corruptions of v.mp4, made by
 * docs/validation/isobmff-conformance-fixtures/generate.sh (+ corrupt.py).
 */
class IsoBmffFixtureTest {
    private fun fixture(name: String) = File(javaClass.getResource("/isobmff-conformance/$name")!!.toURI())
    private fun check(name: String) = ByteReader.open(fixture(name)).use { IsoBmffConformanceChecker.check(it) }
    private fun ConformanceReport.status(id: String) = findings.single { it.rule.id == id }.status
    private fun ConformanceReport.ids(vararg s: com.multiviewer.parser.conformance.ConformanceStatus) =
        findings.filter { it.status in s }.map { it.rule.id }.toSet()
    private fun ConformanceReport.dump() = findings.filter { it.status != PASS }.map { "${it.status} ${it.rule.id} ${it.details}" }

    @Test
    fun `ffmpeg default files have no FAIL and no WARN`() {
        // Every ffmpeg file has an edit list (tkhd/mdhd duration comparison is not applicable); M4A has no vmhd.
        val expected = mapOf(
            "v.mp4" to Triple("ISO-BMFF", "isom", setOf("bmff.duration.consistency")),
            "v.mov" to Triple("QuickTime", "qt  ", setOf("bmff.duration.consistency")),
            "a.m4a" to Triple("ISO-BMFF", "M4A ", setOf("bmff.duration.consistency", "bmff.vmhd.flags")),
            "v.3gp" to Triple("ISO-BMFF", "3gp4", setOf("bmff.duration.consistency")),
        )
        expected.forEach { (name, e) ->
            val (format, brand, notApplicable) = e
            val r = check(name)
            assertEquals(brand, fixture(name).readBytes().copyOfRange(8, 12).toString(Charsets.US_ASCII), name)
            assertEquals(format, r.format, name)
            assertNull(r.inspectionError, name)
            assertEquals(emptySet(), r.ids(FAIL, WARN, NOT_CHECKED), "$name: ${r.dump()}")
            assertEquals(notApplicable, r.ids(NOT_APPLICABLE), "$name: ${r.dump()}")
            assertEquals(32, r.findings.size, name)
        }
    }

    /** file → targeted rule, plus FAILs that are direct consequences of the same corruption (none observed). */
    private val corrupted = mapOf(
        "no-moov.mp4" to ("bmff.moov.count" to emptySet<String>()),
        "dup-mvhd.mp4" to ("bmff.mvhd.count" to emptySet()),
        "track-id-0.mp4" to ("bmff.tkhd.track_id" to emptySet()),
        "stts-mismatch.mp4" to ("bmff.stbl.sample_count" to emptySet()),
        "stsc-gap.mp4" to ("bmff.stsc.order" to emptySet()),
        "stco-past-eof.mp4" to ("bmff.sample.location" to emptySet()),
        "vmhd-flags-0.mp4" to ("bmff.vmhd.flags" to emptySet()),
        "size0-nested.mp4" to ("bmff.box.size0" to emptySet()),
        "trailing-garbage.mp4" to ("bmff.box.trailing" to emptySet()),
        // 2nd trak's type → 00 01 02 03: the audio trak and udta after it are not boxes inside moov.
        "bad-type-nested.mp4" to ("bmff.box.size" to emptySet()),
    )

    @Test
    fun `each corrupted file fails exactly its targeted rule`() {
        corrupted.forEach { (name, e) ->
            val (target, allowed) = e
            val r = check(name)
            assertNull(r.inspectionError, name)
            assertEquals(FAIL, r.status(target), "$name: ${r.dump()}")
            val unexpected = r.ids(FAIL) - target - allowed
            assertTrue(unexpected.isEmpty(), "$name unexpected FAIL $unexpected: ${r.dump()}")
            assertEquals(emptySet(), r.ids(WARN), "$name: ${r.dump()}")
        }
    }

    @Test
    fun `fixtures stay tiny`() {
        (listOf("v.mp4", "v.mov", "a.m4a", "v.3gp", "frag.mp4") + corrupted.keys).forEach { name ->
            assertTrue(fixture(name).length() < 20_000, "$name is ${fixture(name).length()} bytes")
        }
    }

    @Test
    fun `renamed moov leaves dependent rules not checked rather than passing`() {
        val r = check("no-moov.mp4")
        listOf("bmff.mvhd.count", "bmff.trak.count", "bmff.trak.required", "bmff.tkhd.track_id", "bmff.stbl.sample_count",
            "bmff.sample.location").forEach { assertEquals(NOT_CHECKED, r.status(it), it) }
    }

    @Test
    fun `stsc gap stops coverage and sample location from being computed for that track`() {
        val r = check("stsc-gap.mp4")
        assertEquals(NOT_CHECKED, r.status("bmff.stsc.coverage"))
        assertEquals(NOT_CHECKED, r.status("bmff.sample.location"))
    }

    @Test
    fun `non box bytes inside moov are reported, never an overall PASS`() {
        val r = check("bad-type-nested.mp4")
        assertTrue(r.overall != PASS, r.dump().toString())
        val f = r.findings.single { it.rule.id == "bmff.box.size" }
        assertTrue(f.details.any { it.contains("바이트가 moov 안에서 박스로 해석되지 않습니다 @0x") }, f.details.toString())
        assertEquals(1, f.ranges.size)
    }

    @Test
    fun `ffmpeg fragmented MP4 fails only next_track_ID and skips sample tables`() {
        // ffmpeg +frag_keyframe+empty_moov writes next_track_ID 2 with track_IDs 1 and 2 (bytes checked with xxd);
        // reported as FAIL by decision (strict: next_track_ID must exceed every track_ID).
        val r = check("frag.mp4")
        assertNull(r.inspectionError)
        assertEquals("ISO-BMFF", r.format)
        val frag = r.findings.single { it.rule.id == "bmff.fragmented" }
        assertEquals(PASS, frag.status)
        assertTrue(frag.details.any { it.startsWith("조각화 파일: ") && it.contains("mvex") && it.contains("moof") }, frag.details.toString())
        listOf("bmff.stbl.sample_count", "bmff.stsc.order", "bmff.stsc.coverage", "bmff.ctts.count", "bmff.stss.order",
            "bmff.sample.location", "bmff.mdat.present", "bmff.duration.consistency")
            .forEach { assertEquals(NOT_APPLICABLE, r.status(it), it) }
        assertEquals(setOf("bmff.mvhd.next_track_id"), r.ids(FAIL), r.dump().toString())
        val next = r.findings.single { it.rule.id == "bmff.mvhd.next_track_id" }
        assertTrue(next.details.any { it.contains("next_track_ID 2") }, next.details.toString())
        assertEquals(FAIL, r.overall)
    }
}
