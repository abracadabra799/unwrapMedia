package com.multiviewer.ui

import com.multiviewer.parser.conformance.SpecCitation
import com.multiviewer.parser.conformance.label
import com.multiviewer.parser.integrity.CheckStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SpecCitationLinkTest {
    private val citation = SpecCitation("AVIF v1.2.0 (AOMedia, 2025-10-16)", "§7 General constraints", null,
        "https://aomediacodec.github.io/av1-avif/v1.2.0.html#file-constraints", true)

    private fun finding(citation: SpecCitation?, source: String, stage: FindingStage = FindingStage.CONFORMANCE) = ContentFinding(
        id = "r", stage = stage, status = CheckStatus.FAIL, title = "t", location = null, offset = null, length = null,
        summary = "s", verifiedFacts = listOf("Source: $source"), citation = citation,
    )

    @Test fun parsesHttpsUrl() {
        assertNotNull(parseCitationUri(citation.url))
    }

    @Test fun invalidOrNonWebUrisAreRejectedWithoutThrowing() {
        assertNull(parseCitationUri("not a uri with spaces ^^"))
        assertNull(parseCitationUri(""))
        assertNull(parseCitationUri("file:///etc/passwd"))
        assertNull(parseCitationUri("javascript:alert(1)"))
    }

    @Test fun openCitationWithInvalidUriDoesNotThrow() {
        openCitation("::: invalid")
    }

    @Test fun sourceLineIsLinkWhenCited() {
        val line = contentFindingSourceLine(finding(citation, "AVIF v1.2.0 (AOMedia)"))
        assertTrue(line is CitationSourceLine.Link)
        assertEquals(citation.url, line.url)
        assertEquals(citation.label(), line.text)
    }

    @Test fun structuralObservationGetsAppLabel() {
        val line = contentFindingSourceLine(finding(null, "unwrapMedia structural observation; AVC header fields"))
        assertEquals(CitationSourceLine.Plain(STRUCTURAL_OBSERVATION_LABEL), line)
        val check = contentFindingSourceLine(finding(null, "unwrapMedia structural check"))
        assertEquals(CitationSourceLine.Plain(STRUCTURAL_OBSERVATION_LABEL), check)
    }

    @Test fun uncitedNormativeRuleHasNoSourceLine() {
        assertNull(contentFindingSourceLine(finding(null, "ISO/IEC 14496-12 (공개 자료 기반, 원문 미대조)")))
    }

    @Test fun nonConformanceStageWithoutCitationHasNoSourceLine() {
        assertNull(contentFindingSourceLine(finding(null, "unwrapMedia structural check", FindingStage.STRUCTURE)))
    }
}
