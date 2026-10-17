package com.multiviewer.ui

enum class CheckRunStatus { NOT_RUN, QUEUED, RUNNING, COMPLETED, PARTIAL, FAILED, CANCELLED }

data class CheckProgress(val completed: Long, val total: Long?, val unit: String)

data class CheckStageState(
    val status: CheckRunStatus,
    val progress: CheckProgress? = null,
    val reason: String? = null,
)

fun initialCheckStates(specs: List<CheckStageSpec>): Map<CheckStage, CheckStageState> =
    specs.associate { it.stage to CheckStageState(CheckRunStatus.NOT_RUN) }

fun queueApplicableStages(specs: List<CheckStageSpec>): Map<CheckStage, CheckStageState> =
    specs.filter { it.availability == CheckAvailability.AVAILABLE }
        .associate { it.stage to CheckStageState(CheckRunStatus.QUEUED) }

fun shouldPublishCheckState(runMatches: Boolean, jobActive: Boolean, status: CheckRunStatus): Boolean =
    runMatches && (jobActive || status == CheckRunStatus.CANCELLED)
