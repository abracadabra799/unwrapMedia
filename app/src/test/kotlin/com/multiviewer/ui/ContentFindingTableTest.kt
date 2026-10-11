package com.multiviewer.ui

import com.multiviewer.parser.integrity.CheckStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ContentFindingTableTest {
    @Test fun actionableModeShowsFailuresWarningsAndFailedSteps() {
        val findings = listOf(
            finding("pass", CheckStatus.PASS), finding("skip", CheckStatus.SKIP),
            finding("failed-step", CheckStatus.FAIL), finding("warning", CheckStatus.WARN),
        )

        assertEquals(listOf("failed-step", "warning"), contentFindingTableRows(findings, showAll = false).map { it.id })
    }

    @Test fun showAllIncludesPassAndSkip() {
        val findings = listOf(finding("skip", CheckStatus.SKIP), finding("pass", CheckStatus.PASS))

        assertEquals(listOf("skip", "pass"), contentFindingTableRows(findings, showAll = true).map { it.id })
    }

    @Test fun summaryUsesFindingCounts() {
        val summary = contentFindingTableSummary(listOf(
            finding("fail", CheckStatus.FAIL), finding("warn", CheckStatus.WARN),
            finding("info", CheckStatus.INFO), finding("skip", CheckStatus.SKIP),
        ))

        assertEquals(ContentFindingSummary(total = 4, failures = 1, warnings = 1, passed = 1, unverified = 1), summary)
    }

    @Test fun rangeSelectionRequiresPositiveInFileRange() {
        assertTrue(isSelectableContentRange(finding("valid", CheckStatus.WARN, 4, 3), 7))
        assertFalse(isSelectableContentRange(finding("zero-length", CheckStatus.WARN, 4, 0), 7))
        assertFalse(isSelectableContentRange(finding("negative", CheckStatus.WARN, -1, 2), 7))
        assertFalse(isSelectableContentRange(finding("past-end", CheckStatus.WARN, 6, 2), 7))
        assertFalse(isSelectableContentRange(finding("overflow", CheckStatus.WARN, Long.MAX_VALUE, 2), Long.MAX_VALUE))
        assertFalse(isSelectableContentRange(finding("no-range", CheckStatus.WARN), 7))
    }

    private fun finding(id: String, status: CheckStatus, offset: Long? = null, length: Long? = null) = ContentFinding(
        id = id, stage = FindingStage.STRUCTURE, status = status, title = id,
        location = null, offset = offset, length = length, summary = id, verifiedFacts = listOf(id),
    )
}
