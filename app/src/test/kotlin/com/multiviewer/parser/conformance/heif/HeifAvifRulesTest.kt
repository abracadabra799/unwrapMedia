package com.multiviewer.parser.conformance.heif

import com.multiviewer.parser.ByteReader
import com.multiviewer.parser.conformance.ConformanceReport
import com.multiviewer.parser.conformance.ConformanceStatus
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

class HeifAvifRulesTest {
    private val avifIds = listOf(
        "avif.ftyp.miaf", "avif.ftyp.avif", "avif.primary", "avif.props.av1c_essential", "avif.props.pixi",
        "avif.props.pasp", "avif.props.a1op", "avif.props.a1lx", "avif.alpha.auxc",
    )

    private fun check(spec: HeifSpec) = ByteReader.open(fileOf(buildHeif(spec))).use { HeifConformanceChecker.check(it) }
    private fun ConformanceReport.finding(id: String) = findings.single { it.rule.id == id }
    private fun ConformanceReport.status(id: String) = finding(id).status
    private fun ConformanceReport.assertClean() {
        val bad = findings.filter { it.status == FAIL || it.status == WARN }
        assertTrue(bad.isEmpty(), "unexpected findings: ${bad.map { it.rule.id to it.details }}")
        assertNull(inspectionError)
    }
    private fun assertStatus(expected: ConformanceStatus, r: ConformanceReport, id: String) =
        assertEquals(expected, r.status(id), "$id: ${r.finding(id).details}")

    /** ffmpeg libsvtav1 shape: props 1 ispe, 2 pixi, 3 av1C (essential), 4 colr. */
    private val avif = HeifSpec(
        major = "avif", compatible = listOf("avif", "mif1", "miaf", "MA1B"),
        items = listOf(HeifItem(1, "av01")),
        props = listOf(ispe(64, 48), pixi(8, 8, 8), av1C(), colrNclx()),
        assoc = listOf(1L to listOf(1 to false, 2 to false, 3 to true, 4 to false)),
    )
    private val avifProps = listOf(1 to false, 2 to false, 3 to true, 4 to false)

    /** [avif] with [extra] appended to ipco (indices 5…) and associated with item 1 using [essential]. */
    private fun withExtra(vararg extra: Pair<ByteArray, Boolean>) = avif.copy(
        props = avif.props + extra.map { it.first },
        assoc = listOf(1L to avifProps + extra.mapIndexed { i, (_, e) -> 5 + i to e }),
    )

    @Test
    fun `ffmpeg shaped AVIF is clean and passes the AVIF rules`() {
        val r = check(avif)
        r.assertClean()
        assertEquals("AVIF", r.format)
        listOf("avif.ftyp.miaf", "avif.ftyp.avif", "avif.primary", "avif.props.av1c_essential", "avif.props.pixi")
            .forEach { assertStatus(PASS, r, it) }
        listOf("avif.props.pasp", "avif.props.a1op", "avif.props.a1lx", "avif.alpha.auxc").forEach { assertStatus(NOT_APPLICABLE, r, it) }
    }

    @Test
    fun `AVIF without miaf fails`() {
        assertStatus(FAIL, check(avif.copy(compatible = listOf("avif", "mif1", "MA1B"))), "avif.ftyp.miaf")
    }

    @Test
    fun `AV1 primary without avif or avis brand fails`() {
        val r = check(avif.copy(major = "mif1", compatible = listOf("mif1", "miaf", "MA1B")))
        assertEquals("AVIF", r.format)
        assertStatus(FAIL, r, "avif.ftyp.avif")
        // avif.primary only binds files that carry the avif brand.
        assertStatus(NOT_APPLICABLE, r, "avif.primary")
    }

    @Test
    fun `avis brand satisfies the avif-or-avis rule`() {
        assertStatus(PASS, check(avif.copy(major = "avis", compatible = listOf("avis", "msf1", "miaf"))), "avif.ftyp.avif")
    }

    @Test
    fun `hvc1 primary in an avif-branded file fails`() {
        val spec = avif.copy(
            items = listOf(HeifItem(1, "av01", hidden = true), HeifItem(2, "hvc1")), primary = 2,
            props = avif.props + hvcC(),
            assoc = listOf(1L to avifProps, 2L to listOf(5 to true, 1 to false, 2 to false)),
        )
        assertStatus(FAIL, check(spec), "avif.primary")
    }

    /** 1×2 grid (item 10) of two 64×48 av01 tiles; props 1 ispe 64×48, 2 pixi, 3 av1C, 4 colr, 5 ispe 128×48. */
    private fun avifGrid(tileType: String = "av01") = avif.copy(
        items = listOf(HeifItem(1, tileType, hidden = true), HeifItem(2, "av01", hidden = true),
            HeifItem(10, "grid", data = gridData(1, 2, 128, 48), method = 1)),
        primary = 10,
        props = avif.props + ispe(128, 48) + hvcC(),
        assoc = listOf(
            1L to (if (tileType == "av01") avifProps else listOf(6 to true, 1 to false, 2 to false)),
            2L to avifProps, 10L to listOf(5 to false, 2 to false, 4 to false),
        ),
        refs = listOf(Triple("dimg", 10L, listOf(1L, 2L))),
    )

    @Test
    fun `grid of av01 tiles as primary passes, a grid with an hvc1 tile fails`() {
        val r = check(avifGrid())
        r.assertClean()
        assertStatus(PASS, r, "avif.primary")
        assertStatus(FAIL, check(avifGrid(tileType = "hvc1")), "avif.primary")
    }

    @Test
    fun `primary resolving to an unknown item type is not checked`() {
        val spec = avifGrid().let { g -> g.copy(items = g.items.map { if (it.id == 1L) it.copy(type = "zzzz") else it }) }
        assertStatus(NOT_CHECKED, check(spec), "avif.primary")
    }

    @Test
    fun `sample transform derived primary over av01 inputs passes`() {
        val spec = avif.copy(
            items = listOf(HeifItem(1, "av01", hidden = true), HeifItem(2, "av01", hidden = true), HeifItem(10, "sato", data = ByteArray(8), method = 1)),
            primary = 10,
            assoc = listOf(1L to avifProps, 2L to avifProps, 10L to listOf(1 to false, 2 to false)),
            refs = listOf(Triple("dimg", 10L, listOf(1L, 2L))),
        )
        assertStatus(PASS, check(spec), "avif.primary")
    }

    @Test
    fun `non essential av1C warns`() {
        val r = check(avif.copy(assoc = listOf(1L to listOf(1 to false, 2 to false, 3 to false, 4 to false))))
        assertStatus(WARN, r, "avif.props.av1c_essential")
    }

    @Test
    fun `av01 item without pixi fails`() {
        assertStatus(FAIL, check(avif.copy(assoc = listOf(1L to listOf(1 to false, 3 to true, 4 to false)))), "avif.props.pixi")
    }

    @Test
    fun `pasp must be 1 to 1`() {
        assertStatus(FAIL, check(withExtra(pasp(2, 1) to false)), "avif.props.pasp")
        assertStatus(PASS, check(withExtra(pasp(1, 1) to false)), "avif.props.pasp")
        assertStatus(PASS, check(withExtra(pasp(3, 3) to false)), "avif.props.pasp")
    }

    @Test
    fun `a1op must be essential`() {
        assertStatus(FAIL, check(withExtra(box("a1op", byteArrayOf(0)) to false)), "avif.props.a1op")
        assertStatus(PASS, check(withExtra(box("a1op", byteArrayOf(0)) to true)), "avif.props.a1op")
    }

    @Test
    fun `a1lx must not be essential`() {
        val a1lx = box("a1lx", byteArrayOf(0), be16(100), be16(0), be16(0))
        assertStatus(FAIL, check(withExtra(a1lx to true)), "avif.props.a1lx")
        assertStatus(PASS, check(withExtra(a1lx to false)), "avif.props.a1lx")
    }

    /** av01 master 1 and av01 auxiliary 2 (auxl 2 → 1) whose auxC names [urn]. */
    private fun withAux(urn: String) = avif.copy(
        items = listOf(HeifItem(1, "av01"), HeifItem(2, "av01", hidden = true)),
        props = avif.props + auxC(urn),
        assoc = listOf(1L to avifProps, 2L to listOf(1 to false, 2 to false, 3 to true, 5 to true)),
        refs = listOf(Triple("auxl", 2L, listOf(1L))),
    )

    @Test
    fun `auxiliary URNs - alpha and non-alpha pass, a near-miss alpha URN warns`() {
        val ok = check(withAux("urn:mpeg:mpegB:cicp:systems:auxiliary:alpha"))
        ok.assertClean()
        assertStatus(PASS, ok, "avif.alpha.auxc")
        assertStatus(PASS, check(withAux("urn:mpeg:hevc:2015:auxid:1")), "avif.alpha.auxc")
        assertStatus(PASS, check(withAux("urn:mpeg:mpegB:cicp:systems:auxiliary:depth")), "avif.alpha.auxc")
        assertStatus(PASS, check(withAux("urn:foo")), "avif.alpha.auxc")
        assertStatus(PASS, check(withAux("urn:com:photo:aux:hdrgainmap")), "avif.alpha.auxc")
        // §4.1 defines alpha by this exact aux_type; a near miss is not an alpha item (no "shall" to violate): WARN.
        assertStatus(WARN, check(withAux("urn:mpeg:mpegB:cicp:systems:auxiliary:alfa")), "avif.alpha.auxc")
        assertStatus(WARN, check(withAux("urn:mpeg:mpegb:cicp:systems:auxiliary:alpha")), "avif.alpha.auxc")
    }

    @Test
    fun `truncated ipma leaves the AVIF property rules unchecked`() {
        val ipma = fullBox("ipma", 0, 0, be32(2), be16(1), byteArrayOf(2, 0x83.toByte(), 0x01))
        val iprp = box("iprp", box("ipco", *avif.props.toTypedArray()), ipma)
        val r = check(avif.copy(dropMetaChild = "iprp", extraMetaChildren = listOf(iprp)))
        listOf("avif.props.av1c_essential", "avif.props.pixi", "avif.props.pasp", "avif.props.a1op", "avif.props.a1lx", "avif.alpha.auxc")
            .forEach { assertStatus(NOT_CHECKED, r, it) }
    }

    @Test
    fun `HEIC files report every AVIF rule as not applicable`() {
        val r = check(HeifSpec())
        assertEquals("HEIF", r.format)
        avifIds.forEach { assertStatus(NOT_APPLICABLE, r, it) }
    }

    @Test
    fun `AVIF rules carry the AVIF source, scope and verified anchors`() {
        val r = check(avif)
        val anchors = mapOf(
            "avif.ftyp.miaf" to "file-constraints", "avif.ftyp.avif" to "file-constraints",
            "avif.primary" to "image-and-image-collection-brand",
            "avif.props.av1c_essential" to "av1-item-configuration-property",
            "avif.props.pixi" to "avif-required-boxes", "avif.props.pasp" to "avif-required-boxes-additional",
            "avif.props.a1op" to "operating-point-selector-property", "avif.props.a1lx" to "layered-image-indexing-property",
            "avif.alpha.auxc" to "auxiliary-images",
        )
        assertEquals(avifIds.toSet(), anchors.keys)
        for ((id, anchor) in anchors) {
            val rule = r.finding(id).rule
            assertEquals("AVIF v1.2.0 (AOMedia)", rule.source, id)
            assertEquals("AVIF", rule.scope, id)
            assertTrue(rule.title.contains(" / "), rule.title)
            assertTrue(rule.reference!!.startsWith("AVIF v1.2.0 §"), rule.reference)
            val c = assertNotNull(rule.citation, id)
            assertEquals("AVIF v1.2.0 (AOMedia, 2025-10-16)", c.document)
            assertEquals("https://aomediacodec.github.io/av1-avif/v1.2.0.html#$anchor", c.url)
            assertEquals(true, c.verified)
            assertNull(c.page)
        }
    }
}
