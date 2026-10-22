package com.multiviewer.ui

import com.multiviewer.parser.motionphoto.toSefCheckResult
import com.multiviewer.parser.motionphoto.groupLabel
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.multiviewer.parser.motionphoto.FrameAlignmentVerdict
import com.multiviewer.parser.motionphoto.FrameMatchResult
import com.multiviewer.parser.motionphoto.LivePhotoPairReport
import com.multiviewer.parser.motionphoto.LivePhotoPairStatus
import com.multiviewer.parser.motionphoto.PlatformCompatibilityEntry
import com.multiviewer.parser.motionphoto.PlatformSupportLevel

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
    MotionPhotoFormat.APPLE_MPVD -> "HEIC/AVIF mpvd (구글 모션포토) / HEIC/AVIF mpvd (Google Motion Photo)"
}

internal fun googleSpecSectionTitle(group: String) = "구글 모션포토 1.0 / Google Motion Photo 1.0 · $group"

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
        LazyColumn(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            // 1. Google Motion Photo 1.0 — first-priority spec for every motion photo, one table per rule group
            report.googleSpec.groupBy { it.groupLabel() }.forEach { (group, findings) ->
                item { CheckSection(googleSpecSectionTitle(group), findings.map { it.toSefCheckResult() }) }
            }

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

            item { CheckSection("임베디드 비디오 디코딩 확인", report.decodeChecks) }

            // 5. 심층 분석: 스틸 ↔ 비디오 프레임 매칭
            report.deepAnalysis?.frameMatch?.let { match ->
                item { FrameMatchSection(match) }
            }

            // 6. 심층 분석: Apple Live Photo 페어 검사
            report.deepAnalysis?.livePhotoPair?.let { pair ->
                item { LivePhotoPairSection(pair) }
            }

            // 7. 심층 분석: 크로스 플랫폼 호환성 매트릭스
            report.deepAnalysis?.compatibilityMatrix?.let { matrix ->
                item {
                    CompatibilityMatrixSection(
                        detectedProfile = matrix.detectedProfile,
                        entries = matrix.entries,
                    )
                }
            }
        }
    }
}

@Composable
private fun FrameMatchSection(match: FrameMatchResult) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text("스틸 ↔ 비디오 프레임 매칭 (Shutter Frame Match)", fontWeight = FontWeight.Bold, fontSize = 13.sp)
        Spacer(modifier = Modifier.height(4.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF202020), RoundedCornerShape(4.dp))
                .border(1.dp, Color(0xFF333333), RoundedCornerShape(4.dp))
                .padding(10.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        FrameAlignmentBadge(match.alignmentVerdict)
                        Text(
                            if (match.isKeyframe) "I-Frame (키프레임)" else "Non-keyframe (P/B-Frame)",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (match.isKeyframe) Color(0xFF4CAF50) else Color(0xFFFFB74D),
                        )
                    }
                    Text(
                        "오차: ${match.deltaUs} μs (${String.format("%.2f", match.deltaUs / 1000.0)} ms)",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("셔터 타임스탬프: ${match.presentationTimestampUs} μs (${String.format("%.3f", match.presentationTimestampUs / 1_000_000.0)}s)", fontSize = 11.sp)
                        Text("매칭 비디오 PTS: ${match.matchedPtsUs} μs (${String.format("%.3f", match.matchedPtsUs / 1_000_000.0)}s)", fontSize = 11.sp)
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text("매칭 프레임: #${match.matchedFrameNumber} / ${match.totalSamples} (Index ${match.matchedSampleIndex})", fontSize = 11.sp)
                        Text("비디오 정보: ${String.format("%.1f", match.fps)} FPS, 전체 ${String.format("%.3f", match.videoDurationUs / 1_000_000.0)}s", fontSize = 11.sp)
                    }
                }

                // 타임라인 위치 게이지
                Column(modifier = Modifier.fillMaxWidth().padding(top = 2.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("비디오 타임라인 위치", fontSize = 10.sp, color = Color.Gray)
                        Text(String.format("%.1f%%", match.relativePositionPercent), fontSize = 10.sp, color = Color.Gray)
                    }
                    LinearProgressIndicator(
                        progress = { (match.relativePositionPercent / 100.0).toFloat().coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().height(4.dp).padding(top = 2.dp),
                        color = Color(0xFF2196F3),
                        trackColor = Color(0xFF333333),
                    )
                }
            }
        }
    }
}

@Composable
private fun FrameAlignmentBadge(verdict: FrameAlignmentVerdict) {
    val (label, color) = when (verdict) {
        FrameAlignmentVerdict.EXACT_KEYFRAME -> "✓ 키프레임 정밀 일치" to Color(0xFF4CAF50)
        FrameAlignmentVerdict.ALIGNED_NON_KEYFRAME -> "✓ 정상 정렬" to Color(0xFF2196F3)
        FrameAlignmentVerdict.MISALIGNED -> "⚠ 오차 과다" to Color(0xFFFFB74D)
        FrameAlignmentVerdict.OUT_OF_BOUNDS -> "✗ 범위 초과" to Color(0xFFE57373)
    }
    Text(
        label,
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
private fun LivePhotoPairSection(pair: LivePhotoPairReport) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text("Apple Live Photo 페어 검사 (Pair Inspection)", fontWeight = FontWeight.Bold, fontSize = 13.sp)
        Spacer(modifier = Modifier.height(4.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF202020), RoundedCornerShape(4.dp))
                .border(1.dp, Color(0xFF333333), RoundedCornerShape(4.dp))
                .padding(10.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LivePhotoStatusBadge(pair.status)
                    pair.timeDeltaSeconds?.let { delta ->
                        Text(
                            "촬영 시각 오차: ${delta}초",
                            fontSize = 11.sp,
                            color = if (kotlin.math.abs(delta) <= 5L) Color(0xFF4CAF50) else Color(0xFFFFB74D),
                        )
                    }
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Still Content ID: ${pair.stillMetadata?.contentIdentifier ?: "없음"}", fontSize = 11.sp)
                        Text("Video Content ID: ${pair.videoMetadata?.contentIdentifier ?: "없음"}", fontSize = 11.sp)
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        pair.videoMetadata?.stillImageTime?.let {
                            Text("Still Image Time: $it", fontSize = 11.sp)
                        }
                        pair.stillFile?.let { Text("스틸 파일: ${it.name}", fontSize = 11.sp, color = Color.Gray) }
                        pair.videoFile?.let { Text("비디오 파일: ${it.name}", fontSize = 11.sp, color = Color.Gray) }
                    }
                }

                if (pair.checks.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    pair.checks.forEach { check ->
                        CheckRow(check)
                    }
                }
            }
        }
    }
}

@Composable
private fun LivePhotoStatusBadge(status: LivePhotoPairStatus) {
    val (label, color) = when (status) {
        LivePhotoPairStatus.MATCHED -> "✓ UUID 일치 페어" to Color(0xFF4CAF50)
        LivePhotoPairStatus.MISMATCHED_UUID -> "✗ UUID 불일치" to Color(0xFFE57373)
        LivePhotoPairStatus.MISSING_STILL_ID -> "⚠ 스틸 UUID 누락" to Color(0xFFFFB74D)
        LivePhotoPairStatus.MISSING_VIDEO_ID -> "⚠ 비디오 UUID 누락" to Color(0xFFFFB74D)
        LivePhotoPairStatus.NOT_LIVE_PHOTO -> "ℹ Live Photo 식별자 없음" to Color(0xFF9E9E9E)
    }
    Text(
        label,
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
private fun CompatibilityMatrixSection(detectedProfile: String, entries: List<PlatformCompatibilityEntry>) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("크로스 플랫폼 호환성 매트릭스 (Compatibility Matrix)", fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Text(detectedProfile, fontSize = 11.sp, color = Color(0xFF81D4FA), fontWeight = FontWeight.SemiBold)
        }
        Spacer(modifier = Modifier.height(4.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF202020), RoundedCornerShape(4.dp))
                .border(1.dp, Color(0xFF333333), RoundedCornerShape(4.dp))
                .padding(8.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                entries.forEach { entry ->
                    CompatibilityRow(entry)
                }
            }
        }
    }
}

@Composable
private fun CompatibilityRow(entry: PlatformCompatibilityEntry) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(modifier = Modifier.width(90.dp)) {
            SupportLevelBadge(entry.supportLevel)
        }
        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(entry.platformName, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                Text(entry.currentStatusText, fontSize = 11.sp, color = Color.LightGray)
            }
            Text("요구: ${entry.requiredFeatures}", fontSize = 10.sp, color = Color.Gray)
            Text(entry.notes, fontSize = 11.sp, color = Color(0xFFCCCCCC))
        }
    }
}

@Composable
private fun SupportLevelBadge(level: PlatformSupportLevel) {
    val (label, color) = when (level) {
        PlatformSupportLevel.FULL -> "완전 지원" to Color(0xFF4CAF50)
        PlatformSupportLevel.PARTIAL -> "부분 지원" to Color(0xFF2196F3)
        PlatformSupportLevel.STILL_ONLY -> "스틸 전용" to Color(0xFFFFB74D)
        PlatformSupportLevel.UNSUPPORTED -> "미지원" to Color(0xFFE57373)
    }
    Text(
        label,
        color = color,
        fontWeight = FontWeight.Bold,
        fontSize = 10.sp,
        modifier = Modifier
            .background(color.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
            .border(1.dp, color, RoundedCornerShape(4.dp))
            .padding(horizontal = 4.dp, vertical = 2.dp),
    )
}

/** Report verdict; detected-but-unconfirmed (no format confirmed by the analyzer) is SKIP, never PASS. */
fun MotionPhotoIntegrityReport.verdictStatus(): com.multiviewer.parser.integrity.CheckStatus =
    if (detectedFormats.isEmpty()) com.multiviewer.parser.integrity.CheckStatus.SKIP else overallSeverity.toCheckStatus()
