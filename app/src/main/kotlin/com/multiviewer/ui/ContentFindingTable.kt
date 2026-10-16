package com.multiviewer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal fun contentFindingTableRows(findings: List<ContentFinding>, showAll: Boolean): List<ContentFinding> =
    orderedContentFindings(findings, showAll)

internal enum class ContentFindingEmptyState { NO_CHECKS, FILTERED }

internal fun contentFindingEmptyState(findings: List<ContentFinding>, showAll: Boolean): ContentFindingEmptyState? = when {
    findings.isEmpty() -> ContentFindingEmptyState.NO_CHECKS
    contentFindingTableRows(findings, showAll).isEmpty() -> ContentFindingEmptyState.FILTERED
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
        Text((if (ko) "미검증 " else "Unverified ") + counts.unverified, color = AppColors.TextMuted, fontSize = 12.sp)
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
    var showAll by remember { mutableStateOf(true) }
    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    val rows = contentFindingTableRows(findings, showAll)
    Column(modifier.fillMaxSize()) {
        ContentFindingSummaryBar(findings, ko, Modifier.padding(bottom = 3.dp))
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Checkbox(checked = showAll, onCheckedChange = { showAll = it })
            Text(if (ko) "통과·미검증 항목 포함" else "Show pass and unverified checks", color = AppColors.TextSecondary, fontSize = 12.sp)
        }
        Row(Modifier.fillMaxWidth().background(AppColors.Panel).padding(vertical = 5.dp)) {
            Text(if (ko) "상태" else "Status", color = AppColors.TextSecondary, modifier = Modifier.width(72.dp), fontSize = 11.sp)
            Text(if (ko) "검사" else "Check", color = AppColors.TextSecondary, modifier = Modifier.width(185.dp), fontSize = 11.sp)
            Text(if (ko) "위치" else "Location", color = AppColors.TextSecondary, modifier = Modifier.width(130.dp), fontSize = 11.sp)
            Text(if (ko) "결과" else "Finding", color = AppColors.TextSecondary, fontSize = 11.sp)
        }
        LazyColumn(Modifier.weight(1f)) {
            itemsIndexed(rows, key = { index, finding -> "${finding.id}#$index" }) { index, finding ->
                val rowKey = "${finding.id}#$index"
                val isExpanded = expanded[rowKey] == true
                Column(Modifier.fillMaxWidth().clickable { expanded[rowKey] = !isExpanded }) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                        Text(findingStatusLabel(finding.status, ko), color = checkStatusColor(finding.status), modifier = Modifier.width(72.dp), fontSize = 11.sp)
                        Text(finding.title, color = AppColors.TextPrimary, modifier = Modifier.width(185.dp), fontSize = 12.sp)
                        Text(contentFindingLocationLabel(finding, ko), color = AppColors.TextSecondary,
                            fontFamily = FontFamily.Monospace, modifier = Modifier.width(130.dp), fontSize = 11.sp)
                        Text(contentFindingCollapsedSummary(finding.summary), color = AppColors.TextSecondary,
                            fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    if (isExpanded) {
                        Column(Modifier.fillMaxWidth().padding(start = 72.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
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
                                Text(if (ko) "Hex에서 바이트 범위 선택" else "Select byte range in Hex",
                                    color = AppColors.NeonBlue, modifier = Modifier.clickable {
                                        onSelectRange.invoke(finding.offset!!, finding.length!!)
                                    }.padding(vertical = 3.dp), fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
            if (rows.isEmpty()) item {
                val counts = contentFindingTableSummary(findings)
                val emptyText = when (contentFindingEmptyState(findings, showAll)) {
                    ContentFindingEmptyState.NO_CHECKS -> if (ko)
                        "검사 결과 항목이 없습니다. 이 결과만으로 정상 여부를 판단할 수 없습니다."
                    else "No check results are available. This alone does not establish a pass."
                    ContentFindingEmptyState.FILTERED -> if (ko)
                        "표시 중인 실패·경고 없음 · 검사 ${counts.total}건 중 정상·정보 ${counts.passed}건, 미검증 ${counts.unverified}건"
                    else "No failures or warnings in this filter · ${counts.total} checks: ${counts.passed} pass/info, ${counts.unverified} unverified"
                    null -> ""
                }
                Text(emptyText, color = AppColors.TextMuted, modifier = Modifier.padding(12.dp))
            }
        }
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text("$label: ", color = AppColors.TextPrimary, fontSize = 11.sp)
        androidx.compose.foundation.text.selection.SelectionContainer {
            Text(value.take(4000), color = AppColors.TextSecondary, fontSize = 11.sp)
        }
    }
}
