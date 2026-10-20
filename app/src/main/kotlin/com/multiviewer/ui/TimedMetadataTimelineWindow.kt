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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
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
import com.multiviewer.parser.metadata.SensorType
import com.multiviewer.parser.metadata.TimedMetadataReport
import com.multiviewer.parser.metadata.readTimedMetadataReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.util.Locale

@Composable
fun TimedMetadataTimelineWindow(
    tab: TabState,
    language: AppLanguage,
    onCloseRequest: () -> Unit,
) {
    val ko = language == AppLanguage.KO
    fun label(korean: String, english: String) = if (ko) korean else english

    Window(
        onCloseRequest = onCloseRequest,
        state = rememberWindowState(size = DpSize(1080.dp, 820.dp), position = WindowPosition(Alignment.Center)),
        title = "${I18n.menuTimedMetadataTimeline(language)} - ${tab.file.name}",
    ) {
        var loading by remember(tab.file) { mutableStateOf(true) }
        var error by remember(tab.file) { mutableStateOf<String?>(null) }
        var report by remember(tab.file) { mutableStateOf<TimedMetadataReport?>(null) }

        var selectedSensorType by remember { mutableStateOf<SensorType?>(null) }
        var selectedSampleIndex by remember { mutableStateOf<Int?>(null) }
        var saveStatus by remember { mutableStateOf<String?>(null) }

        val tableState = rememberLazyListState()
        val scope = rememberCoroutineScope()

        LaunchedEffect(tab.file) {
            val root = tab.root
            if (root == null) {
                error = label("파싱된 컨테이너 구조가 없습니다", "No parsed container structure")
                loading = false
                return@LaunchedEffect
            }

            withContext(Dispatchers.IO) {
                try {
                    val identity = ContentFileIdentity.capture(tab.file) // before the read: a mid-read change reads as stale
                    val rep = readTimedMetadataReport(tab.file, root)
                    if (rep == null || rep.samples.isEmpty()) {
                        error = label("Timed Metadata 트랙(camm / gpmd / mebx)을 찾을 수 없습니다", "No Timed Metadata track (camm / gpmd / mebx) found")
                    } else {
                        report = rep
                        tab.timedMetadata = StoredAnalysis(rep, identity)
                    }
                } catch (e: Exception) {
                    error = e.message ?: e.toString()
                }
            }
            loading = false
        }

        fun saveCsv() {
            val r = report ?: return
            val dialog = FileDialog(null as Frame?, label("센서 타임라인 CSV 저장", "Save Sensor Timeline CSV"), FileDialog.SAVE)
            dialog.file = "${tab.file.nameWithoutExtension}-sensor-timeline.csv"
            dialog.isVisible = true
            val output = dialog.file?.let { File(dialog.directory, it) }
            dialog.dispose()
            if (output == null) return

            scope.launch {
                val failure = withContext(Dispatchers.IO) {
                    try {
                        val csv = buildString {
                            append("SampleIndex,Time(s),Type,X,Y,Z,Label\n")
                            for (p in r.samples) {
                                append("${p.sampleIndex},${String.format(Locale.US, "%.4f", p.timestampSeconds)},${p.type},${p.x},${p.y},${p.z},\"${p.label ?: ""}\"\n")
                            }
                        }
                        output.writeText("\uFEFF$csv")
                        null
                    } catch (e: Exception) {
                        e.message ?: e.toString()
                    }
                }
                saveStatus = if (failure == null) label("저장됨: ", "Saved: ") + output.path else label("저장 실패: ", "Save failed: ") + failure
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(AppColors.Background)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            when {
                loading -> {
                    Text(label("Timed Metadata 센서 트랙 로딩 중…", "Loading Timed Metadata sensor track…"), color = AppColors.TextPrimary)
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                error != null -> {
                    Text(label("안내: ", "Notice: ") + error, color = AppColors.NeonYellow)
                }
                report != null -> {
                    val r = report!!
                    val availableTypes = remember(r) { r.samples.map { it.type }.distinct() }

                    // Header Status Bar
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            val badgeText = "Track #${r.trackId} · ${r.trackFormat.uppercase()}"
                            Text(
                                text = badgeText,
                                color = AppColors.NeonBlue,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                modifier = Modifier
                                    .background(AppColors.NeonBlue.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                                    .border(1.dp, AppColors.NeonBlue, RoundedCornerShape(4.dp))
                                    .padding(horizontal = 8.dp, vertical = 3.dp),
                            )

                            Text(
                                String.format(
                                    Locale.US,
                                    "%d samples · %.2fs · %.1f Hz",
                                    r.totalSamples,
                                    r.durationSeconds,
                                    r.sampleRateHz,
                                ),
                                color = AppColors.TextPrimary,
                                fontSize = 13.sp,
                            )
                        }

                        OutlinedButton(onClick = { saveCsv() }, shape = AppControlShape) {
                            Text(label("CSV 저장", "Save CSV"), fontSize = 12.sp)
                        }
                    }

                    saveStatus?.let { Text(it, color = AppColors.TextSecondary, fontSize = 11.sp, maxLines = 1) }

                    // Sensor Type Filter Bar & Legend
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilterChip(
                                selected = selectedSensorType == null,
                                onClick = { selectedSensorType = null },
                                label = { Text(label("전체 센서", "All Sensors"), fontSize = 11.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = AppColors.NeonBlue.copy(alpha = 0.25f),
                                    selectedLabelColor = AppColors.NeonBlue,
                                ),
                            )
                            availableTypes.forEach { st ->
                                FilterChip(
                                    selected = selectedSensorType == st,
                                    onClick = { selectedSensorType = st },
                                    label = { Text(st.name, fontSize = 11.sp) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = AppColors.NeonBlue.copy(alpha = 0.25f),
                                        selectedLabelColor = AppColors.NeonBlue,
                                    ),
                                )
                            }
                        }

                        // Legend
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("━ X-axis", color = Color(0xFF29B6F6), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Text("━ Y-axis", color = Color(0xFF66BB6A), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Text("━ Z-axis", color = Color(0xFFFF7043), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    val filteredPoints = remember(r, selectedSensorType) {
                        if (selectedSensorType == null) r.samples else r.samples.filter { it.type == selectedSensorType }
                    }

                    // Timeline Sensor Graph
                    SensorTimelineGraph(
                        points = filteredPoints,
                        selectedSampleIndex = selectedSampleIndex,
                        onSelectSample = { sIdx ->
                            selectedSampleIndex = sIdx
                            val listIdx = filteredPoints.indexOfFirst { it.sampleIndex == sIdx }
                            if (listIdx >= 0) {
                                scope.launch { tableState.animateScrollToItem(listIdx) }
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(240.dp)
                            .background(AppColors.Panel, RoundedCornerShape(4.dp))
                            .border(1.dp, AppColors.Border, RoundedCornerShape(4.dp))
                            .padding(8.dp),
                    )

                    // Samples Table
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .background(AppColors.Panel, RoundedCornerShape(4.dp))
                            .border(1.dp, AppColors.Border, RoundedCornerShape(4.dp))
                            .padding(8.dp),
                    ) {
                        Text(
                            String.format(Locale.US, "%-6s %10s  %-12s  %-12s  %-12s  %-8s %s", "sample", "time", "X", "Y", "Z", "type", "description"),
                            color = AppColors.TextSecondary,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            maxLines = 1,
                            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                        )
                        LazyColumn(state = tableState, modifier = Modifier.fillMaxWidth().weight(1f)) {
                            itemsIndexed(filteredPoints) { _, p ->
                                val isSelected = selectedSampleIndex == p.sampleIndex
                                val line = String.format(
                                    Locale.US,
                                    "%-6d %9.3fs  %12.4f  %12.4f  %12.4f  %-8s %s",
                                    p.sampleIndex,
                                    p.timestampSeconds,
                                    p.x,
                                    p.y,
                                    p.z,
                                    p.type.name,
                                    p.label ?: "",
                                )
                                Text(
                                    text = line,
                                    color = if (isSelected) Color.White else AppColors.TextPrimary,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(if (isSelected) AppColors.Selection else Color.Transparent)
                                        .clickable { selectedSampleIndex = p.sampleIndex }
                                        .padding(vertical = 2.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
