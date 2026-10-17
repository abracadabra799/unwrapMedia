package com.multiviewer.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertFailsWith

class ContentCheckRunnerTest {
    @Test
    fun runAllExecutesInRequestedOrderAndPublishesAfterEachStage() = runBlocking {
        val events = mutableListOf<String>()
        val runner = ContentCheckRunner { stage, _ -> events += "execute:$stage" }
        runner.run(listOf(CheckStage.STRUCTURE, CheckStage.CODEC, CheckStage.VISUAL_DECODE)) { stage, state ->
            if (state.status == CheckRunStatus.COMPLETED) events += "complete:$stage"
        }
        assertEquals(listOf("execute:STRUCTURE", "complete:STRUCTURE", "execute:CODEC", "complete:CODEC",
            "execute:VISUAL_DECODE", "complete:VISUAL_DECODE"), events)
    }

    @Test
    fun failurePreservesPriorResultAndContinues() = runBlocking {
        val reports = mutableMapOf<CheckStage, String>()
        val states = mutableMapOf<CheckStage, CheckRunStatus>()
        val runner = ContentCheckRunner { stage, _ ->
            if (stage == CheckStage.CODEC) error("unreadable")
            reports[stage] = "report:$stage"
        }
        runner.run(listOf(CheckStage.STRUCTURE, CheckStage.CODEC, CheckStage.VISUAL_DECODE)) { stage, state ->
            states[stage] = state.status
        }
        assertEquals("report:STRUCTURE", reports[CheckStage.STRUCTURE])
        assertEquals(CheckRunStatus.FAILED, states[CheckStage.CODEC])
        assertEquals("report:VISUAL_DECODE", reports[CheckStage.VISUAL_DECODE])
    }

    @Test
    fun cancellationStopsQueueAndMarksOnlyActiveStage() = runBlocking {
        val states = mutableMapOf<CheckStage, CheckRunStatus>()
        val runner = ContentCheckRunner { _, _ -> throw CancellationException("stop") }
        try {
            runner.run(listOf(CheckStage.STRUCTURE, CheckStage.CODEC)) { stage, state -> states[stage] = state.status }
        } catch (_: CancellationException) { }
        assertEquals(CheckRunStatus.CANCELLED, states[CheckStage.STRUCTURE])
        assertEquals(CheckRunStatus.QUEUED, states[CheckStage.CODEC])
    }

    @Test
    fun rerunEmitsOnlySelectedStageAndUnknownTotalStaysUnknown() = runBlocking {
        val calls = mutableListOf<CheckStage>()
        val progress = mutableListOf<CheckProgress>()
        val runner = ContentCheckRunner { stage, report ->
            calls += stage
            report(CheckProgress(17, null, "frames"))
        }
        runner.run(listOf(CheckStage.AUDIO_DECODE)) { _, state -> state.progress?.let(progress::add) }
        assertEquals(listOf(CheckStage.AUDIO_DECODE), calls)
        assertNull(progress.single().total)
    }

    @Test
    fun parsedIdentityMismatchInvalidatesAll() = runBlocking {
        val states = mutableMapOf<CheckStage, CheckRunStatus>()
        val runner = ContentCheckRunner { _, _ -> throw ContentFileChangedException() }
        assertFailsWith<ContentFileChangedException> {
            runner.run(listOf(CheckStage.STRUCTURE, CheckStage.CODEC)) { stage, state -> states[stage] = state.status }
        }
        assertEquals(CheckRunStatus.FAILED, states[CheckStage.STRUCTURE])
        assertEquals(CheckRunStatus.QUEUED, states[CheckStage.CODEC])
    }
}
