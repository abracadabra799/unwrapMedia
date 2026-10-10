package com.multiviewer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

internal fun videoStatusLabel(value: IntegrityStatus, ko: Boolean): String {
    fun label(korean: String, english: String) = if (ko) korean else english
    return when (value) {
        IntegrityStatus.NOT_RUN -> label("미검사", "Not inspected")
        IntegrityStatus.CLEAN -> label("완료 · 오류 없음", "Completed · no errors")
        IntegrityStatus.ISSUES -> label("완료 · 오류 발견", "Completed · errors found")
        IntegrityStatus.FAILED -> label("검사 실패 · 정상 여부 확인 불가", "Failed · integrity unconfirmed")
    }
}

@Composable
internal fun VideoDecodePanel(report: VideoIntegrityReport, ko: Boolean, onShowPackets: () -> Unit, modifier: Modifier) {
    fun label(korean: String, english: String) = if (ko) korean else english
    var expandedDiagnostic by remember(report) { mutableStateOf<Int?>(null) }
    Text(videoStatusLabel(report.decodeStatus, ko) + label(" · 디코딩된 프레임: ", " · Decoded frames: ") + report.decodedFrames,
        color = AppColors.TextPrimary)
    Text(label("오류 로그만으로 정확한 패킷 위치를 확정할 수 없습니다. 패킷 탭에서 시간과 바이트 위치를 확인하세요.",
        "Logs do not establish exact error packet positions. Inspect timestamps and byte positions in the packet tab."),
        color = AppColors.TextSecondary)
    if (report.logsTruncated) Text(label("로그 저장 한도에 도달했습니다. 일부 로그는 생략됩니다.", "Log limit reached; some messages omitted."))
    val diagnostics = remember(report) { report.logs.map(::explainIntegrityLog) }
    SelectionContainer(modifier) {
        LazyColumn(Modifier.fillMaxSize()) {
            itemsIndexed(diagnostics) { index, diagnostic ->
                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(diagnostic.message, fontFamily = FontFamily.Monospace, color = AppColors.TextPrimary)
                    Text(label("위치 미확인 · 원인 해설은 추정입니다", "Location unavailable · explanation is a hypothesis"), color = AppColors.TextSecondary)
                    TextButton(onClick = { expandedDiagnostic = if (expandedDiagnostic == index) null else index }) {
                        Text(label("오류 해설: ", "Explanation (Korean): ") + diagnostic.explanation.title)
                    }
                    if (expandedDiagnostic == index) {
                        val explanation = diagnostic.explanation
                        Text(label("해석: ", "Interpretation: ") + explanation.summary, color = AppColors.TextPrimary)
                        Text(label("가능한 원인: ", "Possible cause: ") + explanation.probableCause, color = AppColors.TextPrimary)
                        Text(label("가능한 영향: ", "Possible impact: ") + explanation.visualImpact, color = AppColors.TextPrimary)
                        Text(label("추가 확인: ", "Next checks: ") + explanation.actionableFix, color = AppColors.TextPrimary)
                        TextButton(onClick = onShowPackets) { Text(label("패킷 목록에서 직접 확인", "Inspect packet list manually")) }
                    }
                }
            }
        }
    }
}

@Composable
internal fun VideoPacketPanel(report: VideoIntegrityReport, tab: TabState, ko: Boolean, modifier: Modifier) {
    fun label(korean: String, english: String) = if (ko) korean else english
    var selectedPacket by remember(report) { mutableStateOf<IntegrityPacket?>(null) }
    Text(videoStatusLabel(report.packetStatus, ko) + " · ${report.packets.size}" + if (report.packetsTruncated) " (limited to 100,000)" else "",
        color = AppColors.TextPrimary)
    Text(label("패킷을 선택하면 기존 Hex 뷰어에서 해당 범위를 강조합니다. N/A는 정보 없음입니다.",
        "Select a packet to highlight its bytes in the main Hex viewer. N/A means unavailable."), color = AppColors.TextSecondary)
    Text("#     PTS(s)          DTS(s)          Offset         Bytes       Key", fontFamily = FontFamily.Monospace, color = AppColors.TextSecondary)
    LazyColumn(modifier) {
        items(report.packetLogs) { Text(it, color = AppColors.NeonRed) }
        items(report.packets, key = { it.index }) { packet ->
            Text("${packet.index.toString().padEnd(6)}${(packet.pts ?: "N/A").padEnd(16)}${(packet.dts ?: "N/A").padEnd(16)}${(packet.offset?.toString() ?: "N/A").padEnd(15)}${(packet.size?.toString() ?: "N/A").padEnd(12)}${if (packet.keyframe) "K" else ""}",
                fontFamily = FontFamily.Monospace, color = AppColors.TextPrimary,
                modifier = Modifier.fillMaxWidth().background(if (selectedPacket == packet) AppColors.Selection else AppColors.Background)
                    .clickable {
                        selectedPacket = packet
                        val offset = packet.offset
                        val size = packet.size
                        if (offset != null && size != null && offset < tab.file.length() && size <= tab.file.length() - offset) {
                            tab.parameterSetHighlightRange = offset until (offset + size)
                        }
                    }.padding(vertical = 5.dp))
        }
    }
}
