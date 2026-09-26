# Image Quality Comparison Enhancements Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add numeric PSNR/SSIM, a capture-condition mismatch warning, and a
cursor pixel-value readout to the Media Comparison Analyzer
(`ImageCompareWindow.kt`), reusing existing infrastructure wherever possible
(ffmpeg-backed PSNR/SSIM passes already used for video, the already-generic
metadata diff, and the already-implemented synchronized zoom/pan model).

**Architecture:** `QualityMetrics.kt` gains one new function
(`computeStillImageQualityMetrics`) wrapping the existing `runPsnrPass`/
`runSsimPass` for the two-still-images case — no new metric math.
`ImageCompareWindow.kt` gains: a pure `captureConditionMismatches` function
consumed by both `MetadataDiffView` (a warning banner) and `VisualDiffView`
(a warning badge next to the new PSNR/SSIM readout); a pure
`screenPointToNativePixel` function (hand-derived and verified against this
file's existing `fittedContentSize`/`panToPoint` transform math) consumed by
`SideBySideCompareView`'s new hover-tracking pixel-RGB readout.

**Tech Stack:** Kotlin, Compose Desktop, JUnit5 (`kotlin("test-junit5")`), ffmpeg (already bundled).

## Global Constraints

- Design spec: `docs/superpowers/specs/2026-09-26-image-quality-comparison-enhancements-design.md`.
- No new PSNR/SSIM math — reuse `QualityMetrics.kt`'s existing
  `runPsnrPass`/`runSsimPass` exactly as they already work for video.
- No color-coded quality-band thresholds (e.g. green/red PSNR ranges) — this
  app has no such convention anywhere yet; don't invent one for this
  feature alone.
- PSNR/SSIM and the capture-condition warning apply only to the still-image
  comparison case (`!isVideoCompare` in `VisualDiffView`) — video pairs
  already have their own dedicated tool (`QualityCompareWindow.kt`).
- The pixel RGB readout shows one shared native-pixel coordinate looked up
  in both bitmaps (not two independently-hovered positions) — the two
  images are assumed to represent the same scene/content, matching this
  tool's primary use case.

---

## File Structure

| File | Change |
|---|---|
| `app/src/main/kotlin/com/multiviewer/ui/QualityMetrics.kt` | Add `StillImageQualityMetrics` data class + `computeStillImageQualityMetrics` function (Task 2). |
| `app/src/main/kotlin/com/multiviewer/ui/ImageCompareWindow.kt` | Add `captureConditionMismatches` (Task 1), wire PSNR/SSIM + warning badge into `VisualDiffView`'s Diff Heatmap mode (Task 2), add `screenPointToNativePixel` + hover-tracking pixel readout to `SideBySideCompareView` (Task 3). |
| `app/src/test/kotlin/com/multiviewer/ui/ImageCompareMetadataTest.kt` | Extend (already exists, already tests `extractMetadataDiffRows`/`MetadataDiffRow` — the natural home for `captureConditionMismatches` tests, Task 1). |
| `app/src/test/kotlin/com/multiviewer/ui/ImageCompareZoomTest.kt` | New — unit tests for `screenPointToNativePixel` (Task 3; this is geometry/zoom math, unrelated to `ImageCompareMetadataTest.kt`'s metadata-diff domain, so it gets its own file rather than a misleadingly-broad `ImageCompareWindowTest.kt`). |

---

### Task 1: Capture-condition mismatch warning

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/ui/ImageCompareWindow.kt`
- Test: `app/src/test/kotlin/com/multiviewer/ui/ImageCompareMetadataTest.kt` (already exists, already tests `extractMetadataDiffRows`/`MetadataDiffRow` — add the new tests to this existing class, matching its existing style/imports, rather than creating a new file)

**Interfaces:**
- Consumes: `MetadataDiffRow(category, key, valueA, valueB, isDifferent)` (existing), `extractMetadataDiffRows(infoA, infoB): List<MetadataDiffRow>` (existing).
- Produces: `internal fun captureConditionMismatches(rows: List<MetadataDiffRow>): List<String>` — consumed by Task 2.

- [ ] **Step 1: Write the failing tests**

Add the following 3 test methods inside the existing
`class ImageCompareMetadataTest { ... }` in
`app/src/test/kotlin/com/multiviewer/ui/ImageCompareMetadataTest.kt` (after
its existing test method, before the closing brace):

```kotlin
    @Test
    fun `captureConditionMismatches returns empty when nothing capture-related differs`() {
        val rows = listOf(
            MetadataDiffRow("General", "File Name", "a.jpg", "b.jpg", isDifferent = true),
            MetadataDiffRow("Exif", "ISO", "100", "100", isDifferent = false),
        )
        assertEquals(emptyList(), captureConditionMismatches(rows))
    }

    @Test
    fun `captureConditionMismatches returns the differing capture-condition labels`() {
        val rows = listOf(
            MetadataDiffRow("Exif", "ISO", "100", "400", isDifferent = true),
            MetadataDiffRow("Exif", "Exposure Time", "1/60", "1/60", isDifferent = false),
            MetadataDiffRow("Exif", "F-Number", "1.8", "2.2", isDifferent = true),
            MetadataDiffRow("General", "File Name", "a.jpg", "b.jpg", isDifferent = true),
        )
        assertEquals(listOf("ISO", "F-Number"), captureConditionMismatches(rows))
    }

    @Test
    fun `captureConditionMismatches recognizes both aperture label spellings`() {
        val rowsFNumber = listOf(MetadataDiffRow("Exif", "F-Number", "1.8", "2.2", isDifferent = true))
        val rowsAperture = listOf(MetadataDiffRow("Camera", "Aperture", "f/1.8", "f/2.2", isDifferent = true))
        assertEquals(listOf("F-Number"), captureConditionMismatches(rowsFNumber))
        assertEquals(listOf("Aperture"), captureConditionMismatches(rowsAperture))
    }
```

(Do not add a closing `}` — these 3 methods go inside the file's existing
`ImageCompareMetadataTest` class, which already has its own closing brace
after its current single test method.)

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests "com.multiviewer.ui.ImageCompareMetadataTest"`
Expected: FAIL to compile (`captureConditionMismatches` doesn't exist yet).

- [ ] **Step 3: Implement**

In `app/src/main/kotlin/com/multiviewer/ui/ImageCompareWindow.kt`, add near
`extractMetadataDiffRows` (e.g. right after its closing brace):

```kotlin
private val CAPTURE_CONDITION_LABELS = setOf("ISO", "Exposure Time", "F-Number", "Aperture", "Focal Length", "White Balance")

// Fields whose mismatch specifically invalidates a pixel-level quality comparison between two
// images (as opposed to any other metadata difference, e.g. file name or GPS, which doesn't).
// Two label spellings exist for the same underlying value across MediaSummaryBuilder.kt's two
// summary-building code paths ("F-Number" vs "Aperture") -- both are recognized.
internal fun captureConditionMismatches(rows: List<MetadataDiffRow>): List<String> {
    return rows.filter { it.key in CAPTURE_CONDITION_LABELS && it.isDifferent }.map { it.key }
}
```

Then update `MetadataDiffView` to show a warning banner when non-empty. Find:

```kotlin
    val allRows = remember(infoA, infoB) {
        extractMetadataDiffRows(infoA, infoB)
    }

    val filteredRows = remember(allRows, onlyDiffs, searchQuery) {
```

and insert a new `remember` right after `allRows` (before `filteredRows`):

```kotlin
    val allRows = remember(infoA, infoB) {
        extractMetadataDiffRows(infoA, infoB)
    }
    val captureMismatches = remember(allRows) { captureConditionMismatches(allRows) }

    val filteredRows = remember(allRows, onlyDiffs, searchQuery) {
```

Then find the `Column(modifier = Modifier.fillMaxSize()) {` that opens
`MetadataDiffView`'s body (immediately followed by the search-bar `Row`) and
insert the banner as the first child:

```kotlin
    Column(modifier = Modifier.fillMaxSize()) {
        if (captureMismatches.isNotEmpty()) {
            Surface(
                color = Color(0xFFEF6C00).copy(alpha = 0.18f),
                shape = RoundedCornerShape(6.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFEF6C00)),
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            ) {
                Text(
                    if (language == AppLanguage.KO) {
                        "⚠️ 촬영조건이 다릅니다: ${captureMismatches.joinToString(", ")} — 화질 비교 결과가 왜곡될 수 있습니다"
                    } else {
                        "⚠️ Capture conditions differ: ${captureMismatches.joinToString(", ")} — quality comparison may be misleading"
                    },
                    modifier = Modifier.padding(8.dp),
                    color = Color(0xFFEF6C00),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
```

(The `Row(...)` line above is the existing search-bar row already in the
file — this step only adds the `if (captureMismatches...)` block before it,
the rest of the function is unchanged.)

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew test --tests "com.multiviewer.ui.ImageCompareMetadataTest"`
Expected: PASS, all 4 tests (1 existing + 3 new).

- [ ] **Step 5: Compile and run the full test suite**

Run: `./gradlew compileKotlin` then `./gradlew test`
Expected: `BUILD SUCCESSFUL`, zero failures.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/ui/ImageCompareWindow.kt app/src/test/kotlin/com/multiviewer/ui/ImageCompareMetadataTest.kt
git commit -m "feat: warn when compared images have different capture conditions

New captureConditionMismatches checks the already-computed metadata diff
rows for the specific fields (ISO/Exposure Time/F-Number/Aperture/Focal
Length/White Balance) whose mismatch specifically invalidates a
pixel-level quality comparison, as opposed to any other metadata
difference. Shown as a banner in the Metadata Diff tab; Task 2 also
surfaces it next to the new PSNR/SSIM readout.

See docs/superpowers/specs/2026-09-26-image-quality-comparison-enhancements-design.md"
```

---

### Task 2: PSNR/SSIM in Diff Heatmap mode

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/ui/QualityMetrics.kt`
- Modify: `app/src/main/kotlin/com/multiviewer/ui/ImageCompareWindow.kt`

**Interfaces:**
- Consumes: `runPsnrPass`/`runSsimPass`/`resolutionsMatch` (existing,
  `QualityMetrics.kt`), `captureConditionMismatches` + `extractMetadataDiffRows`
  (Task 1).
- Produces: `data class StillImageQualityMetrics(val psnrDb: Double, val ssim: Double)`,
  `fun computeStillImageQualityMetrics(fileA: File, fileB: File): StillImageQualityMetrics?`.

- [ ] **Step 1: Implement the metrics function**

This task has no new pure-logic unit tests of its own (it's a thin wrapper
around already-tested ffmpeg passes, plus Compose UI wiring — matches this
codebase's precedent of not re-testing ffmpeg's own filters, see
`AvSyncAnalysisWindow.kt`/`BitstreamCorruptionWindow.kt` having no dedicated
test files either). Verify via `./gradlew compileKotlin` and the manual
verification in Task 4.

Add to `app/src/main/kotlin/com/multiviewer/ui/QualityMetrics.kt`, after
`runSsimPass`:

```kotlin
data class StillImageQualityMetrics(val psnrDb: Double, val ssim: Double)

// Computes PSNR and SSIM between two still images by reusing the same ffmpeg psnr/ssim filter
// passes already used for video -- ffmpeg treats a still image file as a 1-frame video natively,
// so no new metric math is needed here. A still-image pass always produces exactly one
// MetricFrameSample; computeStatistics already handles a 1-element list correctly
// (min=max=mean=median), so .statistics.mean is simply the one PSNR/SSIM value. Returns null if
// either pass fails (matches runPsnrPass/runSsimPass's existing null-on-failure contract).
// Blocking -- callers must invoke this off the UI thread, matching this file's existing
// isVmafAvailable/runXPass convention.
fun computeStillImageQualityMetrics(fileA: File, fileB: File): StillImageQualityMetrics? {
    val autoScale = !resolutionsMatch(fileA, fileB)
    val psnrResult = runPsnrPass(fileA, fileB, onProgress = { _, _ -> }, isCancelled = { false }, autoScale = autoScale) ?: return null
    val ssimResult = runSsimPass(fileA, fileB, onProgress = { _, _ -> }, isCancelled = { false }, autoScale = autoScale) ?: return null
    return StillImageQualityMetrics(psnrDb = psnrResult.statistics.mean, ssim = ssimResult.statistics.mean)
}
```

- [ ] **Step 2: Wire it into `VisualDiffView`'s Diff Heatmap mode**

In `app/src/main/kotlin/com/multiviewer/ui/ImageCompareWindow.kt`, find the
`VisualCompareMode.DIFF_HEATMAP ->` branch inside `VisualDiffView` (currently):

```kotlin
                    VisualCompareMode.DIFF_HEATMAP -> {
                        val diffBitmap = remember(displayBitmapA, displayBitmapB) { computeDiffBitmap(displayBitmapA, displayBitmapB) }
                        if (diffBitmap != null) {
                            Box(modifier = Modifier.fillMaxSize().padding(8.dp), contentAlignment = Alignment.Center) {
                                androidx.compose.foundation.Image(bitmap = diffBitmap, contentDescription = "Diff Heatmap", modifier = Modifier.fillMaxSize())
                                Text(
                                    if (language == AppLanguage.KO) "🔍 차이점 마스크 (변화가 있는 픽셀이 밝게 표시됨)" else "🔍 Diff Mask (Changed pixels highlighted)",
                                    modifier = Modifier.align(Alignment.BottomCenter).background(Color.Black.copy(alpha = 0.7f)).padding(6.dp),
                                    color = Color.Yellow,
                                    fontSize = 11.sp,
                                )
                            }
                        }
                    }
```

Replace the entire branch with:

```kotlin
                    VisualCompareMode.DIFF_HEATMAP -> {
                        val diffBitmap = remember(displayBitmapA, displayBitmapB) { computeDiffBitmap(displayBitmapA, displayBitmapB) }
                        var metrics by remember(infoA.file, infoB.file) { mutableStateOf<StillImageQualityMetrics?>(null) }
                        var metricsLoading by remember(infoA.file, infoB.file) { mutableStateOf(false) }
                        var metricsFailed by remember(infoA.file, infoB.file) { mutableStateOf(false) }
                        val captureMismatches = remember(infoA, infoB) { captureConditionMismatches(extractMetadataDiffRows(infoA, infoB)) }

                        LaunchedEffect(infoA.file, infoB.file, isVideoCompare) {
                            if (isVideoCompare) return@LaunchedEffect
                            metricsLoading = true
                            metricsFailed = false
                            val result = withContext(Dispatchers.IO) { computeStillImageQualityMetrics(infoA.file, infoB.file) }
                            metrics = result
                            metricsFailed = result == null
                            metricsLoading = false
                        }

                        if (diffBitmap != null) {
                            Box(modifier = Modifier.fillMaxSize().padding(8.dp), contentAlignment = Alignment.Center) {
                                androidx.compose.foundation.Image(bitmap = diffBitmap, contentDescription = "Diff Heatmap", modifier = Modifier.fillMaxSize())
                                Column(
                                    modifier = Modifier.align(Alignment.BottomCenter).background(Color.Black.copy(alpha = 0.7f)).padding(8.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    Text(
                                        if (language == AppLanguage.KO) "🔍 차이점 마스크 (변화가 있는 픽셀이 밝게 표시됨)" else "🔍 Diff Mask (Changed pixels highlighted)",
                                        color = Color.Yellow,
                                        fontSize = 11.sp,
                                    )
                                    if (!isVideoCompare) {
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            val metricsText = when {
                                                metricsLoading -> if (language == AppLanguage.KO) "PSNR/SSIM 계산 중..." else "Computing PSNR/SSIM..."
                                                metricsFailed -> if (language == AppLanguage.KO) "PSNR/SSIM 계산 실패" else "PSNR/SSIM computation failed"
                                                metrics != null -> "PSNR: ${"%.2f".format(metrics!!.psnrDb)} dB | SSIM: ${"%.4f".format(metrics!!.ssim)}"
                                                else -> ""
                                            }
                                            if (metricsText.isNotEmpty()) {
                                                Text(metricsText, color = Color.White, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                                            }
                                            if (captureMismatches.isNotEmpty()) {
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text(
                                                    if (language == AppLanguage.KO) "⚠️ 촬영조건 다름" else "⚠️ Capture conditions differ",
                                                    color = Color(0xFFFFB74D),
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Medium,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
```

(`Dispatchers`/`withContext` are already imported in this file — no new
imports needed. `LaunchedEffect`/`mutableStateOf`/`remember` come from the
existing `androidx.compose.runtime.*` wildcard import.)

- [ ] **Step 3: Compile and run the full test suite**

Run: `./gradlew compileKotlin` then `./gradlew test`
Expected: `BUILD SUCCESSFUL`, zero failures.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/ui/QualityMetrics.kt app/src/main/kotlin/com/multiviewer/ui/ImageCompareWindow.kt
git commit -m "feat: numeric PSNR/SSIM for still images in Diff Heatmap mode

Reuses the existing ffmpeg-backed runPsnrPass/runSsimPass (already used
for video quality benchmarking) for two still images -- ffmpeg treats an
image file as a 1-frame video natively, so no new metric math is written.
Computed asynchronously (LaunchedEffect + Dispatchers.IO) so the UI
thread never blocks on the ffmpeg subprocess. Scoped to the still-image
case only (isVideoCompare gates it off); video already has its own
dedicated PSNR/SSIM/VMAF tool. Shows the capture-condition warning
(Task 1) inline next to the numbers, so a viewer sees the caveat in the
same glance rather than only in the separate Metadata tab.

See docs/superpowers/specs/2026-09-26-image-quality-comparison-enhancements-design.md"
```

---

### Task 3: Cursor pixel RGB readout in Side-by-Side mode

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/ui/ImageCompareWindow.kt`
- Test: `app/src/test/kotlin/com/multiviewer/ui/ImageCompareZoomTest.kt` (new file)

**Interfaces:**
- Consumes: `fittedContentSize(boxSize: Size, nativeSize: Size): Size`
  (existing, `PixelInspectorPreview.kt`, same package — no import needed).
- Produces: `internal fun screenPointToNativePixel(pointerPos: Offset, boxSize: Size, nativeSize: Size, scale: Float, offset: Offset): Pair<Int, Int>?`.

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/kotlin/com/multiviewer/ui/ImageCompareZoomTest.kt`:

```kotlin
package com.multiviewer.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import kotlin.test.Test
import kotlin.test.assertEquals

class ImageCompareZoomTest {
    @Test
    fun `screenPointToNativePixel at 1x zoom with no letterbox maps the box center to the native center`() {
        val result = screenPointToNativePixel(
            pointerPos = Offset(50f, 50f),
            boxSize = Size(100f, 100f),
            nativeSize = Size(100f, 100f),
            scale = 1f,
            offset = Offset.Zero,
        )
        assertEquals(50 to 50, result)
    }

    @Test
    fun `screenPointToNativePixel accounts for letterboxing on a wider-than-box image`() {
        // box=100x100, native=200x100 (2:1) -- fitScale=min(100/200, 100/100)=0.5, so the image
        // draws at 100x50 within the 100x100 box, letterboxed by 25px top and bottom.
        val boxSize = Size(100f, 100f)
        val nativeSize = Size(200f, 100f)

        // Center of the box is also the center of the letterboxed image -> native center (100, 50).
        val center = screenPointToNativePixel(Offset(50f, 50f), boxSize, nativeSize, scale = 1f, offset = Offset.Zero)
        assertEquals(100 to 50, center)

        // y=10 falls inside the top letterbox bar (image starts at y=25) -> out of bounds.
        val inLetterbox = screenPointToNativePixel(Offset(50f, 10f), boxSize, nativeSize, scale = 1f, offset = Offset.Zero)
        assertEquals(null, inLetterbox)
    }

    @Test
    fun `screenPointToNativePixel accounts for zoom and pan`() {
        // Same letterboxed 200x100-in-100x100 setup as above, now zoomed 2x with an arbitrary pan.
        // Hand-derived and round-tripped forward through the same transform to confirm: a native
        // point (110, 15) maps forward to screen (60, 40) at scale=2, offset=(-50,-25) via
        // screenX = offset.x + scale*(letterboxX0 + nativeX*fitScale), and this test checks the
        // inverse direction.
        val result = screenPointToNativePixel(
            pointerPos = Offset(60f, 40f),
            boxSize = Size(100f, 100f),
            nativeSize = Size(200f, 100f),
            scale = 2f,
            offset = Offset(-50f, -25f),
        )
        assertEquals(110 to 15, result)
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests "com.multiviewer.ui.ImageCompareZoomTest"`
Expected: FAIL to compile (`screenPointToNativePixel` doesn't exist yet).

- [ ] **Step 3: Implement the coordinate-mapping function**

Add to `app/src/main/kotlin/com/multiviewer/ui/ImageCompareWindow.kt`, near
`computeDiffBitmap`:

```kotlin
// Maps a pointer position (in box-local coordinates -- the same space Compose pointer events
// already report relative to the Box they're attached to) through the fitted-content + user-zoom/
// pan transform this view's graphicsLayer applies, down to a native pixel coordinate in the
// displayed bitmap -- or null if the pointer is outside the drawn image (a letterbox bar, or an
// unmeasured box). Uses the same (pointerPos - offset) / scale inversion `panToPoint`
// (PixelInspectorPreview.kt) already establishes to recover a pre-zoom, box-local content point;
// this additionally subtracts the letterbox origin and divides by fitScale to reach native pixels.
// Hand-verified: at scale=1/offset=Zero with no letterboxing this is the identity mapping; with a
// 200x100 image letterboxed into a 100x100 box, the box center (50,50) correctly resolves to the
// native center (100,50), and a point in the letterbox margin resolves to null; at scale=2 with an
// arbitrary pan (offset=(-50,-25)), pointer (60,40) resolves to native (110,15), and forward-
// transforming (110,15) through the same formula (screenX = offset.x + scale*(letterboxX0 +
// nativeX*fitScale)) returns exactly (60,40), confirming the inversion round-trips correctly.
internal fun screenPointToNativePixel(pointerPos: Offset, boxSize: Size, nativeSize: Size, scale: Float, offset: Offset): Pair<Int, Int>? {
    if (nativeSize.width <= 0f || nativeSize.height <= 0f || boxSize.width <= 0f || boxSize.height <= 0f) return null
    val contentSize = fittedContentSize(boxSize, nativeSize)
    val fitScale = contentSize.width / nativeSize.width
    if (fitScale <= 0f) return null
    val letterboxX0 = (boxSize.width - contentSize.width) / 2f
    val letterboxY0 = (boxSize.height - contentSize.height) / 2f
    val lx = (pointerPos.x - offset.x) / scale
    val ly = (pointerPos.y - offset.y) / scale
    val nativeX = ((lx - letterboxX0) / fitScale).toInt()
    val nativeY = ((ly - letterboxY0) / fitScale).toInt()
    if (nativeX < 0 || nativeX >= nativeSize.width.toInt() || nativeY < 0 || nativeY >= nativeSize.height.toInt()) return null
    return nativeX to nativeY
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew test --tests "com.multiviewer.ui.ImageCompareZoomTest"`
Expected: PASS, all 3 tests.

- [ ] **Step 5: Wire hover-tracking and the readout overlay into `SideBySideCompareView`**

In `app/src/main/kotlin/com/multiviewer/ui/ImageCompareWindow.kt`, inside
`SideBySideCompareView`, add a new shared state variable alongside the
existing `scale`/`offset`/`paneSize`:

```kotlin
    var scale by remember(bitmapA, bitmapB) { mutableStateOf(1f) }
    var offset by remember(bitmapA, bitmapB) { mutableStateOf(Offset.Zero) }
    var paneSize by remember { mutableStateOf(Size.Zero) }
    var hoverNativePixel by remember(bitmapA, bitmapB) { mutableStateOf<Pair<Int, Int>?>(null) }
```

(only the last line is new; the three above it already exist — shown for
placement context.)

In Pane A's modifier chain, add a pointer-move handler right after the
existing `.onPointerEvent(PointerEventType.Scroll, ...)` block (before
`.pointerInput(bitmapA, bitmapB) { detectDragGestures ... }`):

```kotlin
                    .onPointerEvent(PointerEventType.Move, pass = PointerEventPass.Initial) { event ->
                        val pos = event.changes.firstOrNull()?.position
                        hoverNativePixel = pos?.let {
                            screenPointToNativePixel(it, paneSize, Size(bitmapA.width.toFloat(), bitmapA.height.toFloat()), scale, offset)
                        }
                    }
                    .onPointerEvent(PointerEventType.Exit, pass = PointerEventPass.Initial) {
                        hoverNativePixel = null
                    }
```

Add the identical pair of handlers to Pane B's modifier chain (same
insertion point relative to its own existing `Scroll` handler), except
using `bitmapB` instead of `bitmapA` in the `Size(...)` call.

Finally, add the readout overlay. Find the existing zoom-level indicator
(`if (scale > 1.01f) { Surface(... .align(Alignment.BottomEnd) ...`) near
the end of the outermost `Box` in `SideBySideCompareView`, and add this new
block as a sibling, right before it:

```kotlin
        hoverNativePixel?.let { (nx, ny) ->
            val skiaA = bitmapA.asSkiaBitmap()
            val skiaB = bitmapB.asSkiaBitmap()
            val colorA = if (nx < skiaA.width && ny < skiaA.height) skiaA.getColor(nx, ny) else null
            val colorB = if (nx < skiaB.width && ny < skiaB.height) skiaB.getColor(nx, ny) else null
            Surface(
                color = Color.Black.copy(alpha = 0.75f),
                shape = RoundedCornerShape(4.dp),
                border = androidx.compose.foundation.BorderStroke(0.5.dp, Color.White.copy(alpha = 0.3f)),
                modifier = Modifier.align(Alignment.BottomStart).padding(8.dp),
            ) {
                Text(
                    buildString {
                        append("(%d, %d)  ".format(nx, ny))
                        colorA?.let { append("A: #%06X  ".format(it and 0xFFFFFF)) }
                        colorB?.let { append("B: #%06X".format(it and 0xFFFFFF)) }
                    },
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    color = Color.White,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
```

(`bitmapA.asSkiaBitmap()`/`getColor` are already imported and used by
`computeDiffBitmap` elsewhere in this same file.)

- [ ] **Step 6: Compile and run the full test suite**

Run: `./gradlew compileKotlin` then `./gradlew test`
Expected: `BUILD SUCCESSFUL`, zero failures.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/ui/ImageCompareWindow.kt app/src/test/kotlin/com/multiviewer/ui/ImageCompareZoomTest.kt
git commit -m "feat: cursor pixel RGB readout in Side-by-Side compare mode

New screenPointToNativePixel inverts this view's existing fitted-content
+ zoom/pan transform (the same one panToPoint already uses) to map a
pointer position down to a native bitmap pixel coordinate, hand-verified
against a letterboxed and a zoomed+panned case. One shared hover state
(not per-pane) so hovering either image shows both images' color at the
same underlying content position, matching this tool's assumption that
the two compared images represent the same scene.

See docs/superpowers/specs/2026-09-26-image-quality-comparison-enhancements-design.md"
```

---

### Task 4: Manual verification

- [ ] **Step 1**: Run `./gradlew compileKotlin` — expect `BUILD SUCCESSFUL`.
- [ ] **Step 2**: Run the full suite once more (`./gradlew test`) as a final check.
- [ ] **Step 3 (manual)**: Open two real images in the Compare window (e.g.
  two JPEGs of the same scene at different quality/compression settings, or
  even the same file twice as a smoke test). Switch to Diff Heatmap mode
  and confirm PSNR/SSIM numbers appear after a brief "계산 중..." state,
  with sensible values (comparing a file against itself should show a very
  high PSNR and SSIM near 1.0). Specifically verify this works for at least
  one HEIC pair, since ffmpeg's HEIC decode path has had rough edges
  elsewhere in this app's history — if HEIC PSNR/SSIM fails, note it in the
  report rather than silently accepting a "계산 실패" result as expected
  behavior.
- [ ] **Step 4 (manual)**: Construct or find two images with genuinely
  different EXIF ISO/exposure/aperture values (e.g. two different shots, or
  hand-edit one image's EXIF with a tool like `exiftool` if available) and
  confirm the capture-condition warning banner appears in the Metadata Diff
  tab and the warning badge appears next to the PSNR/SSIM numbers; confirm
  it's absent for two images with matching capture settings.
- [ ] **Step 5 (manual)**: In Side-by-Side mode, hover over each pane at
  various zoom levels (1x, and zoomed in via scroll) and confirm the RGB
  readout tracks the cursor and shows sensible values for both A and B;
  confirm it disappears when the cursor leaves both panes, and (if the two
  test images have different aspect ratios) confirm hovering in a
  letterboxed margin correctly shows no readout rather than a wrong one.
