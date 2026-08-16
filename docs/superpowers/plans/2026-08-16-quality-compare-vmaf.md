# Quality Compare: VMAF Support Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add VMAF as a third, independently selectable quality metric (alongside PSNR/SSIM) to the existing "품질 비교" (Quality Compare) window, gated behind a cached runtime availability check, with an optional frame-subsampling speed-up ("빠른 모드").

**Architecture:** One new pure/synchronous function set in the existing `QualityMetrics.kt` (`runVmafPass`, `isVmafAvailable`, `parseVmafLog`), following the exact same shape as the existing `runPsnrPass`/`runSsimPass`. `QualityCompareWindow.kt` gains two checkboxes (VMAF, 빠른 모드), a one-time-per-session cached availability check, and a third branch in its existing run-queue dispatch. No new files, no changes to `QualityExport.kt`/`MetricGraph.kt`/`Main.kt`/`determineComparisonPairs` — all of those are already metric-agnostic.

**Tech Stack:** Kotlin, Compose Desktop. Reuses `FfmpegLocator`, the existing `qualityCompareExecutor`, `EventQueue.invokeLater`. No new dependencies.

## Global Constraints

- VMAF uses `log_fmt=csv` (not JSON) — verified against real ffmpeg 8.1.2 output: CSV produces a flat header row (`Frame,...,vmaf,` — trailing comma) followed by one comma-separated row per frame, avoiding any JSON parsing in a codebase with no JSON library.
- `Frame` and `vmaf` columns are located by name in the header (not a fixed index) — libvmaf's exact set of feature columns varies by version and isn't a stable contract to hardcode against.
- `n_threads` is always set automatically to `Runtime.getRuntime().availableProcessors()` — never user-configurable, no UI for it.
- `n_subsample` is a fixed binary choice via the "빠른 모드" checkbox: unchecked = every frame (no `n_subsample` option passed, i.e. default of 1), checked = `n_subsample=5` — not a tunable numeric input. Verified: `n_subsample` produces real (non-compacted) frame indices in the `Frame` column, e.g. a 10-frame clip with `n_subsample=5` produces rows at `Frame` 0 and 5.
- `runVmafPass` follows the same fixed `(comparison: File, reference: File, ...)` input-order convention already established for `runPsnrPass`/`runSsimPass` — `comparison` is always ffmpeg's first `-i`, `reference` the second. This convention exists specifically because VMAF is asymmetric (verified in an earlier phase: swapping input order changed the VMAF score).
- `isVmafAvailable()` detects support by checking whether `ffmpeg -filters` output contains the substring `libvmaf` — verified against a real `--enable-libvmaf` build. It performs no caching itself (pure, synchronous, blocking — same separation this file already has between metric-adjacent pure functions and UI-layer orchestration).
- The availability check runs only when the Compare window is opened for the first time in a session — never at app startup, never on the file-open path — and is cached at the module level in `QualityCompareWindow.kt` so re-opening the window later in the same session reuses the cached result.
- VMAF's checkbox is disabled until the availability check resolves `true`; when it resolves `false`, an inline text explains why (matching this window's existing inline-status-text convention — no new tooltip component).
- The run queue only ever includes a VMAF item when both `vmafEnabled` is checked AND the cached availability is exactly `true` — never when availability is still unresolved (`null`) or `false`. This is a belt-and-suspenders check in addition to the checkbox being disabled in that case.
- `runVmafPass`/`isVmafAvailable` return `null`/`false` on any failure, never throw — matching every other metric-pass function in this file.
- No changes to `QualityExport.kt`, `MetricGraph.kt`, `Main.kt`, or `determineComparisonPairs` — all already metric-agnostic (results are a `Map<String, MetricRunResult>`/`Map<String, Map<String, MetricRunResult>>` with no metric-specific logic).

---

### Task 1: `QualityMetrics.kt` — VMAF pass, CSV parsing, availability check

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/ui/QualityMetrics.kt` (insert `parseVmafLog` after `parseSsimLog`, i.e. after its closing `}` on line 201, before `fun runPsnrPass(` on line 203; append `isVmafAvailable` and `runVmafPass` at the end of the file, after `runSsimPass`'s closing `}`)
- Test: `app/src/test/kotlin/com/multiviewer/ui/QualityMetricsTest.kt` (insert new tests after the existing `runSsimPass` tests, i.e. after the `runSsimPass reports exactly 1_0 for identical frames` test's closing `}` on line 218, before the class's closing `}` on line 220)

**Interfaces:**
- Consumes: `MetricRunResult`, `MetricFrameSample`, `computeStatistics`, `runMetricPass`, `escapeForFilterGraph` (all existing, unchanged, in this same file).
- Produces: `fun isVmafAvailable(): Boolean`; `fun runVmafPass(comparison: File, reference: File, onProgress: (currentFrame: Int, totalFrames: Int?) -> Unit, isCancelled: () -> Boolean, fastMode: Boolean): MetricRunResult?` — Task 2 calls both.

- [ ] **Step 1: Write the failing tests**

Insert into `app/src/test/kotlin/com/multiviewer/ui/QualityMetricsTest.kt`, right after the `runSsimPass reports exactly 1_0 for identical frames` test's closing `}` (after line 218), before the class's final closing `}`:

```kotlin

    // isVmafAvailable ------------------------------------------------------------------------------

    @Test
    fun `isVmafAvailable returns true for a real ffmpeg build with libvmaf support`() {
        assertTrue(isVmafAvailable())
    }

    // runVmafPass -----------------------------------------------------------------------------------

    @Test
    fun `runVmafPass reports a score in the valid 0 to 100 range for two different real encodes`() {
        val reference = generateTestClip("64x48", "vmaf-ref")
        val comparison = reencode(reference, crf = 30, suffix = "vmaf-cmp")

        val result = runVmafPass(comparison, reference, onProgress = { _, _ -> }, isCancelled = { false }, fastMode = false)

        assertNotNull(result)
        assertEquals(10, result.perFrame.size)
        assertTrue(result.statistics.mean in 0.0..100.0)
        reference.delete(); comparison.delete()
    }

    @Test
    fun `runVmafPass in fast mode returns fewer frames at correct non-sequential frame indices`() {
        val reference = generateTestClip("64x48", "vmaf-fast-ref")
        val comparison = reencode(reference, crf = 30, suffix = "vmaf-fast-cmp")

        val result = runVmafPass(comparison, reference, onProgress = { _, _ -> }, isCancelled = { false }, fastMode = true)

        assertNotNull(result)
        assertTrue(result.perFrame.size < 10)
        assertEquals(0, result.perFrame.first().frameIndex)
        assertTrue(result.perFrame.all { it.frameIndex % 5 == 0 })
        reference.delete(); comparison.delete()
    }

    @Test
    fun `runVmafPass reports progress with an increasing current-frame count`() {
        val reference = generateTestClip("64x48", "vmaf-progress-ref")
        val comparison = reencode(reference, crf = 30, suffix = "vmaf-progress-cmp")
        val reportedFrames = mutableListOf<Int>()

        runVmafPass(comparison, reference, onProgress = { current, _ -> reportedFrames.add(current) }, isCancelled = { false }, fastMode = false)

        assertTrue(reportedFrames.isNotEmpty())
        assertEquals(reportedFrames.max(), reportedFrames.last())
        reference.delete(); comparison.delete()
    }

    @Test
    fun `runVmafPass returns null when cancelled immediately`() {
        val reference = generateTestClip("64x48", "vmaf-cancel-ref")
        val comparison = reencode(reference, crf = 30, suffix = "vmaf-cancel-cmp")

        val result = runVmafPass(comparison, reference, onProgress = { _, _ -> }, isCancelled = { true }, fastMode = false)

        assertNull(result)
        reference.delete(); comparison.delete()
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:test --tests "com.multiviewer.ui.QualityMetricsTest"`
Expected: FAIL — compile error, `isVmafAvailable`/`runVmafPass` don't exist yet.

- [ ] **Step 3: Add `parseVmafLog` to `QualityMetrics.kt`**

Insert into `app/src/main/kotlin/com/multiviewer/ui/QualityMetrics.kt`, right after `parseSsimLog`'s closing `}` (after line 201), before `fun runPsnrPass(`:

```kotlin

// Parses ffmpeg's libvmaf filter CSV log format (log_fmt=csv), verified against real ffmpeg 8.1
// output: a header row naming each column (feature columns vary by libvmaf version, but "Frame" and
// "vmaf" are always present), followed by one comma-separated row per scored frame. Locates the
// "Frame" and "vmaf" columns by name rather than a fixed index, since libvmaf's exact set of feature
// columns isn't a stable contract to hardcode against. Each row (and the header) has a trailing
// comma from libvmaf's own CSV writer, trimmed before splitting so column counts line up.
private fun parseVmafLog(statsFile: File): List<MetricFrameSample> {
    val lines = statsFile.readLines()
    val header = lines.firstOrNull()?.trimEnd(',')?.split(",") ?: return emptyList()
    val frameColumn = header.indexOf("Frame")
    val vmafColumn = header.indexOf("vmaf")
    if (frameColumn == -1 || vmafColumn == -1) return emptyList()
    return lines.drop(1).mapNotNull { line ->
        val fields = line.trimEnd(',').split(",")
        if (fields.size <= frameColumn || fields.size <= vmafColumn) return@mapNotNull null
        val frame = fields[frameColumn].toIntOrNull() ?: return@mapNotNull null
        val value = fields[vmafColumn].toDoubleOrNull() ?: return@mapNotNull null
        MetricFrameSample(frameIndex = frame, value = value)
    }
}
```

- [ ] **Step 4: Append `isVmafAvailable` and `runVmafPass` to `QualityMetrics.kt`**

Append to the end of `app/src/main/kotlin/com/multiviewer/ui/QualityMetrics.kt`, after `runSsimPass`'s closing `}`:

```kotlin

// Checks whether the resolved ffmpeg binary was built with libvmaf support, by looking for
// "libvmaf" in `ffmpeg -filters` output (verified: a --enable-libvmaf build lists a "libvmaf"
// filter line). Blocking -- callers must invoke this off the UI thread. Performs no caching itself;
// callers are responsible for caching the result (matches this file's existing separation between
// pure metric-adjacent functions and UI-layer orchestration/caching).
fun isVmafAvailable(): Boolean {
    return try {
        val process = ProcessBuilder(FfmpegLocator.ffmpegPath(), "-filters")
            .also { FfmpegLocator.configureEnvironment(it) }
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor(30, TimeUnit.SECONDS)
        output.contains("libvmaf")
    } catch (e: Exception) {
        false
    }
}

fun runVmafPass(
    comparison: File,
    reference: File,
    onProgress: (currentFrame: Int, totalFrames: Int?) -> Unit,
    isCancelled: () -> Boolean,
    fastMode: Boolean,
): MetricRunResult? {
    val statsFile = try {
        File.createTempFile("multiviewer_vmaf_", ".csv")
    } catch (e: Exception) {
        return null
    }
    return try {
        val threads = Runtime.getRuntime().availableProcessors()
        val subsampleOption = if (fastMode) ":n_subsample=5" else ""
        val success = runMetricPass(
            comparison, reference,
            filterSpec = "libvmaf=log_path=${escapeForFilterGraph(statsFile.absolutePath)}:log_fmt=csv:n_threads=$threads$subsampleOption",
            statsFile = statsFile, onProgress = onProgress, isCancelled = isCancelled,
        )
        if (!success) return null
        val perFrame = parseVmafLog(statsFile)
        val statistics = computeStatistics(perFrame) ?: return null
        MetricRunResult(perFrame, statistics)
    } finally {
        statsFile.delete()
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:test --tests "com.multiviewer.ui.QualityMetricsTest"`
Expected: PASS (22/22 tests — 17 existing + 5 new). These tests invoke real `ffmpeg`/`ffprobe` processes with `libvmaf` support (already confirmed present in this project's dev environment) — `ffmpeg` must be built with `--enable-libvmaf` in the test environment.

- [ ] **Step 6: Run the full test suite**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:test`
Expected: BUILD SUCCESSFUL, no regressions.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/ui/QualityMetrics.kt \
        app/src/test/kotlin/com/multiviewer/ui/QualityMetricsTest.kt
git commit -m "Add VMAF metric pass, CSV log parsing, and availability check"
```

---

### Task 2: `QualityCompareWindow.kt` — VMAF checkbox, 빠른 모드, availability caching, queue wiring

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/ui/QualityCompareWindow.kt` (full rewrite of file contents)

**Interfaces:**
- Consumes: `isVmafAvailable`, `runVmafPass` (Task 1); `determineComparisonPairs`, `ComparisonPair`, `resolutionsMatch`, `runPsnrPass`, `runSsimPass`, `MetricRunResult` (existing, unchanged); `writeResultsCsv`, `writeMultiPairResultsJson` (existing, unchanged); `MetricGraph` (existing, unchanged).

No new automated tests in this task — UI wiring only, matching this codebase's established convention for Compose UI tasks.

**Design decisions locked in for this task (not left to the implementer to improvise):**
- The availability check is triggered via a `remember { ... }` block used purely for its one-time-per-composition-entry side effect (kicking off the background check), rather than introducing `LaunchedEffect`/coroutines into a file that otherwise sticks entirely to this codebase's established `Executor` + `EventQueue.invokeLater` pattern. This is a deliberate stylistic consistency choice, not an oversight — do not "fix" it by switching to `LaunchedEffect`.
- `vmafAvailableCache: Boolean?` is a private module-level `var` (outside the composable function, alongside the existing `qualityCompareExecutor`) — `null` means "not yet checked", so it persists across window open/close within the same app session.
- All four checkboxes (PSNR, SSIM, VMAF, 빠른 모드) stay in one `Row`, matching this file's existing simple-`Row` style (no wrapping/multi-row layout logic exists elsewhere in this file either).
- 빠른 모드's checkbox is enabled only when `vmafAvailable == true && vmafEnabled` — it's meaningless (and disabled) whenever VMAF itself can't run.
- The run queue's dispatch `when` block gains a third `"VMAF"` branch calling `runVmafPass(..., fastMode = fastModeEnabled)` — reading `fastModeEnabled` directly from the background executor's closure, matching this file's existing (already-reviewed, accepted) pattern of reading `psnrEnabled`/`ssimEnabled` the same way.

- [ ] **Step 1: Replace `QualityCompareWindow.kt` in full**

Replace the entire contents of `app/src/main/kotlin/com/multiviewer/ui/QualityCompareWindow.kt` with:

```kotlin
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
```

- [ ] **Step 2: Compile**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Run the full test suite**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:test`
Expected: BUILD SUCCESSFUL, no regressions

- [ ] **Step 4: Manual verification**

Generate test files (reuse from Phase 3 if still present, or regenerate):

```bash
ffmpeg -y -f lavfi -i "testsrc=size=320x240:rate=10:duration=2" -c:v libx264 -pix_fmt yuv420p /tmp/qc_raw.mp4
ffmpeg -y -i /tmp/qc_raw.mp4 -c:v libx264 -crf 28 -pix_fmt yuv420p /tmp/qc_encoded_a.mp4
```

Launch the app (`./gradlew :app:run`) and open 품질 비교, then confirm:
- On this dev machine (confirmed to have `libvmaf`), the VMAF checkbox starts disabled, then becomes enabled and checked shortly after the window opens (the availability check completing).
- Closing and reopening the Compare window: VMAF checkbox is immediately enabled (no re-check delay) — confirms the module-level cache is working.
- 빠른 모드 checkbox is disabled while VMAF is unchecked; becomes clickable once VMAF is checked.
- Selecting Raw + Encoded A and running with PSNR, SSIM, and VMAF all checked: the progress label cycles through "... — PSNR", "... — SSIM", "... — VMAF" in that order; results show all three metrics for the pair, each with stats + graph.
- Running again with 빠른 모드 checked: VMAF completes noticeably faster, and its graph shows visibly fewer points than a full-frame PSNR/SSIM run for the same clip.
- CSV and JSON export both include the VMAF column/section alongside PSNR/SSIM, with no export code changes needed (confirms the metric-agnostic export functions work unchanged).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/ui/QualityCompareWindow.kt
git commit -m "Add VMAF checkbox, fast-mode subsampling, and availability caching to Quality Compare window"
```
