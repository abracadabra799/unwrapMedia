package com.multiviewer.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Runs one requested stage at a time; reports are published by [execute] before COMPLETED is sent. */
class ContentCheckRunner(
    private val execute: suspend (CheckStage, (CheckProgress) -> Unit) -> Unit,
) {
    suspend fun run(stages: List<CheckStage>, onState: (CheckStage, CheckStageState) -> Unit) {
        stages.forEach { onState(it, CheckStageState(CheckRunStatus.QUEUED)) }
        for (stage in stages) {
            currentCoroutineContext().ensureActive()
            onState(stage, CheckStageState(CheckRunStatus.RUNNING))
            try {
                execute(stage) { progress ->
                    onState(stage, CheckStageState(CheckRunStatus.RUNNING, progress))
                }
                currentCoroutineContext().ensureActive()
                onState(stage, CheckStageState(CheckRunStatus.COMPLETED))
            } catch (e: CancellationException) {
                onState(stage, CheckStageState(CheckRunStatus.CANCELLED, reason = e.message))
                throw e
            } catch (e: ContentFileChangedException) {
                onState(stage, CheckStageState(CheckRunStatus.FAILED, reason = e.message))
                throw e
            } catch (e: Exception) {
                onState(stage, CheckStageState(CheckRunStatus.FAILED, reason = e.message ?: e.toString()))
            }
        }
    }
}
