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
import com.multiviewer.parser.ByteReader
import com.multiviewer.parser.buildVideoSampleTable
import com.multiviewer.parser.hrd.HrdSimulationReport
import com.multiviewer.parser.hrd.VideoFrameItem
import com.multiviewer.parser.hrd.simulateHrdBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.util.Locale

@Composable
fun HrdBufferSimulationWindow(
    tab: TabState,
    language: AppLanguage,
    onCloseRequest: () -> Unit,
) {
    val ko = language == AppLanguage.KO
    fun label(korean: String, english: String) = if (ko) korean else english

    Window(
        onCloseRequest = onCloseRequest,
        state = rememberWindowState(size = DpSize(1080.dp, 820.dp), position = WindowPosition(Alignment.Center)),
        title = "${I18n.menuHrdBufferSimulation(language)} - ${tab.file.name}",
    ) {
        var loading by remember(tab.file) { mutableStateOf(true) }
        var error by remember(tab.file) { mutableStateOf<String?>(null) }
        var frames by remember(tab.file) { mutableStateOf<List<VideoFrameItem>>(emptyList()) }
        var codecName by remember(tab.file) { mutableStateOf("") }
        // Identity of the bytes the frames were read from, captured once at load (not per parameter edit).
        var loadIdentity by remember(tab.file) { mutableStateOf<ContentFileIdentity?>(null) }

        var bitrateKbpsText by remember { mutableStateOf("5000") }
        var cpbKbText by remember { mutableStateOf("1000") }
        var initialDelayText by remember { mutableStateOf("0.5") }
        var selectedFrame by remember { mutableStateOf<Int?>(null) }
        var saveStatus by remember { mutableStateOf<String?>(null) }

        val tableState = rememberLazyListState()
        val scope = rememberCoroutineScope()

        LaunchedEffect(tab.file) {
            if (tab.type != MediaType.VIDEO) {
                error = label("지원하지 않는 미디어 타입입니다 (비디오 파일만 지원)", "Unsupported media type (video files only)")
                loading = false
                return@LaunchedEffect
            }
            val root = tab.root
            if (root == null) {
                error = label("파싱된 컨테이너 구조가 없습니다", "No parsed container structure")
                loading = false
                return@LaunchedEffect
            }

            withContext(Dispatchers.IO) {
                try {
                    loadIdentity = null
                    val identityAtLoad = ContentFileIdentity.capture(tab.file)
                    ByteReader.open(tab.file).use { reader ->
                        val table = buildVideoSampleTable(reader, root)
                        if (table == null || table.samples.isEmpty()) {
                            error = label("비디오 트랙 샘플 테이블을 찾을 수 없습니다 (H.264/HEVC/AV1)", "No video sample table found (H.264/HEVC/AV1)")
                            loadIdentity = null
                        } else {
                            val timescale = table.timescale.coerceAtLeast(1L).toDouble()
                            val parsedFrames = table.samples.map { s ->
                                VideoFrameItem(
                                    index = s.index,
                                    sizeBytes = s.size,
                                    dtsSeconds = s.decodeTime / timescale,
                                    ptsSeconds = (s.decodeTime + s.compositionOffset) / timescale,
                                    isKeyframe = s.isSync,
                                )
                            }
                            frames = parsedFrames
                            codecName = table.codec.name

                            val duration = if (parsedFrames.isNotEmpty()) {
                                (parsedFrames.last().dtsSeconds - parsedFrames.first().dtsSeconds).coerceAtLeast(0.1)
                            } else 1.0
                            val totalBytes = parsedFrames.sumOf { it.sizeBytes }
                            val avgBitrateBps = ((totalBytes * 8.0) / duration).toLong().coerceAtLeast(100_000L)
                            val defaultCpbBytes = ((avgBitrateBps / 8.0) * 1.5).toLong().coerceAtLeast(50_000L)

                            bitrateKbpsText = (avgBitrateBps / 1000L).toString()
                            cpbKbText = (defaultCpbBytes / 1024L).toString()
                            loadIdentity = identityAtLoad
                        }
                    }
                } catch (e: Exception) {
                    error = e.message ?: e.toString()
                    loadIdentity = null
                }
            }
            loading = false
        }

        val bitrateBps = (bitrateKbpsText.toLongOrNull() ?: 5000L).coerceAtLeast(10L) * 1000L
        val cpbSizeBytes = (cpbKbText.toLongOrNull() ?: 1000L).coerceAtLeast(1L) * 1024L
        val initialDelaySec = (initialDelayText.toDoubleOrNull() ?: 0.5).coerceAtLeast(0.0)

        val report: HrdSimulationReport = remember(frames, bitrateBps, cpbSizeBytes, initialDelaySec) {
            simulateHrdBuffer(
                frames = frames,
                bitrateBps = bitrateBps,
                cpbSizeBytes = cpbSizeBytes,
                initialDelaySeconds = initialDelaySec,
            )
        }
        // Keep the latest report on the tab for the AI (not while frames are unloaded / errored).
        LaunchedEffect(report, loadIdentity) {
            val id = loadIdentity
            if (frames.isNotEmpty() && id != null) tab.hrdReport = StoredAnalysis(report, id)
        }

        fun saveCsv() {
            val dialog = FileDialog(null as Frame?, label("HRD 버퍼 시뮬레이션 CSV 저장", "Save HRD Buffer Simulation CSV"), FileDialog.SAVE)
            dialog.file = "${tab.file.nameWithoutExtension}-hrd-buffer.csv"
            dialog.isVisible = true
            val output = dialog.file?.let { File(dialog.directory, it) }
            dialog.dispose()
            if (output == null) return

            scope.launch {
                val failure = withContext(Dispatchers.IO) {
                    try {
                        val csv = buildString {
                            append("FrameIndex,DTS(s),Size(Bytes),BufferLevel(Bytes),Occupancy(%),IsUnderflow,IsWarning,IsKeyframe\n")
                            for (p in report.points) {
                                append("${p.frameIndex},${String.format(Locale.US, "%.4f", p.dtsSeconds)},${p.frameSizeBytes},${p.bufferLevelBytes},${String.format(Locale.US, "%.2f", p.occupancyPercent)},${p.isUnderflow},${p.isWarning},${p.isKeyframe}\n")
                            }
                        }
                        // UTF-8 BOM
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
                    Text(label("비디오 샘플 및 버퍼 모델 분석 중…", "Analyzing video samples & buffer model…"), color = AppColors.TextPrimary)
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                error != null -> {
                    Text(label("오류: ", "Error: ") + error, color = AppColors.NeonRed)
                }
                frames.isEmpty() -> {
                    Text(label("분석 가능한 프레임 샘플이 없습니다.", "No analyzable video frame samples."), color = AppColors.NeonYellow)
                }
                else -> {
                    // Header Status Bar
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            val isSafe = report.underflowCount == 0
                            val badgeColor = if (isSafe) AppColors.NeonGreen else AppColors.NeonRed
                            Text(
                                text = if (isSafe) label("✓ SAFE (언더플로우 0)", "✓ SAFE (0 Underflow)") else label("✗ FAIL (${report.underflowCount}회 언더플로우)", "✗ FAIL (${report.underflowCount} Underflow)"),
                                color = badgeColor,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                modifier = Modifier
                                    .background(badgeColor.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                                    .border(1.dp, badgeColor, RoundedCornerShape(4.dp))
                                    .padding(horizontal = 8.dp, vertical = 3.dp),
                            )
                            Text(
                                String.format(
                                    Locale.US,
                                    "%s · %d frames · avg %.1f%% · min %.1f%%",
                                    codecName,
                                    report.totalFrames,
                                    report.avgOccupancyPercent,
                                    report.minOccupancyPercent,
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

                    // Parameter Controls
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        OutlinedTextField(
                            value = bitrateKbpsText,
                            onValueChange = { bitrateKbpsText = it },
                            label = { Text(label("비트레이트 (kbps)", "Bitrate (kbps)"), fontSize = 10.sp) },
                            singleLine = true,
                            modifier = Modifier.width(140.dp),
                        )
                        OutlinedTextField(
                            value = cpbKbText,
                            onValueChange = { cpbKbText = it },
                            label = { Text(label("CPB 버퍼 (KB)", "CPB Buffer (KB)"), fontSize = 10.sp) },
                            singleLine = true,
                            modifier = Modifier.width(130.dp),
                        )
                        OutlinedTextField(
                            value = initialDelayText,
                            onValueChange = { initialDelayText = it },
                            label = { Text(label("초기 지연 (s)", "Initial Delay (s)"), fontSize = 10.sp) },
                            singleLine = true,
                            modifier = Modifier.width(110.dp),
                        )
                    }

                    // Buffer Occupancy Graph
                    HrdBufferGraph(
                        points = report.points,
                        selectedFrame = selectedFrame,
                        onSelectFrame = { f ->
                            selectedFrame = f
                            scope.launch { tableState.animateScrollToItem(f) }
                        },
                        language = language,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(220.dp)
                            .background(AppColors.Panel, RoundedCornerShape(4.dp))
                            .border(1.dp, AppColors.Border, RoundedCornerShape(4.dp))
                            .padding(8.dp),
                    )

                    // Legend & Detail Tables
                    Row(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        // Left: Underflow & Warning Frames List
                        Column(
                            modifier = Modifier
                                .weight(0.35f)
                                .fillMaxSize()
                                .background(AppColors.Panel, RoundedCornerShape(4.dp))
                                .border(1.dp, AppColors.Border, RoundedCornerShape(4.dp))
                                .padding(8.dp),
                        ) {
                            Text(
                                label("이상 지점 목록 (${report.underflowCount} 언더플로우 / ${report.warningFrames.size} 주의)", "Anomalies (${report.underflowCount} Underflow / ${report.warningFrames.size} Warning)"),
                                color = AppColors.NeonBlue,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                            )
                            val anomalies = (report.underflowFrames.map { it to true } + report.warningFrames.map { it to false })
                                .distinctBy { it.first }
                                .sortedBy { it.first }

                            if (anomalies.isEmpty()) {
                                Text(
                                    label("버퍼 결핍 또는 위험 구간이 없습니다.", "No buffer starvation or warning zones."),
                                    color = AppColors.TextMuted,
                                    fontSize = 11.sp,
                                    modifier = Modifier.padding(top = 8.dp),
                                )
                            } else {
                                LazyColumn(modifier = Modifier.fillMaxSize().padding(top = 4.dp)) {
                                    itemsIndexed(anomalies) { _, (f, isUnderflow) ->
                                        val p = report.points.getOrNull(f)
                                        val color = if (isUnderflow) AppColors.NeonRed else Color(0xFFFFB74D)
                                        val tag = if (isUnderflow) "[UNDERFLOW]" else "[WARN < 15%]"
                                        val text = String.format(
                                            Locale.US,
                                            "%s #%-5d (%.1f%%) · %.1f KB",
                                            tag,
                                            f,
                                            p?.occupancyPercent ?: 0.0,
                                            (p?.frameSizeBytes ?: 0L) / 1024.0,
                                        )
                                        Text(
                                            text = text,
                                            color = color,
                                            fontSize = 11.sp,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable {
                                                    selectedFrame = f
                                                    scope.launch { tableState.animateScrollToItem(f) }
                                                }
                                                .padding(vertical = 2.dp),
                                        )
                                    }
                                }
                            }
                        }

                        // Right: Frames Table
                        Column(
                            modifier = Modifier
                                .weight(0.65f)
                                .fillMaxSize()
                                .background(AppColors.Panel, RoundedCornerShape(4.dp))
                                .border(1.dp, AppColors.Border, RoundedCornerShape(4.dp))
                                .padding(8.dp),
                        ) {
                            Text(
                                String.format(Locale.US, "%-6s %9s %2s  %-9s %-12s %-8s %s", "frame", "dts", "K", "size(KB)", "buffer(KB)", "occupancy", "status"),
                                color = AppColors.TextSecondary,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                maxLines = 1,
                                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                            )
                            LazyColumn(state = tableState, modifier = Modifier.fillMaxWidth().weight(1f)) {
                                itemsIndexed(report.points) { index, p ->
                                    val isSelected = selectedFrame == index
                                    val statusStr = when {
                                        p.isUnderflow -> "UNDERFLOW"
                                        p.isWarning -> "WARN"
                                        else -> "OK"
                                    }
                                    val statusColor = when {
                                        p.isUnderflow -> AppColors.NeonRed
                                        p.isWarning -> Color(0xFFFFB74D)
                                        else -> AppColors.TextMuted
                                    }
                                    val line = String.format(
                                        Locale.US,
                                        "%-6d %8.3fs %2s  %9.1f %12.1f %7.1f%%   %s",
                                        p.frameIndex,
                                        p.dtsSeconds,
                                        if (p.isKeyframe) "K" else " ",
                                        p.frameSizeBytes / 1024.0,
                                        p.bufferLevelBytes / 1024.0,
                                        p.occupancyPercent,
                                        statusStr,
                                    )
                                    Text(
                                        text = line,
                                        color = if (isSelected) Color.White else statusColor,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(if (isSelected) AppColors.Selection else Color.Transparent)
                                            .clickable { selectedFrame = index }
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
}
