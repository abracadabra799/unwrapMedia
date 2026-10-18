package com.multiviewer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.multiviewer.parser.BoxNode
import com.multiviewer.parser.MediaSummary

enum class OverviewRowStatusTone {
    SUCCESS,
    RUNNING,
    WARNING,
    ERROR,
    IDLE,
    MUTED,
}

data class ContentCheckOverviewRow(
    val category: CheckCategory,
    val stage: CheckStage,
    val stageLabel: String,
    val targetLabel: String,
    val status: String,
    val statusTone: OverviewRowStatusTone,
    val verdict: String?,
    val progress: CheckProgress?,
    val isRunning: Boolean,
    val isAvailable: Boolean,
    val actionLabel: String,
    val canAction: Boolean,
)

fun buildContentCheckOverviewRows(
    specs: List<CheckStageSpec>,
    states: Map<CheckStage, CheckStageState>,
    type: MediaType,
    root: BoxNode?,
    summary: MediaSummary?,
    ko: Boolean,
    running: Boolean,
    fileChanged: Boolean,
    verdictResolver: (CheckStage) -> String?,
): List<ContentCheckOverviewRow> = specs.map { spec ->
    val state = states[spec.stage] ?: CheckStageState(CheckRunStatus.NOT_RUN)
    val isAvailable = spec.availability == CheckAvailability.AVAILABLE
    val (statusText, statusTone) = when (spec.availability) {
        CheckAvailability.UNSUPPORTED -> (if (ko) "미지원" else "Unsupported") to OverviewRowStatusTone.MUTED
        CheckAvailability.NOT_APPLICABLE -> (if (ko) "해당 없음" else "Not applicable") to OverviewRowStatusTone.MUTED
        CheckAvailability.LOADING -> (if (ko) "로딩 중" else "Loading") to OverviewRowStatusTone.RUNNING
        CheckAvailability.AVAILABLE -> when (state.status) {
            CheckRunStatus.NOT_RUN -> (if (ko) "미검사" else "Not run") to OverviewRowStatusTone.IDLE
            CheckRunStatus.QUEUED -> (if (ko) "대기" else "Queued") to OverviewRowStatusTone.IDLE
            CheckRunStatus.RUNNING -> (if (ko) "검사 중" else "Running") to OverviewRowStatusTone.RUNNING
            CheckRunStatus.COMPLETED -> (if (ko) "완료" else "Completed") to OverviewRowStatusTone.SUCCESS
            CheckRunStatus.PARTIAL -> (if (ko) "부분 검사" else "Partial") to OverviewRowStatusTone.WARNING
            CheckRunStatus.FAILED -> (if (ko) "실패" else "Failed") to OverviewRowStatusTone.ERROR
            CheckRunStatus.CANCELLED -> (if (ko) "취소" else "Cancelled") to OverviewRowStatusTone.MUTED
        }
    }
    val verdict = if (state.status == CheckRunStatus.COMPLETED || state.status == CheckRunStatus.PARTIAL) {
        verdictResolver(spec.stage)
    } else null

    val isRunning = state.status == CheckRunStatus.RUNNING
    val progress = if (isRunning) state.progress else null
    val actionLabel = checkStageActionLabel(state.status, ko)
    val canAction = !running && !fileChanged && isAvailable

    ContentCheckOverviewRow(
        category = checkCategory(spec.stage),
        stage = spec.stage,
        stageLabel = checkStageLabel(spec.stage, ko),
        targetLabel = checkStageTargetLabel(spec.stage, type, root, summary),
        status = statusText,
        statusTone = statusTone,
        verdict = verdict,
        progress = progress,
        isRunning = isRunning,
        isAvailable = isAvailable,
        actionLabel = actionLabel,
        canAction = canAction,
    )
}

@Composable
fun overviewStatusToneColor(tone: OverviewRowStatusTone): Color = when (tone) {
    OverviewRowStatusTone.SUCCESS -> AppColors.NeonGreen
    OverviewRowStatusTone.RUNNING -> AppColors.NeonYellow
    OverviewRowStatusTone.WARNING -> AppColors.NeonOrange
    OverviewRowStatusTone.ERROR -> AppColors.NeonRed
    OverviewRowStatusTone.IDLE -> AppColors.TextSecondary
    OverviewRowStatusTone.MUTED -> AppColors.TextMuted
}

@Composable
fun ContentCheckOverviewTable(
    rows: List<ContentCheckOverviewRow>,
    selectedStage: CheckStage,
    onSelectStage: (CheckStage) -> Unit,
    onRunStage: (CheckStage) -> Unit,
    ko: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, AppColors.Border, RoundedCornerShape(4.dp))
            .background(AppColors.Surface, RoundedCornerShape(4.dp)),
    ) {
        // Table Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(AppColors.Panel)
                .padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (ko) "구분" else "Category",
                color = AppColors.TextSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.width(165.dp),
            )
            Text(
                if (ko) "검사 항목" else "Check Item",
                color = AppColors.TextSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.width(155.dp),
            )
            Text(
                if (ko) "대상 / 규격" else "Target / Format",
                color = AppColors.TextSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.width(160.dp),
            )
            Text(
                if (ko) "상태" else "Status",
                color = AppColors.TextSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.width(90.dp),
            )
            Text(
                if (ko) "결과 요약" else "Summary / Verdict",
                color = AppColors.TextSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            Text(
                if (ko) "실행" else "Action",
                color = AppColors.TextSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.width(75.dp),
            )
        }

        // Table Rows
        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            itemsIndexed(rows, key = { _, row -> row.stage.name }) { index, row ->
                val isSelected = row.stage == selectedStage
                val isFirstOfCategory = index == 0 || rows[index - 1].category != row.category

                if (isFirstOfCategory && index > 0) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(AppColors.Border.copy(alpha = 0.5f)),
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(if (isSelected) AppColors.Surface.copy(alpha = 0.9f) else Color.Transparent)
                        .clickable { onSelectStage(row.stage) }
                        .padding(vertical = 5.dp, horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Category Column (with left accent stripe if selected)
                    Row(
                        modifier = Modifier.width(165.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (isSelected) {
                            Box(
                                modifier = Modifier
                                    .width(3.dp)
                                    .height(16.dp)
                                    .background(AppColors.NeonBlue, RoundedCornerShape(1.5.dp)),
                            )
                            Spacer(Modifier.width(6.dp))
                        } else {
                            Spacer(Modifier.width(9.dp))
                        }
                        Text(
                            checkCategoryLabel(row.category, ko),
                            color = if (isSelected) AppColors.NeonBlue else AppColors.TextSecondary,
                            fontSize = 11.sp,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    // Check Item Column
                    Text(
                        row.stageLabel,
                        color = if (isSelected) AppColors.TextPrimary else AppColors.TextPrimary.copy(alpha = 0.85f),
                        fontSize = 12.sp,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        modifier = Modifier.width(155.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )

                    // Target / Format Column
                    Text(
                        row.targetLabel,
                        color = AppColors.TextSecondary,
                        fontSize = 11.sp,
                        modifier = Modifier.width(160.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )

                    // Status Column
                    val toneColor = overviewStatusToneColor(row.statusTone)
                    Box(modifier = Modifier.width(90.dp)) {
                        Surface(
                            color = toneColor.copy(alpha = 0.12f),
                            shape = RoundedCornerShape(4.dp),
                        ) {
                            Text(
                                text = row.status,
                                color = toneColor,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }

                    // Summary / Verdict Column
                    Row(
                        modifier = Modifier.weight(1f).padding(end = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        if (row.isRunning) {
                            val progress = row.progress
                            if (progress?.total != null && progress.total > 0) {
                                LinearProgressIndicator(
                                    progress = { (progress.completed.toFloat() / progress.total).coerceIn(0f, 1f) },
                                    modifier = Modifier.width(100.dp).height(4.dp),
                                )
                            } else {
                                LinearProgressIndicator(
                                    modifier = Modifier.width(100.dp).height(4.dp),
                                )
                            }
                            if (progress != null) {
                                Text(
                                    checkProgressLabel(progress),
                                    color = AppColors.TextSecondary,
                                    fontSize = 11.sp,
                                )
                            }
                        } else {
                            Text(
                                row.verdict ?: "-",
                                color = if (row.verdict != null) AppColors.TextPrimary else AppColors.TextMuted,
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }

                    // Action Column
                    Box(modifier = Modifier.width(75.dp), contentAlignment = Alignment.CenterStart) {
                        if (row.isAvailable) {
                            OutlinedButton(
                                enabled = row.canAction,
                                onClick = { onRunStage(row.stage) },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                modifier = Modifier.height(24.dp),
                            ) {
                                Text(row.actionLabel, fontSize = 11.sp)
                            }
                        } else {
                            Text("-", color = AppColors.TextMuted, fontSize = 11.sp, modifier = Modifier.padding(start = 12.dp))
                        }
                    }
                }
            }
        }
    }
}
