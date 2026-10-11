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
import com.multiviewer.parser.integrity.AudioStructureReport
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
internal fun ImageStructurePanel(report: ImageStructureReport?, error: String?, ko: Boolean, fileLength: Long, onSelect: (Long, Long) -> Unit) {
    when {
        error != null -> Text(error, color = AppColors.NeonRed)
        report == null -> Text(if (ko) "구조 검사 중…" else "Checking structure…", color = AppColors.TextSecondary)
        else -> ContentFindingTable(
            structureFindings(report, FindingStage.STRUCTURE), fileLength, ko, Modifier.fillMaxSize(), onSelect,
        )
    }
}

@Composable
internal fun AudioStructurePanel(report: AudioStructureReport?, error: String?, ko: Boolean, fileLength: Long, onSelect: (Long, Long) -> Unit) {
    when {
        error != null -> Text(error, color = AppColors.NeonRed)
        report == null -> Text(if (ko) "오디오 구조 검사 중…" else "Checking audio structure…", color = AppColors.TextSecondary)
        else -> ContentFindingTable(
            audioStructureFindings(report), fileLength, ko, Modifier.fillMaxSize(), onSelect,
        )
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
    ContentFindingTable(imageDecodeFindings(report), Long.MAX_VALUE, ko, Modifier.fillMaxSize())
}
