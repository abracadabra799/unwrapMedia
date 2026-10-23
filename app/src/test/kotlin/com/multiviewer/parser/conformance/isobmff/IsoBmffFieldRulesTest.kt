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

class IsoBmffFieldRulesTest {
    private val fieldIds = listOf(
        "bmff.fullbox.version", "bmff.mvhd.timescale", "bmff.mdhd.timescale", "bmff.tkhd.track_id",
        "bmff.mvhd.next_track_id", "bmff.vmhd.flags", "bmff.hdlr.predefined", "bmff.mdhd.language",
        "bmff.dref.entries", "bmff.stsd.entries",
    )

    private fun check(bytes: ByteArray) = ByteReader.open(fileOf(bytes)).use { IsoBmffConformanceChecker.check(it) }
    private fun check(spec: MovieSpec) = check(buildMovie(spec))
    private fun ConformanceReport.finding(id: String) = findings.single { it.rule.id == id }
    private fun ConformanceReport.status(id: String) = finding(id).status

    private val qtFtyp = box("ftyp", "qt  ".toByteArray(), be32(0x200), "qt  ".toByteArray())
    private val qtSpec = MovieSpec(ftyp = qtFtyp, handlerPreDefined = "mhlr", qtDataHandler = true,
        extraTopLevelBefore = listOf(box("wide")), language = 0x7FFF)

    @Test
    fun `default movie passes every header field rule once with the header scope`() {
        val r = check(MovieSpec())
        assertNull(r.inspectionError)
        fieldIds.forEach { id ->
            val f = r.finding(id)
            assertEquals(PASS, f.status, "$id ${f.details}")
            assertEquals("헤더 필드 / Header fields", f.rule.scope, id)
            assertEquals("ISO/IEC 14496-12 (공개 자료 기반, 원문 미대조)", f.rule.source, id)
        }
    }

    @Test
    fun `QuickTime default movie passes every header field rule`() {
        val r = check(qtSpec)
        assertEquals("QuickTime", r.format)
        fieldIds.forEach { id -> assertEquals(PASS, r.status(id), "$id ${r.finding(id).details}") }
        assertTrue(r.findings.all { it.rule.source == "QuickTime File Format (공개 자료 기반, 원문 미대조)" })
    }

    @Test
    fun `mvhd version 2 fails the version rule only`() {
        val r = check(MovieSpec(mvhdVersion = 2))
        assertNull(r.inspectionError)
        assertEquals(FAIL, r.status("bmff.fullbox.version"))
        assertTrue(r.finding("bmff.fullbox.version").details.any { it.contains("mvhd") && it.contains("version 2") })
        assertEquals(NOT_CHECKED, r.status("bmff.mvhd.timescale"))
        assertEquals(NOT_CHECKED, r.status("bmff.mvhd.next_track_id"))
    }

    @Test
    fun `zero timescales fail without an inspection error`() {
        val m = check(MovieSpec(movieTimescale = 0))
        assertNull(m.inspectionError)
        assertEquals(FAIL, m.status("bmff.mvhd.timescale"))
        assertEquals(PASS, m.status("bmff.mdhd.timescale"))
        val d = check(MovieSpec(mediaTimescale = 0))
        assertNull(d.inspectionError)
        assertEquals(FAIL, d.status("bmff.mdhd.timescale"))
        assertEquals(PASS, d.status("bmff.mvhd.timescale"))
    }

    @Test
    fun `track_ID zero and duplicates fail`() {
        assertEquals(FAIL, check(MovieSpec(trackId = 0)).status("bmff.tkhd.track_id"))
        val dup = check(MovieSpec(secondTrackId = 1))
        assertEquals(FAIL, dup.status("bmff.tkhd.track_id"))
        assertTrue(dup.finding("bmff.tkhd.track_id").details.any { it.contains("중복") })
        assertEquals(PASS, check(MovieSpec(secondTrackId = 2, nextTrackId = 3)).status("bmff.tkhd.track_id"))
    }

    @Test
    fun `next_track_ID must exceed every track_ID, all-ones accepted`() {
        assertEquals(FAIL, check(MovieSpec(nextTrackId = 1)).status("bmff.mvhd.next_track_id"))
        assertEquals(FAIL, check(MovieSpec(secondTrackId = 2, nextTrackId = 2)).status("bmff.mvhd.next_track_id"))
        val none = check(MovieSpec(nextTrackId = 0xFFFFFFFFL)).finding("bmff.mvhd.next_track_id")
        assertEquals(PASS, none.status)
        assertTrue(none.details.any { it.contains("0xFFFFFFFF") })
    }

    @Test
    fun `vmhd flags 0 fails, no vmhd is not applicable`() {
        assertEquals(FAIL, check(MovieSpec(mediaHeader = fullBox("vmhd", 0, 0, ByteArray(8)))).status("bmff.vmhd.flags"))
        val audio = MovieSpec(handler = "soun", mediaHeader = fullBox("smhd", 0, 0, ByteArray(4)))
        assertEquals(NOT_APPLICABLE, check(audio).status("bmff.vmhd.flags"))
    }

    @Test
    fun `hdlr pre_defined mhlr fails in ISO, passes in QuickTime`() {
        assertEquals(FAIL, check(MovieSpec(handlerPreDefined = "mhlr")).status("bmff.hdlr.predefined"))
        assertEquals(PASS, check(qtSpec).status("bmff.hdlr.predefined"))
        // QuickTime: mdia hdlr must say mhlr
        assertEquals(FAIL, check(qtSpec.copy(handlerPreDefined = "\u0000\u0000\u0000\u0000")).status("bmff.hdlr.predefined"))
        assertEquals(FAIL, check(qtSpec.copy(handlerPreDefined = "dhlr")).status("bmff.hdlr.predefined"))
    }

    @Test
    fun `language packing per profile`() {
        assertEquals(PASS, check(MovieSpec(language = 0x55C4)).status("bmff.mdhd.language"))
        assertEquals(WARN, check(MovieSpec(language = 0)).status("bmff.mdhd.language"))
        assertEquals(WARN, check(MovieSpec(language = 0x7FFF)).status("bmff.mdhd.language"))
        assertEquals(PASS, check(qtSpec.copy(language = 0)).status("bmff.mdhd.language"))
        assertEquals(PASS, check(qtSpec.copy(language = 0x7FFF)).status("bmff.mdhd.language"))
        assertEquals(PASS, check(qtSpec.copy(language = 0x55C4)).status("bmff.mdhd.language"))
        listOf(0xD5C4, 0x8000).forEach { v ->
            assertEquals(WARN, check(MovieSpec(language = v)).status("bmff.mdhd.language"), "ISO $v")
            assertEquals(WARN, check(qtSpec.copy(language = v)).status("bmff.mdhd.language"), "QT $v")
        }
    }

    @Test
    fun `external dref passes with a detail, empty dref and stsd fail`() {
        val bytes = buildMovie(MovieSpec(drefEntry = fullBox("url ", 0, 0)))
        val ext = check(bytes).finding("bmff.dref.entries")
        assertEquals(PASS, ext.status)
        assertTrue(ext.details.any { it.contains("외부 데이터 참조") }, ext.details.toString())
        ByteReader.open(fileOf(bytes)).use { reader ->
            val ctx = BmffContext(IsoBmffBoxTree.walk(reader), reader, BmffProfile.ISO)
            assertEquals(false, ctx.trackDataSelfContained(ctx.traks.single()))
        }
        ByteReader.open(fileOf(buildMovie())).use { reader ->
            val ctx = BmffContext(IsoBmffBoxTree.walk(reader), reader, BmffProfile.ISO)
            assertEquals(true, ctx.trackDataSelfContained(ctx.traks.single()))
        }
        assertEquals(FAIL, check(MovieSpec(drefEntryCount = 0)).status("bmff.dref.entries"))
        assertEquals(FAIL, check(MovieSpec(stsdEntryCount = 0)).status("bmff.stsd.entries"))
    }

    @Test
    fun `dref and stsd entries must fit`() {
        val badDref = MovieSpec(drefEntry = be32(40) + "url ".toByteArray() + be32(1))
        assertEquals(FAIL, check(badDref).status("bmff.dref.entries"))
    }

    private fun fixture(name: String) = File(javaClass.getResource("/isobmff-conformance/$name")!!.toURI())

    @Test
    fun `ffmpeg chapter fixtures pass header fields except the chapter track language`() {
        listOf("chapters.mp4", "chapters.m4a").forEach { name ->
            val r = ByteReader.open(fixture(name)).use { IsoBmffConformanceChecker.check(it) }
            assertNull(r.inspectionError, name)
            fieldIds.filter { it != "bmff.mdhd.language" && it != "bmff.vmhd.flags" }
                .forEach { id -> assertEquals(PASS, r.status(id), "$name $id ${r.finding(id).details}") }
            assertEquals(NOT_APPLICABLE, r.status("bmff.vmhd.flags"), name)
            // ffmpeg writes language 0 on its text chapter track (audio track: 'und').
            assertEquals(WARN, r.status("bmff.mdhd.language"), name)
            assertEquals(1, r.finding("bmff.mdhd.language").details.count { it.contains("0x0000") }, name)
        }
    }
}
