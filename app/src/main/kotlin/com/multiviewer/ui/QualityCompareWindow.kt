package com.multiviewer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import java.awt.EventQueue
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

private val qualityCompareExecutor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable).apply { isDaemon = true } }

@Composable
fun QualityCompareWindow(onCloseRequest: () -> Unit) {
    Window(
        onCloseRequest = onCloseRequest,
        title = "품질 비교",
        state = rememberWindowState(size = DpSize(900.dp, 700.dp)),
    ) {
        var referenceFile by remember { mutableStateOf<File?>(null) }
        var comparisonFile by remember { mutableStateOf<File?>(null) }
        var psnrEnabled by remember { mutableStateOf(true) }
        var ssimEnabled by remember { mutableStateOf(true) }
        var isRunning by remember { mutableStateOf(false) }
        var currentFrame by remember { mutableStateOf(0) }
        var totalFrames by remember { mutableStateOf<Int?>(null) }
        var results by remember { mutableStateOf<Map<String, MetricRunResult>?>(null) }
        var statusMessage by remember { mutableStateOf<String?>(null) }
        val cancelRequested = remember { AtomicBoolean(false) }

        fun pickFile(title: String, onPicked: (File) -> Unit) {
            val dialog = FileDialog(null as Frame?, title, FileDialog.LOAD)
            dialog.isVisible = true
            val fileName = dialog.file
            val directory = dialog.directory
            if (fileName != null && directory != null) onPicked(File(directory, fileName))
        }

        fun runComparison() {
            val comparison = comparisonFile ?: return
            val reference = referenceFile ?: return
            if (!psnrEnabled && !ssimEnabled) return

            isRunning = true
            currentFrame = 0
            totalFrames = null
            results = null
            statusMessage = null
            cancelRequested.set(false)

            qualityCompareExecutor.execute {
                if (!resolutionsMatch(comparison, reference)) {
                    EventQueue.invokeLater {
                        isRunning = false
                        statusMessage = "해상도가 일치하지 않습니다"
                    }
                    return@execute
                }

                val collected = mutableMapOf<String, MetricRunResult>()
                val onProgress: (Int, Int?) -> Unit = { frame, total ->
                    EventQueue.invokeLater {
                        currentFrame = frame
                        totalFrames = total
                    }
                }

                if (psnrEnabled) {
                    val psnrResult = runPsnrPass(comparison, reference, onProgress, { cancelRequested.get() })
                    if (psnrResult == null && cancelRequested.get()) {
                        EventQueue.invokeLater { isRunning = false; statusMessage = "취소됨" }
                        return@execute
                    }
                    if (psnrResult != null) collected["PSNR"] = psnrResult
                }
                if (ssimEnabled && !cancelRequested.get()) {
                    val ssimResult = runSsimPass(comparison, reference, onProgress, { cancelRequested.get() })
                    if (ssimResult == null && cancelRequested.get()) {
                        EventQueue.invokeLater { isRunning = false; statusMessage = "취소됨" }
                        return@execute
                    }
                    if (ssimResult != null) collected["SSIM"] = ssimResult
                }

                EventQueue.invokeLater {
                    isRunning = false
                    if (collected.isEmpty()) {
                        statusMessage = "측정 실패"
                    } else {
                        results = collected
                    }
                }
            }
        }

        fun exportResults(asJson: Boolean) {
            val currentResults = results ?: return
            val dialog = FileDialog(null as Frame?, "결과 저장", FileDialog.SAVE)
            dialog.file = if (asJson) "quality_compare.json" else "quality_compare.csv"
            dialog.isVisible = true
            val fileName = dialog.file
            val directory = dialog.directory
            if (fileName == null || directory == null) return
            val destination = File(directory, fileName)
            if (asJson) writeResultsJson(destination, currentResults) else writeResultsCsv(destination, currentResults)
            statusMessage = "저장됨: ${destination.name}"
        }

        Column(modifier = Modifier.fillMaxSize().background(AppColors.Background).padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Reference: ${referenceFile?.name ?: "(없음)"}", modifier = Modifier.weight(1f))
                Button(onClick = { pickFile("Reference 파일 선택") { referenceFile = it } }) { Text("선택") }
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Comparison: ${comparisonFile?.name ?: "(없음)"}", modifier = Modifier.weight(1f))
                Button(onClick = { pickFile("Comparison 파일 선택") { comparisonFile = it } }) { Text("선택") }
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = psnrEnabled, onCheckedChange = { psnrEnabled = it })
                Text("PSNR")
                Spacer(Modifier.height(1.dp))
                Checkbox(checked = ssimEnabled, onCheckedChange = { ssimEnabled = it })
                Text("SSIM")
            }
            Spacer(Modifier.height(8.dp))
            Row {
                Button(
                    onClick = { runComparison() },
                    enabled = !isRunning && referenceFile != null && comparisonFile != null && (psnrEnabled || ssimEnabled),
                ) { Text("비교 시작") }
                if (isRunning) {
                    Spacer(Modifier.height(1.dp))
                    Button(onClick = { cancelRequested.set(true) }) { Text("취소") }
                }
            }
            if (isRunning) {
                Spacer(Modifier.height(8.dp))
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
                    Button(onClick = { exportResults(asJson = false) }) { Text("CSV로 내보내기") }
                    Spacer(Modifier.height(1.dp))
                    Button(onClick = { exportResults(asJson = true) }) { Text("JSON으로 내보내기") }
                }
                Spacer(Modifier.height(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    currentResults.forEach { (name, result) ->
                        Text("$name — min: ${"%.3f".format(result.statistics.min)}, max: ${"%.3f".format(result.statistics.max)}, " +
                            "mean: ${"%.3f".format(result.statistics.mean)}, median: ${"%.3f".format(result.statistics.median)}")
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
