package com.multiviewer.ui

import com.multiviewer.parser.integrity.CheckStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ContentFindingTest {
    @Test
    fun filtersKeepIssuesAndUnverifiedSeparate() {
        val input = listOf(
            finding("pass", CheckStatus.PASS, 4), finding("skip", CheckStatus.SKIP, 3),
            finding("info", CheckStatus.INFO, 2), finding("warn", CheckStatus.WARN, 8),
            finding("fail", CheckStatus.FAIL, 9),
        )
        assertEquals(listOf("fail", "warn", "info", "skip", "pass"),
            filteredContentFindings(input, ContentFindingFilter.ALL).map { it.id })
        assertEquals(listOf("fail", "warn"),
            filteredContentFindings(input, ContentFindingFilter.ISSUES).map { it.id })
        assertEquals(listOf("skip"),
            filteredContentFindings(input, ContentFindingFilter.UNVERIFIED).map { it.id })
    }
    @Test
    fun mapsInspectionStatusesWithoutTreatingNotRunAsClean() {
        assertEquals(CheckStatus.SKIP, mapIntegrityStatus(IntegrityStatus.NOT_RUN))
        assertEquals(CheckStatus.PASS, mapIntegrityStatus(IntegrityStatus.CLEAN))
        assertEquals(CheckStatus.WARN, mapIntegrityStatus(IntegrityStatus.ISSUES))
        assertEquals(CheckStatus.FAIL, mapIntegrityStatus(IntegrityStatus.FAILED))
        assertEquals(CheckStatus.SKIP, mapImageDecodeStatus(ImageDecodeStatus.NOT_RUN))
        assertEquals(CheckStatus.PASS, mapImageDecodeStatus(ImageDecodeStatus.CLEAN))
        assertEquals(CheckStatus.WARN, mapImageDecodeStatus(ImageDecodeStatus.ISSUES))
        assertEquals(CheckStatus.FAIL, mapImageDecodeStatus(ImageDecodeStatus.FAILED))
    }

    @Test
    fun countsEachFindingByStatus() {
        val findings = listOf(
            finding("fail", CheckStatus.FAIL), finding("warn", CheckStatus.WARN),
            finding("info", CheckStatus.INFO), finding("pass", CheckStatus.PASS), finding("skip", CheckStatus.SKIP),
        )

        assertEquals(ContentFindingSummary(total = 5, failures = 1, warnings = 1, passed = 2, unverified = 1), contentFindingSummary(findings))
    }

    @Test
    fun hidesPassAndSkipUntilShowAll() {
        val findings = listOf(finding("pass", CheckStatus.PASS), finding("skip", CheckStatus.SKIP), finding("warn", CheckStatus.WARN))

        assertEquals(listOf("warn"), orderedContentFindings(findings, showAll = false).map { it.id })
        assertEquals(listOf("warn", "skip", "pass"), orderedContentFindings(findings, showAll = true).map { it.id })
    }

    @Test
    fun ordersBySeverityThenOffsetAndKeepsTiesStable() {
        val findings = listOf(
            finding("pass", CheckStatus.PASS, offset = 1),
            finding("warn-late", CheckStatus.WARN, offset = 20),
            finding("fail", CheckStatus.FAIL, offset = 40),
            finding("warn-first", CheckStatus.WARN, offset = 2),
            finding("same-a", CheckStatus.FAIL, offset = 40),
        )

        assertEquals(listOf("fail", "same-a", "warn-first", "warn-late", "pass"), orderedContentFindings(findings, showAll = true).map { it.id })
    }

    @Test
    fun keepsUnknownExplanationEmpty() {
        assertNull(finding("unknown", CheckStatus.FAIL).hypothesis)
    }

    @Test
    fun marksMissingLocationUnavailable() {
        val finding = finding("unknown", CheckStatus.WARN)

        assertEquals("위치 확인 불가", contentFindingLocationLabel(finding, ko = true))
        assertEquals("Location unavailable", contentFindingLocationLabel(finding, ko = false))
    }

    @Test
    fun formatsByteOffsetAndCleansConformanceLocation() {
        val withOffset = finding("soi", CheckStatus.PASS, offset = 0L).copy(length = 2L)
        assertEquals("0x0 (+2 B)", contentFindingLocationLabel(withOffset, ko = true))
        assertEquals("0x0 (+2 B)", contentFindingLocationLabel(withOffset, ko = false))

        val conformanceWithoutOffset = finding("trailing", CheckStatus.PASS, offset = null).copy(
            stage = FindingStage.CONFORMANCE,
            location = null,
        )
        assertEquals("-", contentFindingLocationLabel(conformanceWithoutOffset, ko = true))

        val audioTrack = finding("audio.track", CheckStatus.PASS, offset = null).copy(
            location = "Track 0",
        )
        assertEquals("Track 0", contentFindingLocationLabel(audioTrack, ko = true))

        val boilerplate = finding("test", CheckStatus.PASS, offset = null).copy(
            stage = FindingStage.CONFORMANCE,
            location = "청크 경계; 기존 바이트·구조 검사 범위 / Existing byte/structure check only",
        )
        assertEquals("-", contentFindingLocationLabel(boilerplate, ko = true))
    }

    @Test
    fun conformanceRowsWithOffsetsKeepTheirRuleGroup() {
        val grouped = finding("bmff.stsz.count", CheckStatus.FAIL, offset = 0x20L).copy(
            length = 16L, stage = FindingStage.CONFORMANCE,
            location = cleanConformanceLocation("샘플 테이블 / Sample tables", "stsz / stsz"),
        )
        assertEquals("샘플 테이블 · 0x20 (+16 B)", contentFindingLocationLabel(grouped, ko = true))
        assertEquals("Sample tables · 0x20 (+16 B)", contentFindingLocationLabel(grouped, ko = false))
    }


    private fun finding(id: String, status: CheckStatus, offset: Long? = null) = ContentFinding(
        id = id,
        stage = FindingStage.STRUCTURE,
        status = status,
        title = id,
        location = null,
        offset = offset,
        length = offset?.let { 4 },
        summary = id,
        verifiedFacts = listOf("fact:$id"),
    )
}
