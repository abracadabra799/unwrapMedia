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
import com.multiviewer.parser.ByteReader
import com.multiviewer.parser.SefIntegrityAnalyzer
import com.multiviewer.parser.SefIntegrityReport
import com.multiviewer.parser.SefIntegritySeverity
import java.io.File

@Composable
fun SefIntegrityWindow(
    file: File,
    sefdOffset: Long,
    sefdHeaderSize: Int,
    sefdSize: Long,
    themeMode: ThemeMode = ThemeMode.DARK,
    onCloseRequest: () -> Unit,
) {
    var report by remember(file) { mutableStateOf<SefIntegrityReport?>(null) }
    var isLoading by remember(file) { mutableStateOf(true) }
    var error by remember(file) { mutableStateOf<String?>(null) }

    LaunchedEffect(file) {
        isLoading = true
        try {
            ByteReader.open(file).use { reader ->
                report = SefIntegrityAnalyzer.analyze(reader, sefdOffset, sefdHeaderSize, sefdSize, file.length())
            }
        } catch (e: Exception) {
            error = e.message ?: e.toString()
        }
        isLoading = false
    }

    Window(onCloseRequest = onCloseRequest, state = rememberWindowState(width = 900.dp, height = 760.dp), title = "SEF 무결성 검사 - ${file.name}") {
        Column(modifier = Modifier.fillMaxSize().background(AppColors.Background).padding(16.dp)) {
            val currentReport = report
            when {
                error != null -> Text("오류: $error", color = Color.Red)
                isLoading || currentReport == null -> Text("분석 중...")
                else -> {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SeverityBadge(currentReport.overallSeverity)
                        val issueCount = (currentReport.structuralChecks + currentReport.semanticChecks)
                            .count { it.severity == SefIntegritySeverity.WARNING || it.severity == SefIntegritySeverity.CRITICAL } +
                            currentReport.directoryEntries.count { it.status == SefIntegritySeverity.CRITICAL } +
                            (if (currentReport.declaredEntryCountSeverity == SefIntegritySeverity.CRITICAL) 1 else 0)
                        Text("${issueCount}개 문제 발견", fontSize = 13.sp)
                    }
                    LazyColumn(modifier = Modifier.fillMaxSize().padding(top = 8.dp)) {
                        item { CheckSection("구조적 검사", currentReport.structuralChecks) }
                        item { DirectoryEntryTable(currentReport.declaredEntryCount, currentReport.directoryEntries, currentReport.declaredEntryCountSeverity) }
                        item { CheckSection("필드별 의미론 검사", currentReport.semanticChecks) }
                    }
                }
            }
        }
    }
}
