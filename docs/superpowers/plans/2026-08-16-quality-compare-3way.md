# Quality Compare Phase 3: 3-Way Comparison Mode Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extend the "품질 비교" (Quality Compare) window from Phase 1's fixed two-slot (Reference/Comparison) pair into a three-slot model — Raw (optional), Encoded A (required), Encoded B (optional) — that determines and runs whichever of the three comparison pairs (Raw↔A, Raw↔B, A↔B) the filled slots imply, with a live per-pair resolution-match status shown before the user runs anything.

**Architecture:** Two small, pure, independently-testable additions (a pair-determination function in `QualityMetrics.kt`, a multi-pair JSON writer in `QualityExport.kt`) feed a rewritten `QualityCompareWindow.kt` that owns the three file slots, the live per-pair status map, the sequential (pair × metric) run queue, and the grouped-by-pair results view. `QualityMetrics.kt`'s existing `resolutionsMatch`/`runPsnrPass`/`runSsimPass` are reused completely unchanged — every metric pass still operates on one `(comparison, reference)` file pair exactly as it already does, just invoked once per queued pair instead of once total.

**Tech Stack:** Kotlin, Compose Desktop. Reuses Phase 1's `FfmpegLocator`, dedicated single-thread executor (`qualityCompareExecutor`), `EventQueue.invokeLater` UI-thread marshaling, and `FileDialog`-based save flow. No new dependencies.

## Global Constraints

- PSNR/SSIM only — no VMAF checkbox or availability-check code in this phase (VMAF remains a separate, later phase).
- Three file slots: **Raw** (optional), **Encoded A** (required — nothing can run without it), **Encoded B** (optional). Which pairs run is fully determined by which slots are filled: Raw+A only → Raw↔A; A+B only → A↔B; all three → Raw↔A, Raw↔B, A↔B (fixed order, always: Raw↔A first, then Raw↔B, then A↔B).
- For every pair, ffmpeg's `-i` first-input/`comparison` argument and second-input/`reference` argument follow this fixed convention (established in Phase 1, still binding): Raw↔A → comparison=Encoded A, reference=Raw. Raw↔B → comparison=Encoded B, reference=Raw. A↔B → comparison=Encoded B, reference=Encoded A.
- Resolution-match status for every currently-candidate pair (both its files filled) is computed **immediately** in the background whenever a relevant file slot changes — not deferred until the user clicks 비교 시작 — and shown live next to that pair as "확인 중..." (checking), "일치" (match), or "불일치 (건너뜀)" (mismatch, skipped).
- 비교 시작 only queues pairs whose live status is currently "일치" (`true`). A pair still checking (`null`) or mismatched (`false`) is excluded from the run, not retried or blocked on.
- Cancel is all-or-nothing for the whole queue: clicking 취소 kills the in-flight ffmpeg process and skips every remaining queued pass. Already-completed pairs' results are kept and shown.
- File-picker 선택 buttons for all three slots are disabled while a comparison is running (`enabled = !isRunning`) — this is new in this phase, since resolution checks and the run queue now share the same single dedicated executor, and letting the user swap files mid-run would queue a resolution check behind a possibly multi-minute run.
- Results are grouped by pair first, then by metric within each pair, in the same fixed pair order as above (only pairs that actually produced at least one result are shown).
- Export produces one JSON file (all pairs nested: `pairLabel → metricName → { statistics, perFrame }`) and one CSV file per pair (same per-pair CSV shape Phase 1 already writes via the existing `writeResultsCsv`, called once per pair with a derived filename).
- Every metric-pass/resolution-check function continues to follow this codebase's established convention: pure functions return `null`/safe values on failure, never throw. This plan's new pure function (`determineComparisonPairs`) has no I/O and cannot fail — it's a plain data transformation.

---

### Task 1: `QualityMetrics.kt` — pair-determination logic

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/ui/QualityMetrics.kt` (insert after the existing `resolutionsMatch` function, i.e. after its closing `}` on line 53, before the `// Total video-stream frame count...` comment on line 55)
- Test: `app/src/test/kotlin/com/multiviewer/ui/QualityMetricsTest.kt` (insert after the existing `resolutionsMatch` tests, i.e. after the `resolutionsMatch is false...` test's closing `}` on line 75, before the `// runPsnrPass ---` comment on line 77)

**Interfaces:**
- Consumes: nothing new (plain `File?` parameters).
- Produces: `data class ComparisonPair(val id: String, val label: String, val comparison: File, val reference: File)`; `fun determineComparisonPairs(raw: File?, encodedA: File?, encodedB: File?): List<ComparisonPair>` — Task 3 calls this to determine both the live status rows to display and the run queue to build.

- [ ] **Step 1: Write the failing tests**

Insert into `app/src/test/kotlin/com/multiviewer/ui/QualityMetricsTest.kt`, right after the `resolutionsMatch is false for two files with different resolutions` test (after its closing `}`, before the `// runPsnrPass ---------------------------------------------------------------------------------` comment):

```kotlin

    // determineComparisonPairs ---------------------------------------------------------------------

    @Test
    fun `determineComparisonPairs returns an empty list when only Encoded A is filled`() {
        val encodedA = File("encoded-a.mp4")
        assertEquals(emptyList(), determineComparisonPairs(raw = null, encodedA = encodedA, encodedB = null))
    }

    @Test
    fun `determineComparisonPairs returns an empty list when nothing is filled`() {
        assertEquals(emptyList(), determineComparisonPairs(raw = null, encodedA = null, encodedB = null))
    }

    @Test
    fun `determineComparisonPairs returns only Raw-A when Raw and Encoded A are filled`() {
        val raw = File("raw.mp4")
        val encodedA = File("encoded-a.mp4")

        val pairs = determineComparisonPairs(raw, encodedA, encodedB = null)

        assertEquals(1, pairs.size)
        assertEquals("raw_a", pairs[0].id)
        assertEquals("Raw ↔ Encoded A", pairs[0].label)
        assertEquals(encodedA, pairs[0].comparison)
        assertEquals(raw, pairs[0].reference)
    }

    @Test
    fun `determineComparisonPairs returns only A-B when Encoded A and Encoded B are filled`() {
        val encodedA = File("encoded-a.mp4")
        val encodedB = File("encoded-b.mp4")

        val pairs = determineComparisonPairs(raw = null, encodedA, encodedB)

        assertEquals(1, pairs.size)
        assertEquals("a_b", pairs[0].id)
        assertEquals("Encoded A ↔ Encoded B", pairs[0].label)
        assertEquals(encodedB, pairs[0].comparison)
        assertEquals(encodedA, pairs[0].reference)
    }

    @Test
    fun `determineComparisonPairs returns all three pairs in Raw-A, Raw-B, A-B order when all three slots are filled`() {
        val raw = File("raw.mp4")
        val encodedA = File("encoded-a.mp4")
        val encodedB = File("encoded-b.mp4")

        val pairs = determineComparisonPairs(raw, encodedA, encodedB)

        assertEquals(3, pairs.size)
        assertEquals(listOf("raw_a", "raw_b", "a_b"), pairs.map { it.id })
        assertEquals(listOf("Raw ↔ Encoded A", "Raw ↔ Encoded B", "Encoded A ↔ Encoded B"), pairs.map { it.label })
        assertEquals(raw, pairs[1].reference)
        assertEquals(encodedB, pairs[1].comparison)
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:test --tests "com.multiviewer.ui.QualityMetricsTest"`
Expected: FAIL — compile error, `ComparisonPair`/`determineComparisonPairs` don't exist yet.

- [ ] **Step 3: Add `ComparisonPair` and `determineComparisonPairs` to `QualityMetrics.kt`**

Insert into `app/src/main/kotlin/com/multiviewer/ui/QualityMetrics.kt`, right after the `resolutionsMatch` function's closing `}` (after line 53), before the `// Total video-stream frame count...` comment:

```kotlin

data class ComparisonPair(val id: String, val label: String, val comparison: File, val reference: File)

// Determines which of the 3 possible comparison pairs (Raw-A, Raw-B, A-B) the currently-filled file
// slots imply, in this fixed order -- pure structural logic, no I/O, no resolution checking (that's
// resolutionsMatch, checked separately per pair once this list is known). Encoded A alone never
// produces a pair since there's nothing to compare it against yet.
fun determineComparisonPairs(raw: File?, encodedA: File?, encodedB: File?): List<ComparisonPair> {
    val pairs = mutableListOf<ComparisonPair>()
    if (raw != null && encodedA != null) {
        pairs.add(ComparisonPair(id = "raw_a", label = "Raw ↔ Encoded A", comparison = encodedA, reference = raw))
    }
    if (raw != null && encodedB != null) {
        pairs.add(ComparisonPair(id = "raw_b", label = "Raw ↔ Encoded B", comparison = encodedB, reference = raw))
    }
    if (encodedA != null && encodedB != null) {
        pairs.add(ComparisonPair(id = "a_b", label = "Encoded A ↔ Encoded B", comparison = encodedB, reference = encodedA))
    }
    return pairs
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:test --tests "com.multiviewer.ui.QualityMetricsTest"`
Expected: PASS (16/16 tests — 11 existing + 5 new).

- [ ] **Step 5: Run the full test suite**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:test`
Expected: BUILD SUCCESSFUL, no regressions.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/ui/QualityMetrics.kt \
        app/src/test/kotlin/com/multiviewer/ui/QualityMetricsTest.kt
git commit -m "Add comparison-pair determination logic for 3-way Quality Compare"
```

---

### Task 2: `QualityExport.kt` — multi-pair JSON export

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/ui/QualityExport.kt` (append after the existing `writeResultsJson` function)
- Test: `app/src/test/kotlin/com/multiviewer/ui/QualityExportTest.kt` (insert after the existing `writeResultsJson` test, before the class's closing `}`)

**Interfaces:**
- Consumes: `MetricRunResult` (Task 1's file, unchanged — already exists from Phase 1).
- Produces: `fun writeMultiPairResultsJson(destination: File, pairResults: Map<String, Map<String, MetricRunResult>>)` — Task 3 calls this from the results view's JSON export button. `pairResults` is keyed by pair label (e.g. `"Raw ↔ Encoded A"`), each value keyed by metric display name (e.g. `"PSNR"`), matching Phase 1's existing single-pair `writeResultsJson`'s inner shape one level deeper.

- [ ] **Step 1: Write the failing test**

Insert into `app/src/test/kotlin/com/multiviewer/ui/QualityExportTest.kt`, right after the `writeResultsJson writes statistics and per-frame data for every metric` test's closing `}`, before the class's final closing `}`:

```kotlin

    @Test
    fun `writeMultiPairResultsJson nests statistics and per-frame data under each pair then each metric`() {
        val destination = File.createTempFile("quality-export-multipair-json-test-", ".json")
        destination.deleteOnExit()

        writeMultiPairResultsJson(
            destination,
            linkedMapOf(
                "Raw ↔ Encoded A" to linkedMapOf("PSNR" to psnrResult, "SSIM" to ssimResult),
                "Encoded A ↔ Encoded B" to linkedMapOf("PSNR" to psnrResult),
            ),
        )

        val content = destination.readText()
        assertTrue(content.contains("\"Raw ↔ Encoded A\""))
        assertTrue(content.contains("\"Encoded A ↔ Encoded B\""))
        assertTrue(content.contains("\"PSNR\""))
        assertTrue(content.contains("\"SSIM\""))
        assertTrue(content.contains("\"min\": 45.0"))
        assertTrue(content.contains("\"mean\": 45.75"))
        assertTrue(content.contains("\"frameIndex\": 0, \"value\": 45.0"))
        assertTrue(content.contains("\"frameIndex\": 0, \"value\": 0.98"))
        destination.delete()
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:test --tests "com.multiviewer.ui.QualityExportTest"`
Expected: FAIL — compile error, `writeMultiPairResultsJson` doesn't exist yet.

- [ ] **Step 3: Add `writeMultiPairResultsJson` to `QualityExport.kt`**

Append to `app/src/main/kotlin/com/multiviewer/ui/QualityExport.kt`, after the existing `writeResultsJson` function:

```kotlin

// Writes every comparison pair's full results (per-frame series + aggregate statistics for every
// computed metric) as one JSON document, nested one level deeper than the single-pair writeResultsJson:
// pairLabel -> metricName -> { statistics, perFrame }. Same hand-written-JSON rationale as
// writeResultsJson applies here (no JSON library in this codebase, no user-input escaping needed --
// pair labels come from determineComparisonPairs's own fixed set, e.g. "Raw ↔ Encoded A").
fun writeMultiPairResultsJson(destination: File, pairResults: Map<String, Map<String, MetricRunResult>>) {
    destination.bufferedWriter().use { writer ->
        writer.write("{\n")
        val pairEntries = pairResults.entries.toList()
        pairEntries.forEachIndexed { pairIndex, (pairLabel, metricResults) ->
            writer.write("  \"$pairLabel\": {\n")
            val metricEntries = metricResults.entries.toList()
            metricEntries.forEachIndexed { metricIndex, (metricName, result) ->
                writer.write("    \"$metricName\": {\n")
                writer.write("      \"statistics\": {")
                writer.write(
                    "\"min\": ${result.statistics.min}, \"max\": ${result.statistics.max}, " +
                        "\"mean\": ${result.statistics.mean}, \"median\": ${result.statistics.median}",
                )
                writer.write("},\n")
                writer.write("      \"perFrame\": [")
                writer.write(result.perFrame.joinToString(", ") { "{\"frameIndex\": ${it.frameIndex}, \"value\": ${it.value}}" })
                writer.write("]\n")
                writer.write(if (metricIndex == metricEntries.lastIndex) "    }\n" else "    },\n")
            }
            writer.write(if (pairIndex == pairEntries.lastIndex) "  }\n" else "  },\n")
        }
        writer.write("}\n")
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:test --tests "com.multiviewer.ui.QualityExportTest"`
Expected: PASS (4/4 tests — 3 existing + 1 new).

- [ ] **Step 5: Run the full test suite**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:test`
Expected: BUILD SUCCESSFUL, no regressions.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/ui/QualityExport.kt \
        app/src/test/kotlin/com/multiviewer/ui/QualityExportTest.kt
git commit -m "Add multi-pair JSON export for 3-way Quality Compare"
```

---

### Task 3: `QualityCompareWindow.kt` — three-slot UI, live status, sequential queue

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/ui/QualityCompareWindow.kt` (full rewrite — replace entire file contents)

**Interfaces:**
- Consumes: `determineComparisonPairs`, `ComparisonPair` (Task 1); `resolutionsMatch`, `runPsnrPass`, `runSsimPass`, `MetricRunResult` (existing, from Phase 1, unchanged); `writeResultsCsv` (existing, from Phase 1, unchanged — called once per pair); `writeMultiPairResultsJson` (Task 2); `MetricGraph` (existing, from Phase 1, unchanged).

No new automated tests in this task — UI wiring only, matching this codebase's established convention (Phase 1's `QualityCompareWindow.kt` and this codebase's other Compose UI tasks have no automated tests).

**Design decisions locked in for this task (not left to the implementer to improvise):**
- The CSV export button ignores the exact filename the user picks in the `FileDialog` and only uses its **directory** — because it must write one file per pair (up to 3), not one. This mirrors Phase 1's existing CSV button but loops. Add a one-line comment explaining this, since a `FileDialog` in `SAVE` mode normally implies "this exact name," and silently deriving different filenames from it is the one non-obvious behavior in this task.
- Per-pair CSV filenames are derived from the pair's `label` via a small private `sanitizeForFilename` helper: replace `" "` with `"_"`, then `"↔"` with `"vs"` (e.g. `"Raw ↔ Encoded A"` → `"Raw_vs_Encoded_A"`), giving files like `quality_compare_Raw_vs_Encoded_A.csv`.
- The JSON export button keeps Phase 1's exact-filename behavior (single file, the picked name is used as-is) since `writeMultiPairResultsJson` only ever writes one file.
- Live per-pair status rows only appear for pairs currently returned by `determineComparisonPairs` (i.e., only pairs whose both required files are filled) — a pair with a missing file simply has no row, not a row showing some "N/A" placeholder.
- `determineComparisonPairs(rawFile, encodedAFile, encodedBFile)` is called directly (not cached via `remember`/`derivedStateOf`) everywhere it's needed in the composable body — it's a cheap pure function over three `File?` values, and this matches this codebase's existing simple-recomposition style rather than introducing a new state-caching pattern.

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
        var isRunning by remember { mutableStateOf(false) }
        var currentFrame by remember { mutableStateOf(0) }
        var totalFrames by remember { mutableStateOf<Int?>(null) }
        var currentPassLabel by remember { mutableStateOf<String?>(null) }
        var pairStatuses by remember { mutableStateOf<Map<String, Boolean?>>(emptyMap()) }
        var results by remember { mutableStateOf<Map<String, Map<String, MetricRunResult>>?>(null) }
        var statusMessage by remember { mutableStateOf<String?>(null) }

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
            if (queuedPairs.isEmpty() || (!psnrEnabled && !ssimEnabled)) return

            val queue = queuedPairs.flatMap { pair ->
                listOfNotNull(
                    if (psnrEnabled) QueueItem(pair, "PSNR") else null,
                    if (ssimEnabled) QueueItem(pair, "SSIM") else null,
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
                    val result = if (item.metricName == "PSNR") {
                        runPsnrPass(item.pair.comparison, item.pair.reference, onProgress, { cancelRequested.get() })
                    } else {
                        runSsimPass(item.pair.comparison, item.pair.reference, onProgress, { cancelRequested.get() })
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
            }
            Spacer(Modifier.height(8.dp))
            Row {
                Button(
                    onClick = { runComparison() },
                    enabled = !isRunning && (psnrEnabled || ssimEnabled) &&
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
                Column(modifier = Modifier.weight(1f)) {
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

Generate three small real test files:

```bash
ffmpeg -y -f lavfi -i "testsrc=size=320x240:rate=10:duration=2" -c:v libx264 -pix_fmt yuv420p /tmp/qc_raw.mp4
ffmpeg -y -i /tmp/qc_raw.mp4 -c:v libx264 -crf 28 -pix_fmt yuv420p /tmp/qc_encoded_a.mp4
ffmpeg -y -i /tmp/qc_raw.mp4 -c:v libx264 -crf 35 -pix_fmt yuv420p /tmp/qc_encoded_b.mp4
ffmpeg -y -f lavfi -i "testsrc=size=160x120:rate=10:duration=2" -c:v libx264 -pix_fmt yuv420p /tmp/qc_mismatched.mp4
```

Launch the app (`./gradlew :app:run`) and open 품질 비교, then confirm:
- Selecting only Encoded A: no pair status rows appear, 비교 시작 stays disabled.
- Selecting Raw + Encoded A: one status row ("Raw ↔ Encoded A") appears, briefly shows "확인 중...", then "일치"; 비교 시작 becomes enabled.
- Additionally selecting Encoded B: two more status rows appear ("Raw ↔ Encoded B", "Encoded A ↔ Encoded B"), both eventually showing "일치".
- Clicking 비교 시작 runs all three pairs' PSNR and SSIM sequentially — the label above the progress bar changes for each pass (e.g. "Raw ↔ Encoded A — PSNR", then "... — SSIM", then "Raw ↔ Encoded B — PSNR", etc.), 6 passes total.
- File-picker 선택 buttons are disabled (greyed out, unclickable) while a comparison is running.
- Results show three sections, one per pair, each with PSNR and SSIM stats + graph.
- Clicking 취소 partway through the run stops the current pass and skips the rest — a "취소됨" status appears, and any pairs that had already fully completed still show their results.
- Replace Encoded B with `/tmp/qc_mismatched.mp4` (different resolution): the "Raw ↔ Encoded B" and "Encoded A ↔ Encoded B" rows show "불일치 (건너뜀)", while "Raw ↔ Encoded A" still shows "일치". Running only executes the Raw↔A pair.
- "CSV로 내보내기" writes one CSV file per pair that ran (check the chosen directory for e.g. `quality_compare_Raw_vs_Encoded_A.csv`, `quality_compare_Raw_vs_Encoded_B.csv`, `quality_compare_Encoded_A_vs_Encoded_B.csv`).
- "JSON으로 내보내기" writes one JSON file containing all pairs nested under their labels.
- Closing the window mid-run (via the window's close button) stops the running ffmpeg process (no orphaned process left behind — check via `ps aux | grep ffmpeg` immediately after closing during an active run).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/ui/QualityCompareWindow.kt
git commit -m "Add 3-way comparison mode to Quality Compare window"
```
