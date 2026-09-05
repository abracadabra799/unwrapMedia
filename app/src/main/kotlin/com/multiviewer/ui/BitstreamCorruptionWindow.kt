package com.multiviewer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import java.util.Locale

@Composable
fun BitstreamCorruptionWindow(
    tab: TabState,
    themeMode: ThemeMode = ThemeMode.DARK,
    onCloseRequest: () -> Unit,
    onJumpToHex: (LongRange) -> Unit = {},
) {
    val windowState = rememberWindowState(
        size = DpSize(1000.dp, 720.dp),
        position = WindowPosition(Alignment.Center),
    )

    Window(
        onCloseRequest = onCloseRequest,
        state = windowState,
        title = "비트스트림 결함 & 손상 프레임 정밀 분석 - ${tab.file.name}",
    ) {
        var report by remember(tab.file) { mutableStateOf<BitstreamCorruptionReport?>(null) }
        var isLoading by remember(tab.file) { mutableStateOf(true) }

        LaunchedEffect(tab.file) {
            if (tab.bitstreamCorruptionReport != null) {
                report = tab.bitstreamCorruptionReport
                isLoading = false
            } else {
                isLoading = true
                val scanned = BitstreamCorruptionScanner.scan(tab.file, tab.gopFrames)
                report = scanned
                tab.bitstreamCorruptionReport = scanned
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
                            "비디오 비트스트림 NAL 슬라이스 및 매크로블록 무결성 스캔 중...",
                            style = AppTypography.bodyLarge.copy(color = AppColors.TextSecondary)
                        )
                    }
                }
                report == null -> {
                    Text(
                        "스캔 결과를 가져올 수 없습니다.",
                        modifier = Modifier.align(Alignment.Center),
                        style = AppTypography.bodyMedium.copy(color = AppColors.TextSecondary)
                    )
                }
                else -> {
                    BitstreamReportContent(
                        report = report!!,
                        onJumpToHex = onJumpToHex
                    )
                }
            }
        }
    }
}

@Composable
private fun BitstreamReportContent(
    report: BitstreamCorruptionReport,
    onJumpToHex: (LongRange) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. Metric Overview Cards
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            val statusColor = when (report.overallStatus) {
                CorruptionSeverity.CRITICAL -> Color(0xFFC62828)
                CorruptionSeverity.WARNING -> Color(0xFFF57F17)
                CorruptionSeverity.INFO, CorruptionSeverity.PASS -> Color(0xFF2E7D32)
            }
            val statusLabel = when (report.overallStatus) {
                CorruptionSeverity.CRITICAL -> "손상 감지 (CRITICAL)"
                CorruptionSeverity.WARNING -> "경고 (WARNING)"
                CorruptionSeverity.INFO, CorruptionSeverity.PASS -> "무결성 정상 (PASS)"
            }

            MetricCard(
                modifier = Modifier.weight(1.2f),
                title = "비트스트림 무결성 상태",
                value = statusLabel,
                subtext = "스캔 프레임: ${report.totalFramesScanned}개",
                valueColor = statusColor
            )

            MetricCard(
                modifier = Modifier.weight(1f),
                title = "손상/경고 항목 수",
                value = "${report.corruptFrameCount} 건",
                subtext = "치명적 결함: ${report.criticalErrorCount}건",
                valueColor = if (report.criticalErrorCount > 0) Color(0xFFC62828) else if (report.corruptFrameCount > 0) Color(0xFFF57F17) else Color(0xFF2E7D32)
            )

            MetricCard(
                modifier = Modifier.weight(1f),
                title = "참조 오염 (Reference Loss)",
                value = if (report.hasReferenceLoss) "발생 (YES)" else "없음 (NO)",
                subtext = if (report.hasReferenceLoss) "이후 프레임 연쇄 왜곡 위험" else "단일 프레임 제한",
                valueColor = if (report.hasReferenceLoss) Color(0xFFC62828) else Color(0xFF2E7D32)
            )
        }

        // Summary Recommendation Banner
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            colors = CardDefaults.cardColors(containerColor = AppColors.Surface),
            border = androidx.compose.foundation.BorderStroke(1.dp, AppColors.Border)
        ) {
            Row(
                modifier = Modifier.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("💡", fontSize = 18.sp)
                Column {
                    Text(
                        "진단 요약 및 실무 권장 가이드",
                        style = AppTypography.bodySmall.copy(color = AppColors.NeonBlue, fontWeight = FontWeight.Bold)
                    )
                    Text(
                        report.summaryRecommendation,
                        style = AppTypography.bodyMedium.copy(color = AppColors.TextPrimary, fontSize = 13.sp)
                    )
                }
            }
        }

        // 2. Corrupt Frame Table Header & List
        Text(
            "감지된 손상 프레임 및 디코더 에러 로그 목록 (${report.entries.size}건)",
            style = AppTypography.headlineSmall.copy(color = AppColors.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        )

        if (report.entries.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(AppColors.Panel, RoundedCornerShape(8.dp))
                    .border(1.dp, AppColors.Border, RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "✓ 비트스트림 손상이나 디코더 에러가 감지되지 않았습니다. 깨끗한 정상 파일입니다.",
                    style = AppTypography.bodyMedium.copy(color = Color(0xFF81C784), fontWeight = FontWeight.SemiBold)
                )
            }
        } else {
            val listState = rememberLazyListState()
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(AppColors.Panel, RoundedCornerShape(8.dp))
                    .border(1.dp, AppColors.Border, RoundedCornerShape(8.dp))
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(report.entries) { entry ->
                    CorruptEntryCard(entry = entry, onJumpToHex = onJumpToHex)
                }
            }
        }
    }
}

@Composable
private fun CorruptEntryCard(
    entry: CorruptFrameEntry,
    onJumpToHex: (LongRange) -> Unit
) {
    val borderColor = when (entry.severity) {
        CorruptionSeverity.CRITICAL -> Color(0xFFC62828)
        CorruptionSeverity.WARNING -> Color(0xFFF57F17)
        CorruptionSeverity.INFO, CorruptionSeverity.PASS -> Color(0xFF0288D1)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(6.dp),
        colors = CardDefaults.cardColors(containerColor = AppColors.Surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, borderColor.copy(alpha = 0.6f))
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val badgeColor = when (entry.frameType) {
                        'I' -> AppColors.FrameTypeI
                        'P' -> AppColors.FrameTypeP
                        'B' -> AppColors.FrameTypeB
                        else -> AppColors.TextSecondary
                    }
                    Box(
                        modifier = Modifier
                            .background(badgeColor.copy(alpha = 0.2f), RoundedCornerShape(4.dp))
                            .border(1.dp, badgeColor, RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            "${entry.frameType ?: '?'}-Frame ${entry.frameIndex?.let { "#$it" } ?: ""}",
                            style = AppTypography.bodySmall.copy(color = badgeColor, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                        )
                    }

                    if (entry.ptsSeconds != null) {
                        Text(
                            "PTS: ${String.format(Locale.US, "%.3f", entry.ptsSeconds)}s",
                            style = AppTypography.bodySmall.copy(color = AppColors.TextSecondary, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        )
                    }

                    if (entry.byteOffset != null) {
                        Text(
                            "Offset: 0x${entry.byteOffset.toString(16).uppercase()}",
                            style = AppTypography.bodySmall.copy(color = AppColors.NeonBlue, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        )
                    }
                }

                if (entry.byteOffset != null) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 1. Precise Error Point Jump (Buffer Overrun/Break Location)
                        val exactRange = entry.errorSpecificByteRange
                        if (exactRange != null) {
                            Button(
                                onClick = { onJumpToHex(exactRange) },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F)),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                modifier = Modifier.height(28.dp)
                            ) {
                                Text(
                                    "🎯 손상 지점(0x${exactRange.first.toString(16).uppercase()})",
                                    style = AppTypography.bodySmall.copy(color = Color.White, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                )
                            }
                        }

                        // 2. Full Frame Jump
                        Button(
                            onClick = {
                                val len = entry.sizeBytes?.toLong() ?: 64L
                                onJumpToHex(entry.byteOffset until (entry.byteOffset + len))
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0288D1)),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            modifier = Modifier.height(28.dp)
                        ) {
                            Text(
                                "📦 프레임 전체",
                                style = AppTypography.bodySmall.copy(color = Color.White, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                            )
                        }
                    }
                }
            }

            // Error Message (Raw log)
            Surface(
                color = Color.Black.copy(alpha = 0.4f),
                shape = RoundedCornerShape(4.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF37474F)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "LOG",
                        style = AppTypography.labelSmall.copy(
                            color = Color(0xFFB0BEC5),
                            fontWeight = FontWeight.Bold,
                            fontSize = 9.sp,
                        ),
                        modifier = Modifier
                            .background(Color(0xFF263238), RoundedCornerShape(2.dp))
                            .padding(horizontal = 4.dp, vertical = 1.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    SelectionContainer {
                        Text(
                            entry.errorMessage,
                            style = AppTypography.bodySmall.copy(
                                color = Color(0xFFFF8A80),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        )
                    }
                }
            }

            // Structured Friendly Explanation
            val exp = entry.explanation
            if (exp != null) {
                Surface(
                    color = AppColors.Background.copy(alpha = 0.6f),
                    shape = RoundedCornerShape(6.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E293B)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        // Title & Summary
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text("💡", fontSize = 13.sp)
                            Text(
                                exp.title,
                                style = AppTypography.bodyMedium.copy(
                                    color = AppColors.NeonBlue,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.5.sp
                                )
                            )
                        }

                        Text(
                            exp.summary,
                            style = AppTypography.bodySmall.copy(
                                color = AppColors.TextPrimary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )
                        )

                        // Coordinates & Details Pill Row
                        if (exp.mbCoordinates != null || exp.pixelCoordinates != null || exp.bufferOverrunBytes != null) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                exp.mbCoordinates?.let { (x, y) ->
                                    Box(
                                        modifier = Modifier
                                            .background(Color(0xFF332D00), RoundedCornerShape(4.dp))
                                            .border(1.dp, Color(0xFFFFD54F), RoundedCornerShape(4.dp))
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    ) {
                                        Text(
                                            "매크로블록: ($x, $y)",
                                            style = AppTypography.labelSmall.copy(
                                                color = Color(0xFFFFE082),
                                                fontSize = 10.5.sp,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                        )
                                    }
                                }

                                exp.pixelCoordinates?.let { (pxX, pxY) ->
                                    Box(
                                        modifier = Modifier
                                            .background(Color(0xFF002244), RoundedCornerShape(4.dp))
                                            .border(1.dp, Color(0xFF4FC3F7), RoundedCornerShape(4.dp))
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    ) {
                                        Text(
                                            "화면 좌표: [X: ${pxX}~${pxX + 16}px, Y: ${pxY}~${pxY + 16}px]",
                                            style = AppTypography.labelSmall.copy(
                                                color = Color(0xFF81D4FA),
                                                fontSize = 10.5.sp,
                                                fontFamily = FontFamily.Monospace
                                            )
                                        )
                                    }
                                }

                                exp.bufferOverrunBytes?.let { bytes ->
                                    val absB = kotlin.math.abs(bytes)
                                    Box(
                                        modifier = Modifier
                                            .background(Color(0xFF3E1A1A), RoundedCornerShape(4.dp))
                                            .border(1.dp, Color(0xFFEF5350), RoundedCornerShape(4.dp))
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    ) {
                                        Text(
                                            "버퍼 초과/유실: ${absB}바이트",
                                            style = AppTypography.labelSmall.copy(
                                                color = Color(0xFFFFCDD2),
                                                fontSize = 10.5.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        )
                                    }
                                }
                            }
                        }

                        // Probable Cause & Visual Impact
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Row(verticalAlignment = Alignment.Top) {
                                Text(
                                    "• 추정 원인: ",
                                    style = AppTypography.bodySmall.copy(
                                        color = AppColors.TextSecondary,
                                        fontSize = 11.5.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                )
                                Text(
                                    exp.probableCause,
                                    style = AppTypography.bodySmall.copy(
                                        color = AppColors.TextPrimary,
                                        fontSize = 11.5.sp
                                    )
                                )
                            }
                            Row(verticalAlignment = Alignment.Top) {
                                Text(
                                    "• 시각 증상: ",
                                    style = AppTypography.bodySmall.copy(
                                        color = AppColors.TextSecondary,
                                        fontSize = 11.5.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                )
                                Text(
                                    exp.visualImpact,
                                    style = AppTypography.bodySmall.copy(
                                        color = Color(0xFFFFCC80),
                                        fontSize = 11.5.sp
                                    )
                                )
                            }
                        }

                        // Actionable Guide
                        Surface(
                            color = Color(0xFF1B2A1E),
                            shape = RoundedCornerShape(4.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF2E7D32)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.Top,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text("🔧", fontSize = 11.sp)
                                Column {
                                    Text(
                                        "권장 디버깅 / 조치 가이드:",
                                        style = AppTypography.labelSmall.copy(
                                            color = Color(0xFFA5D6A7),
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 10.5.sp
                                        )
                                    )
                                    Text(
                                        exp.actionableFix,
                                        style = AppTypography.bodySmall.copy(
                                            color = Color(0xFFE8F5E9),
                                            fontSize = 11.sp
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Android Decoder Impact & Cascading Risk
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text("📱", fontSize = 12.sp)
                Text(
                    "플랫폼 영향도: ${entry.androidDecoderRisk}",
                    style = AppTypography.bodySmall.copy(color = AppColors.TextSecondary, fontSize = 11.sp)
                )
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
            Text(value, style = AppTypography.headlineMedium.copy(color = valueColor, fontWeight = FontWeight.Bold, fontSize = 19.sp))
            Text(subtext, style = AppTypography.bodySmall.copy(color = AppColors.TextSecondary, fontSize = 11.sp))
        }
    }
}
