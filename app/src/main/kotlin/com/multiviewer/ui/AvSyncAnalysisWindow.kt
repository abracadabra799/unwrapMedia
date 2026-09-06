package com.multiviewer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
        var selectedSyncPoint by remember { mutableStateOf<SyncPoint?>(null) }

        LaunchedEffect(tab.file) {
            if (tab.avSyncReport != null) {
                report = tab.avSyncReport
                isLoading = false
            } else {
                isLoading = true
                val analyzed = AvSyncAnalyzer.analyze(tab.file)
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
                            "이 파일에 비디오 또는 오디오 트랙이 둘 다 존재하지 않거나, 패킷 타임스탬프를 읽을 수 없습니다.",
                            style = AppTypography.bodyMedium.copy(color = AppColors.TextSecondary)
                        )
                    }
                }
                else -> {
                    val r = report!!
                    AvSyncReportContent(
                        report = r,
                        selectedSyncPoint = selectedSyncPoint,
                        onSelectSyncPoint = { selectedSyncPoint = it }
                    )
                }
            }
        }
    }
}

@Composable
private fun AvSyncReportContent(
    report: AvSyncReport,
    selectedSyncPoint: SyncPoint?,
    onSelectSyncPoint: (SyncPoint?) -> Unit
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. Overview Metric Cards
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
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

            MetricCard(
                modifier = Modifier.weight(1.2f),
                title = "종합 상태",
                value = statusLabel,
                subtext = "전체 싱크 무결성",
                valueColor = statusColor
            )

            MetricCard(
                modifier = Modifier.weight(1f),
                title = "초기 립싱크 (Initial Skew)",
                value = String.format(Locale.US, "%+.1f ms", report.initialSkewMs),
                subtext = if (report.initialSkewMs > 0) "오디오 선행" else if (report.initialSkewMs < 0) "비디오 선행" else "완전 정렬",
                valueColor = if (abs(report.initialSkewMs) > 100) Color(0xFFC62828) else if (abs(report.initialSkewMs) > 40) Color(0xFFF57F17) else Color(0xFF2E7D32)
            )

            MetricCard(
                modifier = Modifier.weight(1f),
                title = "트랙 길이 차이 (Delta)",
                value = String.format(Locale.US, "%+.1f ms", report.durationDeltaSec * 1000.0),
                subtext = if (report.durationDeltaSec > 0) "비디오가 더 긺" else if (report.durationDeltaSec < 0) "오디오가 더 긺" else "동일",
                valueColor = if (abs(report.durationDeltaSec) > 0.25) Color(0xFFC62828) else if (abs(report.durationDeltaSec) > 0.05) Color(0xFFF57F17) else Color(0xFF2E7D32)
            )

            MetricCard(
                modifier = Modifier.weight(1f),
                title = "점진적 드리프트율",
                value = String.format(Locale.US, "%+.2f ms/m", report.driftRateMsPerMin),
                subtext = "클럭 누적 편차율",
                valueColor = if (abs(report.driftRateMsPerMin) > 20) Color(0xFFC62828) else if (abs(report.driftRateMsPerMin) > 5) Color(0xFFF57F17) else Color(0xFF2E7D32)
            )
        }

        // 2. At-a-glance sync bar + verdict, then the annotated skew curve
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            colors = CardDefaults.cardColors(containerColor = AppColors.Surface)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                // Layer 1: at-a-glance
                Text(
                    "한눈에 보는 동기화 상태",
                    style = AppTypography.headlineSmall.copy(color = AppColors.TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
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
                Spacer(Modifier.height(8.dp))
                val verdictColor = when (report.overallSeverity) {
                    SyncSeverity.PASS -> Color(0xFF2E7D32)
                    SyncSeverity.WARNING -> Color(0xFFF57F17)
                    SyncSeverity.CRITICAL -> Color(0xFFC62828)
                }
                Text(
                    avSyncVerdict(report),
                    style = AppTypography.bodyMedium.copy(color = verdictColor, fontWeight = FontWeight.SemiBold)
                )

                Spacer(Modifier.height(20.dp))

                // Layer 2: annotated curve
                Text(
                    "A/V 타임스탬프 편차 곡선 (Δt = Video PTS − Audio PTS)",
                    style = AppTypography.headlineSmall.copy(color = AppColors.TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                )
                Text(
                    "양수: 오디오 선행 / 음수: 비디오 선행",
                    style = AppTypography.bodySmall.copy(color = AppColors.TextSecondary)
                )
                Spacer(Modifier.height(12.dp))
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
                    } else {
                        AvSyncGraph(
                            points = report.syncPoints,
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
        shape = RoundedCornerShape(8.dp),
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
        shape = RoundedCornerShape(8.dp),
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
                        .background(AppColors.Background, RoundedCornerShape(6.dp))
                        .border(1.dp, Color(0xFF0288D1).copy(alpha = 0.5f), RoundedCornerShape(6.dp))
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
                            modifier = Modifier.height(30.dp)
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
