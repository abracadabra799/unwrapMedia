package com.multiviewer.parser.conformance.isobmff

import com.multiviewer.parser.ByteReader
import com.multiviewer.parser.conformance.ConformanceReport
import com.multiviewer.parser.conformance.ConformanceStatus
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

class IsoBmffStructureRulesTest {
    private val isoSource = "ISO/IEC 14496-12 (공개 자료 기반, 원문 미대조)"
    private val qtSource = "QuickTime File Format (공개 자료 기반, 원문 미대조)"

    private val structureIds = listOf(
        "bmff.box.size", "bmff.box.size0", "bmff.box.trailing", "bmff.ftyp.present", "bmff.ftyp.position",
        "bmff.ftyp.brands", "bmff.moov.count", "bmff.mvhd.count", "bmff.trak.count", "bmff.trak.required",
        "bmff.mediaheader.match", "bmff.box.parent",
    )

    private fun check(bytes: ByteArray) = ByteReader.open(fileOf(bytes)).use { IsoBmffConformanceChecker.check(it) }
    private fun check(spec: MovieSpec) = check(buildMovie(spec))
    private fun ConformanceReport.finding(id: String) = findings.single { it.rule.id == id }
    private fun ConformanceReport.status(id: String) = finding(id).status
    private fun ConformanceReport.assertClean() {
        val bad = findings.filter { it.status == FAIL || it.status == WARN }
        assertTrue(bad.isEmpty(), "unexpected findings: ${bad.map { it.rule.id to it.details }}")
        assertNull(inspectionError)
    }
    private fun profileOf(bytes: ByteArray) =
        ByteReader.open(fileOf(bytes)).use { IsoBmffConformanceChecker.profileOf(IsoBmffBoxTree.walk(it), it) }

    private val qtFtyp = box("ftyp", "qt  ".toByteArray(), be32(0x200), "qt  ".toByteArray())
    private val qtSpec = MovieSpec(ftyp = qtFtyp, handlerPreDefined = "mhlr", qtDataHandler = true,
        extraTopLevelBefore = listOf(box("wide")), language = 0x7FFF)

    @Test
    fun `default movie is clean ISO with every structure rule emitted once`() {
        val bytes = buildMovie()
        val report = check(bytes)
        report.assertClean()
        assertEquals(BmffProfile.ISO, profileOf(bytes))
        assertEquals("ISO-BMFF", report.format)
        assertEquals("ISO/IEC 14496-12 phase 1", report.profile)
        assertTrue(report.findings.all { it.rule.source == isoSource })
        structureIds.forEach { id -> assertEquals(PASS, report.status(id), id) }
    }

    @Test
    fun `catalog titles are bilingual and scopes are group labels`() {
        val scopes = setOf("박스 구조 / Box structure", "ftyp", "필수 박스 / Required boxes", "헤더 필드 / Header fields",
            "샘플 테이블 / Sample tables", "편집 목록 / Edit lists")
        assertTrue(structureIds.all { id -> IsoBmffRules.all.any { it.id == id } })
        IsoBmffRules.all.forEach { def ->
            assertTrue(def.scope in scopes, def.id)
            val rule = IsoBmffRules.rule(def, BmffProfile.QUICKTIME)
            assertEquals(qtSource, rule.source)
            assertTrue(rule.title.contains(" / "), def.id)
            assertTrue(rule.reference!!.startsWith("QTFF: "), def.id)
            assertTrue(IsoBmffRules.rule(def, BmffProfile.ISO).reference!!.startsWith("ISO/IEC 14496-12 §"), def.id)
        }
    }

    @Test
    fun `missing moov fails moov count`() {
        assertEquals(FAIL, check(MovieSpec(dropBox = "moov")).status("bmff.moov.count"))
    }

    @Test
    fun `duplicate moov fails moov count`() {
        val bytes = buildMovie()
        val moov = buildMovie().let { b -> ByteReader.open(fileOf(b)).use { r -> IsoBmffBoxTree.walk(r).top.last() } }
        val twice = bytes + bytes.copyOfRange(moov.offset.toInt(), moov.end.toInt())
        assertEquals(FAIL, check(twice).status("bmff.moov.count"))
    }

    @Test
    fun `second mvhd fails mvhd count`() {
        val mvhd = fullBox("mvhd", 0, 0, ByteArray(96))
        assertEquals(FAIL, check(MovieSpec(extraMoov = listOf(mvhd))).status("bmff.mvhd.count"))
    }

    @Test
    fun `missing mvhd fails mvhd count`() {
        assertEquals(FAIL, check(MovieSpec(dropBox = "mvhd")).status("bmff.mvhd.count"))
    }

    @Test
    fun `missing trak fails trak count`() {
        assertEquals(FAIL, check(MovieSpec(dropBox = "trak")).status("bmff.trak.count"))
    }

    @Test
    fun `missing stsz stco dinf hdlr each fail trak required naming the box`() {
        listOf("stsz", "stco", "dinf", "hdlr", "tkhd", "mdhd", "stts", "stsc", "stsd").forEach { type ->
            val f = check(MovieSpec(dropBox = type)).finding("bmff.trak.required")
            assertEquals(FAIL, f.status, type)
            assertTrue(f.details.any { it.contains(type) }, "$type: ${f.details}")
        }
    }

    @Test
    fun `missing media header fails trak required`() {
        val f = check(MovieSpec(mediaHeader = null)).finding("bmff.trak.required")
        assertEquals(FAIL, f.status)
    }

    @Test
    fun `duplicate stss fails trak required`() {
        val stss = fullBox("stss", 0, 0, be32(1), be32(1))
        // A second stss placed in moov would be a parent violation; build a second stss inside stbl instead.
        val bytes = buildMovie(MovieSpec(stss = listOf(1)))
        val withTwo = injectIntoStbl(bytes, stss)
        val f = check(withTwo).finding("bmff.trak.required")
        assertEquals(FAIL, f.status)
        assertTrue(f.details.any { it.contains("stss") })
    }

    @Test
    fun `sound handler with vmhd fails media header match and smhd passes`() {
        assertEquals(FAIL, check(MovieSpec(handler = "soun")).status("bmff.mediaheader.match"))
        val ok = check(MovieSpec(handler = "soun", mediaHeader = fullBox("smhd", 0, 0, ByteArray(4))))
        assertEquals(PASS, ok.status("bmff.mediaheader.match"))
        ok.assertClean()
    }

    @Test
    fun `other handler requires nmhd in ISO`() {
        assertEquals(FAIL, check(MovieSpec(handler = "meta")).status("bmff.mediaheader.match"))
        assertEquals(PASS, check(MovieSpec(handler = "meta", mediaHeader = fullBox("nmhd", 0, 0))).status("bmff.mediaheader.match"))
    }

    @Test
    fun `vmhd inside stbl fails parent rule`() {
        val f = check(MovieSpec(vmhdInStbl = true)).finding("bmff.box.parent")
        assertEquals(FAIL, f.status)
        assertTrue(f.details.any { it.contains("vmhd") })
        assertTrue(f.ranges.isNotEmpty())
    }

    @Test
    fun `mvhd at top level fails parent rule`() {
        val mvhd = fullBox("mvhd", 0, 0, ByteArray(96))
        assertEquals(FAIL, check(MovieSpec(extraTopLevelBefore = listOf(mvhd))).status("bmff.box.parent"))
    }

    @Test
    fun `unknown boxes pass parent rule with an ignore detail`() {
        val f = check(MovieSpec(extraMoov = listOf(box("zzzz", ByteArray(4))))).finding("bmff.box.parent")
        assertEquals(PASS, f.status)
        assertTrue(f.details.any { it.contains("알 수 없는 박스(무시 허용)") && it.contains("zzzz") })
    }

    @Test
    fun `ffmpeg MP4 shape with free and udta meta is clean`() {
        val ilst = box("ilst", box("©too", box("data", be32(1), be32(0), "Lavf".toByteArray())))
        val meta = fullBox("meta", 0, 0, fullBox("hdlr", 0, 0, ByteArray(4), "mdir".toByteArray(), "appl".toByteArray(), ByteArray(8), byteArrayOf(0)), ilst)
        check(MovieSpec(extraTopLevelBefore = listOf(box("free")), extraMoov = listOf(box("udta", meta)))).assertClean()
    }

    @Test
    fun `Samsung MP4 shape with meta in moov and custom udta atoms is clean`() {
        val meta = fullBox("meta", 0, 0, fullBox("hdlr", 0, 0, ByteArray(4), "mdta".toByteArray(), ByteArray(12), byteArrayOf(0)),
            box("keys", be32(0), be32(0)), box("ilst"))
        val udta = box("udta", box("SDLN", "SEQ_PLAY".toByteArray()), box("smrd", "TRUEBLUE".toByteArray()),
            box("©xyz", be16(5), be16(0), "+37.5+127.0/".toByteArray()), box("smta", ByteArray(12)))
        val samsungFtyp = box("ftyp", "mp42".toByteArray(), be32(0), "isommp42".toByteArray())
        check(MovieSpec(ftyp = samsungFtyp, extraMoov = listOf(meta, udta))).assertClean()
    }

    @Test
    fun `missing ftyp warns in ISO`() {
        assertEquals(WARN, check(MovieSpec(ftyp = null)).status("bmff.ftyp.present"))
    }

    @Test
    fun `ftyp after moov fails position`() {
        val r = check(MovieSpec(ftyp = null, trailing = box("ftyp", "isom".toByteArray(), be32(0))))
        assertEquals(FAIL, r.status("bmff.ftyp.position"))
    }

    @Test
    fun `free before ftyp is allowed`() {
        val spec = MovieSpec(ftyp = null, extraTopLevelBefore = listOf(box("free"), box("ftyp", "isom".toByteArray(), be32(0), "isom".toByteArray())))
        val r = check(spec)
        assertEquals(PASS, r.status("bmff.ftyp.position"))
        r.assertClean()
    }

    @Test
    fun `ftyp with odd size fails brands`() {
        val ftyp = box("ftyp", "isom".toByteArray(), be32(0), "ab".toByteArray())
        assertEquals(FAIL, check(MovieSpec(ftyp = ftyp)).status("bmff.ftyp.brands"))
    }

    @Test
    fun `ftyp with non printable brand fails brands`() {
        val ftyp = box("ftyp", "isom".toByteArray(), be32(0), byteArrayOf(0, 1, 2, 3))
        assertEquals(FAIL, check(MovieSpec(ftyp = ftyp)).status("bmff.ftyp.brands"))
    }

    @Test
    fun `ftyp without an ISO brand warns`() {
        val ftyp = box("ftyp", "zzzz".toByteArray(), be32(0), "zzzz".toByteArray())
        assertEquals(WARN, check(MovieSpec(ftyp = ftyp)).status("bmff.ftyp.brands"))
    }

    @Test
    fun `M4A and 3GP brands are accepted`() {
        listOf("M4A " to "M4A isomiso2", "3gp4" to "isom3gp4", "mp42" to "mp42mp41", "isml" to "ismlpiff", "piff" to "piff").forEach { (major, compat) ->
            val ftyp = box("ftyp", major.toByteArray(), be32(0), compat.toByteArray())
            assertEquals(PASS, check(MovieSpec(ftyp = ftyp)).status("bmff.ftyp.brands"), major)
        }
    }

    @Test
    fun `trailing non box bytes fail`() {
        val f = check(MovieSpec(trailing = ByteArray(20) { 0x0E })).finding("bmff.box.trailing")
        assertEquals(FAIL, f.status)
        assertEquals(20, f.ranges.single().count())
    }

    @Test
    fun `top level size zero box passes size0`() {
        val r = check(MovieSpec(trailing = be32(0) + "free".toByteArray()))
        assertEquals(PASS, r.status("bmff.box.size0"))
        r.assertClean()
    }

    @Test
    fun `nested size zero box fails size0`() {
        val f = check(MovieSpec(extraMoov = listOf(be32(0) + "free".toByteArray()))).finding("bmff.box.size0")
        assertEquals(FAIL, f.status)
        assertTrue(f.details.any { it.contains("moov") })
    }

    @Test
    fun `box overrunning its parent fails box size`() {
        val bad = be32(1000) + "free".toByteArray() + ByteArray(4)
        val f = check(MovieSpec(extraMoov = listOf(bad))).finding("bmff.box.size")
        assertEquals(FAIL, f.status)
        assertTrue(f.ranges.isNotEmpty())
    }

    @Test
    fun `QuickTime movie is clean with QT profile and source`() {
        val bytes = buildMovie(qtSpec)
        assertEquals(BmffProfile.QUICKTIME, profileOf(bytes))
        val report = check(bytes)
        report.assertClean()
        assertEquals("QuickTime", report.format)
        assertEquals("QuickTime File Format phase 1", report.profile)
        assertTrue(report.findings.all { it.rule.source == qtSource })
        structureIds.forEach { id -> assertEquals(PASS, report.status(id), id) }
    }

    @Test
    fun `QuickTime movie without ftyp is QT and ftyp present passes as optional`() {
        val bytes = buildMovie(qtSpec.copy(ftyp = null))
        assertEquals(BmffProfile.QUICKTIME, profileOf(bytes))
        val f = check(bytes).finding("bmff.ftyp.present")
        assertEquals(PASS, f.status)
        assertTrue(f.details.any { it.contains("QuickTime: 선택 사항") })
    }

    @Test
    fun `wide alone without ftyp selects QT`() {
        assertEquals(BmffProfile.QUICKTIME, profileOf(buildMovie(MovieSpec(ftyp = null, extraTopLevelBefore = listOf(box("wide"))))))
        assertEquals(BmffProfile.ISO, profileOf(buildMovie(MovieSpec(ftyp = null))))
    }

    @Test
    fun `single data handler hdlr inside minf warns in ISO and a second one fails`() {
        val one = check(MovieSpec(qtDataHandler = true)).finding("bmff.box.parent")
        assertEquals(WARN, one.status)
        assertTrue(one.details.any { it.contains("QuickTime 계열 data handler hdlr") })
        val dhlr = fullBox("hdlr", 0, 0, "dhlr".toByteArray(), "url ".toByteArray(), ByteArray(12), byteArrayOf(0))
        val two = injectInto(buildMovie(MovieSpec(qtDataHandler = true)), listOf("trak", "mdia", "minf"), dhlr)
        assertEquals(FAIL, check(two).status("bmff.box.parent"))
    }

    @Test
    fun `ISO text track with gmhd passes required boxes and warns on media header`() {
        val gmhd = box("gmhd", fullBox("gmin", 0, 0, ByteArray(12)))
        val r = check(MovieSpec(handler = "text", mediaHeader = gmhd))
        assertEquals(PASS, r.status("bmff.trak.required"))
        assertEquals(PASS, r.status("bmff.box.parent"))
        val f = r.finding("bmff.mediaheader.match")
        assertEquals(WARN, f.status)
        assertTrue(f.details.any { it.contains("QuickTime 계열 gmhd (ISO/IEC 14496-12는 nmhd)") })
    }

    @Test
    fun `ISO video handler with gmhd still fails media header match`() {
        val gmhd = box("gmhd", fullBox("gmin", 0, 0, ByteArray(12)))
        assertEquals(FAIL, check(MovieSpec(mediaHeader = gmhd)).status("bmff.mediaheader.match"))
    }

    private fun fixture(name: String) = File(javaClass.getResource("/isobmff-conformance/$name")!!.toURI())

    @Test
    fun `ffmpeg default MP4 and M4A with a chapter track only warn on gmhd and chapter language`() {
        listOf("chapters.mp4", "chapters.m4a").forEach { name ->
            val r = ByteReader.open(fixture(name)).use { IsoBmffConformanceChecker.check(it) }
            assertEquals("ISO-BMFF", r.format, name)
            assertNull(r.inspectionError, name)
            assertTrue(r.findings.none { it.status == FAIL }, "$name: ${r.findings.filter { it.status == FAIL }.map { it.rule.id to it.details }}")
            // gmhd chapter track (structure) + ffmpeg's language 0 on that text track (header fields).
            assertEquals(listOf("bmff.mediaheader.match", "bmff.mdhd.language"), r.findings.filter { it.status == WARN }.map { it.rule.id }, name)
            structureIds.filter { it != "bmff.mediaheader.match" }.forEach { id -> assertEquals(PASS, r.status(id), "$name $id") }
        }
    }

    @Test
    fun `QuickTime wide inside a container is allowed`() {
        assertEquals(PASS, check(qtSpec.copy(extraMoov = listOf(box("wide")))).status("bmff.box.parent"))
    }

    @Test
    fun `brand cap adds a detail line`() {
        val compat = ByteArray(4 * 4097) { "isom"[it % 4].code.toByte() }
        val f = check(MovieSpec(ftyp = box("ftyp", "isom".toByteArray(), be32(0), compat))).finding("bmff.ftyp.brands")
        assertEquals(PASS, f.status)
        assertTrue(f.details.any { it.contains("4096") && it.contains("4097") }, f.details.toString())
    }

    @Test
    fun `QuickTime reference movie without trak is not applicable for trak count`() {
        val rmra = box("rmra", box("rmda", box("rdrf", be32(0), "url ".toByteArray(), be32(4), "a.mo".toByteArray())))
        val f = check(qtSpec.copy(dropBox = "trak", extraMoov = listOf(rmra))).finding("bmff.trak.count")
        assertEquals(NOT_APPLICABLE, f.status)
        assertTrue(f.details.any { it.contains("rmra") })
    }

    @Test
    fun `mvhd and trak counts are not checked without moov`() {
        val r = check(MovieSpec(dropBox = "moov"))
        assertEquals(NOT_CHECKED, r.status("bmff.mvhd.count"))
        assertEquals(NOT_CHECKED, r.status("bmff.trak.count"))
    }

    @Test
    fun `duplicate ftyp fails position`() {
        val dup = box("ftyp", "isom".toByteArray(), be32(0), "isom".toByteArray())
        assertEquals(FAIL, check(MovieSpec(extraTopLevelBefore = listOf(dup))).status("bmff.ftyp.position"))
    }

    @Test
    fun `QuickTime file with a non ISO brand has no brand warning`() {
        val ftyp = box("ftyp", "qt  ".toByteArray(), be32(0), "zzzz".toByteArray())
        assertEquals(PASS, check(qtSpec.copy(ftyp = ftyp)).status("bmff.ftyp.brands"))
    }

    @Test
    fun `meta udta and sbgp inside traf pass parent rule`() {
        val traf = box("traf", fullBox("tfhd", 0, 0, be32(1)), box("udta"), fullBox("meta", 0, 0),
            fullBox("sbgp", 0, 0, "roll".toByteArray(), be32(0)))
        val moof = box("moof", fullBox("mfhd", 0, 0, be32(1)), traf)
        assertEquals(PASS, check(MovieSpec(trailing = moof)).status("bmff.box.parent"))
    }

    @Test
    fun `QuickTime gmhd is accepted for a non audio video handler`() {
        val gmhd = box("gmhd", fullBox("gmin", 0, 0, ByteArray(12)))
        val r = check(qtSpec.copy(handler = "tmcd", mediaHeader = gmhd))
        assertEquals(PASS, r.status("bmff.mediaheader.match"))
        assertEquals(PASS, r.status("bmff.box.parent"))
    }

    @Test
    fun `empty input is not a pass`() {
        val r = check(ByteArray(0))
        assertTrue(r.overall != PASS)
        assertTrue(r.findings.none { it.rule.id == "bmff.moov.count" && it.status == PASS })
    }

    @Test
    fun `garbage input does not throw`() {
        val r = check(ByteArray(64) { (it * 37).toByte() })
        assertTrue(r.overall == FAIL || r.overall == ConformanceStatus.NOT_CHECKED)
    }

    /** Inserts [extra] at the end of stbl, fixing the stbl/minf/mdia/trak/moov sizes. */
    private fun injectIntoStbl(bytes: ByteArray, extra: ByteArray) = injectInto(bytes, listOf("trak", "mdia", "minf", "stbl"), extra)

    /** Inserts [extra] at the end of the box reached from moov via [path], fixing every enclosing size. */
    private fun injectInto(bytes: ByteArray, path: List<String>, extra: ByteArray): ByteArray {
        val tree = ByteReader.open(fileOf(bytes)).use { IsoBmffBoxTree.walk(it) }
        val moov = tree.top.single { it.type == "moov" }
        val chain = ArrayList<BmffBox>()
        var cur: BmffBox? = moov
        for (t in path) { chain += cur!!; cur = cur.children.single { it.type == t } }
        chain += cur!!
        val out = (bytes.copyOfRange(0, cur.end.toInt()) + extra + bytes.copyOfRange(cur.end.toInt(), bytes.size))
        for (b in chain) {
            val newSize = b.size + extra.size
            be32(newSize).copyInto(out, b.offset.toInt())
        }
        return out
    }

    @Test
    fun `non box bytes inside a nested container fail box size`() {
        val garbage = be32(16) + byteArrayOf(0, 1, 2, 3) + ByteArray(8)
        val bytes = buildMovie(MovieSpec(extraMoov = listOf(garbage)))
        val f = check(bytes).finding("bmff.box.size")
        assertEquals(FAIL, f.status, f.details.toString())
        val start = (bytes.size - 16).toLong()
        assertTrue(f.details.any { it == "16바이트가 moov 안에서 박스로 해석되지 않습니다 @0x%X".format(start) }, f.details.toString())
        assertEquals(listOf(start until start + 16), f.ranges)
    }

    @Test
    fun `a 4-byte zero terminator inside udta and junk inside meta are tolerated`() {
        val udta = box("udta", box("name", "x".toByteArray()), ByteArray(4))
        val meta = box("meta", ByteArray(4), fullBox("hdlr", 0, 0, ByteArray(4), "mdir".toByteArray(), ByteArray(12)), ByteArray(12) { 0x0E })
        val f = check(MovieSpec(extraMoov = listOf(udta, meta))).finding("bmff.box.size")
        assertEquals(PASS, f.status, f.details.toString())
    }
}
