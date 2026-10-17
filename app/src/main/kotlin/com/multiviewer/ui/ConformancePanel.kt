package com.multiviewer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.multiviewer.parser.conformance.ConformanceReport
import com.multiviewer.parser.conformance.ConformanceStatus

internal fun conformanceStatusLabel(status: ConformanceStatus, ko: Boolean): String = when (status) {
    ConformanceStatus.PASS -> if (ko) "구현된 검사 통과" else "Implemented checks passed"
    ConformanceStatus.FAIL -> if (ko) "실패" else "Fail"
    ConformanceStatus.WARN -> if (ko) "경고" else "Warning"
    ConformanceStatus.NOT_CHECKED -> if (ko) "미검증" else "Not checked"
    ConformanceStatus.NOT_APPLICABLE -> if (ko) "해당 없음" else "Not applicable"
}

@Composable
internal fun conformanceStatusColor(status: ConformanceStatus): Color = when (status) {
    ConformanceStatus.PASS -> AppColors.NeonGreen
    ConformanceStatus.FAIL -> AppColors.NeonRed
    ConformanceStatus.WARN -> AppColors.NeonYellow
    ConformanceStatus.NOT_CHECKED, ConformanceStatus.NOT_APPLICABLE -> AppColors.TextMuted
}

/** Presentation only: the report is produced by the window's cancellable IO workflow. */
@Composable
fun ConformancePanel(report: ConformanceReport?, language: AppLanguage,
                     coverageStatus: ConformanceStatus? = null, onSelectRange: (Long, Long) -> Unit) {
    val ko = language == AppLanguage.KO
    fun label(korean: String, english: String) = if (ko) korean else english
    if (report == null) {
        Text(label("규격 검사 준비 중 · 미검증", "Preparing conformance checks · not checked"), color = AppColors.TextMuted)
        return
    }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val shownStatus = coverageStatus ?: report.overall
        val normative = report.findings.filterNot { it.rule.source.startsWith("unwrapMedia structural check") ||
            it.rule.source.contains("structural observation") }
        Text(conformanceStatusLabel(shownStatus, ko), color = conformanceStatusColor(shownStatus))
        if (shownStatus == ConformanceStatus.NOT_CHECKED && report.overall == ConformanceStatus.PASS) {
            Text(label("검사 완료 · 일부 미검증", "Completed · some rules not checked"), color = AppColors.NeonYellow)
        }
        Text("${report.format} · ${report.profile}", color = AppColors.TextSecondary)
        Text(
            label("전체 규격 준수를 의미하지 않습니다. 아래 구현된 검사와 범위만 평가합니다.",
                "This does not establish full specification compliance. Only the implemented checks and scopes below are evaluated."),
            color = AppColors.TextSecondary, fontSize = 12.sp,
        )
        Text(ConformanceStatus.entries.joinToString(" · ") { status ->
            "${status.name}: ${normative.count { it.status == status }}"
        }, color = AppColors.TextSecondary, fontSize = 12.sp)
        Text(label("구조 관측 ${report.findings.size - normative.size}건 · 규격 판정과 별도",
            "${report.findings.size - normative.size} structural observations · separate from rule verdicts"),
            color = AppColors.TextSecondary, fontSize = 12.sp)
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                SelectionContainer {
                    Column(Modifier.fillMaxWidth().background(AppColors.Surface).padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(label("제한 및 파서 근거 (규격 판정과 별도)", "Limitations and parser evidence (separate from conformance)"), color = AppColors.TextPrimary)
                        report.inspectionError?.let { Text(label("검사 미완료: ", "Inspection incomplete: ") + it, color = AppColors.NeonYellow) }
                        report.limitations.forEach { Text(it, color = AppColors.TextSecondary, fontSize = 12.sp) }
                    }
                }
            }
            items(report.findings, key = { it.rule.id }) { finding ->
                Column(Modifier.fillMaxWidth().background(AppColors.Surface).padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    SelectionContainer {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("[${finding.status.name}] ${finding.rule.id} · ${finding.rule.title}", color = conformanceStatusColor(finding.status))
                            Text(label("출처: ", "Source: ") + finding.rule.source, color = AppColors.TextSecondary, fontSize = 12.sp)
                            finding.rule.reference?.let { Text(it, color = AppColors.NeonBlue, fontSize = 12.sp) }
                            Text(label("검사 범위: ", "Scope: ") + finding.rule.scope, color = AppColors.TextSecondary, fontSize = 12.sp)
                            finding.details.forEach { Text(it, color = AppColors.TextPrimary, fontSize = 12.sp) }
                        }
                    }
                    finding.ranges.distinct().forEach { range ->
                        val extent = range.last - range.first
                        val selectable = range.first >= 0 && range.last >= range.first && extent < Long.MAX_VALUE
                        TextButton(enabled = selectable, onClick = { onSelectRange(range.first, extent + 1) }) {
                            Text(label("Hex 근거: ", "Hex evidence: ") +
                                "0x${range.first.toString(16).uppercase()}–0x${range.last.toString(16).uppercase()}")
                        }
                    }
                }
            }
        }
    }
}
