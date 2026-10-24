package com.multiviewer.parser.conformance

import com.multiviewer.parser.parseFile
import com.multiviewer.cli.renderCompact
import com.multiviewer.ui.ContentCheckSnapshot
import com.multiviewer.ui.MediaType
import com.multiviewer.ui.codecBitstreamFindings
import com.multiviewer.ui.contentCheckPromptSection
import com.multiviewer.ui.contentCheckWorkflowJson
import kotlinx.coroutines.runBlocking
import java.io.File
import com.multiviewer.ui.conformanceSourceNote
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CodecCitationTest {
    private val codecRules: List<ConformanceRule> = AvcRuleCatalog.all + HevcRuleCatalog.all + VvcRuleCatalog.all +
        Av1RuleCatalog.all + Vp9RuleCatalog.all + ApvRuleCatalog.all + AacRuleCatalog.all + OpusRuleCatalog.all +
        FlacRuleCatalog.all

    private val pngRules = rulesFor("PNG")

    private fun paid(rule: ConformanceRule) = rule.citation!!.url.startsWith("https://www.iso.org/standard/")

    @Test
    fun everyCodecAndPngRuleHasACitationMatchingItsReference() {
        assertEquals(6, pngRules.size)
        assertEquals(45, codecRules.size)
        for (rule in codecRules + pngRules) {
            val c = assertNotNull(rule.citation, rule.id)
            assertEquals(c.url, rule.reference, rule.id)
            assertTrue(c.document.isNotBlank() && c.section.isNotBlank(), rule.id)
            assertNull(conformanceSourceNote(rule), rule.id)
            assertTrue(!rule.source.contains("structural"), rule.id)
        }
    }

    @Test
    fun verifiedRulesCarryPageOrAnchorAndPaidRulesAreUnverifiedStorePages() {
        for (rule in codecRules + pngRules) {
            val c = rule.citation!!
            if (paid(rule)) {
                assertTrue(!c.verified && c.page == null, rule.id)
                assertTrue(rule.source.contains("원문 미대조"), rule.id)
            } else {
                assertTrue(c.verified, rule.id)
                if (c.url.endsWith(".pdf#page=${c.page}") || c.url.contains("type=items#page=")) {
                    assertEquals(c.url.substringAfter("#page=").toInt(), c.page, rule.id)
                } else {
                        assertTrue(c.url.contains("#") && c.page == null, rule.id)
                }
            }
        }
        assertEquals(
            setOf("avc.nal.framing", "hevc.nal.framing", "vvc.nal.framing", "vvc.config.vvcc", "vvc.sample.framing") +
                codecRules.filter { it.id.startsWith("aac.") }.map { it.id },
            codecRules.filter { paid(it) }.map { it.id }.toSet(),
        )
    }

    @Test
    fun spotCheckExactUrls() {
        assertEquals(
            "https://www.itu.int/rec/dologin_pub.asp?lang=e&id=T-REC-H.265-202601-I!!PDF-E&type=items#page=83",
            HevcRuleCatalog.rule("hevc.nal.forbidden_zero_bit")!!.citation!!.url,
        )
        assertEquals("https://www.rfc-editor.org/rfc/rfc6716.html#section-3.1", OpusRuleCatalog.rule("opus.packet.toc")!!.citation!!.url)
        assertEquals("https://www.w3.org/TR/png-3/#11IHDR", pngRules.single { it.id == "png.ihdr" }.citation!!.url)
        assertEquals(
            "https://github.com/xiph/flac/blob/78d85dd4e4e8044d0f6989182cd1a22a461dce37/doc/isoflac.txt#L227",
            FlacRuleCatalog.rule("flac.config.dfla")!!.citation!!.url,
        )
    }

    @Test
    fun definitionalRulesAreMarked() {
        for (id in listOf("av1.obu.framing", "vp9.frame.framing", "apv.pbu.framing", "opus.sample.framing", "flac.sample.framing", "opus.packet.toc", "flac.frame.crc", "png.truncated", "png.crc")) {
            val rule = (codecRules + pngRules).single { it.id == id }
            assertTrue(rule.citation!!.section.endsWith(DEFINITIONAL_SUFFIX), id)
        }
    }

    @Test
    fun codecFailFindingCarriesCitationAndNoSourceNote() {
        val rule = HevcRuleCatalog.rule("hevc.nal.forbidden_zero_bit")!!
        val report = CodecBitstreamReport(
            BitstreamCodec.HEVC,
            listOf(ConformanceFinding(rule, ConformanceStatus.FAIL, listOf("bad"), listOf(10L..11L))),
            1, 1, emptyMap(), emptyList(), "track 1", true,
        )
        val finding = codecBitstreamFindings(report).single()
        assertEquals(rule.citation, finding.citation)
        assertNull(finding.sourceNote)
    }

    private fun fixture(dir: String, name: String) = File(javaClass.getResource("/$dir/$name")!!.toURI())

    @Test
    fun realCorruptedFixturesProduceCitedFailFindings() = runBlocking {
        val hevc = fixture("hevc-conformance", "forbidden-bit.mp4")
        val av1 = fixture("av1-conformance", "forbidden-bit.mp4")
        val vp9 = fixture("vp9-conformance", "bad-marker.mp4")
        val cases = listOf(
            Triple(analyzeHevcBitstream(hevc, parseFile(hevc)) { _, _ -> }, "hevc.nal.forbidden_zero_bit",
                "https://www.itu.int/rec/dologin_pub.asp?lang=e&id=T-REC-H.265-202601-I!!PDF-E&type=items#page=83"),
            Triple(analyzeAv1Bitstream(av1, parseFile(av1)) { _, _ -> }, "av1.obu.forbidden_bit",
                "https://aomediacodec.github.io/av1-spec/#obu-header-semantics"),
            Triple(analyzeVp9Bitstream(vp9, parseFile(vp9)) { _, _ -> }, "vp9.frame.marker",
                "https://storage.googleapis.com/downloads.webmproject.org/docs/vp9/vp9-bitstream-specification-v0.7-20170222-draft.pdf#page=68"),
        )
        for ((report, ruleId, url) in cases) {
            val finding = codecBitstreamFindings(report).first { it.id == ruleId && it.status == com.multiviewer.parser.integrity.CheckStatus.FAIL }
            assertEquals(url, finding.citation?.url, ruleId)
            assertNull(finding.sourceNote, ruleId)
        }
    }

    private fun reportWith(vararg findings: Pair<ConformanceRule, ConformanceStatus>) = CodecBitstreamReport(
        BitstreamCodec.HEVC, findings.map { (r, st) -> ConformanceFinding(r, st, listOf("bad"), listOf(10L..11L)) },
        1, 1, emptyMap(), emptyList(), "track 1", true,
    )

    private fun snapshot(report: CodecBitstreamReport) =
        ContentCheckSnapshot(null, null, false, null, null, null, MediaType.VIDEO, codecBitstreams = listOf(report))

    @Test
    fun provenanceFactDistinguishesVerbatimDerivedAndUnverified() {
        fun fact(rule: ConformanceRule) = codecBitstreamFindings(reportWith(rule to ConformanceStatus.FAIL)).single().verifiedFacts.last()
        assertTrue(fact(HevcRuleCatalog.rule("hevc.nal.forbidden_zero_bit")!!).startsWith("Checked against the cited spec text"))
        assertTrue(fact(OpusRuleCatalog.rule("opus.packet.toc")!!).startsWith("Derived from the cited definition"))
        assertTrue(fact(HevcRuleCatalog.rule("hevc.nal.framing")!!).startsWith("Structural observation"))
    }

    @Test
    fun promptAndJsonCarryCitationAndNeverCallVerifiedRulesStructural() {
        val file = File("x.mp4")
        val verified = HevcRuleCatalog.rule("hevc.nal.forbidden_zero_bit")!!
        val paid = HevcRuleCatalog.rule("hevc.nal.framing")!!
        val snap = snapshot(reportWith(verified to ConformanceStatus.FAIL, paid to ConformanceStatus.FAIL))
        val prompt = contentCheckPromptSection(snap, file).lines()
        val v = prompt.single { it.startsWith("  • [FAIL] hevc.nal.forbidden_zero_bit") }
        assertTrue(v.contains("#page=83") && !v.contains("구조 관측값·규격 조항 미확인"), v)
        val p = prompt.single { it.startsWith("  • [FAIL] hevc.nal.framing") }
        assertTrue(p.contains("구조 관측값·규격 조항 미확인") && p.contains("원문 미대조"), p)
        val json = contentCheckWorkflowJson(snap, file).renderCompact()
        assertTrue(json.contains("\"page\":83") && json.contains("\"verified\":true") && json.contains("type=items#page=83"), json)
        assertTrue(json.contains("\"verified\":false") && json.contains("https://www.iso.org/standard/89118.html"), json)
    }
}
