package com.multiviewer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.multiviewer.parser.conformance.ConformanceReport
import com.multiviewer.parser.conformance.ConformanceStatus

internal fun conformanceStatusLabel(status: ConformanceStatus, ko: Boolean): String = when (status) {
    ConformanceStatus.PASS -> if (ko) "구현된 검사 통과" else "Implemented checks passed"
    ConformanceStatus.FAIL -> if (ko) "실패" else "Fail"
    ConformanceStatus.WARN -> if (ko) "경고" else "Warning"
    ConformanceStatus.NOT_CHECKED -> if (ko) "미검증" else "Not checked"
    ConformanceStatus.NOT_APPLICABLE -> if (ko) "해당 없음" else "Not applicable"
}

@Composable
internal fun conformanceStatusColor(status: ConformanceStatus): Color = when (status) {
    ConformanceStatus.PASS -> AppColors.NeonGreen
    ConformanceStatus.FAIL -> AppColors.NeonRed
    ConformanceStatus.WARN -> AppColors.NeonYellow
    ConformanceStatus.NOT_CHECKED, ConformanceStatus.NOT_APPLICABLE -> AppColors.TextMuted
}
