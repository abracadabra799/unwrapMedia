package com.multiviewer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.multiviewer.parser.integrity.CheckStatus
import com.multiviewer.parser.integrity.ImageStructureReport
import com.multiviewer.parser.integrity.IntegrityCheckItem

@Composable
internal fun checkStatusColor(status: CheckStatus): Color = when (status) {
    CheckStatus.PASS -> AppColors.NeonGreen
    CheckStatus.INFO -> AppColors.NeonBlue
    CheckStatus.WARN -> AppColors.NeonYellow
    CheckStatus.FAIL -> AppColors.NeonRed
    CheckStatus.SKIP -> AppColors.TextMuted
}

@Composable
internal fun decodeStatusColor(status: ImageDecodeStatus): Color = when (status) {
    ImageDecodeStatus.NOT_RUN -> AppColors.TextMuted
    ImageDecodeStatus.CLEAN -> AppColors.NeonGreen
    ImageDecodeStatus.ISSUES -> AppColors.NeonYellow
    ImageDecodeStatus.FAILED -> AppColors.NeonRed
}

internal fun checkStatusLabel(status: CheckStatus, ko: Boolean): String = when (status) {
    CheckStatus.PASS -> if (ko) "정상" else "Pass"
    CheckStatus.INFO -> if (ko) "참고" else "Info"
    CheckStatus.WARN -> if (ko) "경고" else "Warning"
    CheckStatus.FAIL -> if (ko) "실패" else "Fail"
    CheckStatus.SKIP -> if (ko) "해당 없음" else "Skipped"
}

internal fun decodeStatusLabel(status: ImageDecodeStatus, ko: Boolean): String = when (status) {
    ImageDecodeStatus.NOT_RUN -> if (ko) "미검사" else "Not run"
    ImageDecodeStatus.CLEAN -> if (ko) "완료 · 오류 없음" else "Completed · no errors"
    ImageDecodeStatus.ISSUES -> if (ko) "완료 · 오류 발견" else "Completed · errors found"
    ImageDecodeStatus.FAILED -> if (ko) "실패 · 정상 여부 확인 불가" else "Failed · integrity unconfirmed"
}

/** A finished run that ended NOT_RUN (e.g. RAW without a preview) is "not verifiable", not "never started". */
internal fun headerDecodeLabel(reportPresent: Boolean, status: ImageDecodeStatus, ko: Boolean): String =
    if (reportPresent && status == ImageDecodeStatus.NOT_RUN) { if (ko) "검증 불가" else "Not verifiable" }
    else decodeStatusLabel(status, ko)

@Composable
internal fun ImageStructurePanel(report: ImageStructureReport?, error: String?, ko: Boolean, onSelect: (IntegrityCheckItem) -> Unit) {
    when {
        error != null -> Text(error, color = AppColors.NeonRed)
        report == null -> Text(if (ko) "구조 검사 중…" else "Checking structure…", color = AppColors.TextSecondary)
        else -> Column {
            Text(
                if (ko) "오프셋이 있는 항목을 클릭하면 Hex 뷰에서 해당 범위를 강조합니다." else "Click a row with an offset to highlight it in the Hex view.",
                color = AppColors.TextMuted, fontSize = 12.sp,
            )
            LazyColumn(Modifier.fillMaxSize().padding(top = 6.dp)) {
                items(report.items) { item ->
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable(enabled = item.offset != null) { onSelect(item) }
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(checkStatusLabel(item.status, ko), color = checkStatusColor(item.status), modifier = Modifier.width(72.dp), fontSize = 13.sp)
                        Text(item.title, color = AppColors.TextPrimary, modifier = Modifier.width(220.dp), fontSize = 13.sp)
                        Text(
                            item.offset?.let { "0x%X".format(it) + (item.length?.let { l -> " +$l" } ?: "") } ?: "—",
                            color = AppColors.TextSecondary, fontFamily = FontFamily.Monospace, modifier = Modifier.width(150.dp), fontSize = 12.sp,
                        )
                        Text(item.detail, color = AppColors.TextSecondary, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
internal fun ImageDecodePanel(report: ImageDecodeReport?, ko: Boolean) {
    if (report == null) {
        Text(
            if (ko) "'검사 시작'을 눌러 FFmpeg/Skia 디코딩 검사를 실행하세요." else "Press 'Start inspection' to run the FFmpeg/Skia decode check.",
            color = AppColors.TextSecondary,
        )
        return
    }
    fun size(w: Int?, h: Int?) = if (w != null && h != null) "${w}x$h" else "—"
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text((if (ko) "대상: " else "Source: ") + report.source, color = AppColors.TextPrimary, fontSize = 13.sp)
        Text(
            "FFmpeg ${report.ffmpegVersion ?: "?"}: ${decodeStatusLabel(report.ffmpegStatus, ko)} · " +
                (if (ko) "프레임 " else "frames ") + report.decodedFrames + " · " + size(report.decodedWidth, report.decodedHeight),
            color = decodeStatusColor(report.ffmpegStatus), fontSize = 13.sp,
        )
        val skiaColor = when {
            !report.skia.attempted -> AppColors.TextMuted
            report.skia.ok -> AppColors.NeonGreen
            else -> AppColors.NeonYellow
        }
        Text("Skia: ${report.skia.detail}", color = skiaColor, fontSize = 13.sp)
        Text(
            (if (ko) "해상도: 선언 " else "Resolution: declared ") + size(report.declaredWidth, report.declaredHeight) +
                (if (ko) " / 디코딩 " else " / decoded ") + size(report.decodedWidth, report.decodedHeight) +
                " — " + checkStatusLabel(report.resolutionStatus, ko),
            color = checkStatusColor(report.resolutionStatus), fontSize = 13.sp,
        )
        Text(
            (if (ko) "오류 로그" else "Error log") + " (${report.logs.size}${if (report.logsTruncated) "+" else ""})",
            color = AppColors.TextSecondary, fontSize = 12.sp,
        )
        SelectionContainer {
            LazyColumn(Modifier.fillMaxSize().background(AppColors.Panel).padding(8.dp)) {
                items(report.logs) { line -> Text(line, color = AppColors.TextPrimary, fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
            }
        }
    }
}
