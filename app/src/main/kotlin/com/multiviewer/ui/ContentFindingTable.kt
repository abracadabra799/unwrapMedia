package com.multiviewer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal fun contentFindingTableRows(findings: List<ContentFinding>, showAll: Boolean): List<ContentFinding> =
    orderedContentFindings(findings, showAll)

internal fun contentFindingTableRows(findings: List<ContentFinding>, filter: ContentFindingFilter): List<ContentFinding> =
    filteredContentFindings(findings, filter)

internal enum class ContentFindingEmptyState { NO_CHECKS, FILTERED }

internal fun contentFindingEmptyState(findings: List<ContentFinding>, showAll: Boolean): ContentFindingEmptyState? = when {
    findings.isEmpty() -> ContentFindingEmptyState.NO_CHECKS
    contentFindingTableRows(findings, showAll).isEmpty() -> ContentFindingEmptyState.FILTERED
    else -> null
}

internal fun contentFindingEmptyState(findings: List<ContentFinding>, filter: ContentFindingFilter): ContentFindingEmptyState? = when {
    findings.isEmpty() -> ContentFindingEmptyState.NO_CHECKS
    contentFindingTableRows(findings, filter).isEmpty() -> ContentFindingEmptyState.FILTERED
    else -> null
}

internal fun contentFindingTableSummary(findings: List<ContentFinding>): ContentFindingSummary =
    contentFindingSummary(findings)

private fun findingStatusLabel(status: com.multiviewer.parser.integrity.CheckStatus, ko: Boolean): String =
    if (status == com.multiviewer.parser.integrity.CheckStatus.SKIP) {
        if (ko) "미검증" else "Unverified"
    } else checkStatusLabel(status, ko)

internal fun isSelectableContentRange(finding: ContentFinding, fileLength: Long): Boolean {
    val offset = finding.offset ?: return false
    val length = finding.length ?: return false
    if (offset < 0 || length <= 0 || fileLength < 0) return false
    val end = offset + length
    return end >= offset && end <= fileLength
}

internal const val MAX_COLLAPSED_FINDING_SUMMARY_LENGTH = 240
private const val MAX_EXPANDED_FINDING_SUMMARY_LENGTH = 4000

internal fun contentFindingCollapsedSummary(summary: String): String =
    if (summary.length <= MAX_COLLAPSED_FINDING_SUMMARY_LENGTH) summary
    else summary.take(MAX_COLLAPSED_FINDING_SUMMARY_LENGTH - 1).trimEnd() + "…"

internal fun contentFindingExpandedSummary(summary: String): String = summary.take(MAX_EXPANDED_FINDING_SUMMARY_LENGTH)

internal fun canSelectContentFindingRange(finding: ContentFinding, fileLength: Long, callbackAvailable: Boolean): Boolean =
    callbackAvailable && isSelectableContentRange(finding, fileLength)

internal fun unknownDecoderCauseLabel(finding: ContentFinding, ko: Boolean): String? =
    if (finding.stage == FindingStage.VIDEO_DECODE && finding.id.startsWith("video.decode.log.") && finding.hypothesis == null) {
        if (ko) "원인 미확정" else "Cause not determined"
    } else null

@Composable
internal fun ContentFindingSummaryBar(findings: List<ContentFinding>, ko: Boolean, modifier: Modifier = Modifier) {
    val counts = contentFindingTableSummary(findings)
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(if (ko) "검사 항목 ${counts.total}" else "Findings ${counts.total}", color = AppColors.TextPrimary, fontSize = 12.sp)
        Text((if (ko) "실패 " else "Fail ") + counts.failures, color = AppColors.NeonRed, fontSize = 12.sp)
        Text((if (ko) "경고 " else "Warn ") + counts.warnings, color = AppColors.NeonYellow, fontSize = 12.sp)
        Text((if (ko) "정상·정보 " else "Pass/info ") + counts.passed, color = AppColors.NeonGreen, fontSize = 12.sp)
    }
}

@Composable
internal fun ContentFindingTable(
    findings: List<ContentFinding>,
    fileLength: Long,
    ko: Boolean,
    modifier: Modifier = Modifier,
    onSelectRange: ((Long, Long) -> Unit)? = null,
) {
    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    val rows = findings

    val maxCheckLen = rows.maxOfOrNull { it.title.length } ?: 10
    val checkWidth = when {
        maxCheckLen > 40 -> 360.dp
        maxCheckLen > 25 -> 310.dp
        maxCheckLen > 15 -> 260.dp
        else -> 200.dp
    }

    val maxLocLen = rows.maxOfOrNull { contentFindingLocationLabel(it, ko).length } ?: 10
    val locWidth = when {
        maxLocLen > 35 -> 260.dp
        maxLocLen > 25 -> 220.dp
        maxLocLen > 15 -> 170.dp
        else -> 120.dp
    }
    val statusWidth = 65.dp

    Column(modifier.fillMaxSize()) {
        ContentFindingSummaryBar(findings, ko, Modifier.padding(bottom = 6.dp))

        // Table Grid Container with rounded border
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
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
                Box(Modifier.width(statusWidth).padding(horizontal = 8.dp, vertical = 7.dp)) {
                    Text(if (ko) "상태" else "Status", color = AppColors.TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                }
                TableVerticalDivider(AppColors.Border.copy(alpha = 0.6f))
                Box(Modifier.width(checkWidth).padding(horizontal = 8.dp, vertical = 7.dp)) {
                    Text(if (ko) "검사" else "Check", color = AppColors.TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                }
                TableVerticalDivider(AppColors.Border.copy(alpha = 0.6f))
                Box(Modifier.width(locWidth).padding(horizontal = 8.dp, vertical = 7.dp)) {
                    Text(if (ko) "위치" else "Location", color = AppColors.TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                }
                TableVerticalDivider(AppColors.Border.copy(alpha = 0.6f))
                Box(Modifier.weight(1f).padding(horizontal = 8.dp, vertical = 7.dp)) {
                    Text(if (ko) "결과" else "Finding", color = AppColors.TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            HorizontalDivider(color = AppColors.Border, thickness = 1.dp)

            // Table Rows
            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                itemsIndexed(rows, key = { index, finding -> "${finding.id}#$index" }) { index, finding ->
                    val rowKey = "${finding.id}#$index"
                    val isExpanded = expanded[rowKey] == true

                    if (index > 0) {
                        HorizontalDivider(color = AppColors.Border.copy(alpha = 0.35f), thickness = 1.dp)
                    }

                    Column(
                        Modifier
                            .fillMaxWidth()
                            .background(if (isExpanded) AppColors.Panel.copy(alpha = 0.3f) else Color.Transparent)
                            .clickable { expanded[rowKey] = !isExpanded },
                    ) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .height(IntrinsicSize.Min),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // Column 1: Status
                            Box(
                                Modifier.width(statusWidth).padding(horizontal = 8.dp, vertical = 5.dp),
                                contentAlignment = Alignment.CenterStart,
                            ) {
                                Text(
                                    findingStatusLabel(finding.status, ko),
                                    color = checkStatusColor(finding.status),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                )
                            }
                            TableVerticalDivider()

                            // Column 2: Check (Title)
                            Box(
                                Modifier.width(checkWidth).padding(horizontal = 8.dp, vertical = 5.dp),
                                contentAlignment = Alignment.CenterStart,
                            ) {
                                val titleFontSize = when {
                                    finding.title.length > 35 -> 10.sp
                                    finding.title.length > 20 -> 10.5.sp
                                    else -> 11.5.sp
                                }
                                Text(
                                    finding.title,
                                    color = AppColors.TextPrimary,
                                    fontSize = titleFontSize,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            TableVerticalDivider()

                            // Column 3: Location
                            Box(
                                Modifier.width(locWidth).padding(horizontal = 8.dp, vertical = 5.dp),
                                contentAlignment = Alignment.CenterStart,
                            ) {
                                val loc = contentFindingLocationLabel(finding, ko)
                                val locFontSize = when {
                                    loc.length > 30 -> 9.5.sp
                                    loc.length > 18 -> 10.5.sp
                                    else -> 11.sp
                                }
                                Text(
                                    loc,
                                    color = AppColors.TextSecondary,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = locFontSize,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            TableVerticalDivider()

                            // Column 4: Finding (Summary)
                            Box(
                                Modifier.weight(1f).padding(horizontal = 8.dp, vertical = 5.dp),
                                contentAlignment = Alignment.CenterStart,
                            ) {
                                Text(
                                    contentFindingCollapsedSummary(finding.summary),
                                    color = AppColors.TextSecondary,
                                    fontSize = 11.5.sp,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }

                        if (isExpanded) {
                            HorizontalDivider(color = AppColors.Border.copy(alpha = 0.2f), thickness = 1.dp)
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(start = 83.dp, end = 12.dp, top = 6.dp, bottom = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(3.dp),
                            ) {
                                DetailLine(if (ko) "검사 ID" else "Check ID", finding.id, monospace = true)
                                if (finding.summary.length > MAX_COLLAPSED_FINDING_SUMMARY_LENGTH) {
                                    DetailLine(if (ko) "전체 결과" else "Full result", contentFindingExpandedSummary(finding.summary))
                                }
                                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 260.dp)) {
                                    itemsIndexed(finding.verifiedFacts, key = { factIndex, _ -> "${finding.id}.fact.$factIndex" }) { _, fact ->
                                        DetailLine(if (ko) "검증된 사실" else "Verified facts", fact)
                                    }
                                }
                                finding.hypothesis?.let { DetailLine(if (ko) "가능성 / 가설" else "Possible cause / hypothesis", it) }
                                unknownDecoderCauseLabel(finding, ko)?.let {
                                    DetailLine(if (ko) "가능성 / 가설" else "Possible cause / hypothesis", it)
                                }
                                finding.nextChecks.take(10).forEach { DetailLine(if (ko) "다음 확인" else "Next checks", it) }
                                if (finding.summary.length > MAX_EXPANDED_FINDING_SUMMARY_LENGTH || finding.nextChecks.size > 10) {
                                    Text(if (ko) "세부 정보 일부 생략됨" else "Some details omitted", color = AppColors.TextMuted, fontSize = 11.sp)
                                }
                                if (onSelectRange != null && canSelectContentFindingRange(finding, fileLength, callbackAvailable = true)) {
                                    Text(
                                        if (ko) "Hex에서 바이트 범위 선택" else "Select byte range in Hex",
                                        color = AppColors.NeonBlue,
                                        modifier = Modifier.clickable {
                                            onSelectRange.invoke(finding.offset!!, finding.length!!)
                                        }.padding(vertical = 3.dp),
                                        fontSize = 11.sp,
                                    )
                                }
                            }
                        }
                    }
                }

                if (rows.isEmpty()) item {
                    val emptyText = if (ko)
                        "검사 결과 항목이 없습니다. 이 결과만으로 정상 여부를 판단할 수 없습니다."
                    else "No check results are available. This alone does not establish a pass."
                    Text(emptyText, color = AppColors.TextMuted, modifier = Modifier.padding(12.dp))
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

@Composable
private fun DetailLine(label: String, value: String, monospace: Boolean = false) {
    Row(Modifier.fillMaxWidth()) {
        Text("$label: ", color = AppColors.TextPrimary, fontSize = 11.sp)
        androidx.compose.foundation.text.selection.SelectionContainer {
            Text(value.take(4000), color = AppColors.TextSecondary, fontSize = 11.sp,
                fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default)
        }
    }
}
