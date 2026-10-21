package com.multiviewer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import com.multiviewer.util.ClipboardUtil
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.abs

@Composable
fun AvSyncAnalysisWindow(
    tab: TabState,
    themeMode: ThemeMode = ThemeMode.DARK,
    onCloseRequest: () -> Unit,
) {
    val windowState = rememberWindowState(
        size = DpSize(1000.dp, 750.dp),
        position = WindowPosition(Alignment.Center),
    )

    Window(
        onCloseRequest = onCloseRequest,
        state = windowState,
        title = "A/V 싱크 및 타임스탬프 드리프트 정밀 분석 - ${tab.file.name}",
    ) {
        var report by remember(tab.file) { mutableStateOf<AvSyncReport?>(null) }
        var isLoading by remember(tab.file) { mutableStateOf(true) }
        var timedOut by remember(tab.file) { mutableStateOf(false) }
        var selectedSyncPoint by remember { mutableStateOf<SyncPoint?>(null) }

        LaunchedEffect(tab.file) {
            if (tab.avSyncReport != null) {
                report = tab.avSyncReport
                isLoading = false
            } else {
                isLoading = true
                val analysis = AvSyncAnalyzer.analyzeWithStatus(tab.file)
                val analyzed = analysis.report
                timedOut = analysis.timedOut
                report = analyzed
                tab.avSyncReport = analyzed
                isLoading = false
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(AppColors.Background)
        ) {
            when {
                isLoading -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CircularProgressIndicator(
                            color = AppColors.NeonBlue,
                            strokeWidth = 3.dp,
                            modifier = Modifier.size(36.dp)
                        )
                        Text(
                            "비디오 및 오디오 패킷 타임라인(PTS) 분석 중...",
                            style = AppTypography.bodyLarge.copy(color = AppColors.TextSecondary)
                        )
                    }
                }
                report == null -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            "A/V 싱크 분석 불가",
                            style = AppTypography.headlineSmall.copy(color = AppColors.TextPrimary, fontWeight = FontWeight.Bold)
                        )
                        Text(
                            if (timedOut) avSyncTimeoutMessage()
                            else "이 파일에 비디오 또는 오디오 트랙이 둘 다 존재하지 않거나, 패킷 타임스탬프를 읽을 수 없습니다.",
                            style = AppTypography.bodyMedium.copy(color = AppColors.TextSecondary)
                        )
                    }
                }
                else -> {
                    val r = report!!
                    SelectionContainer {
                        AvSyncReportContent(
                            fileName = tab.file.name,
                            report = r,
                            selectedSyncPoint = selectedSyncPoint,
                            onSelectSyncPoint = { selectedSyncPoint = it },
                        )
                    }
                }
            }
        }
    }
}

fun formatAvSyncReportAsText(fileName: String, report: AvSyncReport): String = buildString {
    appendLine("# A/V Sync & Timestamp Drift Report — $fileName")
    appendLine("- Overall Severity: ${report.overallSeverity}")
    appendLine("- Pattern Type: ${report.patternType.labelKo}")
    appendLine("- Verdict: ${avSyncVerdict(report)}")
    appendLine()
    appendLine("## Track Alignment & Key Metrics")
    appendLine(String.format(Locale.US, "- Video Track: %.3fs .. %.3fs (duration %.3fs, nominal step %.2fms)", report.videoStartPtsSec, report.videoEndPtsSec, report.videoDurationSec, report.nominalVideoStepMs))
    appendLine(String.format(Locale.US, "- Audio Track: %.3fs .. %.3fs (duration %.3fs, nominal step %.2fms)", report.audioStartPtsSec, report.audioEndPtsSec, report.audioDurationSec, report.nominalAudioStepMs))
    appendLine(String.format(Locale.US, "- Initial Offset (Skew): %+.2f ms", report.initialSkewMs))
    appendLine(String.format(Locale.US, "- Tail Overhang (Audio - Video End): %+.2f ms", report.tailOverhangMs))
    appendLine(String.format(Locale.US, "- Max Skew: %+.2f ms / Avg Skew: %+.2f ms", report.maxSkewMs, report.avgSkewMs))
    appendLine(String.format(Locale.US, "- Drift Rate: %+.2f ms/min", report.driftRateMsPerMin))
    appendLine(String.format(Locale.US, "- Discontinuities: Video Drops=%d (max gap %.1fms), Audio Gaps=%d (max gap %.1fms)", report.videoDropCount, report.maxVideoGapMs, report.audioGapCount, report.maxAudioGapMs))
    if (report.anomalies.isNotEmpty()) {
        appendLine()
        appendLine("## PTS Discontinuity Events (${report.anomalies.size})")
        appendLine("Stream\tPacket#\tPTS(s)\tDeltaPTS(ms)\tSeverity\tDescription")
        report.anomalies.forEach { a ->
            appendLine(String.format(Locale.US, "%s\t#%d\t%.3f\t%+.1f\t%s\t%s", a.mediaType.uppercase(Locale.US), a.packetIndex, a.ptsSeconds, a.gapMs, a.severity, a.description))
        }
    }
    if (report.diagnoses.isNotEmpty()) {
        appendLine()
        appendLine("## Root-Cause Diagnoses")
        report.diagnoses.forEach { d ->
            appendLine("### [${d.severity}] ${d.category}")
            appendLine("- Summary: ${d.summary}")
            d.technicalDetails.forEach { appendLine("  * $it") }
            appendLine("- Android Impact: ${d.androidImpact}")
            appendLine("- Recommended Fix: ${d.recommendedFix}")
            d.suggestedFfmpegCommand?.let { appendLine("- Suggested Command: `$it`") }
        }
    }
}.trimEnd()

@Composable
private fun AvSyncReportContent(
    fileName: String,
    report: AvSyncReport,
    selectedSyncPoint: SyncPoint?,
    onSelectSyncPoint: (SyncPoint?) -> Unit
) {
    val scrollState = rememberScrollState()
    var chartMode by remember { mutableStateOf(0) } // 0 = Sync Skew (Δt), 1 = Frame/Packet Interval (ΔPTS Jitter)
    var reportCopied by remember { mutableStateOf(false) }
    LaunchedEffect(reportCopied) {
        if (reportCopied) {
            delay(2000)
            reportCopied = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        val statusColor = when (report.overallSeverity) {
            SyncSeverity.PASS -> Color(0xFF2E7D32)
            SyncSeverity.WARNING -> Color(0xFFF57F17)
            SyncSeverity.CRITICAL -> Color(0xFFC62828)
        }
        val statusLabel = when (report.overallSeverity) {
            SyncSeverity.PASS -> "정상 (PASS)"
            SyncSeverity.WARNING -> "주의 (WARNING)"
            SyncSeverity.CRITICAL -> "심각 (CRITICAL)"
        }

        // 0. Executive Summary Banner (원인 분류 & 실무 해석 요약)
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(4.dp),
            colors = CardDefaults.cardColors(containerColor = AppColors.Surface),
            border = androidx.compose.foundation.BorderStroke(1.dp, statusColor.copy(alpha = 0.6f)),
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .background(statusColor.copy(alpha = 0.2f), RoundedCornerShape(4.dp))
                                .border(1.dp, statusColor, RoundedCornerShape(4.dp))
                                .padding(horizontal = 8.dp, vertical = 3.dp),
                        ) {
                            Text(
                                statusLabel,
                                style = AppTypography.bodySmall.copy(color = statusColor, fontWeight = FontWeight.Bold, fontSize = 11.5.sp),
                            )
                        }
                        Box(
                            modifier = Modifier
                                .background(Color(report.patternType.badgeColorHex).copy(alpha = 0.16f), RoundedCornerShape(4.dp))
                                .border(0.8.dp, Color(report.patternType.badgeColorHex), RoundedCornerShape(4.dp))
                                .padding(horizontal = 8.dp, vertical = 3.dp),
                        ) {
                            Text(
                                "진단 유형: ${report.patternType.labelKo}",
                                style = AppTypography.bodySmall.copy(color = AppColors.TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 11.5.sp),
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "ITU-R BT.1359 방송 권고: -125ms(오디오 지연) ~ +45ms(오디오 선행)",
                            style = AppTypography.bodySmall.copy(color = AppColors.TextSecondary, fontSize = 11.sp),
                        )
                        OutlinedButton(
                            onClick = {
                                if (ClipboardUtil.copyToClipboard(formatAvSyncReportAsText(fileName, report))) {
                                    reportCopied = true
                                }
                            },
                            shape = AppControlShape,
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                            modifier = Modifier.height(26.dp),
                        ) {
                            Text(
                                if (reportCopied) "✓ 리포트 복사됨" else "📋 리포트 복사",
                                fontSize = 11.sp,
                                color = if (reportCopied) AppColors.NeonGreen else AppColors.NeonBlue,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
                Text(
                    avSyncVerdict(report),
                    style = AppTypography.bodyMedium.copy(color = AppColors.TextPrimary, fontWeight = FontWeight.SemiBold),
                )
            }
        }

        // 1. 4 Separated Cause Metric Cards (시작 오프셋 / 종료 잔류 / 프레임 드랍·PTS갭 / 클럭 드리프트)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            val frameEquiv = if (report.nominalVideoStepMs > 0) abs(report.initialSkewMs) / report.nominalVideoStepMs else 0.0
            val initSkewSub = when {
                abs(report.initialSkewMs) <= 5.0 -> "시작점 일치 (0f)"
                report.initialSkewMs > 0 -> String.format(Locale.US, "오디오 %.1f프레임 선행 시작", frameEquiv)
                else -> String.format(Locale.US, "비디오 %.1f프레임 선행 시작", frameEquiv)
            }
            MetricCard(
                modifier = Modifier.weight(1f),
                title = "① 시작 싱크 (Initial Offset)",
                value = String.format(Locale.US, "%+.1f ms", report.initialSkewMs),
                subtext = initSkewSub,
                valueColor = if (abs(report.initialSkewMs) > 100) Color(0xFFC62828) else if (abs(report.initialSkewMs) > 40) Color(0xFFF57F17) else Color(0xFF2E7D32)
            )

            val tailSub = when {
                abs(report.tailOverhangMs) <= 20.0 -> "종료 시점 일치"
                report.tailOverhangMs > 0 -> "비디오 종료 후 오디오 잔류"
                else -> "오디오 조기 종료 (비디오 잔류)"
            }
            MetricCard(
                modifier = Modifier.weight(1f),
                title = "② 종료 길이 차이 (Tail Overhang)",
                value = String.format(Locale.US, "%+.1f ms", report.tailOverhangMs),
                subtext = tailSub,
                valueColor = if (abs(report.tailOverhangMs) > 250) Color(0xFFC62828) else if (abs(report.tailOverhangMs) > 60) Color(0xFFF57F17) else Color(0xFF2E7D32)
            )

            val totalAnomalies = report.videoDropCount + report.audioGapCount
            val anomalyColor = when {
                report.anomalies.any { it.severity == SyncSeverity.CRITICAL } -> Color(0xFFC62828)
                totalAnomalies > 0 -> Color(0xFFF57F17)
                else -> Color(0xFF2E7D32)
            }
            MetricCard(
                modifier = Modifier.weight(1f),
                title = "③ 프레임 드랍 / PTS 점프",
                value = "V:${report.videoDropCount}건 / A:${report.audioGapCount}건",
                subtext = String.format(
                    Locale.US,
                    "최대 간격 V:%.0fms · A:%.0fms",
                    report.maxVideoGapMs, report.maxAudioGapMs,
                ),
                valueColor = anomalyColor
            )

            val isRealDrift = avSyncIsProgressiveDrift(report.driftRateMsPerMin, report.durationDeltaSec * 1000.0)
            val driftSub = when {
                isRealDrift -> "실제 누적 클럭 드리프트 의심"
                abs(report.durationDeltaSec) > 0.06 -> "끝단 길이 차이로 인한 환산치"
                else -> "클럭 비율 안정적"
            }
            MetricCard(
                modifier = Modifier.weight(1f),
                title = "④ 환산 드리프트율 (Drift Rate)",
                value = String.format(Locale.US, "%+.1f ms/분", report.driftRateMsPerMin),
                subtext = driftSub,
                valueColor = if (isRealDrift) Color(0xFFC62828) else if (abs(report.driftRateMsPerMin) > 20) Color(0xFFF57F17) else Color(0xFF2E7D32)
            )
        }

        if (report.editListAdjusted) {
            Text(
                "ⓘ 시작 오프셋·트랙 길이는 컨테이너 start_time(MP4 elst 편집 리스트 반영) 기준으로 계산했습니다 — 원시 첫 패킷 PTS(Video ${String.format(Locale.US, "%+.1fms", report.rawVideoFirstPtsSec * 1000.0)}, Audio ${String.format(Locale.US, "%+.1fms", report.rawAudioFirstPtsSec * 1000.0)})와 차이가 있습니다.",
                style = AppTypography.bodySmall.copy(color = AppColors.TextSecondary),
            )
        }

        // 1.5. Visual Track Alignment & ITU-R BT.1359 Lip-Sync Gauge
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(4.dp),
            colors = CardDefaults.cardColors(containerColor = AppColors.Surface)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "트랙 시작·종료 정렬 다이어그램 & ITU-R BT.1359 립싱크 허용 게이지",
                    style = AppTypography.headlineSmall.copy(color = AppColors.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                )
                Text(
                    "비디오와 오디오 트랙의 시작 오프셋(앞단)과 종료 시점 잘림/잔류(뒷단)를 분리하여 보여줍니다.",
                    style = AppTypography.bodySmall.copy(color = AppColors.TextSecondary)
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(88.dp)
                        .background(AppColors.Panel, RoundedCornerShape(4.dp))
                        .border(1.dp, AppColors.Border, RoundedCornerShape(4.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    AvTrackAlignmentDiagram(
                        report = report,
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .background(AppColors.Panel, RoundedCornerShape(4.dp))
                        .border(1.dp, AppColors.Border, RoundedCornerShape(4.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    ItuLipSyncGauge(
                        initialSkewMs = report.initialSkewMs,
                        minSkewMs = report.minSkewMs,
                        maxSkewMs = report.maxSkewMs,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }

        // 2. At-a-glance sync bar + interactive chart (Sync Skew vs Frame/Packet ΔPTS Jitter)
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(4.dp),
            colors = CardDefaults.cardColors(containerColor = AppColors.Surface)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    "시간대별 동기화 상태 바 (클릭하여 해당 시점 검사)",
                    style = AppTypography.headlineSmall.copy(color = AppColors.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                )
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    LegendBadge("양호 (±40ms)", Color(0xFF2E7D32))
                    LegendBadge("주의 (±100ms)", Color(0xFFF57F17))
                    LegendBadge("심각 (>100ms)", Color(0xFFC62828))
                    LegendBadge("데이터 없음", Color(0xFF3A3A3A))
                }
                Spacer(Modifier.height(8.dp))
                AvSyncSegmentBar(
                    report = report,
                    selectedPoint = selectedSyncPoint,
                    onSelectPoint = onSelectSyncPoint,
                    modifier = Modifier.fillMaxWidth().height(46.dp)
                )

                Spacer(Modifier.height(16.dp))

                // Chart Mode Selector Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text(
                            if (chartMode == 0) "① 구간별 A/V 타이밍 편차 곡선 (Δt = Video PTS − Audio PTS)"
                            else "② 실측 프레임·패킷 간격 추이 (ΔPTS Jitter & 프레임 드랍 탐지)",
                            style = AppTypography.headlineSmall.copy(color = AppColors.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        )
                        Text(
                            if (chartMode == 0) "양수(+): 오디오가 영상보다 먼저 재생(선행)  |  음수(−): 비디오가 소리보다 먼저 재생(선행)"
                            else "파란선: 비디오 프레임 간격(정상 ${String.format(Locale.US, "%.1fms", report.nominalVideoStepMs)})  |  초록선: 오디오 패킷 간격(정상 ${String.format(Locale.US, "%.1fms", report.nominalAudioStepMs)})  |  빨간점: 드랍/공백 이상 지점",
                            style = AppTypography.bodySmall.copy(color = AppColors.TextSecondary)
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(
                            0 to "싱크 편차 (Δt)",
                            1 to "프레임/패킷 간격 (ΔPTS)",
                        ).forEach { (idx, title) ->
                            val selected = chartMode == idx
                            Box(
                                modifier = Modifier
                                    .background(
                                        if (selected) AppColors.NeonBlue.copy(alpha = 0.22f) else AppColors.Panel,
                                        RoundedCornerShape(4.dp),
                                    )
                                    .border(
                                        1.dp,
                                        if (selected) AppColors.NeonBlue else AppColors.Border,
                                        RoundedCornerShape(4.dp),
                                    )
                                    .clickable { chartMode = idx }
                                    .padding(horizontal = 10.dp, vertical = 5.dp),
                            ) {
                                Text(
                                    title,
                                    style = AppTypography.bodySmall.copy(
                                        color = if (selected) AppColors.NeonBlue else AppColors.TextSecondary,
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                        fontSize = 11.sp,
                                    ),
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(250.dp)
                        .background(AppColors.Panel, RoundedCornerShape(4.dp))
                        .border(1.dp, AppColors.Border, RoundedCornerShape(4.dp))
                ) {
                    if (report.syncPoints.isEmpty()) {
                        Text(
                            "싱크 포인트 데이터가 없습니다.",
                            style = AppTypography.bodyMedium.copy(color = AppColors.TextSecondary),
                            modifier = Modifier.align(Alignment.Center)
                        )
                    } else if (chartMode == 0) {
                        AvSyncGraph(
                            points = report.syncPoints,
                            selectedPoint = selectedSyncPoint,
                            onSelectPoint = onSelectSyncPoint,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        AvIntervalJitterGraph(
                            report = report,
                            selectedPoint = selectedSyncPoint,
                            onSelectPoint = onSelectSyncPoint,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }

                if (selectedSyncPoint != null) {
                    val p = selectedSyncPoint
                    Spacer(modifier = Modifier.height(8.dp))
                    Surface(
                        color = AppColors.Panel,
                        shape = RoundedCornerShape(4.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, AppColors.Border),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "선택된 위치: 시간 ${String.format(Locale.US, "%.3f", p.timeSeconds)}s  |  Video(#${p.videoFrameIndex}): ${String.format(Locale.US, "%.3f", p.videoPts)}s  |  Audio(#${p.audioPacketIndex}): ${String.format(Locale.US, "%.3f", p.audioPts)}s",
                                style = AppTypography.bodySmall.copy(color = AppColors.TextPrimary, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace, fontSize = 11.5.sp)
                            )
                            val skewColor = when {
                                abs(p.deltaMs) > 100 -> Color(0xFFEF5350)
                                abs(p.deltaMs) > 40 -> Color(0xFFFFB74D)
                                else -> Color(0xFF81C784)
                            }
                            Text(
                                text = "편차(Δt): ${String.format(Locale.US, "%+.1f", p.deltaMs)} ms (${if (p.deltaMs > 0) "오디오 선행" else if (p.deltaMs < 0) "비디오 선행" else "일치"})",
                                style = AppTypography.bodySmall.copy(color = skewColor, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, fontSize = 11.5.sp)
                            )
                        }
                    }
                }
            }
        }

        // 2.5. Detected PTS Discontinuity & Frame Drop Event Table
        if (report.anomalies.isNotEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(4.dp),
                colors = CardDefaults.cardColors(containerColor = AppColors.Surface),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "감지된 타임스탬프 불연속 / 프레임 드랍 지점 (${report.anomalies.size}건 · 클릭하여 차트 위치로 이동)",
                            style = AppTypography.headlineSmall.copy(color = AppColors.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold),
                        )
                        Text(
                            "상위 ${minOf(report.anomalies.size, 12)}건 표시",
                            style = AppTypography.bodySmall.copy(color = AppColors.TextSecondary, fontSize = 11.sp),
                        )
                    }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(AppColors.Panel, RoundedCornerShape(4.dp))
                            .border(1.dp, AppColors.Border, RoundedCornerShape(4.dp))
                            .padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        report.anomalies.take(12).forEach { anomaly ->
                            val sevColor = if (anomaly.severity == SyncSeverity.CRITICAL) Color(0xFFEF5350) else Color(0xFFFFB74D)
                            val streamBadge = if (anomaly.mediaType == "video") "VIDEO" else "AUDIO"
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(AppColors.Surface.copy(alpha = 0.6f), RoundedCornerShape(3.dp))
                                    .clickable {
                                        val nearest = report.syncPoints.minByOrNull { abs(it.timeSeconds - anomaly.ptsSeconds) }
                                        onSelectSyncPoint(nearest)
                                    }
                                    .padding(horizontal = 8.dp, vertical = 5.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        streamBadge,
                                        style = AppTypography.bodySmall.copy(
                                            color = if (anomaly.mediaType == "video") Color(0xFF64B5F6) else Color(0xFF81C784),
                                            fontWeight = FontWeight.Bold,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 11.sp,
                                        ),
                                    )
                                    Text(
                                        String.format(Locale.US, "@ %.3fs (#%d)", anomaly.ptsSeconds, anomaly.packetIndex),
                                        style = AppTypography.bodySmall.copy(color = AppColors.TextPrimary, fontFamily = FontFamily.Monospace, fontSize = 11.5.sp),
                                    )
                                    Text(
                                        anomaly.description,
                                        style = AppTypography.bodySmall.copy(color = AppColors.TextSecondary, fontSize = 11.5.sp),
                                    )
                                }
                                Text(
                                    String.format(Locale.US, "ΔPTS %+.1f ms", anomaly.gapMs),
                                    style = AppTypography.bodySmall.copy(color = sevColor, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, fontSize = 11.5.sp),
                                )
                            }
                        }
                    }
                }
            }
        }

        // 3. Root-cause Diagnosis & Recommendations
        Text(
            "원인 정밀 진단 및 안드로이드 프레임워크 가이드",
            style = AppTypography.headlineSmall.copy(color = AppColors.TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        )

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            report.diagnoses.forEach { diag ->
                DiagnosisCard(diag)
            }
        }
    }
}

@Composable
private fun MetricCard(
    modifier: Modifier = Modifier,
    title: String,
    value: String,
    subtext: String,
    valueColor: Color,
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(4.dp),
        colors = CardDefaults.cardColors(containerColor = AppColors.Surface)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(title, style = AppTypography.bodySmall.copy(color = AppColors.TextSecondary, fontSize = 11.sp))
            Text(value, style = AppTypography.headlineMedium.copy(color = valueColor, fontWeight = FontWeight.Bold, fontSize = 20.sp))
            Text(subtext, style = AppTypography.bodySmall.copy(color = AppColors.TextSecondary, fontSize = 11.sp))
        }
    }
}

@Composable
private fun DiagnosisCard(diag: SyncDiagnosis) {
    var copied by remember { mutableStateOf(false) }

    val borderColor = when (diag.severity) {
        SyncSeverity.PASS -> Color(0xFF2E7D32)
        SyncSeverity.WARNING -> Color(0xFFF57F17)
        SyncSeverity.CRITICAL -> Color(0xFFC62828)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(4.dp),
        colors = CardDefaults.cardColors(containerColor = AppColors.Surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, borderColor.copy(alpha = 0.6f))
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    diag.category,
                    style = AppTypography.headlineSmall.copy(
                        color = borderColor,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                )
            }

            Text(
                diag.summary,
                style = AppTypography.bodyMedium.copy(color = AppColors.TextPrimary, fontWeight = FontWeight.SemiBold)
            )

            if (diag.technicalDetails.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(AppColors.Panel, RoundedCornerShape(4.dp))
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    diag.technicalDetails.forEach { detail ->
                        Text("• $detail", style = AppTypography.bodySmall.copy(color = AppColors.TextSecondary, fontSize = 12.sp))
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    "📱 Android Framework 영향:",
                    style = AppTypography.bodySmall.copy(color = AppColors.NeonBlue, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                )
                Text(
                    diag.androidImpact,
                    style = AppTypography.bodySmall.copy(color = AppColors.TextSecondary, fontSize = 12.sp)
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    "🛠️ 권장 해결책 (Fix):",
                    style = AppTypography.bodySmall.copy(color = Color(0xFF81C784), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                )
                Text(
                    diag.recommendedFix,
                    style = AppTypography.bodySmall.copy(color = AppColors.TextSecondary, fontSize = 12.sp)
                )
            }

            diag.suggestedFfmpegCommand?.let { cmd ->
                Spacer(modifier = Modifier.height(6.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(AppColors.Background, RoundedCornerShape(4.dp))
                        .border(1.dp, Color(0xFF0288D1).copy(alpha = 0.5f), RoundedCornerShape(4.dp))
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            "⚡ [권장 해결 실행 명령어 - 아래 명령어를 터미널에서 실행해 보세요]",
                            style = AppTypography.bodySmall.copy(
                                color = Color(0xFF81D4FA),
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.5.sp
                            )
                        )
                    }

                    diag.commandDescription?.let { desc ->
                        Text(
                            "• 설명: $desc",
                            style = AppTypography.bodySmall.copy(
                                color = AppColors.TextSecondary,
                                fontSize = 11.sp
                            )
                        )
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(AppColors.Panel, RoundedCornerShape(4.dp))
                            .border(1.dp, AppColors.Border, RoundedCornerShape(4.dp))
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        SelectionContainer(modifier = Modifier.weight(1f)) {
                            Text(
                                cmd,
                                style = AppTypography.bodySmall.copy(
                                    color = Color(0xFFB3E5FC),
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.5.sp
                                )
                            )
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        Button(
                            onClick = {
                                if (ClipboardUtil.copyToClipboard(cmd)) {
                                    copied = true
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0288D1)),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            modifier = Modifier.height(30.dp),
                            shape = AppControlShape,
                        ) {
                            Text(
                                if (copied) "✓ 복사 완료!" else "명령어 복사",
                                style = AppTypography.bodySmall.copy(color = Color.White, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                            )
                        }
                    }
                }
                LaunchedEffect(copied) {
                    if (copied) {
                        delay(2000)
                        copied = false
                    }
                }
            }
        }
    }
}

/** The packet-scan timeout notice, worded from the actual watch timeout so the two cannot drift apart. */
internal fun avSyncTimeoutMessage(
    timeoutMillis: Long = com.multiviewer.util.ProcessManager.DEFAULT_WATCH_TIMEOUT_MILLIS,
): String {
    val minutes = java.util.concurrent.TimeUnit.MILLISECONDS.toMinutes(timeoutMillis)
    return "패킷 분석이 시간 제한(${minutes}분)을 넘어 중단되었습니다. 파일이 너무 길어 분석을 완료하지 못했습니다. / " +
        "Packet scan timed out after $minutes min; the file is too long to analyze."
}
