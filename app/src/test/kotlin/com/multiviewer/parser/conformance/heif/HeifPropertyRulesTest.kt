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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HeifPropertyRulesTest {
    private fun check(spec: HeifSpec) = ByteReader.open(fileOf(buildHeif(spec))).use { HeifConformanceChecker.check(it) }
    private fun ConformanceReport.finding(id: String) = findings.single { it.rule.id == id }
    private fun ConformanceReport.status(id: String) = finding(id).status
    private fun ConformanceReport.assertClean() {
        val bad = findings.filter { it.status == FAIL || it.status == WARN }
        assertTrue(bad.isEmpty(), "unexpected findings: ${bad.map { it.rule.id to it.details }}")
        assertNull(inspectionError)
    }
    private fun assertStatus(expected: com.multiviewer.parser.conformance.ConformanceStatus, r: ConformanceReport, id: String) =
        assertEquals(expected, r.status(id), "$id: ${r.finding(id).details}")

    private val tileProps = listOf(1 to true, 2 to false)

    /** 1×2 grid (item 10) of two 64×48 hvc1 tiles; props: 1 hvcC, 2 ispe 64×48, 3 ispe 128×48. */
    private fun grid(
        descriptor: ByteArray = gridData(1, 2, 128, 48), tiles: List<Long> = listOf(1L, 2L),
        tileIspe: List<Int> = listOf(2, 2), gridIspe: Int = 3,
    ) = HeifSpec(
        items = tiles.map { HeifItem(it, "hvc1", hidden = true) } + HeifItem(10, "grid", data = descriptor, method = 1),
        primary = 10,
        props = listOf(hvcC(), ispe(64, 48), ispe(128, 48), ispe(32, 48)),
        assoc = tiles.mapIndexed { i, t -> t to listOf(1 to true, tileIspe[i] to false) } + (10L to listOf(gridIspe to false)),
        refs = listOf(Triple("dimg", 10L, tiles)),
    )

    @Test
    fun `default HEIC passes the property rules and has nothing to judge for references or derived items`() {
        val r = check(HeifSpec())
        r.assertClean()
        listOf("heif.ipma.refs", "heif.props.ispe", "heif.props.codec_config", "heif.props.unique").forEach { assertStatus(PASS, r, it) }
        listOf("heif.props.essential", "heif.iref.targets", "heif.derived.grid", "heif.derived.inputs")
            .forEach { assertStatus(NOT_APPLICABLE, r, it) }
    }

    @Test
    fun `valid grid passes the grid and input rules`() {
        val r = check(grid())
        r.assertClean()
        listOf("heif.ipma.refs", "heif.props.ispe", "heif.iref.targets", "heif.derived.grid", "heif.derived.inputs")
            .forEach { assertStatus(PASS, r, it) }
    }

    @Test
    fun `ipma naming an undeclared item fails`() {
        val r = check(HeifSpec(assoc = listOf(1L to tileProps, 7L to listOf(2 to false))))
        assertStatus(FAIL, r, "heif.ipma.refs")
        assertTrue(r.finding("heif.ipma.refs").ranges.isNotEmpty())
    }

    @Test
    fun `property index past ipco fails`() {
        assertStatus(FAIL, check(HeifSpec(assoc = listOf(1L to listOf(1 to true, 2 to false, 3 to false)))), "heif.ipma.refs")
    }

    @Test
    fun `property index 0 means no property and is allowed`() {
        assertStatus(PASS, check(HeifSpec(assoc = listOf(1L to listOf(1 to true, 2 to false, 0 to false)))), "heif.ipma.refs")
    }

    @Test
    fun `ipma item ids out of order fail`() {
        val spec = HeifSpec(items = listOf(HeifItem(1, "hvc1"), HeifItem(2, "hvc1", hidden = true)),
            assoc = listOf(2L to tileProps, 1L to tileProps))
        assertStatus(FAIL, check(spec), "heif.ipma.refs")
    }

    @Test
    fun `item listed twice in ipma fails`() {
        assertStatus(FAIL, check(HeifSpec(assoc = listOf(1L to listOf(1 to true), 1L to listOf(2 to false)))), "heif.ipma.refs")
    }

    @Test
    fun `truncated ipma fails refs and leaves the property rules unchecked`() {
        // entry_count 2, only one entry.
        val ipma = fullBox("ipma", 0, 0, be32(2), be16(1), byteArrayOf(2, 0x81.toByte(), 0x02))
        val ipco = com.multiviewer.parser.conformance.isobmff.box("ipco", hvcC(), ispe(64, 48))
        val iprp = com.multiviewer.parser.conformance.isobmff.box("iprp", ipco, ipma)
        val r = check(HeifSpec(dropMetaChild = "iprp", extraMetaChildren = listOf(iprp)))
        assertStatus(FAIL, r, "heif.ipma.refs")
        assertStatus(NOT_CHECKED, r, "heif.props.ispe")
        assertStatus(NOT_CHECKED, r, "heif.props.codec_config")
    }

    @Test
    fun `item without ispe fails`() {
        assertStatus(FAIL, check(HeifSpec(assoc = listOf(1L to listOf(1 to true)))), "heif.props.ispe")
    }

    @Test
    fun `item with two ispe fails`() {
        val r = check(HeifSpec(props = listOf(hvcC(), ispe(64, 48), ispe(64, 48)), assoc = listOf(1L to listOf(1 to true, 2 to false, 3 to false))))
        assertStatus(FAIL, r, "heif.props.ispe")
        assertStatus(WARN, r, "heif.props.unique")
    }

    @Test
    fun `file without iprp fails ispe and codec config but does not pass ipma refs`() {
        val r = check(HeifSpec(dropMetaChild = "iprp"))
        assertStatus(FAIL, r, "heif.props.ispe")
        assertStatus(FAIL, r, "heif.props.codec_config")
        assertTrue(r.status("heif.ipma.refs") != PASS)
    }

    @Test
    fun `hvc1 item without hvcC fails codec config`() {
        assertStatus(FAIL, check(HeifSpec(assoc = listOf(1L to listOf(2 to false)))), "heif.props.codec_config")
    }

    @Test
    fun `jpeg item codec config is not applicable`() {
        val r = check(HeifSpec(major = "mif1", compatible = listOf("mif1"), items = listOf(HeifItem(1, "jpeg"))))
        assertStatus(NOT_APPLICABLE, r, "heif.props.codec_config")
    }

    @Test
    fun `av01 in an AVIF file uses the verified AVIF citation and av01 without av1C fails`() {
        val avif = HeifSpec(major = "avif", compatible = listOf("avif", "mif1", "miaf"),
            items = listOf(HeifItem(1, "av01")), props = listOf(av1C(), ispe(64, 48)))
        val ok = check(avif)
        val f = ok.finding("heif.props.codec_config")
        assertEquals(PASS, f.status, f.details.toString())
        val c = assertNotNull(f.rule.citation)
        assertEquals("§2.1 AV1 Image Item", c.section)
        assertEquals("https://aomediacodec.github.io/av1-avif/v1.2.0.html#image-item", c.url)
        assertEquals(true, c.verified)
        assertNull(c.page)
        assertStatus(FAIL, check(avif.copy(props = listOf(hvcC(), ispe(64, 48)))), "heif.props.codec_config")
        // HEIC keeps the unverified 23008-12 citation.
        assertEquals("ISO/IEC 23008-12:2025", check(HeifSpec()).finding("heif.props.codec_config").rule.citation?.document)
    }

    @Test
    fun `non essential irot warns, essential irot passes`() {
        val props = listOf(hvcC(), ispe(64, 48), irot(1))
        assertStatus(WARN, check(HeifSpec(props = props, assoc = listOf(1L to listOf(1 to true, 2 to false, 3 to false)))), "heif.props.essential")
        assertStatus(PASS, check(HeifSpec(props = props, assoc = listOf(1L to listOf(1 to true, 2 to false, 3 to true)))), "heif.props.essential")
    }

    @Test
    fun `two irot on one item warn unique`() {
        val props = listOf(hvcC(), ispe(64, 48), irot(1), irot(2))
        val r = check(HeifSpec(props = props, assoc = listOf(1L to listOf(1 to true, 2 to false, 3 to true, 4 to true))))
        assertStatus(WARN, r, "heif.props.unique")
    }

    @Test
    fun `colr uniqueness is per colour type`() {
        val props = listOf(hvcC(), ispe(64, 48), colrNclx(), colrIcc(), colrNclx())
        assertStatus(PASS, check(HeifSpec(props = props, assoc = listOf(1L to listOf(1 to true, 2 to false, 3 to false, 4 to false)))), "heif.props.unique")
        assertStatus(WARN, check(HeifSpec(props = props, assoc = listOf(1L to listOf(1 to true, 2 to false, 3 to false, 5 to false)))), "heif.props.unique")
    }

    @Test
    fun `thmb from an undeclared item fails`() {
        assertStatus(FAIL, check(HeifSpec(refs = listOf(Triple("thmb", 9L, listOf(1L))))), "heif.iref.targets")
    }

    @Test
    fun `auxl source without auxC fails, with auxC passes`() {
        val items = listOf(HeifItem(1, "hvc1"), HeifItem(2, "hvc1", hidden = true))
        val spec = HeifSpec(items = items, props = listOf(hvcC(), ispe(64, 48), auxC("urn:mpeg:mpegB:cicp:systems:auxiliary:alpha")),
            assoc = listOf(1L to tileProps, 2L to tileProps), refs = listOf(Triple("auxl", 2L, listOf(1L))))
        assertStatus(FAIL, check(spec), "heif.iref.targets")
        assertStatus(PASS, check(spec.copy(assoc = listOf(1L to tileProps, 2L to tileProps + (3 to true)))), "heif.iref.targets")
    }

    @Test
    fun `thumbnail of an Exif item fails`() {
        val spec = HeifSpec(items = listOf(HeifItem(1, "hvc1"), HeifItem(2, "Exif"), HeifItem(3, "hvc1")),
            assoc = listOf(1L to tileProps, 3L to tileProps), refs = listOf(Triple("thmb", 3L, listOf(2L))))
        assertStatus(FAIL, check(spec), "heif.iref.targets")
    }

    @Test
    fun `grid with more tiles than rows times columns fails`() {
        val r = check(grid(tiles = listOf(1L, 2L, 3L), tileIspe = listOf(2, 2, 2)))
        assertStatus(FAIL, r, "heif.derived.grid")
    }

    @Test
    fun `grid tiles with different ispe fail`() {
        assertStatus(FAIL, check(grid(tileIspe = listOf(2, 4))), "heif.derived.grid")
    }

    @Test
    fun `tiles that do not cover the output size fail`() {
        // 300×48 output from two 64×48 tiles (128 wide); grid ispe matches the output.
        val spec = grid(descriptor = gridData(1, 2, 300, 48)).let {
            it.copy(props = it.props + ispe(300, 48), assoc = it.assoc.dropLast(1) + (10L to listOf(5 to false)))
        }
        val f = check(spec).finding("heif.derived.grid")
        assertEquals(FAIL, f.status)
        assertTrue(f.details.any { "덮지" in it }, f.details.toString())
    }

    @Test
    fun `grid ispe different from the output size fails`() {
        assertStatus(FAIL, check(grid(gridIspe = 2)), "heif.derived.grid")
    }

    @Test
    fun `grid descriptor version 1 fails`() {
        assertStatus(FAIL, check(grid(descriptor = byteArrayOf(1, 0, 0, 1) + be16(128) + be16(48))), "heif.derived.grid")
    }

    @Test
    fun `grid descriptor with 32-bit sizes passes and a truncated one fails`() {
        assertStatus(PASS, check(grid(descriptor = byteArrayOf(0, 1, 0, 1) + be32(128) + be32(48))), "heif.derived.grid")
        assertStatus(FAIL, check(grid(descriptor = byteArrayOf(0, 1, 0, 1) + be16(128) + be16(48))), "heif.derived.grid")
    }

    @Test
    fun `tmap with one input fails`() {
        val spec = HeifSpec(items = listOf(HeifItem(1, "hvc1"), HeifItem(2, "tmap")), primary = 1,
            assoc = listOf(1L to tileProps, 2L to listOf(2 to false)), refs = listOf(Triple("dimg", 2L, listOf(1L))))
        assertStatus(FAIL, check(spec), "heif.derived.inputs")
        assertStatus(PASS, check(spec.copy(items = spec.items + HeifItem(3, "hvc1", hidden = true),
            assoc = spec.assoc + (3L to tileProps), refs = listOf(Triple("dimg", 2L, listOf(1L, 3L))))), "heif.derived.inputs")
    }

    @Test
    fun `iovl with a missing input fails`() {
        val base = HeifSpec(items = listOf(HeifItem(1, "hvc1", hidden = true), HeifItem(2, "iovl")), primary = 2,
            assoc = listOf(1L to tileProps, 2L to listOf(2 to false)))
        assertStatus(FAIL, check(base), "heif.derived.inputs")
        assertStatus(FAIL, check(base.copy(refs = listOf(Triple("dimg", 2L, listOf(1L, 8L))))), "heif.derived.inputs")
        assertStatus(PASS, check(base.copy(refs = listOf(Triple("dimg", 2L, listOf(1L))))), "heif.derived.inputs")
    }

    @Test
    fun `property and reference rules carry 23008-12 citations and scopes`() {
        val r = check(HeifSpec())
        val scopes = mapOf("heif.ipma.refs" to "속성 / Properties", "heif.props.ispe" to "속성 / Properties",
            "heif.props.codec_config" to "속성 / Properties", "heif.props.essential" to "속성 / Properties",
            "heif.props.unique" to "속성 / Properties", "heif.iref.targets" to "참조 / References",
            "heif.derived.grid" to "파생 항목 / Derived items", "heif.derived.inputs" to "파생 항목 / Derived items")
        scopes.forEach { (id, scope) ->
            val rule = r.finding(id).rule
            assertEquals(scope, rule.scope, id)
            assertEquals("ISO/IEC 23008-12 (공개 자료 기반, 원문 미대조)", rule.source, id)
            assertEquals("ISO/IEC 23008-12:2025", rule.citation?.document, id)
            assertEquals(false, rule.citation?.verified, id)
        }
    }

    /**
     * Samsung Galaxy HEIC shape (scaled down): hidden tiles under a 2×2 grid primary, a thumbnail with thmb, a hidden
     * aux (gain-map) grid via auxl with auxC, tmap over [grid, aux grid], Exif + mime cdsc items, every ispe essential.
     */
    @Test
    fun `Samsung shaped HEIC has no FAIL and no WARN`() {
        val tiles = (1L..4L).map { HeifItem(it, "hvc1", hidden = true) }
        val auxTiles = (43L..46L).map { HeifItem(it, "hvc1", hidden = true) }
        val items = tiles + HeifItem(41, "grid", data = gridData(2, 2, 128, 96), method = 1) +
            HeifItem(42, "hvc1") + auxTiles +
            HeifItem(47, "grid", hidden = true, data = gridData(2, 2, 128, 96), method = 1) +
            HeifItem(48, "tmap", data = ByteArray(20) { 1 }, method = 1) +
            HeifItem(49, "Exif") + HeifItem(50, "mime") + HeifItem(51, "mime")
        // 1 hvcC tile, 2 ispe tile, 3 colr, 4 ispe grid, 5 irot, 6 pixi, 7 hvcC thumb, 8 ispe thumb, 9 auxC, 10 hvcC aux, 11 ispe aux
        val props = listOf(hvcC(), ispe(64, 48), colrNclx(), ispe(128, 96), irot(0), pixi(8, 8, 8), hvcC(), ispe(32, 24),
            auxC("urn:com:photo:aux:hdrgainmap"), hvcC(), ispe(64, 48))
        val assoc = (1L..4L).map { it to listOf(3 to true, 1 to true, 2 to true) } +
            (41L to listOf(3 to true, 4 to true, 5 to true, 6 to false)) +
            (42L to listOf(3 to true, 7 to true, 8 to true, 5 to true, 6 to false)) +
            (43L..46L).map { it to listOf(10 to true, 11 to true) } +
            (47L to listOf(3 to true, 4 to true, 5 to true, 6 to false, 9 to true)) +
            (48L to listOf(3 to true, 4 to true, 5 to true, 6 to false))
        val refs = listOf(Triple("dimg", 41L, (1L..4L).toList()), Triple("thmb", 42L, listOf(41L)),
            Triple("auxl", 47L, listOf(41L)), Triple("dimg", 47L, (43L..46L).toList()), Triple("dimg", 48L, listOf(41L, 47L)),
            Triple("cdsc", 49L, listOf(41L)), Triple("cdsc", 50L, listOf(41L)), Triple("cdsc", 51L, listOf(47L)))
        val r = check(HeifSpec(compatible = listOf("mif1", "heic", "tmap"), items = items, primary = 41, props = props,
            assoc = assoc, refs = refs))
        r.assertClean()
        listOf("heif.ipma.refs", "heif.props.ispe", "heif.props.codec_config", "heif.props.essential", "heif.props.unique",
            "heif.iref.targets", "heif.derived.grid", "heif.derived.inputs").forEach { assertStatus(PASS, r, it) }
    }

    // ---- review fixes ----

    private val avifSpec = HeifSpec(major = "avif", compatible = listOf("avif", "mif1", "miaf"),
        items = listOf(HeifItem(1, "av01")), props = listOf(av1C(), ispe(64, 48)))

    @Test
    fun `AVIF codec config carries the AVIF source together with the AVIF citation`() {
        val rule = check(avifSpec).finding("heif.props.codec_config").rule
        assertEquals("AVIF v1.2.0 (AOMedia)", rule.source)
        assertEquals("§2.1 AV1 Image Item", rule.citation?.section)
        assertEquals(true, rule.citation?.verified)
        val heic = check(HeifSpec()).finding("heif.props.codec_config").rule
        assertEquals("ISO/IEC 23008-12 (공개 자료 기반, 원문 미대조)", heic.source)
        assertEquals("ISO/IEC 23008-12:2025", heic.citation?.document)
    }

    @Test
    fun `AVIF-branded file without av01 items keeps the HEIF source and citation`() {
        val r = check(avifSpec.copy(items = listOf(HeifItem(1, "hvc1")), props = listOf(hvcC(), ispe(64, 48))))
        val rule = r.finding("heif.props.codec_config").rule
        assertEquals("ISO/IEC 23008-12 (공개 자료 기반, 원문 미대조)", rule.source)
        assertEquals("ISO/IEC 23008-12:2025", rule.citation?.document)
    }

    /** iprp with props [hvcC, ispe] and one ipma box per list of item_IDs (each item gets hvcC! + ispe). */
    private fun multiIpma(vararg boxes: List<Int>): HeifSpec {
        val ipmas = boxes.map { ids ->
            fullBox("ipma", 0, 0, be32(ids.size.toLong()), *ids.map { be16(it) + byteArrayOf(2, 0x81.toByte(), 0x02) }.toTypedArray())
        }
        val iprp = box("iprp", box("ipco", hvcC(), ispe(64, 48)), *ipmas.toTypedArray())
        return HeifSpec(items = listOf(HeifItem(1, "hvc1"), HeifItem(2, "hvc1", hidden = true), HeifItem(3, "hvc1", hidden = true)),
            dropMetaChild = "iprp", extraMetaChildren = listOf(iprp))
    }

    @Test
    fun `ipma order is checked per box`() {
        assertStatus(PASS, check(multiIpma(listOf(1, 2), listOf(3))), "heif.ipma.refs")
        assertStatus(PASS, check(multiIpma(listOf(3), listOf(1, 2))), "heif.ipma.refs")
        assertStatus(FAIL, check(multiIpma(listOf(2, 1), listOf(3))), "heif.ipma.refs")
        assertStatus(FAIL, check(multiIpma(listOf(1, 2), listOf(2, 3))), "heif.ipma.refs")
    }

    /** iinf whose entry_count (2) does not match its single infe: items may be missing. */
    private val shortIinf = fullBox("iinf", 0, 0, be16(2), fullBox("infe", 2, 0, be16(1), be16(0), "hvc1".toByteArray(), byteArrayOf(0)))

    @Test
    fun `incomplete iinf leaves derived rules unchecked instead of not applicable`() {
        val r = check(HeifSpec(dropMetaChild = "iinf", extraMetaChildren = listOf(shortIinf)))
        assertStatus(NOT_CHECKED, r, "heif.derived.grid")
        assertStatus(NOT_CHECKED, r, "heif.derived.inputs")
    }

    private fun refRulesUnchecked(r: ConformanceReport) =
        listOf("heif.iref.targets", "heif.derived.grid", "heif.derived.inputs").forEach { assertStatus(NOT_CHECKED, r, it) }

    @Test
    fun `item count cap leaves reference and derived rules unchecked`() {
        val iinf = fullBox("iinf", 1, 0, be32(2_000_000))
        refRulesUnchecked(check(grid().copy(dropMetaChild = "iinf", extraMetaChildren = listOf(iinf))))
    }

    @Test
    fun `iref with a broken child box leaves reference and derived rules unchecked`() {
        val iref = fullBox("iref", 0, 0, box("dimg", be16(10), be16(2), be16(1), be16(2)), be32(100), "thmb".toByteArray())
        refRulesUnchecked(check(grid().copy(refs = emptyList(), extraMetaChildren = listOf(iref))))
    }

    @Test
    fun `unreadable reference box leaves reference and derived rules unchecked`() {
        val iref = fullBox("iref", 0, 0, box("dimg", be16(10), be16(2), be16(1), be16(2)), box("thmb", be16(10)))
        refRulesUnchecked(check(grid().copy(refs = emptyList(), extraMetaChildren = listOf(iref))))
    }

    @Test
    fun `auxl or thmb whose source is a metadata item fails`() {
        val base = HeifSpec(items = listOf(HeifItem(1, "hvc1"), HeifItem(2, "Exif")), assoc = listOf(1L to tileProps))
        assertStatus(FAIL, check(base.copy(refs = listOf(Triple("auxl", 2L, listOf(1L))))), "heif.iref.targets")
        assertStatus(FAIL, check(base.copy(refs = listOf(Triple("thmb", 2L, listOf(1L))))), "heif.iref.targets")
        // cdsc is only checked for declaration.
        assertStatus(PASS, check(base.copy(refs = listOf(Triple("cdsc", 2L, listOf(1L))))), "heif.iref.targets")
    }

    @Test
    fun `grid stored in mdat with construction method 0 passes`() {
        val spec = grid().let { g -> g.copy(items = g.items.map { if (it.type == "grid") it.copy(method = 0) else it }) }
        val r = check(spec)
        r.assertClean()
        assertStatus(PASS, r, "heif.derived.grid")
    }

    @Test
    fun `iden with two inputs fails naming its clause`() {
        val spec = HeifSpec(items = listOf(HeifItem(1, "hvc1", hidden = true), HeifItem(2, "hvc1", hidden = true), HeifItem(3, "iden")),
            primary = 3, assoc = listOf(1L to tileProps, 2L to tileProps, 3L to listOf(2 to false)),
            refs = listOf(Triple("dimg", 3L, listOf(1L, 2L))))
        val f = check(spec).finding("heif.derived.inputs")
        assertEquals(FAIL, f.status)
        assertTrue(f.details.any { "§6.6.2.2 iden: dimg 입력 1개" in it }, f.details.toString())
    }
}
