# Quality Comparison Tool — PSNR/SSIM Implementation Plan (Phase 1 of 3)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A standalone "품질 비교" (Quality Compare) pop-out window, independent of the tab system, that runs ffmpeg-based PSNR and SSIM quality metrics between two user-picked media files (video or still image — the same code path handles both), with determinate progress, cancellation, a per-frame line graph, report-ready statistics (min/max/mean/median), and CSV/JSON export.

**Architecture:** `QualityMetrics.kt` (new) provides pure, synchronous, testable functions — `resolutionsMatch`, `runPsnrPass`, `runSsimPass` — each shelling out to one `ffmpeg` process via the existing `FfmpegLocator`/`ProcessBuilder` convention, reporting progress via a callback and honoring cancellation via a polled flag, parsing ffmpeg's own per-frame stats-file output into a common `MetricFrameSample`/`MetricStatistics` shape. `MetricGraph.kt` (new) is a reusable Canvas-based line-graph composable, modeled on this codebase's existing `FrameIntervalAnalysisView` Canvas pattern (no charting library, no `Path` API — plain `drawLine` calls, matching every other graph in this app). `QualityExport.kt` (new) writes CSV/JSON export files with hand-written serialization (no JSON library dependency exists in this codebase, and the fixed, simple output shape doesn't justify adding one). `QualityCompareWindow.kt` (new) is the standalone `Window` composable — its own dedicated single-thread executor (not the shared 2-thread pool `runInBackground` uses, since a metric pass can run for minutes and shouldn't contend with normal tab-open decode work) orchestrates calling the pure functions above and marshals progress/results back to the UI thread via `EventQueue.invokeLater`, matching this codebase's established threading convention. `Main.kt` gains a new, always-enabled menu item (not gated on any tab being open) that opens the window.

**Tech Stack:** Kotlin, Compose Desktop. Reuses `FfmpegLocator`/`ProcessBuilder` (existing `ffmpeg`/`ffprobe` invocation conventions), Compose `Canvas`/`Window`/`FileDialog`. No new dependencies.

This is Phase 1 of 3 for this feature (see `docs/superpowers/specs/2026-08-15-quality-comparison-psnr-ssim-vmaf-design.md`): Phase 1 (this plan) covers PSNR/SSIM only, a single comparison pair (either "raw vs. encoded" or "encoded A vs. encoded B" — structurally identical, just two files being compared). Phase 2 adds VMAF (its own filter, its own JSON log format, and a runtime `libvmaf`-availability check gating a third checkbox). Phase 3 adds the third optional file slot and the "Raw + Encoded A + Encoded B" 3-way comparison mode (three pairs run and shown side by side).

Every ffmpeg command and log/output format referenced in this plan was run for real and inspected during planning (not assumed from documentation): a real `libx264`-encoded MP4 pair (raw + two different CRF levels) and a real JPEG/PNG pair were generated via `ffmpeg -f lavfi -i testsrc=...`, then `psnr`/`ssim` filters, `-progress pipe:1`, `ffprobe` resolution/frame-count queries, and the resolution-mismatch failure path were each run against them and their exact output format recorded below.

## Global Constraints

- PSNR/SSIM only in this plan — VMAF is Phase 2 (a separate later plan); the UI in this plan has exactly two metric checkboxes (PSNR, SSIM), no VMAF checkbox or availability-check code yet.
- Exactly one comparison pair in this plan — two file-picker slots ("Reference" and "Comparison"), no third slot; the 3-way "Raw+A+B" mode is Phase 3.
- Video and still images share one code path — no format-specific branching. Verified: `ffmpeg -i encoded.jpg -i raw.png -lavfi psnr -f null -` produces the exact same log-line shape as the video case (a single `n:1 ...` line), so no special-casing is needed anywhere in this plan's code.
- `-i <comparison-file>` is always ffmpeg's first input, `-i <reference-file>` is always the second, in every metric pass this plan runs — this ordering is a fixed convention (not user-configurable) so results are consistently computed the same way pass to pass. (PSNR/SSIM are symmetric and don't care about this order, but this convention is established now because Phase 2's VMAF is NOT symmetric — verified: swapping argument order on a real test pair changed the VMAF score, 97.827651 vs 97.979732 — so Phase 1 establishes the ordering Phase 2 depends on.)
- Resolution mismatches are rejected outright — verified: ffmpeg exits with code 234 on mismatched input resolutions (`320,240` vs `160,120` tested). This plan pre-checks resolution via `ffprobe` before running any metric pass and shows an inline error rather than letting ffmpeg fail with a less legible message.
- A pixel-identical frame's PSNR is reported by ffmpeg as the literal string `"inf"` (verified: comparing a file against itself). This is capped at a fixed 100.0 dB sentinel rather than parsed as a literal infinity — real 8-bit video PSNR essentially never approaches 100dB for any genuinely different frame, so the cap only ever activates for true zero-error frames, without distorting statistics computed over a run that also contains normal (non-infinite) frames.
- Every metric-pass function returns `null` on any failure (process error, non-zero exit, cancellation, unparseable output) — callers never need their own try/catch, matching this codebase's established convention (see `Av1FrameHeaderAnalyzer.kt`/`H264ParameterSetExtraction.kt` for the same pattern in this codebase's parsing code).
- Metric pass functions in `QualityMetrics.kt` are synchronous, pure (no Compose/UI dependency) functions — all UI-thread marshaling (`EventQueue.invokeLater`) happens in the caller (`QualityCompareWindow.kt`), not inside `QualityMetrics.kt` itself, so `QualityMetrics.kt`'s functions can be unit-tested by calling them directly and blocking, exactly like this codebase's existing `TrackExtractorTest.kt` tests `extractVideoTrack`/`extractAudioTrack`.
- The Quality Compare window and its background work run on their own dedicated single-thread executor — not `ui/BackgroundTask.kt`'s shared 2-thread pool (sized for short per-tab decode/probe tasks, not a potentially minutes-long metric pass).
- The "품질 비교" menu item in `Main.kt` is always enabled, regardless of whether any tab is open — it does not read from or depend on `AppState.tabs`/`TabState` at all.

---

### Task 1: `QualityMetrics.kt` — resolution check, metric passes, log parsing, statistics

**Files:**
- Create: `app/src/main/kotlin/com/multiviewer/ui/QualityMetrics.kt`
- Test: `app/src/test/kotlin/com/multiviewer/ui/QualityMetricsTest.kt`

**Interfaces:**
- Consumes: `FfmpegLocator.ffmpegPath()`/`ffprobePath()`/`configureEnvironment(ProcessBuilder)` (existing, `ui/FfmpegLocator.kt`).
- Produces: `data class MetricFrameSample(val frameIndex: Int, val value: Double)`; `data class MetricStatistics(val min: Double, val max: Double, val mean: Double, val median: Double)`; `data class MetricRunResult(val perFrame: List<MetricFrameSample>, val statistics: MetricStatistics)`; `fun computeStatistics(perFrame: List<MetricFrameSample>): MetricStatistics?`; `fun resolutionsMatch(comparison: File, reference: File): Boolean`; `fun runPsnrPass(comparison: File, reference: File, onProgress: (currentFrame: Int, totalFrames: Int?) -> Unit, isCancelled: () -> Boolean): MetricRunResult?`; `fun runSsimPass(comparison: File, reference: File, onProgress: (currentFrame: Int, totalFrames: Int?) -> Unit, isCancelled: () -> Boolean): MetricRunResult?` — Task 4 calls these three off the UI thread.

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/kotlin/com/multiviewer/ui/QualityMetricsTest.kt`:

```kotlin
package com.multiviewer.ui

import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QualityMetricsTest {
    private fun generateTestClip(sizeSpec: String, suffix: String): File {
        val file = File.createTempFile("quality-metrics-test-$suffix-", ".mp4")
        file.deleteOnExit()
        ProcessBuilder(
            "ffmpeg", "-y", "-f", "lavfi", "-i", "testsrc=size=$sizeSpec:rate=10:duration=1",
            "-c:v", "libx264", "-pix_fmt", "yuv420p", file.absolutePath,
        ).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start().waitFor()
        return file
    }

    private fun reencode(source: File, crf: Int, suffix: String): File {
        val file = File.createTempFile("quality-metrics-test-$suffix-", ".mp4")
        file.deleteOnExit()
        ProcessBuilder(
            "ffmpeg", "-y", "-i", source.absolutePath, "-c:v", "libx264", "-crf", crf.toString(),
            "-pix_fmt", "yuv420p", file.absolutePath,
        ).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start().waitFor()
        return file
    }

    // computeStatistics -------------------------------------------------------------------------

    @Test
    fun `computeStatistics returns min max mean and median over an odd-length series`() {
        val samples = listOf(1.0, 5.0, 3.0).mapIndexed { i, v -> MetricFrameSample(i, v) }
        val stats = computeStatistics(samples)
        assertNotNull(stats)
        assertEquals(1.0, stats.min)
        assertEquals(5.0, stats.max)
        assertEquals(3.0, stats.mean)
        assertEquals(3.0, stats.median)
    }

    @Test
    fun `computeStatistics averages the two middle values for an even-length series`() {
        val samples = listOf(1.0, 2.0, 3.0, 4.0).mapIndexed { i, v -> MetricFrameSample(i, v) }
        val stats = computeStatistics(samples)
        assertNotNull(stats)
        assertEquals(2.5, stats.median)
        assertEquals(2.5, stats.mean)
    }

    @Test
    fun `computeStatistics returns null for an empty series`() {
        assertNull(computeStatistics(emptyList()))
    }

    // resolutionsMatch ----------------------------------------------------------------------------

    @Test
    fun `resolutionsMatch is true for two files with the same resolution`() {
        val a = generateTestClip("64x48", "res-a")
        val b = generateTestClip("64x48", "res-b")
        assertTrue(resolutionsMatch(a, b))
        a.delete(); b.delete()
    }

    @Test
    fun `resolutionsMatch is false for two files with different resolutions`() {
        val a = generateTestClip("64x48", "res-mismatch-a")
        val b = generateTestClip("32x24", "res-mismatch-b")
        assertFalse(resolutionsMatch(a, b))
        a.delete(); b.delete()
    }

    // runPsnrPass ---------------------------------------------------------------------------------

    @Test
    fun `runPsnrPass reports a high but finite score for two different real encodes`() {
        val reference = generateTestClip("64x48", "psnr-ref")
        val comparison = reencode(reference, crf = 30, suffix = "psnr-cmp")

        val result = runPsnrPass(comparison, reference, onProgress = { _, _ -> }, isCancelled = { false })

        assertNotNull(result)
        assertEquals(10, result.perFrame.size)
        assertTrue(result.statistics.mean > 20.0 && result.statistics.mean < 100.0)
        reference.delete(); comparison.delete()
    }

    @Test
    fun `runPsnrPass caps identical-frame infinite PSNR at 100dB instead of propagating infinity`() {
        val file = generateTestClip("64x48", "psnr-identical")

        val result = runPsnrPass(file, file, onProgress = { _, _ -> }, isCancelled = { false })

        assertNotNull(result)
        assertTrue(result.perFrame.all { it.value == 100.0 })
        assertEquals(100.0, result.statistics.mean)
        assertTrue(result.statistics.mean.isFinite())
        file.delete()
    }

    @Test
    fun `runPsnrPass reports progress with an increasing current-frame count`() {
        val reference = generateTestClip("64x48", "psnr-progress-ref")
        val comparison = reencode(reference, crf = 30, suffix = "psnr-progress-cmp")
        val reportedFrames = mutableListOf<Int>()

        runPsnrPass(comparison, reference, onProgress = { current, _ -> reportedFrames.add(current) }, isCancelled = { false })

        assertTrue(reportedFrames.isNotEmpty())
        assertEquals(reportedFrames.max(), reportedFrames.last())
        reference.delete(); comparison.delete()
    }

    @Test
    fun `runPsnrPass returns null when cancelled immediately`() {
        val reference = generateTestClip("64x48", "psnr-cancel-ref")
        val comparison = reencode(reference, crf = 30, suffix = "psnr-cancel-cmp")

        val result = runPsnrPass(comparison, reference, onProgress = { _, _ -> }, isCancelled = { true })

        assertNull(result)
        reference.delete(); comparison.delete()
    }

    // runSsimPass ---------------------------------------------------------------------------------

    @Test
    fun `runSsimPass reports a score close to 1_0 for two different real encodes`() {
        val reference = generateTestClip("64x48", "ssim-ref")
        val comparison = reencode(reference, crf = 30, suffix = "ssim-cmp")

        val result = runSsimPass(comparison, reference, onProgress = { _, _ -> }, isCancelled = { false })

        assertNotNull(result)
        assertEquals(10, result.perFrame.size)
        assertTrue(result.statistics.mean in 0.0..1.0)
        reference.delete(); comparison.delete()
    }

    @Test
    fun `runSsimPass reports exactly 1_0 for identical frames`() {
        val file = generateTestClip("64x48", "ssim-identical")

        val result = runSsimPass(file, file, onProgress = { _, _ -> }, isCancelled = { false })

        assertNotNull(result)
        assertTrue(result.perFrame.all { it.value == 1.0 })
        file.delete()
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:test --tests "com.multiviewer.ui.QualityMetricsTest"`
Expected: FAIL — compile error, `QualityMetrics.kt` doesn't exist yet.

- [ ] **Step 3: Create `QualityMetrics.kt`**

```kotlin
package com.multiviewer.ui

import java.io.File
import java.util.concurrent.TimeUnit

data class MetricFrameSample(val frameIndex: Int, val value: Double)

data class MetricStatistics(val min: Double, val max: Double, val mean: Double, val median: Double)

data class MetricRunResult(val perFrame: List<MetricFrameSample>, val statistics: MetricStatistics)

private const val METRIC_RUN_TIMEOUT_SECONDS = 600L
private const val PSNR_INFINITE_CAP_DB = 100.0
private val PSNR_AVG_REGEX = Regex("""psnr_avg:(\S+)""")
private val SSIM_ALL_REGEX = Regex("""All:(\S+)""")

// Computes min/max/mean/median over a metric's per-frame values -- shared by every metric pass so
// PSNR/SSIM (and, in a later phase, VMAF) report statistics the same way. Returns null for an empty
// list: a metric pass that produced zero frames of data is a failure, not a zero-stats result.
fun computeStatistics(perFrame: List<MetricFrameSample>): MetricStatistics? {
    if (perFrame.isEmpty()) return null
    val values = perFrame.map { it.value }.sorted()
    val n = values.size
    val median = if (n % 2 == 1) values[n / 2] else (values[n / 2 - 1] + values[n / 2]) / 2.0
    return MetricStatistics(min = values.first(), max = values.last(), mean = values.sum() / n, median = median)
}

// ffprobe's video-stream width/height, or null if the file has no video stream or ffprobe fails.
private fun probeResolution(file: File): Pair<Int, Int>? {
    return try {
        val process = ProcessBuilder(
            FfmpegLocator.ffprobePath(), "-v", "error", "-select_streams", "v:0",
            "-show_entries", "stream=width,height", "-of", "csv=p=0", file.absolutePath,
        ).also { FfmpegLocator.configureEnvironment(it) }
            .redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val line = process.inputStream.bufferedReader().readLine()
        process.waitFor(30, TimeUnit.SECONDS)
        val parts = line?.trim()?.split(",") ?: return null
        if (parts.size != 2) return null
        Pair(parts[0].toInt(), parts[1].toInt())
    } catch (e: Exception) {
        null
    }
}

// True only when both files have a resolvable, matching video-stream resolution. Callers must check
// this before running any metric pass -- verified: ffmpeg's psnr/ssim filters exit with a non-zero
// code (234, observed) on mismatched resolutions rather than comparing what they can.
fun resolutionsMatch(comparison: File, reference: File): Boolean {
    val a = probeResolution(comparison) ?: return false
    val b = probeResolution(reference) ?: return false
    return a == b
}

// Total video-stream frame count, used as the progress bar's denominator. Prefers the container's
// stored frame count (nb_frames, fast); falls back to duration * frame rate when nb_frames is
// unavailable ("N/A" on some containers/codecs that don't store it). Returns null if neither source
// is usable -- callers show an indeterminate/unknown-total progress bar in that case.
private fun probeFrameCount(file: File): Int? {
    try {
        val nbFramesProcess = ProcessBuilder(
            FfmpegLocator.ffprobePath(), "-v", "error", "-select_streams", "v:0",
            "-show_entries", "stream=nb_frames", "-of", "csv=p=0", file.absolutePath,
        ).also { FfmpegLocator.configureEnvironment(it) }
            .redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val nbFramesLine = nbFramesProcess.inputStream.bufferedReader().readLine()
        nbFramesProcess.waitFor(30, TimeUnit.SECONDS)
        val nbFrames = nbFramesLine?.trim()?.toIntOrNull()
        if (nbFrames != null) return nbFrames

        val durationProcess = ProcessBuilder(
            FfmpegLocator.ffprobePath(), "-v", "error", "-select_streams", "v:0",
            "-show_entries", "stream=duration,r_frame_rate", "-of", "csv=p=0", file.absolutePath,
        ).also { FfmpegLocator.configureEnvironment(it) }
            .redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val durationLine = durationProcess.inputStream.bufferedReader().readLine()
        durationProcess.waitFor(30, TimeUnit.SECONDS)
        val parts = durationLine?.trim()?.split(",") ?: return null
        if (parts.size != 2) return null
        val duration = parts[0].toDoubleOrNull() ?: return null
        val rateParts = parts[1].split("/")
        if (rateParts.size != 2) return null
        val num = rateParts[0].toDoubleOrNull() ?: return null
        val den = rateParts[1].toDoubleOrNull() ?: return null
        if (den == 0.0) return null
        return (duration * (num / den)).toInt().takeIf { it > 0 }
    } catch (e: Exception) {
        return null
    }
}

// Runs one ffmpeg metric pass (`-lavfi "<filterSpec>"`), reporting progress via onProgress(currentFrame,
// totalFrames) as ffmpeg's own `-progress pipe:1` output reports frames processed (verified real
// output shape: key=value lines including "frame:N", one block per update, "progress=end" on the
// final block), and honoring cancellation via isCancelled -- checked between progress lines, killing
// the process (destroyForcibly) if set. Blocks the calling thread until the process exits, is
// cancelled, or times out; callers must invoke this off the UI thread. Returns true only on a clean
// exit with the stats file actually written.
private fun runMetricPass(
    comparison: File,
    reference: File,
    filterSpec: String,
    statsFile: File,
    onProgress: (currentFrame: Int, totalFrames: Int?) -> Unit,
    isCancelled: () -> Boolean,
): Boolean {
    val totalFrames = probeFrameCount(comparison)
    val process = try {
        ProcessBuilder(
            FfmpegLocator.ffmpegPath(), "-y",
            "-i", comparison.absolutePath, "-i", reference.absolutePath,
            "-lavfi", filterSpec,
            "-progress", "pipe:1",
            "-f", "null", "-",
        ).also { FfmpegLocator.configureEnvironment(it) }
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
    } catch (e: Exception) {
        return false
    }
    return try {
        process.inputStream.bufferedReader().useLines { lines ->
            for (line in lines) {
                if (isCancelled()) {
                    process.destroyForcibly()
                    return false
                }
                if (line.startsWith("frame=")) {
                    val frame = line.substringAfter("frame=").trim().toIntOrNull()
                    if (frame != null) onProgress(frame, totalFrames)
                }
            }
        }
        val finished = process.waitFor(METRIC_RUN_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            return false
        }
        process.exitValue() == 0 && statsFile.exists()
    } catch (e: Exception) {
        process.destroyForcibly()
        false
    }
}

// Parses ffmpeg's psnr filter stats-file format (one line per frame, e.g. "n:1 mse_avg:0.88 ...
// psnr_avg:48.68 ..."), verified against real ffmpeg 8.1 output. Curates only psnr_avg (the
// combined Y/U/V average) per frame, matching this codebase's "curated fields, not every value the
// tool exposes" convention (see e.g. Av1SequenceHeader.kt). A pixel-identical frame reports
// "psnr_avg:inf" (verified: comparing a file against itself) -- Kotlin's toDoubleOrNull doesn't
// parse "inf" (only "Infinity"), and even if it did, a literal infinite value would poison any mean
// computed over a run that also contains normal frames. Capped at PSNR_INFINITE_CAP_DB instead.
private fun parsePsnrLog(statsFile: File): List<MetricFrameSample> {
    return statsFile.readLines().mapIndexedNotNull { index, line ->
        val match = PSNR_AVG_REGEX.find(line) ?: return@mapIndexedNotNull null
        val rawValue = match.groupValues[1]
        val value = if (rawValue == "inf") PSNR_INFINITE_CAP_DB else rawValue.toDoubleOrNull() ?: return@mapIndexedNotNull null
        MetricFrameSample(frameIndex = index, value = value)
    }
}

// Parses ffmpeg's ssim filter stats-file format (one line per frame, e.g. "n:1 Y:0.998812 ...
// All:0.998723 (28.939485)"), verified against real ffmpeg 8.1 output. Curates only All (the
// combined Y/U/V SSIM, always in 0.0..1.0, including for identical frames -- verified: identical
// frames report "All:1.000000", not "inf"; the "(inf)" that DOES appear for identical frames is a
// separate dB-scale figure in parentheses that this regex never captures).
private fun parseSsimLog(statsFile: File): List<MetricFrameSample> {
    return statsFile.readLines().mapIndexedNotNull { index, line ->
        val match = SSIM_ALL_REGEX.find(line) ?: return@mapIndexedNotNull null
        val value = match.groupValues[1].toDoubleOrNull() ?: return@mapIndexedNotNull null
        MetricFrameSample(frameIndex = index, value = value)
    }
}

fun runPsnrPass(
    comparison: File,
    reference: File,
    onProgress: (currentFrame: Int, totalFrames: Int?) -> Unit,
    isCancelled: () -> Boolean,
): MetricRunResult? {
    val statsFile = File.createTempFile("multiviewer_psnr_", ".log")
    return try {
        val success = runMetricPass(
            comparison, reference,
            filterSpec = "psnr=stats_file=${statsFile.absolutePath}",
            statsFile = statsFile, onProgress = onProgress, isCancelled = isCancelled,
        )
        if (!success) return null
        val perFrame = parsePsnrLog(statsFile)
        val statistics = computeStatistics(perFrame) ?: return null
        MetricRunResult(perFrame, statistics)
    } finally {
        statsFile.delete()
    }
}

fun runSsimPass(
    comparison: File,
    reference: File,
    onProgress: (currentFrame: Int, totalFrames: Int?) -> Unit,
    isCancelled: () -> Boolean,
): MetricRunResult? {
    val statsFile = File.createTempFile("multiviewer_ssim_", ".log")
    return try {
        val success = runMetricPass(
            comparison, reference,
            filterSpec = "ssim=stats_file=${statsFile.absolutePath}",
            statsFile = statsFile, onProgress = onProgress, isCancelled = isCancelled,
        )
        if (!success) return null
        val perFrame = parseSsimLog(statsFile)
        val statistics = computeStatistics(perFrame) ?: return null
        MetricRunResult(perFrame, statistics)
    } finally {
        statsFile.delete()
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:test --tests "com.multiviewer.ui.QualityMetricsTest"`
Expected: PASS (11/11 tests). These tests invoke real `ffmpeg`/`ffprobe` processes (same convention as this codebase's existing `TrackExtractorTest.kt`) — `ffmpeg`/`ffprobe` must be on `PATH` in the test environment, already true for this project's dev/CI setup.

- [ ] **Step 5: Run the full test suite**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:test`
Expected: BUILD SUCCESSFUL, no regressions

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/ui/QualityMetrics.kt \
        app/src/test/kotlin/com/multiviewer/ui/QualityMetricsTest.kt
git commit -m "Add PSNR/SSIM metric pass runners with progress and cancellation"
```

---

### Task 2: `MetricGraph.kt` — per-frame line graph

**Files:**
- Create: `app/src/main/kotlin/com/multiviewer/ui/MetricGraph.kt`

**Interfaces:**
- Consumes: `MetricFrameSample` (Task 1).
- Produces: `@Composable fun MetricGraph(perFrame: List<MetricFrameSample>, lineColor: Color, modifier: Modifier = Modifier)` — Task 4 calls this once per computed metric in the results view.

This task is independent of Tasks 1/3 apart from consuming Task 1's data class — it's a second, parallel reader of `MetricFrameSample`, matching how e.g. `Av1CBoxDecoder` and `extractAv1CRawSequenceHeader` are two independent consumers of the same underlying box in this codebase's AV1 work.

No automated tests in this task — UI-wiring/Canvas-drawing only, matching this codebase's established convention (e.g. `FrameIntervalAnalysisView.kt`, which this composable is modeled on, also has no automated tests).

- [ ] **Step 1: Create `MetricGraph.kt`**

```kotlin
package com.multiviewer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp

private const val GRAPH_Y_TICK_COUNT = 4

// Renders `perFrame` (already-computed metric samples, see QualityMetrics.kt) as a connected line
// graph, frame index on X, metric value on Y. Modeled on FrameIntervalAnalysisView.kt's Canvas
// approach (gridlines + BoxWithConstraints for axis-label positioning outside the Canvas) -- this
// codebase has no charting library and no precedent for Compose's Path API, so every graph in this
// app (including this one) is built from discrete drawLine calls. Unlike FrameIntervalAnalysisView's
// unconnected scatter points, this connects consecutive samples (a continuous quality curve reads
// better than discrete points for a per-frame quality trend).
@Composable
fun MetricGraph(perFrame: List<MetricFrameSample>, lineColor: Color, modifier: Modifier = Modifier) {
    if (perFrame.isEmpty()) return
    val minValue = perFrame.minOf { it.value }
    val maxValue = perFrame.maxOf { it.value }
    val valueSpan = (maxValue - minValue).takeIf { it > 0.0 } ?: 1.0
    val minFrame = perFrame.first().frameIndex
    val maxFrame = perFrame.last().frameIndex
    val frameSpan = (maxFrame - minFrame).takeIf { it > 0 } ?: 1

    fun yFraction(value: Double): Float = ((value - minValue) / valueSpan).toFloat()
    fun xFraction(frameIndex: Int): Float = (frameIndex - minFrame).toFloat() / frameSpan

    BoxWithConstraints(modifier = modifier) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            for (tick in 0..GRAPH_Y_TICK_COUNT) {
                val tickY = size.height - size.height * (tick.toFloat() / GRAPH_Y_TICK_COUNT)
                drawLine(
                    color = Color.White.copy(alpha = 0.12f),
                    start = Offset(0f, tickY), end = Offset(size.width, tickY), strokeWidth = 1f,
                )
            }
            for (i in 0 until perFrame.size - 1) {
                val x1 = size.width * xFraction(perFrame[i].frameIndex)
                val y1 = size.height - size.height * yFraction(perFrame[i].value)
                val x2 = size.width * xFraction(perFrame[i + 1].frameIndex)
                val y2 = size.height - size.height * yFraction(perFrame[i + 1].value)
                drawLine(color = lineColor, start = Offset(x1, y1), end = Offset(x2, y2), strokeWidth = 2f)
            }
        }
        for (tick in 0..GRAPH_Y_TICK_COUNT) {
            val value = minValue + valueSpan * (tick.toDouble() / GRAPH_Y_TICK_COUNT)
            val fractionFromTop = 1f - (tick.toFloat() / GRAPH_Y_TICK_COUNT)
            Text(
                text = "%.2f".format(value),
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 10.sp,
                modifier = Modifier.offset(y = maxHeight * fractionFromTop),
            )
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

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/ui/MetricGraph.kt
git commit -m "Add per-frame quality metric line graph"
```

---

### Task 3: `QualityExport.kt` — CSV/JSON export

**Files:**
- Create: `app/src/main/kotlin/com/multiviewer/ui/QualityExport.kt`
- Test: `app/src/test/kotlin/com/multiviewer/ui/QualityExportTest.kt`

**Interfaces:**
- Consumes: `MetricRunResult`, `MetricFrameSample`, `MetricStatistics` (Task 1).
- Produces: `fun writeResultsCsv(destination: File, results: Map<String, MetricRunResult>)`; `fun writeResultsJson(destination: File, results: Map<String, MetricRunResult>)` — Task 4 calls these from the results view's Export button. `results` is keyed by metric display name (e.g. `"PSNR"`, `"SSIM"`).

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/kotlin/com/multiviewer/ui/QualityExportTest.kt`:

```kotlin
package com.multiviewer.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class QualityExportTest {
    private val psnrResult = MetricRunResult(
        perFrame = listOf(MetricFrameSample(0, 45.0), MetricFrameSample(1, 46.5)),
        statistics = MetricStatistics(min = 45.0, max = 46.5, mean = 45.75, median = 45.75),
    )
    private val ssimResult = MetricRunResult(
        perFrame = listOf(MetricFrameSample(0, 0.98), MetricFrameSample(1, 0.99)),
        statistics = MetricStatistics(min = 0.98, max = 0.99, mean = 0.985, median = 0.985),
    )

    @Test
    fun `writeResultsCsv writes one header row and one row per frame with a column per metric`() {
        val destination = File.createTempFile("quality-export-csv-test-", ".csv")
        destination.deleteOnExit()

        writeResultsCsv(destination, linkedMapOf("PSNR" to psnrResult, "SSIM" to ssimResult))

        val lines = destination.readLines()
        assertEquals("frame_index,PSNR,SSIM", lines[0])
        assertEquals("0,45.0,0.98", lines[1])
        assertEquals("1,46.5,0.99", lines[2])
        destination.delete()
    }

    @Test
    fun `writeResultsCsv handles metrics with unequal frame counts by leaving missing cells blank`() {
        val shortResult = MetricRunResult(
            perFrame = listOf(MetricFrameSample(0, 1.0)),
            statistics = MetricStatistics(min = 1.0, max = 1.0, mean = 1.0, median = 1.0),
        )
        val destination = File.createTempFile("quality-export-csv-uneven-test-", ".csv")
        destination.deleteOnExit()

        writeResultsCsv(destination, linkedMapOf("PSNR" to psnrResult, "SHORT" to shortResult))

        val lines = destination.readLines()
        assertEquals("0,45.0,1.0", lines[1])
        assertEquals("1,46.5,", lines[2])
        destination.delete()
    }

    @Test
    fun `writeResultsJson writes statistics and per-frame data for every metric`() {
        val destination = File.createTempFile("quality-export-json-test-", ".json")
        destination.deleteOnExit()

        writeResultsJson(destination, linkedMapOf("PSNR" to psnrResult))

        val content = destination.readText()
        assertTrue(content.contains("\"PSNR\""))
        assertTrue(content.contains("\"min\": 45.0"))
        assertTrue(content.contains("\"max\": 46.5"))
        assertTrue(content.contains("\"mean\": 45.75"))
        assertTrue(content.contains("\"median\": 45.75"))
        assertTrue(content.contains("\"frameIndex\": 0, \"value\": 45.0"))
        assertTrue(content.contains("\"frameIndex\": 1, \"value\": 46.5"))
        destination.delete()
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:test --tests "com.multiviewer.ui.QualityExportTest"`
Expected: FAIL — compile error, `QualityExport.kt` doesn't exist yet.

- [ ] **Step 3: Create `QualityExport.kt`**

```kotlin
package com.multiviewer.ui

import java.io.File

// Writes one comparison's results as CSV: a per-frame table only (frame_index + one column per
// computed metric). CSV doesn't nest well, and the per-frame series is CSV's natural use case
// (spreadsheet import/plotting); aggregate statistics are shown in the app UI directly and included
// in the JSON export (writeResultsJson) instead, which handles nested structure naturally. A metric
// with fewer frames than the longest one (shouldn't normally happen -- both passes run against the
// same two files -- but handled defensively) leaves its later cells blank rather than misaligning
// rows.
fun writeResultsCsv(destination: File, results: Map<String, MetricRunResult>) {
    val metricNames = results.keys.toList()
    val frameCount = results.values.maxOfOrNull { it.perFrame.size } ?: 0
    destination.bufferedWriter().use { writer ->
        writer.write("frame_index," + metricNames.joinToString(",") + "\n")
        for (i in 0 until frameCount) {
            val row = metricNames.joinToString(",") { name ->
                results.getValue(name).perFrame.getOrNull(i)?.value?.toString() ?: ""
            }
            writer.write("$i,$row\n")
        }
    }
}

// Writes one comparison's full results (per-frame series + aggregate statistics for every computed
// metric) as JSON. Hand-written rather than pulling in a JSON library: this codebase has no existing
// JSON dependency (no kotlinx.serialization/Gson/Jackson/org.json anywhere in build.gradle.kts), and
// this output's shape is simple and fixed enough (one flat object per metric, no nested user input
// to escape -- metric names come from this app's own fixed set, and values are always finite Doubles
// after QualityMetrics.kt's inf-capping) that a library and its Gradle plugin wiring isn't justified
// for a write-only export.
fun writeResultsJson(destination: File, results: Map<String, MetricRunResult>) {
    destination.bufferedWriter().use { writer ->
        writer.write("{\n")
        val entries = results.entries.toList()
        entries.forEachIndexed { index, (name, result) ->
            writer.write("  \"$name\": {\n")
            writer.write("    \"statistics\": {")
            writer.write(
                "\"min\": ${result.statistics.min}, \"max\": ${result.statistics.max}, " +
                    "\"mean\": ${result.statistics.mean}, \"median\": ${result.statistics.median}",
            )
            writer.write("},\n")
            writer.write("    \"perFrame\": [")
            writer.write(result.perFrame.joinToString(", ") { "{\"frameIndex\": ${it.frameIndex}, \"value\": ${it.value}}" })
            writer.write("]\n")
            writer.write(if (index == entries.lastIndex) "  }\n" else "  },\n")
        }
        writer.write("}\n")
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:test --tests "com.multiviewer.ui.QualityExportTest"`
Expected: PASS (3/3 tests)

- [ ] **Step 5: Run the full test suite**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:test`
Expected: BUILD SUCCESSFUL, no regressions

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/ui/QualityExport.kt \
        app/src/test/kotlin/com/multiviewer/ui/QualityExportTest.kt
git commit -m "Add CSV/JSON export for quality comparison results"
```

---

### Task 4: `QualityCompareWindow.kt` — standalone window and `Main.kt` wiring

**Files:**
- Create: `app/src/main/kotlin/com/multiviewer/ui/QualityCompareWindow.kt`
- Modify: `app/src/main/kotlin/com/multiviewer/Main.kt` (add a boolean flag, a menu item, and the window's conditional composable)

**Interfaces:**
- Consumes: `resolutionsMatch`/`runPsnrPass`/`runSsimPass`/`MetricRunResult` (Task 1), `MetricGraph` (Task 2), `writeResultsCsv`/`writeResultsJson` (Task 3).

No new automated tests in this task — UI wiring only, matching this codebase's established convention for these features' final tasks (e.g. Phase 1/2 of the AV1 work).

- [ ] **Step 1: Create `QualityCompareWindow.kt`**

```kotlin
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
import androidx.compose.foundation.layout.weight
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
```

- [ ] **Step 2: Wire into `Main.kt`**

In `app/src/main/kotlin/com/multiviewer/Main.kt`, immediately after the existing:

```kotlin
    var frameIntervalWindowOpen by remember { mutableStateOf(false) }
```

insert:

```kotlin
    var qualityCompareWindowOpen by remember { mutableStateOf(false) }
```

In the `MenuBar` block, add a new top-level menu after the existing "프레임 간격 분석" menu (mirroring its `Menu("...") { Item("...", onClick = { ... }) }` shape):

```kotlin
                Menu("품질 비교") {
                    Item("품질 비교 열기", onClick = { qualityCompareWindowOpen = true })
                }
```

Immediately after the existing:

```kotlin
                if (frameIntervalWindowOpen) {
                    val currentTab = appState.tabs.getOrNull(appState.selectedTabIndex)
                    if (currentTab != null) {
                        FrameIntervalAnalysisWindow(appState = appState, tab = currentTab, onCloseRequest = { frameIntervalWindowOpen = false })
                    } else {
                        frameIntervalWindowOpen = false
                    }
                }
```

insert:

```kotlin

                if (qualityCompareWindowOpen) {
                    QualityCompareWindow(onCloseRequest = { qualityCompareWindowOpen = false })
                }
```

Note this new block does **not** guard on `appState.tabs`/`currentTab` the way `frameIntervalWindowOpen`'s block does — per this plan's Global Constraints, Quality Compare is independent of the tab system and must open regardless of whether any tab exists.

- [ ] **Step 3: Compile**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Run the full test suite**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:test`
Expected: BUILD SUCCESSFUL, no regressions

- [ ] **Step 5: Manual verification**

Generate two small real test files (matches this plan's own test fixtures):

```bash
ffmpeg -y -f lavfi -i "testsrc=size=320x240:rate=10:duration=2" -c:v libx264 -pix_fmt yuv420p /tmp/qc_raw.mp4
ffmpeg -y -i /tmp/qc_raw.mp4 -c:v libx264 -crf 30 -pix_fmt yuv420p /tmp/qc_encoded.mp4
```

Launch the app (`./gradlew :app:run`) **without opening any file**, and confirm:
- The "품질 비교" menu is visible and enabled in the menu bar immediately, before opening any file.
- Clicking "품질 비교 열기" opens a standalone window, independent of the (empty) tab area.
- Selecting `/tmp/qc_raw.mp4` as Reference and `/tmp/qc_encoded.mp4` as Comparison, with both PSNR and SSIM checked, and clicking "비교 시작" shows a progress bar advancing, then results: two sections (PSNR, SSIM) each with min/max/mean/median and a line graph.
- Clicking "취소" mid-run stops the comparison and shows a cancelled status instead of results.
- Selecting two files with different resolutions and running shows the "해상도가 일치하지 않습니다" error instead of a crash or a hung run.
- "CSV로 내보내기" and "JSON으로 내보내기" each prompt a save dialog and produce a readable file with the expected content.
- Opening a file as a tab (any existing supported format) and confirming the main window's tab-based inspection UI still works unaffected — Quality Compare's window is fully independent.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/ui/QualityCompareWindow.kt \
        app/src/main/kotlin/com/multiviewer/Main.kt
git commit -m "Add standalone Quality Compare window with PSNR/SSIM support"
```
