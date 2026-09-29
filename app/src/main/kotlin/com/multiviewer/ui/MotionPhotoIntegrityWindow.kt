package com.multiviewer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import com.multiviewer.parser.BoxNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

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
                        SeverityBadge(currentReport.overallSeverity)
                        Text("감지된 형식: ${currentReport.detectedFormats.joinToString(", ") { motionPhotoFormatLabel(it) }}", fontSize = 13.sp)
                    }
                    LazyColumn(modifier = Modifier.fillMaxSize().padding(top = 8.dp)) {
                        // 1. 구글 XMP 모션포토 구성
                        item { CheckSection("구글 XMP 모션포토 구성", currentReport.googleXmpChecks) }

                        currentReport.sefSection?.let { sef ->
                            // 2. SEF 모션포토 관련 필드 (MotionPhoto_Data/AutoPlay/Version only)
                            item {
                                val motionPhotoEntries = sef.directoryEntries.filter { it.name?.startsWith("MotionPhoto") == true }
                                DirectoryEntryTable(sef.declaredEntryCount, motionPhotoEntries, sef.declaredEntryCountSeverity, title = "SEF 모션포토 관련 필드", showCountSummary = false)
                            }
                            item {
                                val motionPhotoSemanticChecks = sef.semanticChecks.filter { it.label.contains("MotionPhoto") }
                                CheckSection("SEF 모션포토 필드 의미론 검사", motionPhotoSemanticChecks)
                            }

                            // 3. SEF 전체 구조 무결성 (identical to the standalone SEF window's own table)
                            item { CheckSection("SEF 구조적 검사", sef.structuralChecks) }
                            item { DirectoryEntryTable(sef.declaredEntryCount, sef.directoryEntries, sef.declaredEntryCountSeverity, title = "SEF 전체 구조 무결성") }
                            item { CheckSection("SEF 필드별 의미론 검사", sef.semanticChecks) }
                        }

                        // 4. HEIC mpvd 박스 (HEIC only -- simply absent from currentReport.appleMpvdChecks for JPEG)
                        item { CheckSection(motionPhotoFormatLabel(MotionPhotoFormat.APPLE_MPVD), currentReport.appleMpvdChecks) }

                        item { CheckSection("임베디드 비디오 디코딩 확인", currentReport.decodeChecks) }
                    }
                }
            }
        }
    }
}
