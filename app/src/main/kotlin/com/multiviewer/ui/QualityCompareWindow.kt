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
fun QualityCompareWindow(appState: AppState? = null, onCloseRequest: () -> Unit) {
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
        var rawRes by remember { mutableStateOf<Pair<Int, Int>?>(null) }
        var encodedARes by remember { mutableStateOf<Pair<Int, Int>?>(null) }
        var encodedBRes by remember { mutableStateOf<Pair<Int, Int>?>(null) }
        var pairResolutions by remember { mutableStateOf<Map<String, Pair<Pair<Int, Int>?, Pair<Int, Int>?>>>(emptyMap()) }
        var psnrEnabled by remember { mutableStateOf(true) }
        var ssimEnabled by remember { mutableStateOf(true) }
        var vmafEnabled by remember { mutableStateOf(true) }
        var fastModeEnabled by remember { mutableStateOf(false) }
        var autoScaleEnabled by remember { mutableStateOf(false) }
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
                pairResolutions = emptyMap()
                rawRes = null
                encodedARes = null
                encodedBRes = null
                return
            }
            pairStatuses = pairs.associate { it.id to null }
            qualityCompareExecutor.execute {
                val rRaw = rawFile?.let { probeResolution(it) }
                val rA = encodedAFile?.let { probeResolution(it) }
                val rB = encodedBFile?.let { probeResolution(it) }
                val pairRes = pairs.associate {
                    val compRes = probeResolution(it.comparison)
                    val refRes = probeResolution(it.reference)
                    it.id to Pair(compRes, refRes)
                }
                val checked = pairs.associate {
                    val (compRes, refRes) = pairRes[it.id] ?: Pair(null, null)
                    it.id to (compRes != null && refRes != null && compRes == refRes)
                }
                EventQueue.invokeLater {
                    rawRes = rRaw
                    encodedARes = rA
                    encodedBRes = rB
                    pairResolutions = pairRes
                    pairStatuses = checked
                }
            }
        }

        fun pickFile(title: String, onPicked: (File) -> Unit) {
            val dialog = FileDialog(null as Frame?, title, FileDialog.LOAD)
            appState?.lastOpenedDirectory?.let { dir ->
                if (dir.exists() && dir.isDirectory) {
                    dialog.directory = dir.absolutePath
                }
            }
            dialog.isVisible = true
            val fileName = dialog.file
            val directory = dialog.directory
            if (fileName != null && directory != null) {
                val file = File(directory, fileName)
                appState?.updateLastOpenedDirectory(file)
                onPicked(file)
                refreshPairStatuses()
            }
        }

        fun runComparison() {
            val queuedPairs = determineComparisonPairs(rawFile, encodedAFile, encodedBFile)
                .filter { pairStatuses[it.id] == true || autoScaleEnabled }
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
                        "PSNR" -> runPsnrPass(item.pair.comparison, item.pair.reference, onProgress, { cancelRequested.get() }, autoScale = autoScaleEnabled)
                        "SSIM" -> runSsimPass(item.pair.comparison, item.pair.reference, onProgress, { cancelRequested.get() }, autoScale = autoScaleEnabled)
                        else -> runVmafPass(item.pair.comparison, item.pair.reference, onProgress, { cancelRequested.get() }, fastMode = fastModeEnabled, autoScale = autoScaleEnabled)
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
            val dialog = FileDialog(null as Frame?, "CSV 저장 폴더 선택", FileDialog.SAVE)
            appState?.lastOpenedDirectory?.let { dir ->
                if (dir.exists() && dir.isDirectory) {
                    dialog.directory = dir.absolutePath
                }
            }
            dialog.file = "quality_compare.csv"
            dialog.isVisible = true
            val directory = dialog.directory ?: return
            val dir = File(directory)
            appState?.updateLastOpenedDirectory(dir)
            currentResults.forEach { (pairLabel, metricResults) ->
                val csvFile = File(directory, "quality_compare_${sanitizeForFilename(pairLabel)}.csv")
                writeResultsCsv(csvFile, metricResults)
            }
            statusMessage = "CSV 저장됨: $directory"
        }

        fun exportJson() {
            val currentResults = results ?: return
            val dialog = FileDialog(null as Frame?, "JSON 결과 저장", FileDialog.SAVE)
            appState?.lastOpenedDirectory?.let { dir ->
                if (dir.exists() && dir.isDirectory) {
                    dialog.directory = dir.absolutePath
                }
            }
            dialog.file = "quality_compare.json"
            dialog.isVisible = true
            val fileName = dialog.file
            val directory = dialog.directory
            if (fileName == null || directory == null) return
            val dest = File(directory, fileName)
            appState?.updateLastOpenedDirectory(dest)
            writeMultiPairResultsJson(dest, currentResults)
            statusMessage = "저장됨: $fileName"
        }

        Column(modifier = Modifier.fillMaxSize().background(AppColors.Background).padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val resStr = rawRes?.let { " (${it.first}x${it.second})" } ?: ""
                Text("Raw: ${rawFile?.name ?: "(없음)"}$resStr", modifier = Modifier.weight(1f))
                Button(enabled = !isRunning, onClick = { pickFile("Raw 파일 선택") { rawFile = it } }) { Text("선택") }
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                val resStr = encodedARes?.let { " (${it.first}x${it.second})" } ?: ""
                Text("Encoded A: ${encodedAFile?.name ?: "(없음)"}$resStr", modifier = Modifier.weight(1f))
                Button(enabled = !isRunning, onClick = { pickFile("Encoded A 파일 선택") { encodedAFile = it } }) { Text("선택") }
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                val resStr = encodedBRes?.let { " (${it.first}x${it.second})" } ?: ""
                Text("Encoded B: ${encodedBFile?.name ?: "(없음)"}$resStr", modifier = Modifier.weight(1f))
                Button(enabled = !isRunning, onClick = { pickFile("Encoded B 파일 선택") { encodedBFile = it } }) { Text("선택") }
            }

            val candidatePairs = determineComparisonPairs(rawFile, encodedAFile, encodedBFile)
            if (candidatePairs.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Column {
                    candidatePairs.forEach { pair ->
                        val isMatch = pairStatuses[pair.id]
                        val (compRes, refRes) = pairResolutions[pair.id] ?: Pair(null, null)
                        val statusText = when {
                            isMatch == null -> "확인 중..."
                            isMatch == true -> "✓ 해상도 일치 (${compRes?.first}x${compRes?.second})"
                            autoScaleEnabled -> "⚠️ 해상도 불일치 (${compRes?.let { "${it.first}x${it.second}" } ?: "?"} vs ${refRes?.let { "${it.first}x${it.second}" } ?: "?"}) → 자동 맞춤 적용"
                            else -> "❌ 해상도 불일치 (${compRes?.let { "${it.first}x${it.second}" } ?: "?"} vs ${refRes?.let { "${it.first}x${it.second}" } ?: "?"}) — [해상도 자동 맞춤] 필요"
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
                Spacer(Modifier.width(8.dp))
                Checkbox(checked = autoScaleEnabled, onCheckedChange = { autoScaleEnabled = it })
                Text("해상도 자동 맞춤")
            }
            Spacer(Modifier.height(8.dp))
            Row {
                Button(
                    onClick = { runComparison() },
                    enabled = !isRunning && (psnrEnabled || ssimEnabled || (vmafEnabled && vmafAvailable == true)) &&
                        candidatePairs.any { pairStatuses[it.id] == true || autoScaleEnabled },
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
