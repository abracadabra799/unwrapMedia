package com.multiviewer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import com.multiviewer.parser.FrameHdrRecord
import com.multiviewer.parser.HdrScanResult
import com.multiviewer.parser.HdrSeverity
import com.multiviewer.parser.HdrThresholds
import com.multiviewer.parser.HdrVerdict
import com.multiviewer.parser.detectHdrAnomalies
import com.multiviewer.parser.hdrVerdict
import com.multiviewer.parser.scanHdrMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.util.Locale

@Composable
fun HdrMetadataWindow(tab: TabState, language: AppLanguage, onCloseRequest: () -> Unit) {
    val ko = language == AppLanguage.KO
    fun label(korean: String, english: String) = if (ko) korean else english

    Window(
        onCloseRequest = onCloseRequest,
        state = rememberWindowState(size = DpSize(1100.dp, 860.dp), position = WindowPosition(Alignment.Center)),
        title = "${I18n.menuHdrSeiAnalysis(language)} - ${tab.file.name}",
    ) {
        var result by remember(tab.file) { mutableStateOf<HdrScanResult?>(null) }
        var progress by remember(tab.file) { mutableStateOf(0f) }
        var scanning by remember(tab.file) { mutableStateOf(true) }
        var error by remember(tab.file) { mutableStateOf<String?>(null) }
        var relativeText by remember { mutableStateOf("50") }
        var absoluteText by remember { mutableStateOf("100") }
        var selectedFrame by remember(tab.file) { mutableStateOf<Int?>(null) }
        val tableState = rememberLazyListState()
        val scope = rememberCoroutineScope()

        // Runs in this Window's composition: closing the window cancels the LaunchedEffect's job,
        // which isCancelled observes between samples.
        LaunchedEffect(tab.file) {
            val root = tab.root
            if (root == null) {
                error = label("파싱된 구조가 없습니다", "No parsed structure")
                scanning = false
                return@LaunchedEffect
            }
            val job = coroutineContext[Job]
            val scanned = withContext(Dispatchers.IO) {
                try {
                    scanHdrMetadata(tab.file, root, isCancelled = { job?.isActive == false }) { done, total ->
                        progress = if (total > 0) done.toFloat() / total else 0f
                    }
                } catch (e: Exception) {
                    error = e.message ?: e.toString()
                    null
                }
            }
            result = scanned
            if (scanned == null && error == null) {
                error = label("지원하지 않는 비디오 트랙입니다 (MP4/MOV의 H.264·HEVC·AV1만 지원)", "Unsupported video track (H.264/HEVC/AV1 in MP4/MOV only)")
            }
            scanning = false
        }

        val thresholds = HdrThresholds(
            relativeChange = (relativeText.toDoubleOrNull() ?: 50.0) / 100.0,
            absoluteChangeNits = absoluteText.toDoubleOrNull() ?: 100.0,
        )
        val current = result
        val anomalies = remember(current, thresholds) { current?.let { detectHdrAnomalies(it.records, it.container, thresholds) } ?: emptyList() }

        Column(modifier = Modifier.fillMaxSize().background(AppColors.Background).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when {
                scanning -> {
                    Text(label("메타데이터 스캔 중…", "Scanning metadata…"), color = AppColors.TextPrimary)
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                }
                error != null -> Text(label("오류: ", "Error: ") + error, color = AppColors.NeonRed)
                current != null -> {
                    val records = current.records
                    val plusCount = records.count { it.hdr10Plus != null }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        VerdictBadge(hdrVerdict(anomalies))
                        Text(
                            String.format(
                                Locale.US, "%s · %d frames · HDR10+ %d/%d (%.1f%%)",
                                current.codec.name, records.size, plusCount, records.size,
                                if (records.isEmpty()) 0.0 else plusCount * 100.0 / records.size,
                            ),
                            color = AppColors.TextPrimary, fontSize = 13.sp,
                        )
                    }
                    val inBandMd = records.firstNotNullOfOrNull { it.masteringDisplay }
                    val inBandCll = records.firstNotNullOfOrNull { it.contentLightLevel }
                    StaticRow(label("컨테이너 MDCV / CLL", "Container MDCV / CLL"), current.container.masteringDisplay, current.container.contentLightLevel)
                    StaticRow(label("비트스트림 MDCV / CLL (첫 값)", "In-band MDCV / CLL (first)"), inBandMd, inBandCll)

                    val mdcv = current.container.masteringDisplay ?: inBandMd
                    val cll = current.container.contentLightLevel ?: inBandCll
                    val references = listOfNotNull(
                        mdcv?.let { "MDCV max" to it.maxLuminance },
                        cll?.takeIf { it.maxCll > 0 }?.let { "MaxCLL" to it.maxCll.toDouble() },
                        cll?.takeIf { it.maxFall > 0 }?.let { "MaxFALL" to it.maxFall.toDouble() },
                    )
                    val marked = anomalies
                        .filter { it.severity != HdrSeverity.INFO }
                        .flatMap { a -> val first = a.firstFrame ?: return@flatMap emptyList(); (first..(a.lastFrame ?: first)).toList() }
                        .toSet()
                    if (plusCount > 0) {
                        HdrTrendGraph(
                            records, references, marked, selectedFrame,
                            onSelectFrame = { f -> selectedFrame = f; scope.launch { tableState.animateScrollToItem(f) } },
                            modifier = Modifier.fillMaxWidth().height(220.dp),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Legend(AppColors.NeonOrange, "MaxSCL")
                            Legend(AppColors.NeonGreen, "average maxRGB")
                            Legend(AppColors.NeonPurple, "targeted display max")
                            Legend(AppColors.NeonYellow, label("기준선 (MDCV/CLL)", "references (MDCV/CLL)"))
                        }
                    } else {
                        Text(label("HDR10+ 메타데이터가 없어 그래프를 생략합니다", "No HDR10+ metadata; graph omitted"), color = AppColors.TextSecondary, fontSize = 12.sp)
                    }

                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(label("급변 임계값", "Abrupt-change thresholds"), color = AppColors.TextSecondary, fontSize = 12.sp)
                        OutlinedTextField(relativeText, { relativeText = it }, label = { Text("%") }, singleLine = true, modifier = Modifier.width(90.dp))
                        OutlinedTextField(absoluteText, { absoluteText = it }, label = { Text("nits") }, singleLine = true, modifier = Modifier.width(110.dp))
                        OutlinedButton(onClick = { saveCsv(label("프레임 CSV 저장", "Save frames CSV"), "${tab.file.nameWithoutExtension}-hdr-frames.csv", buildHdrFramesCsv(records)) }) {
                            Text(label("프레임 CSV", "Frames CSV"))
                        }
                        OutlinedButton(onClick = { saveCsv(label("이상 목록 CSV 저장", "Save anomalies CSV"), "${tab.file.nameWithoutExtension}-hdr-anomalies.csv", buildHdrAnomaliesCsv(anomalies)) }) {
                            Text(label("이상 목록 CSV", "Anomalies CSV"))
                        }
                    }

                    Row(modifier = Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        LazyColumn(modifier = Modifier.weight(0.4f).fillMaxSize()) {
                            item { Text(label("이상 항목 (${anomalies.size})", "Anomalies (${anomalies.size})"), color = AppColors.NeonBlue, fontWeight = FontWeight.Bold) }
                            itemsIndexed(anomalies) { _, a ->
                                Text(
                                    "[${a.severity}] ${a.firstFrame?.let { f -> if (a.lastFrame != null && a.lastFrame != f) "#$f–${a.lastFrame} " else "#$f " } ?: ""}${a.message}",
                                    color = severityTextColor(a.severity),
                                    fontSize = 12.sp,
                                    modifier = Modifier.fillMaxWidth().clickable(enabled = a.firstFrame != null) {
                                        val f = a.firstFrame ?: return@clickable
                                        selectedFrame = f
                                        scope.launch { tableState.animateScrollToItem(f) }
                                    }.padding(vertical = 3.dp),
                                )
                            }
                        }
                        LazyColumn(state = tableState, modifier = Modifier.weight(0.6f).fillMaxSize()) {
                            itemsIndexed(records) { index, r -> FrameRow(r, selected = index == selectedFrame) { selectedFrame = index } }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun severityTextColor(severity: HdrSeverity): Color = when (severity) {
    HdrSeverity.ERROR -> AppColors.NeonRed
    HdrSeverity.WARN -> AppColors.NeonYellow
    HdrSeverity.INFO -> AppColors.TextSecondary
}

@Composable
private fun VerdictBadge(verdict: HdrVerdict) {
    val color = when (verdict) {
        HdrVerdict.PASS -> AppColors.NeonGreen
        HdrVerdict.WARN -> AppColors.NeonYellow
        HdrVerdict.FAIL -> AppColors.NeonRed
    }
    Text(
        verdict.name, color = color, fontWeight = FontWeight.Bold, fontSize = 11.sp,
        modifier = Modifier.background(color.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
            .border(1.dp, color, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
private fun StaticRow(title: String, md: com.multiviewer.parser.MasteringDisplay?, cll: com.multiviewer.parser.ContentLightLevel?) {
    val mdText = md?.let { String.format(Locale.US, "max %.0f / min %.4f cd/m²", it.maxLuminance, it.minLuminance) } ?: "-"
    val cllText = cll?.let { "MaxCLL ${it.maxCll} / MaxFALL ${it.maxFall}" } ?: "-"
    Text("$title: $mdText · $cllText", color = AppColors.TextSecondary, fontSize = 12.sp)
}

@Composable
private fun Legend(color: Color, text: String) {
    Text("━ $text", color = color, fontSize = 11.sp)
}

@Composable
private fun FrameRow(r: FrameHdrRecord, selected: Boolean, onClick: () -> Unit) {
    val w = r.hdr10Plus?.windows?.firstOrNull()
    val line = String.format(
        Locale.US, "#%-5d %9.3fs %s  %-8s %-8s %-6s  %s",
        r.frameNumber, r.ptsSeconds, if (r.isSync) "K" else " ",
        w?.let { String.format(Locale.US, "%.1f", it.maxSclNits) } ?: "-",
        w?.let { String.format(Locale.US, "%.1f", it.averageMaxrgbNits) } ?: "-",
        r.hdr10Plus?.targetedSystemDisplayMaximumLuminance?.toString() ?: "-",
        r.units.joinToString(", ") { it.name },
    )
    Text(
        line,
        color = if (r.hdr10Plus == null) AppColors.TextMuted else AppColors.TextPrimary,
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        modifier = Modifier.fillMaxWidth()
            .background(if (selected) AppColors.Selection else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(vertical = 2.dp, horizontal = 4.dp),
    )
}

private fun saveCsv(title: String, defaultName: String, content: String) {
    val dialog = FileDialog(null as Frame?, title, FileDialog.SAVE)
    dialog.file = defaultName
    dialog.isVisible = true
    val output = dialog.file?.let { File(dialog.directory, it) }
    dialog.dispose()
    output?.writeText(content)
}
