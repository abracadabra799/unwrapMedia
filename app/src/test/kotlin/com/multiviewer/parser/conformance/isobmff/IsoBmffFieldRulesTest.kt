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
    fun `hdlr pre_defined mhlr warns in ISO, other non-zero fails, mhlr passes in QuickTime`() {
        val iso = check(MovieSpec(handlerPreDefined = "mhlr")).finding("bmff.hdlr.predefined")
        assertEquals(WARN, iso.status)
        assertTrue(iso.details.any { it.contains("QuickTime 계열 component type") }, iso.details.toString())
        assertEquals(FAIL, check(MovieSpec(handlerPreDefined = "abcd")).status("bmff.hdlr.predefined"))
        assertEquals(FAIL, check(MovieSpec(handlerPreDefined = "dhlr")).status("bmff.hdlr.predefined"))
        assertEquals(WARN, check(MovieSpec(qtDataHandler = true)).status("bmff.hdlr.predefined"))
        assertEquals(FAIL, check(MovieSpec(qtDataHandler = true, qtDataHandlerType = "mhlr")).status("bmff.hdlr.predefined"))
        assertEquals(FAIL, check(qtSpec.copy(qtDataHandlerType = "mhlr")).status("bmff.hdlr.predefined"))
        assertEquals(FAIL, check(qtSpec.copy(qtDataHandlerType = "\u0000\u0000\u0000\u0000")).status("bmff.hdlr.predefined"))
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
        assertEquals(PASS, check(qtSpec.copy(language = 0x0003)).status("bmff.mdhd.language"))
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

    private fun ctxOf(bytes: ByteArray, block: (BmffContext) -> Unit) = ByteReader.open(fileOf(bytes)).use { reader ->
        block(BmffContext(IsoBmffBoxTree.walk(reader), reader, BmffProfile.ISO))
    }
    private val selfUrl = fullBox("url ", 0, 1)
    private val extUrl = fullBox("url ", 0, 0) + "http://x/\u0000".toByteArray()

    @Test
    fun `self-containment follows each sample entry's data_reference_index`() {
        val two = listOf(selfUrl, extUrl)
        ctxOf(buildMovie(MovieSpec(drefEntries = two, sampleEntryRefs = listOf(1)))) { assertEquals(true, it.trackDataSelfContained(it.traks.single())) }
        ctxOf(buildMovie(MovieSpec(drefEntries = two, sampleEntryRefs = listOf(2)))) { assertEquals(false, it.trackDataSelfContained(it.traks.single())) }
        ctxOf(buildMovie(MovieSpec(drefEntries = two, sampleEntryRefs = listOf(1, 2)))) { assertNull(it.trackDataSelfContained(it.traks.single())) }
        ctxOf(buildMovie(MovieSpec(drefEntries = listOf(selfUrl, selfUrl), sampleEntryRefs = listOf(1, 2)))) { assertEquals(true, it.trackDataSelfContained(it.traks.single())) }
        ctxOf(buildMovie(MovieSpec(sampleEntryRefs = listOf(0)))) { assertNull(it.trackDataSelfContained(it.traks.single())) }
        // first dref entry self-contained but the sample entry uses the external one → external
        val ext = check(MovieSpec(drefEntries = two, sampleEntryRefs = listOf(2))).finding("bmff.dref.entries")
        assertEquals(PASS, ext.status)
        assertTrue(ext.details.any { it.contains("외부 데이터 참조") }, ext.details.toString())
        assertEquals(PASS, check(MovieSpec(drefEntries = two, sampleEntryRefs = listOf(1, 2))).status("bmff.dref.entries"))
    }

    @Test
    fun `data_reference_index 0 or past the dref fails naming track and index`() {
        listOf(0, 2).forEach { idx ->
            val f = check(MovieSpec(sampleEntryRefs = listOf(idx))).finding("bmff.dref.entries")
            assertEquals(FAIL, f.status, "index $idx")
            assertTrue(f.details.any { it.contains("trak #1") && it.contains("data_reference_index $idx") }, f.details.toString())
        }
    }

    @Test
    fun `version 1 headers pass with their fields read at version 1 offsets`() {
        val r = check(MovieSpec(mvhdVersion = 1, tkhdVersion = 1, mdhdVersion = 1, movieTimescale = 600, mediaTimescale = 90000,
            trackId = 7, nextTrackId = 9, language = 0x15C7 /* "eng" */))
        assertNull(r.inspectionError)
        fieldIds.forEach { id -> assertEquals(PASS, r.status(id), "$id ${r.finding(id).details}") }
        assertTrue(r.finding("bmff.mvhd.timescale").details.single().endsWith("timescale 600"))
        assertTrue(r.finding("bmff.mdhd.timescale").details.single().endsWith("timescale 90000"))
        assertTrue(r.finding("bmff.tkhd.track_id").details.single().endsWith("track_ID 7"))
        assertTrue(r.finding("bmff.mvhd.next_track_id").details.single().contains("next_track_ID 9 > 최대 track_ID 7"))
        assertTrue(r.finding("bmff.mdhd.language").details.single().contains("'eng'"))
        assertEquals(FAIL, check(MovieSpec(mvhdVersion = 1, tkhdVersion = 1, trackId = 7, nextTrackId = 7)).status("bmff.mvhd.next_track_id"))
    }

    @Test
    fun `next_track_ID is read from the defined field, not the box end`() {
        // mvhd v0 with 4 extra bytes after next_track_ID (= 2): end - 4 would read 0xFFFFFFFF.
        val mvhd = fullBox("mvhd", 0, 0, be32(0), be32(0), be32(1000), be32(300), be32(0x00010000), be16(0x0100), ByteArray(10),
            ByteArray(36), ByteArray(24), be32(1), be32(0xFFFFFFFFL))
        assertEquals(FAIL, check(MovieSpec(replaceBox = mapOf("mvhd" to mvhd))).status("bmff.mvhd.next_track_id"))
    }

    @Test
    fun `truncated mvhd, tkhd and mdhd fail their fields without an inspection error`() {
        val m = check(MovieSpec(replaceBox = mapOf("mvhd" to fullBox("mvhd", 0, 0, ByteArray(8)))))
        assertNull(m.inspectionError)
        assertEquals(FAIL, m.status("bmff.mvhd.timescale"))
        assertEquals(FAIL, m.status("bmff.mvhd.next_track_id"))
        val t = check(MovieSpec(replaceBox = mapOf("tkhd" to fullBox("tkhd", 0, 3, ByteArray(8)))))
        assertNull(t.inspectionError)
        assertEquals(FAIL, t.status("bmff.tkhd.track_id"))
        val d = check(MovieSpec(replaceBox = mapOf("mdhd" to fullBox("mdhd", 0, 0, ByteArray(12)))))
        assertNull(d.inspectionError)
        assertEquals(FAIL, d.status("bmff.mdhd.timescale"))
        assertEquals(NOT_CHECKED, d.status("bmff.mdhd.language"))
        val d2 = check(MovieSpec(replaceBox = mapOf("mdhd" to fullBox("mdhd", 0, 0, be32(0), be32(0), be32(1000), be32(300)))))
        assertEquals(PASS, d2.status("bmff.mdhd.timescale"))
        assertEquals(NOT_CHECKED, d2.status("bmff.mdhd.language"))
    }

    @Test
    fun `stsd sample entries must be at least 16 bytes and fit`() {
        val past = fullBox("stsd", 0, 0, be32(1), be32(200) + "avc1".toByteArray() + ByteArray(6) + be16(1) + ByteArray(70))
        assertEquals(FAIL, check(MovieSpec(replaceBox = mapOf("stsd" to past))).status("bmff.stsd.entries"))
        val tiny = fullBox("stsd", 0, 0, be32(1), box("avc1", ByteArray(4)))
        assertEquals(FAIL, check(MovieSpec(replaceBox = mapOf("stsd" to tiny))).status("bmff.stsd.entries"))
        val huge = fullBox("stsd", 0, 0, be32(3_000_000), box("avc1", ByteArray(6), be16(1), ByteArray(70)))
        assertEquals(FAIL, check(MovieSpec(replaceBox = mapOf("stsd" to huge))).status("bmff.stsd.entries"))
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
