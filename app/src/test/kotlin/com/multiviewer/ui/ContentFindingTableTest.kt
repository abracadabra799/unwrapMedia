package com.multiviewer.ui

import com.multiviewer.parser.integrity.CheckStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ContentFindingTableTest {
    @Test fun filterEmptyStatesDoNotClaimAFalsePass() {
        for (filter in ContentFindingFilter.entries) {
            assertEquals(ContentFindingEmptyState.NO_CHECKS, contentFindingEmptyState(emptyList(), filter))
        }
        val skip = listOf(finding("skip", CheckStatus.SKIP))
        assertEquals(ContentFindingEmptyState.FILTERED, contentFindingEmptyState(skip, ContentFindingFilter.ISSUES))
        assertEquals(null, contentFindingEmptyState(skip, ContentFindingFilter.UNVERIFIED))
        assertEquals(ContentFindingEmptyState.FILTERED, contentFindingEmptyState(
            listOf(finding("info", CheckStatus.INFO), finding("pass", CheckStatus.PASS)), ContentFindingFilter.ISSUES))
    }
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

    @Test fun emptyTableDistinguishesMissingChecksFromResultsHiddenByFilter() {
        val completed = listOf(finding("pass", CheckStatus.PASS), finding("skip", CheckStatus.SKIP))

        assertEquals(ContentFindingEmptyState.NO_CHECKS, contentFindingEmptyState(emptyList(), showAll = true))
        assertEquals(ContentFindingEmptyState.FILTERED, contentFindingEmptyState(completed, showAll = false))
        assertEquals(null, contentFindingEmptyState(completed, showAll = true))
        assertEquals(null, contentFindingEmptyState(listOf(finding("warning", CheckStatus.WARN)), showAll = false))
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

    @Test fun collapsedSummaryIsBoundedWhileExpandedTextRemainsAvailable() {
        val longSummary = "diagnostic ".repeat(100)
        val collapsed = contentFindingCollapsedSummary(longSummary)

        assertTrue(collapsed.length <= MAX_COLLAPSED_FINDING_SUMMARY_LENGTH)
        assertTrue(collapsed.endsWith("…"))
        assertEquals(longSummary.take(4000), contentFindingExpandedSummary(longSummary))
    }

    @Test fun rangeActionRequiresConnectedSelectionCallback() {
        val ranged = finding("range", CheckStatus.WARN, 3, 2)
        assertTrue(canSelectContentFindingRange(ranged, 5, callbackAvailable = true))
        assertFalse(canSelectContentFindingRange(ranged, 5, callbackAvailable = false))
    }

    @Test fun hasContentFindingDetailsDistinguishesItemsWithDetails() {
        val withFacts = finding("facts", CheckStatus.PASS).copy(verifiedFacts = listOf("fact 1"))
        assertTrue(hasContentFindingDetails(withFacts, 100, true))

        val withLongSummary = finding("summary", CheckStatus.PASS).copy(
            summary = "a".repeat(250), verifiedFacts = emptyList(),
        )
        assertTrue(hasContentFindingDetails(withLongSummary, 100, true))

        val withHypothesis = finding("hypo", CheckStatus.WARN).copy(
            hypothesis = "some cause", verifiedFacts = emptyList(),
        )
        assertTrue(hasContentFindingDetails(withHypothesis, 100, true))

        val withNextChecks = finding("checks", CheckStatus.INFO).copy(
            nextChecks = listOf("check 1"), verifiedFacts = emptyList(),
        )
        assertTrue(hasContentFindingDetails(withNextChecks, 100, true))

        val withRange = finding("range", CheckStatus.PASS, 10, 5).copy(verifiedFacts = emptyList())
        assertTrue(hasContentFindingDetails(withRange, 100, true, onSelectRange = { _, _ -> }))
        assertFalse(hasContentFindingDetails(withRange, 100, true, onSelectRange = null))

        val emptyItem = finding("empty", CheckStatus.PASS).copy(
            summary = "short", verifiedFacts = emptyList(), hypothesis = null, nextChecks = emptyList(),
        )
        assertFalse(hasContentFindingDetails(emptyItem, 100, true))
    }


    private fun finding(id: String, status: CheckStatus, offset: Long? = null, length: Long? = null) = ContentFinding(
        id = id, stage = FindingStage.STRUCTURE, status = status, title = id,
        location = null, offset = offset, length = length, summary = id, verifiedFacts = listOf(id),
    )
}
