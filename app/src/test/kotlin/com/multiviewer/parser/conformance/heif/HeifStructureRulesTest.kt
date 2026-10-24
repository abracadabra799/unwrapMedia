package com.multiviewer.parser.conformance.heif

import com.multiviewer.parser.ByteReader
import com.multiviewer.parser.conformance.ConformanceReport
import com.multiviewer.parser.conformance.ConformanceStatus.FAIL
import com.multiviewer.parser.conformance.ConformanceStatus.NOT_APPLICABLE
import com.multiviewer.parser.conformance.ConformanceStatus.NOT_CHECKED
import com.multiviewer.parser.conformance.ConformanceStatus.PASS
import com.multiviewer.parser.conformance.ConformanceStatus.WARN
import com.multiviewer.parser.conformance.isobmff.be16
import com.multiviewer.parser.conformance.isobmff.be32
import com.multiviewer.parser.conformance.isobmff.box
import com.multiviewer.parser.conformance.isobmff.fullBox
import com.multiviewer.parser.fileOf
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HeifStructureRulesTest {
    private val heifSource = "ISO/IEC 23008-12 (공개 자료 기반, 원문 미대조)"
    private val isoSource = "ISO/IEC 14496-12 (공개 자료 기반, 원문 미대조)"
    private val reusedIds = listOf("bmff.box.size", "bmff.box.size0", "bmff.box.trailing", "bmff.ftyp.position")
    private val structureIds = listOf(
        "heif.ftyp.mif1", "heif.ftyp.codec_brand", "heif.meta.present", "heif.meta.required", "heif.items.ids",
        "heif.items.primary", "heif.iloc.items", "heif.iloc.method", "heif.iloc.extents",
    )
    private val propertyIds = listOf(
        "heif.ipma.refs", "heif.props.ispe", "heif.props.codec_config", "heif.props.essential", "heif.props.unique",
        "heif.iref.targets", "heif.derived.grid", "heif.derived.inputs",
    )
    private val heifIds = structureIds + propertyIds
    private val avifIds = AvifRules.all.map { it.id }
    private val scopes = setOf("ftyp", "meta 구조 / meta structure", "항목 / Items", "항목 위치 / Item locations",
        "속성 / Properties", "참조 / References", "파생 항목 / Derived items", "AVIF")

    private fun check(bytes: ByteArray) = ByteReader.open(fileOf(bytes)).use { HeifConformanceChecker.check(it) }
    private fun check(spec: HeifSpec) = check(buildHeif(spec))
    private fun ConformanceReport.finding(id: String) = findings.single { it.rule.id == id }
    private fun ConformanceReport.status(id: String) = finding(id).status
    private fun ConformanceReport.assertClean() {
        val bad = findings.filter { it.status == FAIL || it.status == WARN }
        assertTrue(bad.isEmpty(), "unexpected findings: ${bad.map { it.rule.id to it.details }}")
        assertNull(inspectionError)
    }

    private val gridSpec = HeifSpec(
        items = listOf(HeifItem(1, "hvc1", hidden = true), HeifItem(2, "hvc1", hidden = true),
            HeifItem(10, "grid", data = gridData(1, 2, 128, 48), method = 1),
            HeifItem(20, "Exif"), HeifItem(30, "iden")),
        primary = 10,
        props = listOf(hvcC(), ispe(64, 48), ispe(128, 48)),
        assoc = listOf(1L to listOf(1 to true, 2 to false), 2L to listOf(1 to true, 2 to false), 10L to listOf(3 to false),
            30L to listOf(3 to false)),
        refs = listOf(Triple("dimg", 10L, listOf(1L, 2L)), Triple("cdsc", 20L, listOf(10L)), Triple("dimg", 30L, listOf(10L))),
    )

    @Test
    fun `default HEIC is clean with every rule emitted once`() {
        val report = check(HeifSpec())
        report.assertClean()
        assertEquals("HEIF", report.format)
        assertEquals("ISO/IEC 23008-12 phase 1", report.profile)
        (reusedIds + structureIds).forEach { id -> assertEquals(PASS, report.status(id), id) }
        assertEquals((reusedIds + heifIds + avifIds).size, report.findings.size)
        assertEquals((reusedIds + heifIds + avifIds).toSet(), report.findings.map { it.rule.id }.toSet())
        avifIds.forEach { id -> assertEquals(NOT_APPLICABLE, report.status(id), id) }
    }

    @Test
    fun `grid primary with hidden tiles, idat, Exif and iden items is clean`() {
        val report = check(gridSpec)
        report.assertClean()
        (structureIds + propertyIds - "heif.props.essential").forEach { id -> assertEquals(PASS, report.status(id), id) }
    }

    @Test
    fun `rules carry sources, scopes and unverified 23008-12 citations`() {
        val report = check(HeifSpec())
        reusedIds.forEach { id -> assertEquals(isoSource, report.finding(id).rule.source, id) }
        heifIds.forEach { id ->
            val rule = report.finding(id).rule
            assertEquals(heifSource, rule.source, id)
            assertTrue(rule.scope in scopes, "$id scope ${rule.scope}")
            assertTrue(rule.title.contains(" / "), rule.title)
            val ref = assertNotNull(rule.reference, id)
            assertTrue(ref.startsWith("ISO/IEC 23008-12 §") && ref.endsWith("(판에 따라 다를 수 있음)"), ref)
            val c = assertNotNull(rule.citation, id)
            assertEquals("ISO/IEC 23008-12:2025", c.document)
            assertNull(c.page)
            assertEquals(false, c.verified)
            assertTrue(c.url.startsWith("https://www.iso.org/standard/") && !c.url.contains("#"), c.url)
            assertTrue(c.section.isNotBlank())
        }
        assertEquals(heifIds.toSet(), HeifRules.all.map { it.id }.toSet())
    }

    @Test
    fun `mif1 missing from compatible brands warns`() {
        assertEquals(WARN, check(HeifSpec(compatible = listOf("heic"))).status("heif.ftyp.mif1"))
    }

    @Test
    fun `HEVC primary without a HEVC brand warns`() {
        val r = check(HeifSpec(major = "mif1", compatible = listOf("mif1")))
        assertEquals(WARN, r.status("heif.ftyp.codec_brand"))
        assertEquals(PASS, r.status("heif.ftyp.mif1"))
    }

    @Test
    fun `HEVC grid primary is judged by its tiles`() {
        val r = check(gridSpec.copy(major = "mif1", compatible = listOf("mif1")))
        assertEquals(WARN, r.status("heif.ftyp.codec_brand"))
    }

    @Test
    fun `non HEVC or AV1 primary codec brand is not applicable`() {
        val r = check(HeifSpec(major = "mif1", compatible = listOf("mif1"), items = listOf(HeifItem(1, "jpeg"))))
        assertEquals(NOT_APPLICABLE, r.status("heif.ftyp.codec_brand"))
    }

    @Test
    fun `two top-level metas fail meta present`() {
        assertEquals(FAIL, check(HeifSpec(extraTop = listOf(fullBox("meta", 0, 0)))).status("heif.meta.present"))
    }

    @Test
    fun `missing pitm or iloc fails meta required`() {
        assertEquals(FAIL, check(HeifSpec(dropMetaChild = "pitm")).status("heif.meta.required"))
        assertEquals(FAIL, check(HeifSpec(dropMetaChild = "iloc")).status("heif.meta.required"))
    }

    @Test
    fun `non pict handler fails meta required`() {
        assertEquals(FAIL, check(HeifSpec(handler = "vide")).status("heif.meta.required"))
    }

    @Test
    fun `duplicate dinf fails meta required`() {
        val dinf = box("dinf", fullBox("dref", 0, 0, be32(0)))
        assertEquals(FAIL, check(HeifSpec(extraMetaChildren = listOf(dinf, dinf))).status("heif.meta.required"))
        assertEquals(PASS, check(HeifSpec(extraMetaChildren = listOf(dinf))).status("heif.meta.required"))
    }

    @Test
    fun `duplicate item ids fail`() {
        val r = check(HeifSpec(items = listOf(HeifItem(1, "hvc1"), HeifItem(1, "Exif"))))
        assertEquals(FAIL, r.status("heif.items.ids"))
    }

    @Test
    fun `item id zero fails`() {
        val r = check(HeifSpec(items = listOf(HeifItem(1, "hvc1"), HeifItem(0, "Exif"))))
        assertEquals(FAIL, r.status("heif.items.ids"))
    }

    @Test
    fun `infe version 1 fails item ids`() {
        val r = check(HeifSpec(items = listOf(HeifItem(1, "hvc1", infeVersion = 1))))
        assertEquals(FAIL, r.status("heif.items.ids"))
    }

    @Test
    fun `item count over the cap is not checked`() {
        val iinf = fullBox("iinf", 1, 0, be32(2_000_000))
        val r = check(HeifSpec(dropMetaChild = "iinf", extraMetaChildren = listOf(iinf)))
        assertEquals(NOT_CHECKED, r.status("heif.items.ids"))
        assertEquals(NOT_CHECKED, r.status("heif.items.primary"))
        assertEquals(NOT_CHECKED, r.status("heif.iloc.items"))
    }

    @Test
    fun `primary naming an undeclared item fails`() {
        assertEquals(FAIL, check(HeifSpec(primary = 9)).status("heif.items.primary"))
    }

    @Test
    fun `hidden primary fails`() {
        assertEquals(FAIL, check(HeifSpec(items = listOf(HeifItem(1, "hvc1", hidden = true)))).status("heif.items.primary"))
    }

    @Test
    fun `metadata primary fails`() {
        val r = check(HeifSpec(items = listOf(HeifItem(1, "hvc1"), HeifItem(2, "Exif")), primary = 2))
        assertEquals(FAIL, r.status("heif.items.primary"))
    }

    @Test
    fun `unknown primary item type is never a pass`() {
        val r = check(HeifSpec(items = listOf(HeifItem(1, "zzzz"))))
        assertEquals(NOT_CHECKED, r.status("heif.items.primary"))
    }

    @Test
    fun `coded item without location fails`() {
        val r = check(HeifSpec(items = listOf(HeifItem(1, "hvc1"), HeifItem(2, "hvc1", data = null))))
        assertEquals(FAIL, r.status("heif.iloc.items"))
    }

    @Test
    fun `grid or Exif item without location fails but iden may omit it`() {
        val noGridData = gridSpec.copy(items = gridSpec.items.map { if (it.type == "grid") it.copy(data = null) else it })
        assertEquals(FAIL, check(noGridData).status("heif.iloc.items"))
        val noExif = gridSpec.copy(items = gridSpec.items.map { if (it.type == "Exif") it.copy(data = null) else it })
        assertEquals(FAIL, check(noExif).status("heif.iloc.items"))
        assertEquals(PASS, check(gridSpec).status("heif.iloc.items"))
    }

    @Test
    fun `iloc entry naming an undeclared item fails`() {
        val iloc = fullBox("iloc", 1, 0, byteArrayOf(0x44, 0), be16(1), be16(7), be16(0), be16(0), be16(1), be32(0), be32(4))
        val r = check(HeifSpec(items = listOf(HeifItem(1, "iden")), dropMetaChild = "iloc", extraMetaChildren = listOf(iloc)))
        assertEquals(FAIL, r.status("heif.iloc.items"))
    }

    @Test
    fun `iloc version 0 with an idat box warns about the unused idat`() {
        val r = check(HeifSpec(ilocVersion = 0, items = listOf(HeifItem(1, "hvc1", method = 1))))
        val f = r.finding("heif.iloc.method")
        assertEquals(WARN, f.status)
        assertTrue(f.details.any { "사용되지 않는 idat" in it }, f.details.toString())
    }

    /** iloc v1, zero-width fields: [entries] items with item_ID 1 and 65535 extents each (no extent bytes). */
    private fun zeroWidthIloc(entries: Int) = fullBox("iloc", 1, 0, byteArrayOf(0, 0), be16(entries),
        *Array(entries) { be16(1) + be16(0) + be16(0) + be16(0xFFFF) })

    @Test
    fun `extent count over the cap is not checked for method, items and extents`() {
        val r = check(HeifSpec(dropMetaChild = "iloc", extraMetaChildren = listOf(zeroWidthIloc(16))))
        assertEquals(NOT_CHECKED, r.status("heif.iloc.method"))
        assertEquals(NOT_CHECKED, r.status("heif.iloc.items"))
        assertEquals(NOT_CHECKED, r.status("heif.iloc.extents"))
    }

    @Test
    fun `truncated iloc is not a method pass`() {
        // entry_count 2 but only one entry present.
        val iloc = fullBox("iloc", 1, 0, byteArrayOf(0x44, 0), be16(2), be16(1), be16(0), be16(0), be16(1), be32(0), be32(4))
        val r = check(HeifSpec(dropMetaChild = "iloc", extraMetaChildren = listOf(iloc)))
        assertEquals(NOT_CHECKED, r.status("heif.iloc.method"))
    }

    @Test
    fun `trailing bytes after iloc entries warn instead of failing items`() {
        val good = buildHeif()
        // Rebuild with the same layout plus 2 trailing bytes inside iloc: offsets shift by 2, so use delta -… via manual iloc.
        val m = ByteReader.open(fileOf(good)).use { HeifItemModel.read(com.multiviewer.parser.conformance.isobmff.IsoBmffBoxTree.walk(it), it) }
        val ext = m.locations.single().extents.single()
        val iloc = fullBox("iloc", 1, 0, byteArrayOf(0x44, 0), be16(1), be16(1), be16(0), be16(0), be16(1),
            be32(ext.offset + 2), be32(ext.length), byteArrayOf(0, 0))
        val r = check(HeifSpec(dropMetaChild = "iloc", extraMetaChildren = listOf(iloc)))
        assertEquals(WARN, r.status("heif.iloc.items"), r.finding("heif.iloc.items").details.toString())
        assertEquals(PASS, r.status("heif.iloc.extents"))
    }

    @Test
    fun `unknown item type without location is not checked`() {
        val r = check(HeifSpec(items = listOf(HeifItem(1, "hvc1"), HeifItem(2, "zzzz", data = null))))
        assertEquals(NOT_CHECKED, r.status("heif.iloc.items"))
    }

    @Test
    fun `method 1 without idat fails method`() {
        val r = check(HeifSpec(items = listOf(HeifItem(1, "hvc1", method = 1)), dropMetaChild = "idat"))
        assertEquals(FAIL, r.status("heif.iloc.method"))
    }

    @Test
    fun `method 2 without iloc reference fails, with it passes`() {
        val spec = HeifSpec(items = listOf(HeifItem(1, "hvc1", method = 2)))
        assertEquals(FAIL, check(spec).status("heif.iloc.method"))
        val r = check(spec.copy(items = listOf(HeifItem(1, "hvc1", method = 2), HeifItem(2, "hvc1", hidden = true)),
            assoc = listOf(1L to listOf(1 to true, 2 to false), 2L to listOf(1 to true, 2 to false)),
            refs = listOf(Triple("iloc", 1L, listOf(2L)))))
        assertEquals(PASS, r.status("heif.iloc.method"))
        assertEquals(NOT_CHECKED, r.status("heif.iloc.extents"))
    }

    @Test
    fun `extent past end of file fails`() {
        val r = check(HeifSpec(ilocExtentDelta = 10_000))
        val f = r.finding("heif.iloc.extents")
        assertEquals(FAIL, f.status)
        assertTrue(f.ranges.isNotEmpty())
    }

    @Test
    fun `extent past idat fails`() {
        val iloc = fullBox("iloc", 1, 0, byteArrayOf(0x44, 0), be16(1), be16(1), be16(1), be16(0), be16(1), be32(4), be32(100))
        val r = check(HeifSpec(items = listOf(HeifItem(1, "hvc1", method = 1)), dropMetaChild = "iloc", extraMetaChildren = listOf(iloc)))
        assertEquals(FAIL, r.status("heif.iloc.extents"))
    }

    @Test
    fun `zero extents fails`() {
        val iloc = fullBox("iloc", 1, 0, byteArrayOf(0x44, 0), be16(1), be16(1), be16(0), be16(0), be16(0))
        val r = check(HeifSpec(dropMetaChild = "iloc", extraMetaChildren = listOf(iloc)))
        assertEquals(FAIL, r.status("heif.iloc.extents"))
    }

    @Test
    fun `second ftyp after meta fails ftyp position`() {
        val bytes = buildHeif() + box("ftyp", "heic".toByteArray(), be32(0), "mif1".toByteArray())
        assertEquals(FAIL, check(bytes).status("bmff.ftyp.position"))
    }

    @Test
    fun `appended mp42 motion-photo video after meta warns instead of failing ftyp position`() {
        // Samsung S26 layout: HEIF ftyp/meta/mdat, then a whole MP4 (ftyp mp42, mdat, moov) at top level.
        val video = box("ftyp", "mp42".toByteArray(), be32(0), "isom".toByteArray(), "mp42".toByteArray()) +
            box("mdat", ByteArray(8)) + box("moov")
        val r = check(buildHeif() + video)
        val f = r.finding("bmff.ftyp.position")
        assertEquals(WARN, f.status, f.details.toString())
        assertTrue(r.findings.none { it.status == FAIL }, r.findings.filter { it.status == FAIL }.map { it.rule.id }.toString())
        assertEquals(PASS, r.status("heif.ftyp.mif1"))
        assertEquals(PASS, r.status("heif.meta.present"))
    }

    private fun mp4(vararg extra: ByteArray, compat: List<String> = listOf("isom", "mp42")) =
        box("ftyp", "mp42".toByteArray(), be32(0), *compat.map { it.toByteArray() }.toTypedArray()) +
            box("mdat", ByteArray(8)) + box("moov") + extra.fold(ByteArray(0)) { a, b -> a + b }

    @Test
    fun `appended MP4 with its own top-level meta never reaches the HEIF model`() {
        val bytes = buildHeif() + mp4(fullBox("meta", 0, 0, box("free")))
        val r = check(bytes)
        assertTrue(r.findings.none { it.status == FAIL }, r.findings.filter { it.status == FAIL }.map { it.rule.id to it.details }.toString())
        assertEquals(PASS, r.status("heif.meta.present"))
        val m = ByteReader.open(fileOf(bytes)).use { HeifConformanceChecker.heifModel(it) }
        assertEquals(1, m.metaCount)
        assertEquals(emptyList(), m.problems)
    }

    @Test
    fun `appended ftyp listing an image brand past 64 brands still fails ftyp position`() {
        val compat = List(70) { "zz%02d".format(it) } + "heic"
        assertEquals(FAIL, check(buildHeif() + mp4(compat = compat)).status("bmff.ftyp.position"))
    }

    @Test
    fun `empty input is never a pass`() {
        val r = check(ByteArray(0))
        assertTrue(r.findings.none { it.status == PASS }, r.findings.map { it.rule.id to it.status }.toString())
    }

    @Test
    fun `AVIF brands with an av01 primary report AVIF format`() {
        val r = check(HeifSpec(major = "avif", compatible = listOf("avif", "mif1", "miaf"),
            items = listOf(HeifItem(1, "av01")), props = listOf(av1C(), ispe(64, 48))))
        assertEquals("AVIF", r.format)
        assertEquals("ISO/IEC 23008-12 + AVIF v1.2.0 phase 1", r.profile)
        assertEquals(PASS, r.status("heif.ftyp.codec_brand"))
    }

    @Test
    fun `macOS sips HEIC has no FAIL`() {
        val sips = File("/usr/bin/sips")
        assumeTrue(sips.exists())
        val png = File("src/test/resources/format-sniff/a.png").takeIf { it.exists() }
            ?: File("app/src/test/resources/format-sniff/a.png")
        assumeTrue(png.exists())
        val dir = Files.createTempDirectory("heif-sips-rules").toFile()
        try {
            val out = File(dir, "out.heic")
            val p = ProcessBuilder(sips.path, "-s", "format", "heic", png.absolutePath, "--out", out.absolutePath)
                .redirectErrorStream(true).redirectOutput(File(dir, "log.txt")).start()
            assumeTrue(p.waitFor(60, TimeUnit.SECONDS) && p.exitValue() == 0 && out.length() > 0)
            val r = ByteReader.open(out).use { HeifConformanceChecker.check(it) }
            r.assertClean()
        } finally {
            dir.deleteRecursively()
        }
    }
}
