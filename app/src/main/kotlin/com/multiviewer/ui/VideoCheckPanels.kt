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
internal fun VideoDecodePanel(
    report: VideoIntegrityReport,
    ko: Boolean,
    expandedDiagnostic: Int?,
    onExpandedDiagnosticChange: (Int?) -> Unit,
    onShowPackets: () -> Unit,
    modifier: Modifier,
) {
    fun label(korean: String, english: String) = if (ko) korean else english
    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(videoStatusLabel(report.decodeStatus, ko) + label(" · 디코딩된 프레임: ", " · Decoded frames: ") + report.decodedFrames,
            color = AppColors.TextPrimary)
        Text(label("오류 로그는 바이트 위치를 특정하지 않습니다. 원인 해석은 가설로만 표시합니다.",
            "Decoder logs do not establish byte locations; interpretations are hypotheses."), color = AppColors.TextSecondary)
        if (report.logsTruncated) Text(label("로그 저장 한도에 도달했습니다. 일부 로그는 생략됩니다.", "Log limit reached; some messages omitted."))
        ContentFindingTable(videoFindings(report).decoderFindings, Long.MAX_VALUE, ko, Modifier.weight(1f))
        if (report.logs.isNotEmpty()) TextButton(onClick = onShowPackets, shape = AppControlShape) {
            Text(label("패킷 목록에서 직접 확인", "Inspect packet list manually"))
        }
    }
}

@Composable
internal fun VideoPacketPanel(
    report: VideoIntegrityReport,
    tab: TabState,
    ko: Boolean,
    selectedPacket: IntegrityPacket?,
    onSelectedPacketChange: (IntegrityPacket) -> Unit,
    modifier: Modifier,
) {
    fun label(korean: String, english: String) = if (ko) korean else english
    Column(modifier.fillMaxSize()) {
        Text(videoStatusLabel(report.packetStatus, ko) + " · ${report.packets.size}" + if (report.packetsTruncated) " (limited to 100,000)" else "",
            color = AppColors.TextPrimary)
        Text(label("패킷을 선택하면 기존 Hex 뷰어에서 해당 범위를 강조합니다. N/A는 정보 없음입니다.",
            "Select a packet to highlight its bytes in the main Hex viewer. N/A means unavailable."), color = AppColors.TextSecondary)
        val findings = videoFindings(report)
        if (findings.packetFindings.isNotEmpty()) {
            ContentFindingTable(findings.packetFindings, tab.file.length(), ko, Modifier.weight(0.38f))
        }
        Text("#     PTS(s)          DTS(s)          Offset         Bytes       Key", fontFamily = FontFamily.Monospace, color = AppColors.TextSecondary)
        LazyColumn(Modifier.weight(if (findings.packetFindings.isNotEmpty()) 0.62f else 1f)) {
            items(report.packets, key = { it.index }) { packet ->
                Text("${packet.index.toString().padEnd(6)}${(packet.pts ?: "N/A").padEnd(16)}${(packet.dts ?: "N/A").padEnd(16)}${(packet.offset?.toString() ?: "N/A").padEnd(15)}${(packet.size?.toString() ?: "N/A").padEnd(12)}${if (packet.keyframe) "K" else ""}",
                    fontFamily = FontFamily.Monospace, color = AppColors.TextPrimary,
                    modifier = Modifier.fillMaxWidth().background(if (selectedPacket == packet) AppColors.Selection else AppColors.Background)
                        .clickable {
                            onSelectedPacketChange(packet)
                            val offset = packet.offset
                            val size = packet.size
                            if (offset != null && size != null && offset < tab.file.length() && size <= tab.file.length() - offset) {
                                tab.parameterSetHighlightRange = offset until (offset + size)
                            }
                        }.padding(vertical = 5.dp))
            }
        }
    }
}
