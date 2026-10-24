package com.multiviewer.parser.conformance

import com.multiviewer.parser.parseFile
import com.multiviewer.ui.codecBitstreamFindings
import kotlinx.coroutines.runBlocking
import java.io.File
import com.multiviewer.ui.conformanceSourceNote
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CodecCitationTest {
    private val codecRules: List<ConformanceRule> = listOf(
        "avc.nal.framing", "avc.nal.forbidden_zero_bit", "avc.nal.idr_ref_idc",
        "hevc.nal.framing", "hevc.nal.forbidden_zero_bit", "hevc.nal.temporal_id_plus1",
        "vvc.config.vvcc", "vvc.nal.framing", "vvc.nal.forbidden_zero_bit", "vvc.nal.reserved_zero_bit",
        "vvc.nal.temporal_id_plus1", "vvc.sample.framing",
        "av1.obu.framing", "av1.obu.forbidden_bit", "av1.obu.reserved_bit", "av1.obu.extension_reserved_bits",
        "av1.config.sequence_header",
        "vp9.frame.framing", "vp9.frame.marker", "vp9.frame.sync_code", "vp9.frame.profile_reserved_bit", "vp9.config.vpcc",
        "apv.au.signature", "apv.pbu.framing", "apv.pbu.reserved_zero", "apv.frame.profile", "apv.frame.reserved_zero",
        "apv.config.apvc",
        "aac.config.esds", "aac.config.asc", "aac.config.match", "aac.frame.adts_guard", "aac.frame.element",
        "aac.frame.ics_reserved", "aac.sample.framing",
        "opus.config.dops", "opus.config.match", "opus.packet.toc", "opus.packet.framing", "opus.sample.framing",
        "flac.config.dfla", "flac.config.match", "flac.frame.header", "flac.frame.crc", "flac.sample.framing",
    ).map { id ->
        assertNotNull(
            when (id.substringBefore('.')) {
                "avc" -> AvcRuleCatalog.rule(id)
                "hevc" -> HevcRuleCatalog.rule(id)
                "vvc" -> VvcRuleCatalog.rule(id)
                "av1" -> Av1RuleCatalog.rule(id)
                "vp9" -> Vp9RuleCatalog.rule(id)
                "apv" -> ApvRuleCatalog.rule(id)
                "aac" -> AacRuleCatalog.rule(id)
                "opus" -> OpusRuleCatalog.rule(id)
                else -> FlacRuleCatalog.rule(id)
            }, id,
        )
    }

    private val pngRules = rulesFor("PNG")

    private fun paid(rule: ConformanceRule) = rule.citation!!.url.startsWith("https://www.iso.org/standard/")

    @Test
    fun everyCodecAndPngRuleHasACitationMatchingItsReference() {
        assertEquals(6, pngRules.size)
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
                    // Opus-in-ISOBMFF is one page without anchors; every other HTML/markdown source has an anchor.
                    assertTrue((c.url.contains("#") || c.url == "https://opus-codec.org/docs/opus_in_isobmff.html") && c.page == null, rule.id)
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
        for (id in listOf("av1.obu.framing", "vp9.frame.framing", "apv.pbu.framing", "opus.sample.framing", "flac.sample.framing", "opus.packet.toc")) {
            val rule = (codecRules).single { it.id == id }
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
}
