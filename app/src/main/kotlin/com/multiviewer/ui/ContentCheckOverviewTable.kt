package com.multiviewer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
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
): List<ContentCheckOverviewRow> = specs
    .filterNot { spec ->
        type == MediaType.IMAGE && (spec.stage == CheckStage.CODEC || spec.stage == CheckStage.AUDIO_DECODE)
    }
    .map { spec ->
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
    val maxTargetLen = rows.maxOfOrNull { it.targetLabel.length } ?: 10
    val targetWidth = when {
        maxTargetLen > 24 -> 200.dp
        maxTargetLen > 16 -> 175.dp
        else -> 150.dp
    }
    val catWidth = 145.dp
    val stageWidth = 150.dp
    val statusWidth = 72.dp
    val actionWidth = 72.dp

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
                .height(IntrinsicSize.Min),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(catWidth).padding(horizontal = 8.dp, vertical = 7.dp)) {
                Text(
                    if (ko) "구분" else "Category",
                    color = AppColors.TextSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            TableVerticalDivider(AppColors.Border.copy(alpha = 0.6f))
            Box(Modifier.width(stageWidth).padding(horizontal = 8.dp, vertical = 7.dp)) {
                Text(
                    if (ko) "검사 항목" else "Check Item",
                    color = AppColors.TextSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            TableVerticalDivider(AppColors.Border.copy(alpha = 0.6f))
            Box(Modifier.width(targetWidth).padding(horizontal = 8.dp, vertical = 7.dp)) {
                Text(
                    if (ko) "대상 / 규격" else "Target / Format",
                    color = AppColors.TextSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            TableVerticalDivider(AppColors.Border.copy(alpha = 0.6f))
            Box(Modifier.width(statusWidth).padding(horizontal = 8.dp, vertical = 7.dp)) {
                Text(
                    if (ko) "상태" else "Status",
                    color = AppColors.TextSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            TableVerticalDivider(AppColors.Border.copy(alpha = 0.6f))
            Box(Modifier.weight(1f).padding(horizontal = 8.dp, vertical = 7.dp)) {
                Text(
                    if (ko) "결과 요약" else "Summary / Verdict",
                    color = AppColors.TextSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            TableVerticalDivider(AppColors.Border.copy(alpha = 0.6f))
            Box(Modifier.width(actionWidth).padding(horizontal = 8.dp, vertical = 7.dp), contentAlignment = Alignment.Center) {
                Text(
                    if (ko) "실행" else "Action",
                    color = AppColors.TextSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        HorizontalDivider(color = AppColors.Border, thickness = 1.dp)

        // Table Rows
        Column(modifier = Modifier.fillMaxWidth()) {
            rows.forEachIndexed { index, row ->
                val isSelected = row.stage == selectedStage
                val isFirstOfCategory = index == 0 || rows[index - 1].category != row.category

                if (index > 0) {
                    val dividerColor = if (isFirstOfCategory) AppColors.Border else AppColors.Border.copy(alpha = 0.35f)
                    HorizontalDivider(color = dividerColor, thickness = 1.dp)
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Min)
                        .background(
                            when {
                                isSelected -> AppColors.NeonBlue.copy(alpha = 0.12f)
                                else -> Color.Transparent
                            }
                        )
                        .clickable { onSelectStage(row.stage) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Category Column (with left accent stripe if selected)
                    Row(
                        modifier = Modifier.width(catWidth).padding(horizontal = 8.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (isSelected) {
                            Box(
                                modifier = Modifier
                                    .width(3.dp)
                                    .height(14.dp)
                                    .background(AppColors.NeonBlue, RoundedCornerShape(1.5.dp)),
                            )
                            Spacer(Modifier.width(5.dp))
                        } else {
                            Spacer(Modifier.width(8.dp))
                        }
                        val catText = checkCategoryLabel(row.category, ko)
                        val catFontSize = if (catText.length > 20) 10.sp else 11.sp
                        Text(
                            catText,
                            color = if (isSelected) AppColors.NeonBlue else AppColors.TextSecondary,
                            fontSize = catFontSize,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    TableVerticalDivider()

                    // Check Item Column
                    Box(
                        modifier = Modifier.width(stageWidth).padding(horizontal = 8.dp, vertical = 5.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        val stageFontSize = if (row.stageLabel.length > 16) 10.5.sp else 11.5.sp
                        Text(
                            row.stageLabel,
                            color = if (isSelected) AppColors.TextPrimary else AppColors.TextPrimary.copy(alpha = 0.85f),
                            fontSize = stageFontSize,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    TableVerticalDivider()

                    // Target / Format Column
                    Box(
                        modifier = Modifier.width(targetWidth).padding(horizontal = 8.dp, vertical = 5.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        val targetFontSize = if (row.targetLabel.length > 20) 10.sp else 11.sp
                        Text(
                            row.targetLabel,
                            color = AppColors.TextSecondary,
                            fontSize = targetFontSize,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    TableVerticalDivider()

                    // Status Column
                    val toneColor = overviewStatusToneColor(row.statusTone)
                    Box(
                        modifier = Modifier.width(statusWidth).padding(horizontal = 8.dp, vertical = 5.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Surface(
                            color = toneColor.copy(alpha = 0.12f),
                            shape = RoundedCornerShape(4.dp),
                        ) {
                            Text(
                                text = row.status,
                                color = toneColor,
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                maxLines = 1,
                            )
                        }
                    }
                    TableVerticalDivider()

                    // Summary / Verdict Column
                    Row(
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp, vertical = 5.dp),
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
                            val verdictText = row.verdict ?: "-"
                            val verdictFontSize = if (verdictText.length > 30) 10.5.sp else 11.sp
                            Text(
                                verdictText,
                                color = if (row.verdict != null) AppColors.TextPrimary else AppColors.TextMuted,
                                fontSize = verdictFontSize,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    TableVerticalDivider()

                    // Action Column
                    Box(
                        modifier = Modifier.width(actionWidth).padding(horizontal = 6.dp, vertical = 4.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (row.isAvailable) {
                            OutlinedButton(
                                enabled = row.canAction,
                                onClick = { onRunStage(row.stage) },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                modifier = Modifier.height(24.dp).fillMaxWidth(),
                            ) {
                                Text(row.actionLabel, fontSize = 10.5.sp)
                            }
                        } else {
                            Text("-", color = AppColors.TextMuted, fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TableVerticalDivider(color: Color = AppColors.Border.copy(alpha = 0.4f)) {
    Box(
        modifier = Modifier
            .fillMaxHeight()
            .width(1.dp)
            .background(color)
    )
}
