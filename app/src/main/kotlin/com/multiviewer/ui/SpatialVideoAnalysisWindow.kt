package com.multiviewer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import com.multiviewer.parser.spatial.SpatialSeverity
import com.multiviewer.parser.spatial.SpatialVideoReport
import com.multiviewer.parser.spatial.analyzeSpatialVideo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.util.Locale

@Composable
fun SpatialVideoAnalysisWindow(
    tab: TabState,
    language: AppLanguage,
    onCloseRequest: () -> Unit,
) {
    val ko = language == AppLanguage.KO
    fun label(korean: String, english: String) = if (ko) korean else english

    Window(
        onCloseRequest = onCloseRequest,
        state = rememberWindowState(size = DpSize(1080.dp, 840.dp), position = WindowPosition(Alignment.Center)),
        title = "${I18n.menuSpatialVideoAnalysis(language)} - ${tab.file.name}",
    ) {
        var loading by remember(tab.file) { mutableStateOf(true) }
        var error by remember(tab.file) { mutableStateOf<String?>(null) }
        var report by remember(tab.file) { mutableStateOf<SpatialVideoReport?>(null) }
        var selectedFrame by remember { mutableStateOf<Int?>(null) }
        var saveStatus by remember { mutableStateOf<String?>(null) }

        val tableState = rememberLazyListState()
        val scope = rememberCoroutineScope()

        LaunchedEffect(tab.file) {
            if (tab.type != MediaType.VIDEO) {
                error = label("지원하지 않는 미디어 타입입니다 (비디오 파일만 지원)", "Unsupported media type (video files only)")
                loading = false
                return@LaunchedEffect
            }
            val root = tab.root
            if (root == null) {
                error = label("파싱된 컨테이너 구조가 없습니다", "No parsed container structure")
                loading = false
                return@LaunchedEffect
            }

            withContext(Dispatchers.IO) {
                try {
                    val rep = analyzeSpatialVideo(tab.file, root)
                    if (rep == null) {
                        error = label("HEVC 비디오 트랙을 찾을 수 없습니다", "No HEVC video track found")
                    } else {
                        report = rep
                    }
                } catch (e: Exception) {
                    error = e.message ?: e.toString()
                }
            }
            loading = false
        }

        fun saveCsv() {
            val r = report ?: return
            val dialog = FileDialog(null as Frame?, label("Spatial Video 분석 CSV 저장", "Save Spatial Video Analysis CSV"), FileDialog.SAVE)
            dialog.file = "${tab.file.nameWithoutExtension}-spatial-video.csv"
            dialog.isVisible = true
            val output = dialog.file?.let { File(dialog.directory, it) }
            dialog.dispose()
            if (output == null) return

            scope.launch {
                val failure = withContext(Dispatchers.IO) {
                    try {
                        val csv = buildString {
                            append("FrameIndex,DTS(s),PTS(s),Layer0Bytes,Layer1Bytes,Layer0NALs,Layer1NALs,Layer0Key,Layer1Key\n")
                            for (f in r.frames) {
                                append("${f.frameIndex},${String.format(Locale.US, "%.4f", f.dtsSeconds)},${String.format(Locale.US, "%.4f", f.ptsSeconds)},${f.layer0Bytes},${f.layer1Bytes},${f.layer0NalCount},${f.layer1NalCount},${f.isLayer0Keyframe},${f.isLayer1Keyframe}\n")
                            }
                        }
                        output.writeText("\uFEFF$csv")
                        null
                    } catch (e: Exception) {
                        e.message ?: e.toString()
                    }
                }
                saveStatus = if (failure == null) label("저장됨: ", "Saved: ") + output.path else label("저장 실패: ", "Save failed: ") + failure
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(AppColors.Background)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            when {
                loading -> {
                    Text(label("공간 비디오(MV-HEVC) 구조 및 레이어 분석 중…", "Analyzing Spatial Video (MV-HEVC) layers…"), color = AppColors.TextPrimary)
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                error != null -> {
                    Text(label("오류: ", "Error: ") + error, color = AppColors.NeonRed)
                }
                report != null -> {
                    val r = report!!
                    val meta = r.metadata

                    // Header Status Bar
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            val badgeText = when {
                                r.isSpatialVideo && r.layerParityMatch -> label("✓ MV-HEVC 3D Spatial Video", "✓ MV-HEVC 3D Spatial Video")
                                r.isSpatialVideo -> label("⚠ Spatial Video (패리티 불일치)", "⚠ Spatial Video (Parity Mismatch)")
                                else -> label("일반 2D 비디오", "Standard 2D Video")
                            }
                            val badgeColor = when {
                                r.isSpatialVideo && r.layerParityMatch -> AppColors.NeonGreen
                                r.isSpatialVideo -> Color(0xFFFFB74D)
                                else -> AppColors.TextMuted
                            }

                            Text(
                                text = badgeText,
                                color = badgeColor,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                modifier = Modifier
                                    .background(badgeColor.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                                    .border(1.dp, badgeColor, RoundedCornerShape(4.dp))
                                    .padding(horizontal = 8.dp, vertical = 3.dp),
                            )

                            meta.heroEye?.let { hero ->
                                Text(
                                    text = "Primary: $hero",
                                    color = AppColors.NeonBlue,
                                    fontSize = 12.sp,
                                    modifier = Modifier
                                        .background(AppColors.NeonBlue.copy(alpha = 0.12f), RoundedCornerShape(4.dp))
                                        .border(1.dp, AppColors.NeonBlue.copy(alpha = 0.4f), RoundedCornerShape(4.dp))
                                        .padding(horizontal = 8.dp, vertical = 3.dp),
                                )
                            }

                            val baselineStr = meta.baselineMm?.let { String.format(Locale.US, "Baseline: %.1f mm", it) } ?: "Baseline: N/A"
                            val fovStr = meta.horizontalFovDeg?.let { String.format(Locale.US, "FOV: %.1f°", it) } ?: "FOV: N/A"
                            Text(
                                "$baselineStr · $fovStr · ${r.totalFrames} frames",
                                color = AppColors.TextPrimary,
                                fontSize = 13.sp,
                            )
                        }

                        OutlinedButton(onClick = { saveCsv() }, shape = AppControlShape) {
                            Text(label("CSV 저장", "Save CSV"), fontSize = 12.sp)
                        }
                    }

                    saveStatus?.let { Text(it, color = AppColors.TextSecondary, fontSize = 11.sp, maxLines = 1) }

                    // Stereo Views Comparison Cards
                    val totalBytes = (r.layer0TotalBytes + r.layer1TotalBytes).coerceAtLeast(1L)
                    val l0Pct = (r.layer0TotalBytes.toDouble() / totalBytes * 100.0)
                    val l1Pct = (r.layer1TotalBytes.toDouble() / totalBytes * 100.0)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        // Base View (Left Eye) Card
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .background(AppColors.Panel, RoundedCornerShape(4.dp))
                                .border(1.dp, AppColors.Border, RoundedCornerShape(4.dp))
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                label("Base View (좌안 Left Eye / Layer 0)", "Base View (Left Eye / Layer 0)"),
                                color = Color(0xFF4FC3F7),
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                            )
                            Text(
                                String.format(Locale.US, "크기: %.2f MB (%.1f%%)", r.layer0TotalBytes / 1_048_576.0, l0Pct),
                                color = AppColors.TextPrimary,
                                fontSize = 11.sp,
                            )
                            Text(
                                "Keyframes: ${r.layer0KeyframeCount} / ${r.totalFrames}",
                                color = AppColors.TextSecondary,
                                fontSize = 11.sp,
                            )
                            Text(
                                label("2D 디코더 단독 재생 가능 (하위 호환)", "2D Backward Compatible"),
                                color = AppColors.NeonGreen,
                                fontSize = 10.sp,
                            )
                        }

                        // Secondary View (Right Eye) Card
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .background(AppColors.Panel, RoundedCornerShape(4.dp))
                                .border(1.dp, AppColors.Border, RoundedCornerShape(4.dp))
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                label("Secondary View (우안 Right Eye / Layer 1)", "Secondary View (Right Eye / Layer 1)"),
                                color = Color(0xFFFFB74D),
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                            )
                            Text(
                                String.format(Locale.US, "크기: %.2f MB (%.1f%%)", r.layer1TotalBytes / 1_048_576.0, l1Pct),
                                color = AppColors.TextPrimary,
                                fontSize = 11.sp,
                            )
                            Text(
                                "Keyframes: ${r.layer1KeyframeCount} / ${r.totalFrames}",
                                color = AppColors.TextSecondary,
                                fontSize = 11.sp,
                            )
                            val relativeRatio = if (r.layer0TotalBytes > 0) String.format(Locale.US, "%.1f%%", r.layer1TotalBytes.toDouble() / r.layer0TotalBytes * 100.0) else "N/A"
                            Text(
                                label("시차 예측 압축률: Base 대비 $relativeRatio", "Disparity compression: $relativeRatio of Base"),
                                color = AppColors.NeonPurple,
                                fontSize = 10.sp,
                            )
                        }
                    }

                    // Layer Bitrate Distribution Bar
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(AppColors.Panel, RoundedCornerShape(4.dp))
                            .border(1.dp, AppColors.Border, RoundedCornerShape(4.dp))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(
                                String.format(Locale.US, "Layer 0 (Base): %.1f%%", l0Pct),
                                color = Color(0xFF4FC3F7),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                String.format(Locale.US, "Layer 1 (Secondary): %.1f%%", l1Pct),
                                color = Color(0xFFFFB74D),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(12.dp)
                                .background(Color(0xFF2A2E35), RoundedCornerShape(4.dp)),
                        ) {
                            Row(modifier = Modifier.fillMaxSize()) {
                                Box(
                                    modifier = Modifier
                                        .weight((l0Pct / 100.0).toFloat().coerceIn(0.01f, 0.99f))
                                        .fillMaxHeight()
                                        .background(Color(0xFF29B6F6), RoundedCornerShape(topStart = 6.dp, bottomStart = 6.dp)),
                                )
                                Box(
                                    modifier = Modifier
                                        .weight((l1Pct / 100.0).toFloat().coerceIn(0.01f, 0.99f))
                                        .fillMaxHeight()
                                        .background(Color(0xFFFFB74D), RoundedCornerShape(topEnd = 6.dp, bottomEnd = 6.dp)),
                                )
                            }
                        }
                    }

                    // Details Section
                    Row(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        // Left: Anomalies List
                        Column(
                            modifier = Modifier
                                .weight(0.35f)
                                .fillMaxSize()
                                .background(AppColors.Panel, RoundedCornerShape(4.dp))
                                .border(1.dp, AppColors.Border, RoundedCornerShape(4.dp))
                                .padding(8.dp),
                        ) {
                            Text(
                                label("이상 진단 항목 (${r.anomalies.size}건)", "Anomalies (${r.anomalies.size})"),
                                color = AppColors.NeonBlue,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                            )
                            if (r.anomalies.isEmpty()) {
                                Text(
                                    label("양안 시차 및 레이어 대칭성에 이상이 없습니다.", "No spatial layer anomalies detected."),
                                    color = AppColors.TextMuted,
                                    fontSize = 11.sp,
                                    modifier = Modifier.padding(top = 8.dp),
                                )
                            } else {
                                LazyColumn(modifier = Modifier.fillMaxSize().padding(top = 4.dp)) {
                                    itemsIndexed(r.anomalies) { _, a ->
                                        val color = when (a.severity) {
                                            SpatialSeverity.ERROR -> AppColors.NeonRed
                                            SpatialSeverity.WARN -> Color(0xFFFFB74D)
                                            SpatialSeverity.INFO -> AppColors.TextSecondary
                                        }
                                        Text(
                                            text = "[${a.severity}] ${a.message}",
                                            color = color,
                                            fontSize = 11.sp,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable(enabled = a.frameIndex != null) {
                                                    val f = a.frameIndex ?: return@clickable
                                                    selectedFrame = f
                                                    scope.launch { tableState.animateScrollToItem(f) }
                                                }
                                                .padding(vertical = 3.dp),
                                        )
                                    }
                                }
                            }
                        }

                        // Right: Frames Table
                        Column(
                            modifier = Modifier
                                .weight(0.65f)
                                .fillMaxSize()
                                .background(AppColors.Panel, RoundedCornerShape(4.dp))
                                .border(1.dp, AppColors.Border, RoundedCornerShape(4.dp))
                                .padding(8.dp),
                        ) {
                            Text(
                                String.format(Locale.US, "%-6s %9s  %-12s %-12s %-6s %s", "frame", "dts", "L0(KB)", "L1(KB)", "NALs", "Key"),
                                color = AppColors.TextSecondary,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                maxLines = 1,
                                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                            )
                            LazyColumn(state = tableState, modifier = Modifier.fillMaxWidth().weight(1f)) {
                                itemsIndexed(r.frames) { index, f ->
                                    val isSelected = selectedFrame == index
                                    val keyStr = "${if (f.isLayer0Keyframe) "K0" else "  "}/${if (f.isLayer1Keyframe) "K1" else "  "}"
                                    val nalsStr = "${f.layer0NalCount}/${f.layer1NalCount}"
                                    val line = String.format(
                                        Locale.US,
                                        "%-6d %8.3fs  %10.1f   %10.1f   %5s  %s",
                                        f.frameIndex,
                                        f.dtsSeconds,
                                        f.layer0Bytes / 1024.0,
                                        f.layer1Bytes / 1024.0,
                                        nalsStr,
                                        keyStr,
                                    )
                                    Text(
                                        text = line,
                                        color = if (isSelected) Color.White else AppColors.TextPrimary,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(if (isSelected) AppColors.Selection else Color.Transparent)
                                            .clickable { selectedFrame = index }
                                            .padding(vertical = 2.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
