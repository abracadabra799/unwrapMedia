package com.multiviewer.ui

import com.multiviewer.parser.BoxNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ContentCheckRunStateTest {
    @Test
    fun treeArrivalDoesNotStartChecks() {
        val before = contentCheckStages(MediaType.VIDEO, null, false, false, true, false)
        val after = contentCheckStages(MediaType.VIDEO, BoxNode("root", 0, 0, 100), false, false, true, false)
        assertEquals(CheckAvailability.LOADING, before[0].availability)
        assertEquals(CheckAvailability.AVAILABLE, after[0].availability)
        assertEquals(CheckRunStatus.NOT_RUN, initialCheckStates(after).getValue(CheckStage.STRUCTURE).status)
    }

    @Test
    fun queueIncludesOnlyAvailableRowsInDisplayedOrder() {
        val specs = contentCheckStages(MediaType.VIDEO, BoxNode("root", 0, 0, 100), false, false, false, false)
        val queued = queueApplicableStages(specs)
        assertEquals(listOf(CheckStage.STRUCTURE, CheckStage.VISUAL_DECODE), queued.keys.toList())
        assertEquals(listOf(CheckRunStatus.QUEUED, CheckRunStatus.QUEUED), queued.values.map { it.status })
    }

    @Test
    fun executionStateDoesNotClaimVerdictOrInventProgressTotal() {
        val completed = CheckStageState(CheckRunStatus.COMPLETED)
        assertEquals(CheckRunStatus.COMPLETED, completed.status)
        assertNull(completed.progress)
        val progress = CheckProgress(17, null, "frames")
        assertNull(progress.total)
    }
}
