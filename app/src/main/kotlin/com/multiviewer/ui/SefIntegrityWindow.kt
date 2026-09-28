package com.multiviewer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
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
import com.multiviewer.parser.SefCheckResult
import com.multiviewer.parser.SefDirectoryEntryRow
import com.multiviewer.parser.SefIntegrityAnalyzer
import com.multiviewer.parser.SefIntegrityReport
import com.multiviewer.parser.SefIntegritySeverity
import java.io.File

private fun severityColor(severity: SefIntegritySeverity): Color = when (severity) {
    SefIntegritySeverity.PASS -> Color(0xFF2E7D32)
    SefIntegritySeverity.INFO -> Color(0xFF1565C0)
    SefIntegritySeverity.WARNING -> Color(0xFFF57F17)
    SefIntegritySeverity.CRITICAL -> Color(0xFFC62828)
    SefIntegritySeverity.SKIPPED -> Color(0xFF757575)
}

private fun severityBadgeText(severity: SefIntegritySeverity): String = when (severity) {
    SefIntegritySeverity.PASS -> "✓ PASS"
    SefIntegritySeverity.INFO -> "ℹ INFO"
    SefIntegritySeverity.WARNING -> "⚠ WARNING"
    SefIntegritySeverity.CRITICAL -> "✗ CRITICAL"
    SefIntegritySeverity.SKIPPED -> "⊘ SKIPPED"
}

@Composable
private fun SeverityBadge(severity: SefIntegritySeverity) {
    val color = severityColor(severity)
    Text(
        severityBadgeText(severity),
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
private fun CheckRow(check: SefCheckResult) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        SeverityBadge(check.severity)
        Column {
            Text(check.label, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
            Text(check.detail, fontSize = 11.sp, color = Color.Gray)
        }
    }
}

@Composable
private fun CheckSection(title: String, checks: List<SefCheckResult>) {
    Text(title, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
    checks.forEach { CheckRow(it) }
}

@Composable
private fun DirectoryEntryCountSummary(declaredCount: Long?, foundCount: Int) {
    val severity = when {
        declaredCount == null -> SefIntegritySeverity.SKIPPED
        declaredCount == foundCount.toLong() -> SefIntegritySeverity.PASS
        else -> SefIntegritySeverity.CRITICAL
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SeverityBadge(severity)
        Text(
            if (declaredCount == null) {
                "SEFH 선언 엔트리 수: 확인 불가 (상위 검사 실패)"
            } else {
                "SEFH 선언 엔트리 수: ${declaredCount}개, 실제 발견: ${foundCount}개"
            },
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
        )
    }
}

@Composable
private fun DirectoryEntryTableHeader() {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text("#", modifier = Modifier.width(28.dp), fontWeight = FontWeight.Bold, fontSize = 11.sp)
        Text("이름", modifier = Modifier.width(140.dp), fontWeight = FontWeight.Bold, fontSize = 11.sp)
        Text("마커", modifier = Modifier.width(64.dp), fontWeight = FontWeight.Bold, fontSize = 11.sp)
        Text("선언 오프셋", modifier = Modifier.width(90.dp), fontWeight = FontWeight.Bold, fontSize = 11.sp)
        Text("선언 길이", modifier = Modifier.width(80.dp), fontWeight = FontWeight.Bold, fontSize = 11.sp)
        Text("실제 위치(시작~끝)", modifier = Modifier.width(170.dp), fontWeight = FontWeight.Bold, fontSize = 11.sp)
        Text("범위 내", modifier = Modifier.width(60.dp), fontWeight = FontWeight.Bold, fontSize = 11.sp)
        Text("마커 일치", modifier = Modifier.width(70.dp), fontWeight = FontWeight.Bold, fontSize = 11.sp)
        Text("상태", modifier = Modifier.width(90.dp), fontWeight = FontWeight.Bold, fontSize = 11.sp)
    }
}

@Composable
private fun DirectoryEntryTableRow(row: SefDirectoryEntryRow) {
    val color = if (row.status == SefIntegritySeverity.CRITICAL) Color(0xFFC62828) else Color.Unspecified
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text("${row.entryIndex}", modifier = Modifier.width(28.dp), fontSize = 11.sp, color = color)
        Text(row.name ?: "—", modifier = Modifier.width(140.dp), fontSize = 11.sp, color = color)
        Text(row.markerHex, modifier = Modifier.width(64.dp), fontSize = 11.sp, color = color)
        Text("${row.declaredOffset}", modifier = Modifier.width(90.dp), fontSize = 11.sp, color = color)
        Text("${row.declaredLength}", modifier = Modifier.width(80.dp), fontSize = 11.sp, color = color)
        Text("${row.computedDataStart}~${row.computedDataEnd}", modifier = Modifier.width(170.dp), fontSize = 11.sp, color = color)
        Text(if (row.inBounds) "✓" else "✗", modifier = Modifier.width(60.dp), fontSize = 11.sp, color = color)
        Text(
            when (row.markerMatches) {
                true -> "✓"
                false -> "✗"
                null -> "—"
            },
            modifier = Modifier.width(70.dp), fontSize = 11.sp, color = color,
        )
        Box(modifier = Modifier.width(90.dp)) { SeverityBadge(row.status) }
    }
}

@Composable
private fun DirectoryEntryTable(declaredCount: Long?, entries: List<SefDirectoryEntryRow>) {
    Text("SEFH 디렉토리 엔트리", fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
    DirectoryEntryCountSummary(declaredCount, entries.size)
    if (entries.isEmpty()) return
    val scrollState = rememberScrollState()
    Column(modifier = Modifier.horizontalScroll(scrollState)) {
        DirectoryEntryTableHeader()
        entries.forEach { DirectoryEntryTableRow(it) }
    }
}

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
                            .count { it.severity == SefIntegritySeverity.WARNING || it.severity == SefIntegritySeverity.CRITICAL }
                        Text("${issueCount}개 문제 발견", fontSize = 13.sp)
                    }
                    LazyColumn(modifier = Modifier.fillMaxSize().padding(top = 8.dp)) {
                        item { CheckSection("구조적 검사", currentReport.structuralChecks) }
                        item { DirectoryEntryTable(currentReport.declaredEntryCount, currentReport.directoryEntries) }
                        item { CheckSection("필드별 의미론 검사", currentReport.semanticChecks) }
                    }
                }
            }
        }
    }
}
