package com.multiviewer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import java.awt.EventQueue
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

private val qualityCompareExecutor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable).apply { isDaemon = true } }

// Cached at module level (not per-window-instance) so re-opening the Compare window later in the
// same app session reuses this result instead of re-checking. null = not yet checked.
private var vmafAvailableCache: Boolean? = null

// Derives a filesystem-safe per-pair CSV filename fragment from a pair's display label, e.g.
// "Raw ↔ Encoded A" -> "Raw_vs_Encoded_A".
private fun sanitizeForFilename(label: String): String = label.replace(" ", "_").replace("↔", "vs")

private data class QueueItem(val pair: ComparisonPair, val metricName: String)

@Composable
fun QualityCompareWindow(onCloseRequest: () -> Unit) {
    val cancelRequested = remember { AtomicBoolean(false) }

    Window(
        onCloseRequest = {
            cancelRequested.set(true)
            onCloseRequest()
        },
        title = "품질 비교",
        state = rememberWindowState(size = DpSize(900.dp, 700.dp)),
    ) {
        var rawFile by remember { mutableStateOf<File?>(null) }
        var encodedAFile by remember { mutableStateOf<File?>(null) }
        var encodedBFile by remember { mutableStateOf<File?>(null) }
        var psnrEnabled by remember { mutableStateOf(true) }
        var ssimEnabled by remember { mutableStateOf(true) }
        var vmafEnabled by remember { mutableStateOf(true) }
        var fastModeEnabled by remember { mutableStateOf(false) }
        var vmafAvailable by remember { mutableStateOf(vmafAvailableCache) }
        var isRunning by remember { mutableStateOf(false) }
        var currentFrame by remember { mutableStateOf(0) }
        var totalFrames by remember { mutableStateOf<Int?>(null) }
        var currentPassLabel by remember { mutableStateOf<String?>(null) }
        var pairStatuses by remember { mutableStateOf<Map<String, Boolean?>>(emptyMap()) }
        var results by remember { mutableStateOf<Map<String, Map<String, MetricRunResult>>?>(null) }
        var statusMessage by remember { mutableStateOf<String?>(null) }

        // Kicks off the (potentially slow) libvmaf-availability check in the background exactly once
        // per window open, using remember's one-time-per-composition-entry semantics rather than
        // introducing LaunchedEffect/coroutines into a file that otherwise sticks to this codebase's
        // Executor + EventQueue.invokeLater pattern throughout. Skipped entirely if a prior window
        // open this session already cached the result.
        remember {
            if (vmafAvailableCache == null) {
                qualityCompareExecutor.execute {
                    val available = isVmafAvailable()
                    vmafAvailableCache = available
                    EventQueue.invokeLater { vmafAvailable = available }
                }
            }
        }

        fun refreshPairStatuses() {
            val pairs = determineComparisonPairs(rawFile, encodedAFile, encodedBFile)
            if (pairs.isEmpty()) {
                pairStatuses = emptyMap()
                return
            }
            pairStatuses = pairs.associate { it.id to null }
            qualityCompareExecutor.execute {
                val checked = pairs.associate { it.id to resolutionsMatch(it.comparison, it.reference) }
                EventQueue.invokeLater { pairStatuses = checked }
            }
        }

        fun pickFile(title: String, onPicked: (File) -> Unit) {
            val dialog = FileDialog(null as Frame?, title, FileDialog.LOAD)
            dialog.isVisible = true
            val fileName = dialog.file
            val directory = dialog.directory
            if (fileName != null && directory != null) {
                onPicked(File(directory, fileName))
                refreshPairStatuses()
            }
        }

        fun runComparison() {
            val queuedPairs = determineComparisonPairs(rawFile, encodedAFile, encodedBFile)
                .filter { pairStatuses[it.id] == true }
            val vmafQueueable = vmafEnabled && vmafAvailable == true
            if (queuedPairs.isEmpty() || (!psnrEnabled && !ssimEnabled && !vmafQueueable)) return

            val queue = queuedPairs.flatMap { pair ->
                listOfNotNull(
                    if (psnrEnabled) QueueItem(pair, "PSNR") else null,
                    if (ssimEnabled) QueueItem(pair, "SSIM") else null,
                    if (vmafQueueable) QueueItem(pair, "VMAF") else null,
                )
            }

            isRunning = true
            currentFrame = 0
            totalFrames = null
            currentPassLabel = null
            results = null
            statusMessage = null
            cancelRequested.set(false)

            qualityCompareExecutor.execute {
                val collected = linkedMapOf<String, MutableMap<String, MetricRunResult>>()
                for (item in queue) {
                    if (cancelRequested.get()) break

                    EventQueue.invokeLater {
                        currentPassLabel = "${item.pair.label} — ${item.metricName}"
                        currentFrame = 0
                        totalFrames = null
                    }
                    val onProgress: (Int, Int?) -> Unit = { frame, total ->
                        EventQueue.invokeLater { currentFrame = frame; totalFrames = total }
                    }
                    val result = when (item.metricName) {
                        "PSNR" -> runPsnrPass(item.pair.comparison, item.pair.reference, onProgress, { cancelRequested.get() })
                        "SSIM" -> runSsimPass(item.pair.comparison, item.pair.reference, onProgress, { cancelRequested.get() })
                        else -> runVmafPass(item.pair.comparison, item.pair.reference, onProgress, { cancelRequested.get() }, fastMode = fastModeEnabled)
                    }
                    if (result != null) {
                        collected.getOrPut(item.pair.label) { mutableMapOf() }[item.metricName] = result
                    }
                }

                EventQueue.invokeLater {
                    isRunning = false
                    currentPassLabel = null
                    statusMessage = when {
                        cancelRequested.get() -> "취소됨"
                        collected.isEmpty() -> "측정 실패"
                        else -> null
                    }
                    if (collected.isNotEmpty()) results = collected
                }
            }
        }

        fun exportCsv() {
            val currentResults = results ?: return
            // The picked filename is intentionally discarded -- only its directory is used, since one
            // CSV file per pair must be written (up to 3), not the single file a SAVE dialog implies.
            val dialog = FileDialog(null as Frame?, "CSV 저장 폴더 선택", FileDialog.SAVE)
            dialog.file = "quality_compare.csv"
            dialog.isVisible = true
            val directory = dialog.directory ?: return
            currentResults.forEach { (pairLabel, metricResults) ->
                val csvFile = File(directory, "quality_compare_${sanitizeForFilename(pairLabel)}.csv")
                writeResultsCsv(csvFile, metricResults)
            }
            statusMessage = "CSV 저장됨: $directory"
        }

        fun exportJson() {
            val currentResults = results ?: return
            val dialog = FileDialog(null as Frame?, "JSON 결과 저장", FileDialog.SAVE)
            dialog.file = "quality_compare.json"
            dialog.isVisible = true
            val fileName = dialog.file
            val directory = dialog.directory
            if (fileName == null || directory == null) return
            writeMultiPairResultsJson(File(directory, fileName), currentResults)
            statusMessage = "저장됨: $fileName"
        }

        Column(modifier = Modifier.fillMaxSize().background(AppColors.Background).padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Raw: ${rawFile?.name ?: "(없음)"}", modifier = Modifier.weight(1f))
                Button(enabled = !isRunning, onClick = { pickFile("Raw 파일 선택") { rawFile = it } }) { Text("선택") }
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Encoded A: ${encodedAFile?.name ?: "(없음)"}", modifier = Modifier.weight(1f))
                Button(enabled = !isRunning, onClick = { pickFile("Encoded A 파일 선택") { encodedAFile = it } }) { Text("선택") }
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Encoded B: ${encodedBFile?.name ?: "(없음)"}", modifier = Modifier.weight(1f))
                Button(enabled = !isRunning, onClick = { pickFile("Encoded B 파일 선택") { encodedBFile = it } }) { Text("선택") }
            }

            val candidatePairs = determineComparisonPairs(rawFile, encodedAFile, encodedBFile)
            if (candidatePairs.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Column {
                    candidatePairs.forEach { pair ->
                        val statusText = when (pairStatuses[pair.id]) {
                            null -> "확인 중..."
                            true -> "일치"
                            false -> "불일치 (건너뜀)"
                        }
                        Text("${pair.label}: $statusText")
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = psnrEnabled, onCheckedChange = { psnrEnabled = it })
                Text("PSNR")
                Spacer(Modifier.width(8.dp))
                Checkbox(checked = ssimEnabled, onCheckedChange = { ssimEnabled = it })
                Text("SSIM")
                Spacer(Modifier.width(8.dp))
                Checkbox(checked = vmafEnabled, enabled = vmafAvailable == true, onCheckedChange = { vmafEnabled = it })
                Text("VMAF")
                if (vmafAvailable == false) {
                    Spacer(Modifier.width(8.dp))
                    Text("VMAF 사용 불가 (ffmpeg에 libvmaf 없음)")
                }
                Spacer(Modifier.width(8.dp))
                Checkbox(checked = fastModeEnabled, enabled = vmafAvailable == true && vmafEnabled, onCheckedChange = { fastModeEnabled = it })
                Text("빠른 모드")
            }
            Spacer(Modifier.height(8.dp))
            Row {
                Button(
                    onClick = { runComparison() },
                    enabled = !isRunning && (psnrEnabled || ssimEnabled || (vmafEnabled && vmafAvailable == true)) &&
                        candidatePairs.any { pairStatuses[it.id] == true },
                ) { Text("비교 시작") }
                if (isRunning) {
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { cancelRequested.set(true) }) { Text("취소") }
                }
            }
            if (isRunning) {
                Spacer(Modifier.height(8.dp))
                currentPassLabel?.let { Text(it) }
                val total = totalFrames
                if (total != null && total > 0) {
                    LinearProgressIndicator(progress = { (currentFrame.toFloat() / total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    Text("프레임 $currentFrame / $total")
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
            statusMessage?.let {
                Spacer(Modifier.height(8.dp))
                Text(it)
            }
            results?.let { currentResults ->
                Spacer(Modifier.height(16.dp))
                Row {
                    Button(onClick = { exportCsv() }) { Text("CSV로 내보내기") }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { exportJson() }) { Text("JSON으로 내보내기") }
                }
                Spacer(Modifier.height(8.dp))
                Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    currentResults.forEach { (pairLabel, metricResults) ->
                        Text(pairLabel, modifier = Modifier.padding(top = 8.dp))
                        metricResults.forEach { (name, result) ->
                            Text("$name — min: ${String.format(Locale.US, "%.3f", result.statistics.min)}, max: ${String.format(Locale.US, "%.3f", result.statistics.max)}, " +
                                "mean: ${String.format(Locale.US, "%.3f", result.statistics.mean)}, median: ${String.format(Locale.US, "%.3f", result.statistics.median)}")
                            Box(modifier = Modifier.fillMaxWidth().height(120.dp)) {
                                MetricGraph(perFrame = result.perFrame, lineColor = AppColors.NeonBlue, modifier = Modifier.fillMaxSize())
                            }
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                }
            }
        }
    }
}
