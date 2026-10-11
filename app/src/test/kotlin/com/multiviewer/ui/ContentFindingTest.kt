package com.multiviewer.ui

import com.multiviewer.parser.integrity.CheckStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ContentFindingTest {
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
