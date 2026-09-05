package com.multiviewer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
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

        // 2. Timeline Skew / Drift Visualizer Graph
        // 2. Timeline Visualization Card (Dual-Lane Track Timeline + Skew Curve Graph)
        var selectedVisualMode by remember { mutableStateOf(0) } // 0: Dual-Lane Timeline, 1: Skew Curve

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            colors = CardDefaults.cardColors(containerColor = AppColors.Surface)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            if (selectedVisualMode == 0) "A/V 프레임/패킷 일직선 동기화 레일 (Dual-Lane Timeline)"
                            else "A/V 타임스탬프 편차 곡선 (A/V Sync Skew Curve)",
                            style = AppTypography.headlineSmall.copy(
                                color = AppColors.TextPrimary,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                        )
                        Text(
                            if (selectedVisualMode == 0) "동일 시간축(X축) 상에서 비디오 프레임(상단)과 오디오 패킷(하단)의 물리적 정렬과 일치선 표시"
                            else "Δt = Video PTS - Audio PTS (양수: 비디오 지연/오디오 선행, 음수: 비디오 선행/오디오 지연)",
                            style = AppTypography.bodySmall.copy(color = AppColors.TextSecondary)
                        )
                    }

                    // Mode switch buttons
                    Row(
                        modifier = Modifier
                            .background(AppColors.Panel, RoundedCornerShape(6.dp))
                            .border(1.dp, AppColors.Border, RoundedCornerShape(6.dp))
                            .padding(2.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Surface(
                            color = if (selectedVisualMode == 0) AppColors.Surface else Color.Transparent,
                            shape = RoundedCornerShape(4.dp),
                            modifier = Modifier.clickable { selectedVisualMode = 0 }
                        ) {
                            Text(
                                "🎞️ 듀얼 레일(프레임별 정렬)",
                                style = AppTypography.labelSmall.copy(
                                    color = if (selectedVisualMode == 0) AppColors.NeonBlue else AppColors.TextSecondary,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.5.sp
                                ),
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                            )
                        }
                        Surface(
                            color = if (selectedVisualMode == 1) AppColors.Surface else Color.Transparent,
                            shape = RoundedCornerShape(4.dp),
                            modifier = Modifier.clickable { selectedVisualMode = 1 }
                        ) {
                            Text(
                                "📈 편차 곡선(Skew)",
                                style = AppTypography.labelSmall.copy(
                                    color = if (selectedVisualMode == 1) AppColors.NeonBlue else AppColors.TextSecondary,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.5.sp
                                ),
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Legends
                if (selectedVisualMode == 0) {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        LegendBadge("비디오 프레임 (Video Frame)", Color(0xFF1E88E5))
                        LegendBadge("오디오 패킷 (Audio Packet)", Color(0xFFAB47BC))
                        LegendBadge("싱크 일치/허용", Color(0xFF2E7D32))
                        LegendBadge("싱크 어긋남(>40ms)", Color(0xFFE53935))
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        LegendBadge("허용 범위 (±40ms)", Color(0xFF2E7D32))
                        LegendBadge("주의 범위 (±100ms)", Color(0xFFF57F17))
                        LegendBadge("심각 (>100ms)", Color(0xFFC62828))
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(250.dp)
                        .background(AppColors.Panel, RoundedCornerShape(4.dp))
                        .border(1.dp, AppColors.Border, RoundedCornerShape(4.dp))
                ) {
                    if (selectedVisualMode == 0) {
                        AvDualLaneTimeline(
                            report = report,
                            selectedPoint = selectedSyncPoint,
                            onSelectPoint = onSelectSyncPoint,
                            modifier = Modifier.fillMaxSize()
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
                                style = AppTypography.bodySmall.copy(
                                    color = AppColors.TextPrimary,
                                    fontWeight = FontWeight.SemiBold,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.5.sp
                                )
                            )
                            val skewColor = when {
                                abs(p.deltaMs) > 100 -> Color(0xFFEF5350)
                                abs(p.deltaMs) > 40 -> Color(0xFFFFB74D)
                                else -> Color(0xFF81C784)
                            }
                            Text(
                                text = "편차(Δt): ${String.format(Locale.US, "%+.1f", p.deltaMs)} ms (${if (p.deltaMs > 0) "오디오 선행" else if (p.deltaMs < 0) "비디오 선행" else "일치"})",
                                style = AppTypography.bodySmall.copy(
                                    color = skewColor,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.5.sp
                                )
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
private fun LegendBadge(label: String, color: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(color, RoundedCornerShape(4.dp))
        )
        Text(label, style = AppTypography.bodySmall.copy(color = AppColors.TextSecondary, fontSize = 11.sp))
    }
}

@Composable
private fun AvSyncGraph(
    points: List<SyncPoint>,
    selectedPoint: SyncPoint?,
    onSelectPoint: (SyncPoint?) -> Unit,
    modifier: Modifier = Modifier
) {
    if (points.isEmpty()) return

    val maxAbsDelta = points.maxOf { abs(it.deltaMs) }.coerceAtLeast(120.0)
    val yCeiling = (maxAbsDelta * 1.25)
    val maxTime = points.maxOf { it.timeSeconds }.coerceAtLeast(0.01)

    Canvas(
        modifier = modifier
            .pointerInput(points) {
                detectTapGestures { offset ->
                    val w = size.width
                    val paddingX = 40f
                    val graphW = w - paddingX * 2
                    val clickFraction = ((offset.x - paddingX) / graphW).coerceIn(0f, 1f)
                    val targetTime = clickFraction * maxTime
                    val nearest = points.minByOrNull { abs(it.timeSeconds - targetTime) }
                    onSelectPoint(nearest)
                }
            }
    ) {
        val w = size.width
        val h = size.height
        val padX = 40f
        val padY = 24f
        val graphW = w - padX * 2
        val graphH = h - padY * 2

        fun toY(deltaMs: Double): Float {
            val fraction = (deltaMs / yCeiling).toFloat()
            return padY + (graphH / 2f) - (fraction * (graphH / 2f))
        }

        fun toX(timeSec: Double): Float {
            return padX + ((timeSec / maxTime).toFloat() * graphW)
        }

        val yPos100 = toY(100.0)
        val yPos40 = toY(40.0)
        val yNeg40 = toY(-40.0)
        val yNeg100 = toY(-100.0)
        val yZero = toY(0.0)

        // Orange Bands (40ms ~ 100ms)
        drawRect(
            color = Color(0x1AF57F17),
            topLeft = Offset(padX, yPos100),
            size = Size(graphW, yPos40 - yPos100)
        )
        drawRect(
            color = Color(0x1AF57F17),
            topLeft = Offset(padX, yNeg40),
            size = Size(graphW, yNeg100 - yNeg40)
        )

        // Green Band (-40ms ~ +40ms)
        drawRect(
            color = Color(0x1A2E7D32),
            topLeft = Offset(padX, yPos40),
            size = Size(graphW, yNeg40 - yPos40)
        )

        // Center Baseline (0ms)
        drawLine(
            color = Color(0x80FFFFFF),
            start = Offset(padX, yZero),
            end = Offset(w - padX, yZero),
            strokeWidth = 1.5f
        )

        // Threshold Dotted Lines
        drawLine(
            color = Color(0x40F57F17),
            start = Offset(padX, yPos40),
            end = Offset(w - padX, yPos40),
            strokeWidth = 1f
        )
        drawLine(
            color = Color(0x40F57F17),
            start = Offset(padX, yNeg40),
            end = Offset(w - padX, yNeg40),
            strokeWidth = 1f
        )

        // Plot Curve
        val path = Path()
        points.forEachIndexed { index, p ->
            val x = toX(p.timeSeconds)
            val y = toY(p.deltaMs)
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(
            path = path,
            color = Color(0xFF64B5F6),
            style = Stroke(width = 2.5f)
        )

        // Plot Points
        points.forEach { p ->
            val x = toX(p.timeSeconds)
            val y = toY(p.deltaMs)
            val ptColor = when {
                abs(p.deltaMs) > 100 -> Color(0xFFE57373)
                abs(p.deltaMs) > 40 -> Color(0xFFFFB74D)
                else -> Color(0xFF81C784)
            }
            drawCircle(color = ptColor, radius = 3.5f, center = Offset(x, y))
        }

        // Selected Point Marker
        if (selectedPoint != null) {
            val x = toX(selectedPoint.timeSeconds)
            val y = toY(selectedPoint.deltaMs)
            drawLine(
                color = Color(0xFFFFEB3B),
                start = Offset(x, padY),
                end = Offset(x, h - padY),
                strokeWidth = 1.5f
            )
            drawCircle(color = Color(0xFFFFEB3B), radius = 7f, center = Offset(x, y))
            drawCircle(color = Color(0xFF1E1E1E), radius = 3f, center = Offset(x, y))
        }
    }
}

@Composable
private fun AvDualLaneTimeline(
    report: AvSyncReport,
    selectedPoint: SyncPoint?,
    onSelectPoint: (SyncPoint?) -> Unit,
    modifier: Modifier = Modifier
) {
    val totalTime = maxOf(report.videoDurationSec, report.audioDurationSec).coerceAtLeast(0.01)
    val syncPoints = report.syncPoints

    Canvas(
        modifier = modifier
            .pointerInput(syncPoints) {
                detectTapGestures { offset ->
                    val w = size.width
                    val padX = 24f
                    val trackW = w - padX * 2
                    if (trackW <= 0) return@detectTapGestures
                    val clickFraction = ((offset.x - padX) / trackW).coerceIn(0f, 1f)
                    val targetTime = clickFraction * totalTime
                    val nearest = syncPoints.minByOrNull { abs(it.timeSeconds - targetTime) }
                    onSelectPoint(nearest)
                }
            }
    ) {
        val w = size.width
        val h = size.height
        val padX = 24f
        val padY = 16f
        val trackW = w - padX * 2
        val usableH = h - padY * 2

        // Lane definitions
        // Video Lane: Top
        val videoLaneY = padY + 12f
        val videoLaneH = 42f

        // Audio Lane: Bottom
        val audioLaneH = 42f
        val audioLaneY = h - padY - audioLaneH - 12f

        // Middle sync connection area
        val midTopY = videoLaneY + videoLaneH
        val midBottomY = audioLaneY

        fun timeToX(t: Double): Float {
            return padX + ((t / totalTime).toFloat() * trackW).coerceIn(0f, trackW)
        }

        // Draw track backgrounds (rails)
        // Video Track background
        drawRoundRect(
            color = Color(0xFF152238),
            topLeft = Offset(padX, videoLaneY),
            size = Size(trackW, videoLaneH),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f, 4f)
        )
        // Audio Track background
        drawRoundRect(
            color = Color(0xFF231633),
            topLeft = Offset(padX, audioLaneY),
            size = Size(trackW, audioLaneH),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f, 4f)
        )

        // Middle connector area subtle background
        drawRect(
            color = Color(0x0AFFFFFF),
            topLeft = Offset(padX, midTopY),
            size = Size(trackW, midBottomY - midTopY)
        )

        // Subsample packets for smooth UI rendering if list is large
        val vPackets = report.sampleVideoPackets
        val aPackets = report.sampleAudioPackets

        // 1. Draw Audio Packets on bottom lane
        if (aPackets.isNotEmpty()) {
            val step = (aPackets.size / 300).coerceAtLeast(1)
            for (i in aPackets.indices step step) {
                val pkt = aPackets[i]
                val startX = timeToX(pkt.ptsSeconds)
                val dur = pkt.durationSeconds ?: 0.023
                val blockW = ((dur / totalTime).toFloat() * trackW).coerceAtLeast(3.5f)

                drawRoundRect(
                    color = Color(0xFF8E24AA),
                    topLeft = Offset(startX, audioLaneY + 6f),
                    size = Size(blockW, audioLaneH - 12f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(2f, 2f)
                )
            }
        }

        // 2. Draw Video Frames on top lane
        if (vPackets.isNotEmpty()) {
            val step = (vPackets.size / 300).coerceAtLeast(1)
            for (i in vPackets.indices step step) {
                val pkt = vPackets[i]
                val startX = timeToX(pkt.ptsSeconds)
                val dur = pkt.durationSeconds ?: 0.033
                val blockW = ((dur / totalTime).toFloat() * trackW).coerceAtLeast(4f)

                drawRoundRect(
                    color = Color(0xFF1E88E5),
                    topLeft = Offset(startX, videoLaneY + 6f),
                    size = Size(blockW, videoLaneH - 12f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(2f, 2f)
                )
            }
        }

        // 3. Draw Sync connection lines in middle zone
        syncPoints.forEach { pt ->
            val vX = timeToX(pt.videoPts)
            val aX = timeToX(pt.audioPts)
            val isSevere = abs(pt.deltaMs) > 40.0
            val lineColor = if (isSevere) Color(0xFFE53935).copy(alpha = 0.85f) else Color(0xFF43A047).copy(alpha = 0.45f)
            val lineWidth = if (isSevere) 1.5f else 1.0f

            // Connector between video block center-bottom and audio block center-top
            val path = Path().apply {
                moveTo(vX, midTopY)
                cubicTo(
                    vX, midTopY + (midBottomY - midTopY) * 0.5f,
                    aX, midTopY + (midBottomY - midTopY) * 0.5f,
                    aX, midBottomY
                )
            }
            drawPath(path, color = lineColor, style = Stroke(width = lineWidth))

            // Draw small anchor pins at stream rails
            drawCircle(
                color = if (isSevere) Color(0xFFE53935) else Color(0xFF66BB6A),
                radius = 2.5f,
                center = Offset(vX, midTopY)
            )
            drawCircle(
                color = if (isSevere) Color(0xFFE53935) else Color(0xFFAB47BC),
                radius = 2.5f,
                center = Offset(aX, midBottomY)
            )
        }

        // 4. Highlight Selected Sync Point
        if (selectedPoint != null) {
            val vX = timeToX(selectedPoint.videoPts)
            val aX = timeToX(selectedPoint.audioPts)

            // Video highlight
            drawRoundRect(
                color = Color(0xFFFFEB3B),
                topLeft = Offset(vX - 3f, videoLaneY + 2f),
                size = Size(8f, videoLaneH - 4f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(3f, 3f)
            )
            // Audio highlight
            drawRoundRect(
                color = Color(0xFFFFEB3B),
                topLeft = Offset(aX - 3f, audioLaneY + 2f),
                size = Size(8f, audioLaneH - 4f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(3f, 3f)
            )

            // Dynamic connector curve for selected
            val selPath = Path().apply {
                moveTo(vX, midTopY)
                cubicTo(
                    vX, midTopY + (midBottomY - midTopY) * 0.5f,
                    aX, midTopY + (midBottomY - midTopY) * 0.5f,
                    aX, midBottomY
                )
            }
            drawPath(
                path = selPath,
                color = Color(0xFFFFEB3B),
                style = Stroke(width = 2.5f)
            )

            // Vertical indicator guides across lanes
            drawLine(
                color = Color(0x66FFEB3B),
                start = Offset(vX, padY),
                end = Offset(vX, h - padY),
                strokeWidth = 1f
            )
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
