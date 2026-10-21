package com.multiviewer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.OutlinedButton
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
import com.multiviewer.parser.ByteReader
import com.multiviewer.parser.SefIntegrityAnalyzer
import com.multiviewer.parser.SefIntegrityReport
import com.multiviewer.parser.SefIntegritySeverity
import com.multiviewer.util.ClipboardUtil
import kotlinx.coroutines.delay
import java.io.File

fun formatSefIntegrityReportAsText(fileName: String, report: SefIntegrityReport): String = buildString {
    appendLine("# SEF Integrity Report — $fileName")
    appendLine("- Overall Severity: ${report.overallSeverity}")
    appendLine("- Declared Entry Count: ${report.declaredEntryCount ?: "N/A"} (${report.declaredEntryCountSeverity})")
    appendLine()
    appendLine("## Structural Checks")
    report.structuralChecks.forEach { c ->
        appendLine("- [${c.severity}] ${c.label}: ${c.detail}")
    }
    appendLine()
    appendLine("## Directory Entries (${report.directoryEntries.size})")
    appendLine("Index\tMarker\tName\tDeclaredOffset\tDeclaredLength\tDataStart..End\tInBounds\tMarkerMatches\tStatus")
    report.directoryEntries.forEach { e ->
        appendLine("#${e.entryIndex}\t${e.markerHex}\t${e.name ?: "(invalid)"}\t${e.declaredOffset}\t${e.declaredLength}\t[${e.computedDataStart}, ${e.computedDataEnd})\t${e.inBounds}\t${e.markerMatches ?: "-"}\t${e.status}")
    }
    appendLine()
    appendLine("## Semantic Checks")
    report.semanticChecks.forEach { c ->
        appendLine("- [${c.severity}] ${c.label}: ${c.detail}")
    }
}.trimEnd()

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
    var copied by remember { mutableStateOf(false) }

    LaunchedEffect(copied) {
        if (copied) {
            delay(2000)
            copied = false
        }
    }

    LaunchedEffect(file) {
        isLoading = true
        try {
            // Off the UI thread: a Windows file lock (or a slow network share) must not hang it.
            report = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                ByteReader.open(file).use { reader ->
                    SefIntegrityAnalyzer.analyze(reader, sefdOffset, sefdHeaderSize, sefdSize, file.length())
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
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
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        SeverityBadge(currentReport.overallSeverity)
                        val issueCount = (currentReport.structuralChecks + currentReport.semanticChecks)
                            .count { it.severity == SefIntegritySeverity.WARNING || it.severity == SefIntegritySeverity.CRITICAL } +
                            currentReport.directoryEntries.count { it.status == SefIntegritySeverity.CRITICAL } +
                            (if (currentReport.declaredEntryCountSeverity == SefIntegritySeverity.CRITICAL) 1 else 0)
                        Text("${issueCount}개 문제 발견", fontSize = 13.sp, color = AppColors.TextPrimary)
                        Spacer(Modifier.weight(1f))
                        OutlinedButton(
                            onClick = {
                                if (ClipboardUtil.copyToClipboard(formatSefIntegrityReportAsText(file.name, currentReport))) {
                                    copied = true
                                }
                            },
                            shape = AppControlShape,
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                            modifier = Modifier.height(28.dp),
                        ) {
                            Text(
                                if (copied) "✓ 리포트 복사됨" else "📋 리포트 복사",
                                fontSize = 11.5.sp,
                                color = if (copied) AppColors.NeonGreen else AppColors.NeonBlue,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                    SelectionContainer(modifier = Modifier.fillMaxSize().padding(top = 8.dp)) {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            item { CheckSection("구조적 검사", currentReport.structuralChecks) }
                            item { DirectoryEntryTable(currentReport.declaredEntryCount, currentReport.directoryEntries, currentReport.declaredEntryCountSeverity) }
                            item { CheckSection("필드별 의미론 검사", currentReport.semanticChecks) }
                        }
                    }
                }
            }
        }
    }
}
