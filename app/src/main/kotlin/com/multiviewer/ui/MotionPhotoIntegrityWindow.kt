package com.multiviewer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import com.multiviewer.parser.BoxNode
import com.multiviewer.parser.SefCheckResult
import com.multiviewer.parser.SefIntegritySeverity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

// Duplicated from SefIntegrityWindow.kt rather than shared -- small, file-scoped private UI
// helpers, matching this codebase's established per-file-copy convention for trivial helpers
// (see SefIntegrityAnalyzer.kt's own comment on readUInt16LE/readUInt32LE for the same reasoning).
private fun motionPhotoSeverityColor(severity: SefIntegritySeverity): Color = when (severity) {
    SefIntegritySeverity.PASS -> Color(0xFF2E7D32)
    SefIntegritySeverity.INFO -> Color(0xFF1565C0)
    SefIntegritySeverity.WARNING -> Color(0xFFF57F17)
    SefIntegritySeverity.CRITICAL -> Color(0xFFC62828)
    SefIntegritySeverity.SKIPPED -> Color(0xFF757575)
}

private fun motionPhotoSeverityBadgeText(severity: SefIntegritySeverity): String = when (severity) {
    SefIntegritySeverity.PASS -> "✓ PASS"
    SefIntegritySeverity.INFO -> "ℹ INFO"
    SefIntegritySeverity.WARNING -> "⚠ WARNING"
    SefIntegritySeverity.CRITICAL -> "✗ CRITICAL"
    SefIntegritySeverity.SKIPPED -> "⊘ SKIPPED"
}

@Composable
private fun MotionPhotoSeverityBadge(severity: SefIntegritySeverity) {
    val color = motionPhotoSeverityColor(severity)
    Text(
        motionPhotoSeverityBadgeText(severity),
        color = color,
        fontWeight = FontWeight.Bold,
        fontSize = 11.sp,
        modifier = Modifier
            .background(color.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
            .border(1.dp, color, RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
private fun MotionPhotoCheckRow(check: SefCheckResult) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        MotionPhotoSeverityBadge(check.severity)
        Column {
            Text(check.label, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
            Text(check.detail, fontSize = 11.sp, color = Color.Gray)
        }
    }
}

@Composable
private fun MotionPhotoCheckSection(title: String, checks: List<SefCheckResult>) {
    if (checks.isEmpty()) return
    Text(title, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
    checks.forEach { MotionPhotoCheckRow(it) }
}

// "mpvd" is not Apple-specific in this app's real-world usage -- it's the box this app's own
// MotionPhotoBuilder.createSamsungHeicMotionPhoto writes for ANY HEIC motion photo, Samsung
// included (confirmed against MotionPhotoBuilder.kt: its HEIC path writes an "mpvd" box, not a
// "sefd" trailer -- SEF is JPEG-only in this app's real-world file support). The MotionPhotoFormat
// enum constant stays APPLE_MPVD (an internal identifier, already committed in Task 1 -- not worth
// reopening for a naming nuance), but user-facing text must not say "Apple/QuickTime", since that
// would misattribute Samsung's own HEIC output to Apple.
private fun motionPhotoFormatLabel(format: MotionPhotoFormat): String = when (format) {
    MotionPhotoFormat.SAMSUNG_SEF -> "삼성 SEF"
    MotionPhotoFormat.GOOGLE_XMP -> "구글 모션포토 (XMP)"
    MotionPhotoFormat.APPLE_MPVD -> "HEIC 임베디드 비디오 (mpvd)"
}

@Composable
fun MotionPhotoIntegrityWindow(
    file: File,
    root: BoxNode,
    themeMode: ThemeMode = ThemeMode.DARK,
    onCloseRequest: () -> Unit,
) {
    var report by remember(file) { mutableStateOf<MotionPhotoIntegrityReport?>(null) }
    var isLoading by remember(file) { mutableStateOf(true) }
    var error by remember(file) { mutableStateOf<String?>(null) }

    LaunchedEffect(file) {
        isLoading = true
        try {
            report = withContext(Dispatchers.IO) { MotionPhotoIntegrityAnalyzer.analyze(file, root) }
        } catch (e: Exception) {
            error = e.message ?: e.toString()
        }
        isLoading = false
    }

    Window(onCloseRequest = onCloseRequest, state = rememberWindowState(width = 640.dp, height = 720.dp), title = "모션포토 정합성 검사 - ${file.name}") {
        Column(modifier = Modifier.fillMaxSize().background(AppColors.Background).padding(16.dp)) {
            val currentReport = report
            when {
                error != null -> Text("오류: $error", color = Color.Red)
                isLoading || currentReport == null -> Text("분석 중...")
                currentReport.detectedFormats.isEmpty() -> Text("이 파일에서 모션포토 형식을 감지하지 못했습니다.")
                else -> {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MotionPhotoSeverityBadge(currentReport.overallSeverity)
                        Text("감지된 형식: ${currentReport.detectedFormats.joinToString(", ") { motionPhotoFormatLabel(it) }}", fontSize = 13.sp)
                    }
                    LazyColumn(modifier = Modifier.fillMaxSize().padding(top = 8.dp)) {
                        currentReport.sefSection?.let { sef ->
                            item { MotionPhotoCheckSection("Samsung SEF - 구조적 검사", sef.structuralChecks) }
                            item {
                                val countLabel = "SEFH 선언 엔트리 수"
                                val countDetail = if (sef.declaredEntryCount == null) {
                                    "확인 불가 (상위 검사 실패)"
                                } else {
                                    "선언: ${sef.declaredEntryCount}개, 실제 발견: ${sef.directoryEntries.size}개"
                                }
                                val entryChecks = listOf(SefCheckResult(sef.declaredEntryCountSeverity, countLabel, countDetail)) +
                                    sef.directoryEntries.map { row ->
                                        SefCheckResult(
                                            row.status,
                                            "Entry #${row.entryIndex}${row.name?.let { " ($it)" } ?: ""} (marker ${row.markerHex})",
                                            "declared offset=${row.declaredOffset}, length=${row.declaredLength}, 실제 위치=${row.computedDataStart}~${row.computedDataEnd}, 범위 내=${row.inBounds}, 마커 일치=${row.markerMatches}",
                                        )
                                    }
                                MotionPhotoCheckSection("Samsung SEF - 디렉토리 엔트리", entryChecks)
                            }
                            item { MotionPhotoCheckSection("Samsung SEF - 필드별 의미론 검사", sef.semanticChecks) }
                        }
                        item { MotionPhotoCheckSection("구글 모션포토 (XMP)", currentReport.googleXmpChecks) }
                        item { MotionPhotoCheckSection(motionPhotoFormatLabel(MotionPhotoFormat.APPLE_MPVD), currentReport.appleMpvdChecks) }
                        item { MotionPhotoCheckSection("임베디드 비디오 디코딩 확인", currentReport.decodeChecks) }
                    }
                }
            }
        }
    }
}
