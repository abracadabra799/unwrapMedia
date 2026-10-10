package com.multiviewer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// "mpvd" is not Apple-specific in this app's real-world usage -- it's the box this app's own
// MotionPhotoBuilder.createSamsungHeicMotionPhoto writes for ANY HEIC motion photo, Samsung
// included (confirmed against MotionPhotoBuilder.kt: its HEIC path writes an "mpvd" box, not a
// "sefd" trailer -- SEF is JPEG-only in this app's real-world file support). The MotionPhotoFormat
// enum constant stays APPLE_MPVD (an internal identifier, already committed in Task 1 -- not worth
// reopening for a naming nuance), but user-facing text must not say "Apple/QuickTime", since that
// would misattribute Samsung's own HEIC output to Apple.
internal fun motionPhotoFormatLabel(format: MotionPhotoFormat): String = when (format) {
    MotionPhotoFormat.SAMSUNG_SEF -> "삼성 SEF"
    MotionPhotoFormat.GOOGLE_XMP -> "구글 모션포토 (XMP)"
    MotionPhotoFormat.APPLE_MPVD -> "HEIC 임베디드 비디오 (mpvd)"
}

/** Badge row + per-section tables for a detected motion photo; shown in the Content Check window's Motion photo tab. */
@Composable
fun MotionPhotoReportContent(report: MotionPhotoIntegrityReport, modifier: Modifier = Modifier) {
    if (report.detectedFormats.isEmpty()) {
        // hasMotionPhotoData can gate a file in while the analyzer confirms no format; never show that as PASS.
        Text("이 파일에서 모션포토 형식을 감지하지 못했습니다.", modifier = modifier)
        return
    }
    Column(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SeverityBadge(report.overallSeverity)
            Text("감지된 형식: ${report.detectedFormats.joinToString(", ") { motionPhotoFormatLabel(it) }}", fontSize = 13.sp)
        }
        LazyColumn(modifier = Modifier.fillMaxSize().padding(top = 8.dp)) {
            // 1. 구글 XMP 모션포토 구성
            item { CheckSection("구글 XMP 모션포토 구성", report.googleXmpChecks) }

            report.sefSection?.let { sef ->
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

            // 4. HEIC mpvd 박스 (HEIC only -- simply absent from report.appleMpvdChecks for JPEG)
            item { CheckSection(motionPhotoFormatLabel(MotionPhotoFormat.APPLE_MPVD), report.appleMpvdChecks) }

            item { CheckSection("임베디드 비디오 디코딩 확인", report.decodeChecks) }
        }
    }
}

/** Report verdict; detected-but-unconfirmed (no format confirmed by the analyzer) is SKIP, never PASS. */
fun MotionPhotoIntegrityReport.verdictStatus(): com.multiviewer.parser.integrity.CheckStatus =
    if (detectedFormats.isEmpty()) com.multiviewer.parser.integrity.CheckStatus.SKIP else overallSeverity.toCheckStatus()
