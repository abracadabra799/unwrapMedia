# Frame Interval Analysis Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a "프레임 간격 분석" menu that opens a separate window showing a scatter plot (frame interval vs. frame number) plus a data table (frame number / timestamp / interval / interval diff), reusing the existing `probeFrameTypes`/`probeVideo` ffprobe-based infrastructure.

**Architecture:** A pure data-computation function (`computeFrameIntervals`) turns the existing `List<FrameInfo>` (already produced by `AppState.analyzeFrames`/`FrameTypeAnalyzer.kt` for the GOP view) into a `List<FrameInterval>`. A pure `FrameIntervalAnalysisView` composable renders that list as a Canvas scatter plot + `LazyColumn` table with bidirectional click highlighting. A thin `FrameIntervalAnalysisWindow` wrapper owns the data-fetching (reusing `tab.gopFrames`/`AppState.analyzeFrames`, plus a new `probeVideo` call for fps) and opens an independent `Window`. `Main.kt` gets one new top-level menu that toggles that window open.

**Tech Stack:** Kotlin, Compose Desktop (Canvas/DrawScope, LazyColumn, Window), existing `ffprobe`-backed `FrameTypeAnalyzer.kt`/`FfmpegVideoPlayer.kt` probing.

## Global Constraints

- This is a file-structure analysis (frame PTS spacing from the encoded file), NOT a measurement of this app's own playback performance. No new `ffprobe`/decode call beyond what's already used for GOP analysis (`probeFrameTypes`) and video probing (`probeVideo`) -- both are reused as-is.
- No "is this a drop" threshold or flag is computed or shown -- the graph and table present raw numbers (interval, interval diff) only; the user judges visually. A reference line at the expected interval (`1000 / fps`) is drawn when `fps` is known, purely as a visual aid.
- Graph: X axis = frame number, Y axis = frame interval (ms), points only -- **no connecting lines between points** (explicit user requirement).
- Points are colored by frame type (I/P/B) using the existing `AppColors.FrameTypeI/P/B` palette (`Theme.kt`) -- no new colors introduced for that purpose.
- Click interaction is bidirectional: clicking a graph point highlights + scrolls to the matching table row, and clicking a table row highlights the matching graph point. A single selection at a time (no multi-select).
- Menu name: **"프레임 간격 분석"**. It opens an independent, resizable `Window` (not a modal `Dialog`) -- same reasoning as the existing GOP frame list, which can be long.
- Menu item is enabled only when the current tab is a video with a probed video track, using the exact same condition already used by the `비트스트림 추출` menu's video-extract item: `currentTab?.type == MediaType.VIDEO && currentTab?.mediaSummary?.sections?.any { it.title == "Video" } == true`.
- Do not modify `GopAnalysisView.kt` or its existing panel -- this is a wholly separate window.

---

## Task 1: Frame interval computation

**Files:**
- Create: `app/src/main/kotlin/com/multiviewer/ui/FrameIntervalAnalysis.kt`
- Test: `app/src/test/kotlin/com/multiviewer/ui/FrameIntervalAnalysisTest.kt`

**Interfaces:**
- Consumes: `FrameInfo(val index: Int, val type: Char, val sizeBytes: Int, val ptsSeconds: Double)` (already defined in `FrameTypeAnalyzer.kt`, same package -- no import needed).
- Produces (consumed by Task 2): `data class FrameInterval(val frameIndex: Int, val type: Char, val ptsSeconds: Double, val intervalMs: Double, val intervalDiffMs: Double)` and `fun computeFrameIntervals(frames: List<FrameInfo>): List<FrameInterval>`.

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/kotlin/com/multiviewer/ui/FrameIntervalAnalysisTest.kt`:

```kotlin
package com.multiviewer.ui

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FrameIntervalAnalysisTest {
    private fun assertClose(actual: Double, expected: Double) =
        assertTrue(abs(actual - expected) < 0.001, "expected $expected, got $actual")

    @Test
    fun `computes zero diff for perfectly regular frame spacing`() {
        val frames = listOf(
            FrameInfo(0, 'I', 100, 0.0),
            FrameInfo(1, 'P', 80, 0.1),
            FrameInfo(2, 'P', 80, 0.2),
            FrameInfo(3, 'P', 80, 0.3),
        )
        val result = computeFrameIntervals(frames)
        assertEquals(3, result.size)
        for (interval in result) {
            assertClose(interval.intervalMs, 100.0)
            assertClose(interval.intervalDiffMs, 0.0)
        }
    }

    @Test
    fun `flags an irregular gap with a nonzero interval diff`() {
        val frames = listOf(
            FrameInfo(0, 'I', 100, 0.0),
            FrameInfo(1, 'P', 80, 0.1),
            FrameInfo(2, 'P', 80, 0.2),
            FrameInfo(3, 'P', 80, 0.5),
            FrameInfo(4, 'P', 80, 0.6),
        )
        val result = computeFrameIntervals(frames)
        assertEquals(4, result.size)
        assertClose(result[0].intervalMs, 100.0)
        assertClose(result[1].intervalMs, 100.0)
        assertClose(result[2].intervalMs, 300.0)
        assertClose(result[2].intervalDiffMs, 200.0)
        assertClose(result[3].intervalMs, 100.0)
        assertClose(result[3].intervalDiffMs, -200.0)
    }

    @Test
    fun `excludes the first frame since it has no preceding interval`() {
        val frames = listOf(
            FrameInfo(0, 'I', 100, 0.0),
            FrameInfo(1, 'P', 80, 0.05),
        )
        val result = computeFrameIntervals(frames)
        assertEquals(1, result.size)
        assertEquals(1, result[0].frameIndex)
    }

    @Test
    fun `carries the frame type through for graph coloring`() {
        val frames = listOf(
            FrameInfo(0, 'I', 100, 0.0),
            FrameInfo(1, 'B', 80, 0.1),
        )
        val result = computeFrameIntervals(frames)
        assertEquals('B', result[0].type)
    }

    @Test
    fun `returns an empty list for fewer than two frames`() {
        assertEquals(emptyList(), computeFrameIntervals(emptyList()))
        assertEquals(emptyList(), computeFrameIntervals(listOf(FrameInfo(0, 'I', 100, 0.0))))
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:test --tests "com.multiviewer.ui.FrameIntervalAnalysisTest"`
Expected: compile failure -- `computeFrameIntervals`/`FrameInterval` are unresolved references.

- [ ] **Step 3: Implement `FrameIntervalAnalysis.kt`**

Create `app/src/main/kotlin/com/multiviewer/ui/FrameIntervalAnalysis.kt`:

```kotlin
package com.multiviewer.ui

// One entry per frame from the second onward -- the first frame has no preceding interval to
// report, so it's excluded rather than given a meaningless 0.0. frameIndex/type/ptsSeconds are
// carried straight from the source FrameInfo so the view can color-code and hit-test points
// without needing a second, index-aligned parallel list.
data class FrameInterval(val frameIndex: Int, val type: Char, val ptsSeconds: Double, val intervalMs: Double, val intervalDiffMs: Double)

// intervalDiffMs is this interval minus the PREVIOUS interval (0.0 for the very first computed
// interval, since there's no interval before it to diff against) -- a large-magnitude diff is
// what visually flags an irregular gap in the graph, without this function judging "is this a
// drop" itself (no threshold; the plan's design explicitly leaves that call to the viewer).
fun computeFrameIntervals(frames: List<FrameInfo>): List<FrameInterval> {
    if (frames.size < 2) return emptyList()
    val result = mutableListOf<FrameInterval>()
    var previousIntervalMs: Double? = null
    for (i in 1 until frames.size) {
        val intervalMs = (frames[i].ptsSeconds - frames[i - 1].ptsSeconds) * 1000.0
        val diffMs = previousIntervalMs?.let { intervalMs - it } ?: 0.0
        result.add(FrameInterval(frames[i].index, frames[i].type, frames[i].ptsSeconds, intervalMs, diffMs))
        previousIntervalMs = intervalMs
    }
    return result
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:test --tests "com.multiviewer.ui.FrameIntervalAnalysisTest"`
Expected: BUILD SUCCESSFUL, all 5 tests pass.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/ui/FrameIntervalAnalysis.kt app/src/test/kotlin/com/multiviewer/ui/FrameIntervalAnalysisTest.kt
git commit -m "feat: compute per-frame interval and interval-diff from frame PTS timestamps"
```

---

## Task 2: Scatter plot + data table view

**Files:**
- Create: `app/src/main/kotlin/com/multiviewer/ui/FrameIntervalAnalysisView.kt`

**Interfaces:**
- Consumes (from Task 1): `FrameInterval`.
- Produces (consumed by Task 3): `@Composable fun FrameIntervalAnalysisView(intervals: List<FrameInterval>, fps: Double?, modifier: Modifier = Modifier)`. Precondition: `intervals` is non-empty -- the caller (Task 3) handles the loading/empty/error states before rendering this.

This task has no automated test (Canvas rendering and click-drag interaction are not unit-testable in this codebase's existing convention -- see `GopAnalysisView.kt`/`AudioMinimap.kt`, neither of which have test files). Verify by compiling and by the controller's manual run in Task 3.

- [ ] **Step 1: Create `FrameIntervalAnalysisView.kt`**

Create `app/src/main/kotlin/com/multiviewer/ui/FrameIntervalAnalysisView.kt`:

```kotlin
package com.multiviewer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private const val GRAPH_POINT_RADIUS_DP = 2.5f
private const val GRAPH_HIGHLIGHT_RADIUS_DP = 5f
private const val GRAPH_HIT_RADIUS_DP = 10f

// Pure UI: given already-computed intervals and the (optional) container fps, draws the scatter
// plot + data table with bidirectional click highlighting. Fetching/caching intervals and fps,
// and handling the loading/empty/error states, is the caller's job (see
// FrameIntervalAnalysisWindow) -- this composable assumes intervals is non-empty.
@Composable
fun FrameIntervalAnalysisView(intervals: List<FrameInterval>, fps: Double?, modifier: Modifier = Modifier) {
    var selectedFrameIndex by remember(intervals) { mutableStateOf<Int?>(null) }

    // AppColors.* getters are @Composable (theme-reactive) and can only be read here, in the
    // composable body -- NOT from inside Canvas's onDraw lambda below, which runs during the draw
    // phase rather than composition. Resolving them to plain Color vals here lets the draw lambda
    // close over the values instead (same reason GopAnalysisView keeps colorForFrameType outside
    // its Canvas-less bar Boxes, and AudioWaveformPeaks/AudioMinimap take color as a parameter).
    val colorI = AppColors.FrameTypeI
    val colorP = AppColors.FrameTypeP
    val colorB = AppColors.FrameTypeB
    val colorDefault = AppColors.TextSecondary
    val selectionRowColor = AppColors.Selection
    val textPrimary = AppColors.TextPrimary
    val textSecondary = AppColors.TextSecondary

    val minFrameIndex = intervals.first().frameIndex
    val maxFrameIndex = intervals.last().frameIndex
    val minIntervalMs = intervals.minOf { it.intervalMs }
    val maxIntervalMs = intervals.maxOf { it.intervalMs }
    val expectedIntervalMs = fps?.takeIf { it > 0.0 }?.let { 1000.0 / it }
    val frameSpan = (maxFrameIndex - minFrameIndex).coerceAtLeast(1)
    // A perfectly regular video (the common, healthy case) has minIntervalMs == maxIntervalMs --
    // naively dividing by a near-zero span would pin every point to the very bottom of the graph
    // instead of the vertical center, which would look alarming/wrong for exactly the case that
    // should look the most reassuring. yFraction returns 0.5 (dead center) when there's no
    // variance to show, and the true proportional fraction otherwise.
    val hasIntervalVariance = maxIntervalMs > minIntervalMs
    fun yFraction(value: Double): Float =
        if (hasIntervalVariance) ((value - minIntervalMs) / (maxIntervalMs - minIntervalMs)).toFloat() else 0.5f

    Column(modifier = modifier) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(8.dp)
                .pointerInput(intervals) {
                    detectTapGestures { offset ->
                        val widthPx = size.width.toFloat()
                        val heightPx = size.height.toFloat()
                        val hitRadiusPx = GRAPH_HIT_RADIUS_DP.dp.toPx()
                        var nearest: FrameInterval? = null
                        var nearestDistanceSq = Float.MAX_VALUE
                        for (interval in intervals) {
                            val x = widthPx * (interval.frameIndex - minFrameIndex).toFloat() / frameSpan
                            val y = heightPx - heightPx * yFraction(interval.intervalMs)
                            val dx = offset.x - x
                            val dy = offset.y - y
                            val distanceSq = dx * dx + dy * dy
                            if (distanceSq < nearestDistanceSq) {
                                nearestDistanceSq = distanceSq
                                nearest = interval
                            }
                        }
                        if (nearest != null && nearestDistanceSq <= hitRadiusPx * hitRadiusPx) {
                            selectedFrameIndex = nearest.frameIndex
                        }
                    }
                },
        ) {
            if (expectedIntervalMs != null && expectedIntervalMs in minIntervalMs..maxIntervalMs) {
                val referenceY = size.height - size.height * yFraction(expectedIntervalMs)
                drawLine(
                    color = Color.White.copy(alpha = 0.3f),
                    start = Offset(0f, referenceY),
                    end = Offset(size.width, referenceY),
                    strokeWidth = 1f,
                )
            }

            val pointRadiusPx = GRAPH_POINT_RADIUS_DP.dp.toPx()
            val highlightRadiusPx = GRAPH_HIGHLIGHT_RADIUS_DP.dp.toPx()
            for (interval in intervals) {
                val x = size.width * (interval.frameIndex - minFrameIndex).toFloat() / frameSpan
                val y = size.height - size.height * yFraction(interval.intervalMs)
                val color = when (interval.type) {
                    'I' -> colorI
                    'P' -> colorP
                    'B' -> colorB
                    else -> colorDefault
                }
                if (interval.frameIndex == selectedFrameIndex) {
                    drawCircle(color = Color.White, radius = highlightRadiusPx, center = Offset(x, y), style = Stroke(width = 2f))
                }
                drawCircle(color = color, radius = pointRadiusPx, center = Offset(x, y))
            }
        }

        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
            Text("프레임 번호", modifier = Modifier.width(100.dp), style = AppTypography.labelLarge.copy(fontSize = 11.sp, color = textSecondary))
            Text("타임스탬프(s)", modifier = Modifier.width(120.dp), style = AppTypography.labelLarge.copy(fontSize = 11.sp, color = textSecondary))
            Text("간격(ms)", modifier = Modifier.width(100.dp), style = AppTypography.labelLarge.copy(fontSize = 11.sp, color = textSecondary))
            Text("간격 diff(ms)", modifier = Modifier.width(120.dp), style = AppTypography.labelLarge.copy(fontSize = 11.sp, color = textSecondary))
        }

        val listState = rememberLazyListState()
        LaunchedEffect(selectedFrameIndex) {
            val index = selectedFrameIndex ?: return@LaunchedEffect
            val position = intervals.indexOfFirst { it.frameIndex == index }
            if (position < 0) return@LaunchedEffect
            val isVisible = listState.layoutInfo.visibleItemsInfo.any { it.index == position }
            if (!isVisible) listState.animateScrollToItem(position)
        }

        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                itemsIndexed(intervals) { _, interval ->
                    val isSelected = interval.frameIndex == selectedFrameIndex
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(if (isSelected) selectionRowColor else Color.Transparent)
                            .clickable { selectedFrameIndex = interval.frameIndex }
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    ) {
                        Text("${interval.frameIndex}", modifier = Modifier.width(100.dp), style = AppTypography.bodyLarge.copy(fontSize = 11.sp, color = textPrimary))
                        Text("%.3f".format(interval.ptsSeconds), modifier = Modifier.width(120.dp), style = AppTypography.bodyLarge.copy(fontSize = 11.sp, color = textPrimary))
                        Text("%.1f".format(interval.intervalMs), modifier = Modifier.width(100.dp), style = AppTypography.bodyLarge.copy(fontSize = 11.sp, color = textPrimary))
                        Text("%.1f".format(interval.intervalDiffMs), modifier = Modifier.width(120.dp), style = AppTypography.bodyLarge.copy(fontSize = 11.sp, color = textPrimary))
                    }
                }
            }
            VerticalScrollbar(adapter = rememberScrollbarAdapter(listState), modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight())
        }
    }
}
```

- [ ] **Step 2: Compile**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Run the full test suite (regression check)**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:test`
Expected: BUILD SUCCESSFUL, no regressions (this task adds no new tests, so the total test count is unchanged from before this task).

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/ui/FrameIntervalAnalysisView.kt
git commit -m "feat: add frame interval scatter plot + data table view with click highlighting"
```

---

## Task 3: Window wrapper + menu wiring

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/ui/FrameIntervalAnalysisView.kt`
- Modify: `app/src/main/kotlin/com/multiviewer/Main.kt`

**Interfaces:**
- Consumes (from Task 1-2): `computeFrameIntervals`, `FrameIntervalAnalysisView`. Consumes existing `AppState.analyzeFrames(tab: TabState)`, `TabState.gopFrames: List<FrameInfo>?`, `TabState.isAnalyzingFrames: Boolean`, `TabState.file: File`, `TabState.type`, `TabState.mediaSummary`, and `probeVideo(file: File): VideoInfo?` / `VideoInfo.fps: Double` (`FfmpegVideoPlayer.kt`).
- Produces: `@Composable fun FrameIntervalAnalysisWindow(appState: AppState, tab: TabState, onCloseRequest: () -> Unit)`, and the new "프레임 간격 분석" menu in `Main.kt`.

No automated test for this task either (Window/menu wiring, same rationale as Task 2) -- verified by compiling, the full regression suite, and the controller's manual run.

- [ ] **Step 1: Add `FrameIntervalAnalysisWindow` to `FrameIntervalAnalysisView.kt`**

In `app/src/main/kotlin/com/multiviewer/ui/FrameIntervalAnalysisView.kt`, add these imports to the existing import block (insert alongside the others, keeping one import per line):

```kotlin
import androidx.compose.ui.window.Window
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
```

Then append this to the end of the file, after the closing brace of `FrameIntervalAnalysisView`:

```kotlin

// Owns data-fetching: reuses the same tab.gopFrames/AppState.analyzeFrames the GOP panel already
// populates (no duplicate ffprobe call if the user already opened GOP analysis for this tab), plus
// a fresh probeVideo call for fps (not cached anywhere else on TabState). Opens an independent,
// resizable Window rather than a modal Dialog since the data table can be long.
@Composable
fun FrameIntervalAnalysisWindow(appState: AppState, tab: TabState, onCloseRequest: () -> Unit) {
    LaunchedEffect(tab) {
        appState.analyzeFrames(tab)
    }
    var videoInfo by remember(tab) { mutableStateOf<VideoInfo?>(null) }
    LaunchedEffect(tab) {
        videoInfo = withContext(Dispatchers.IO) { probeVideo(tab.file) }
    }

    Window(onCloseRequest = onCloseRequest, title = "프레임 간격 분석 - ${tab.file.name}") {
        val frames = tab.gopFrames
        val intervals = remember(frames) { frames?.let { computeFrameIntervals(it) } ?: emptyList() }

        Box(modifier = Modifier.fillMaxSize().background(AppColors.Background)) {
            when {
                tab.isAnalyzingFrames || frames == null -> {
                    DecodingIndicator("프레임 분석 중...", modifier = Modifier.align(Alignment.Center))
                }
                intervals.isEmpty() -> {
                    Text(
                        "간격 정보 없음",
                        modifier = Modifier.align(Alignment.Center),
                        style = AppTypography.bodyLarge.copy(color = AppColors.TextSecondary),
                    )
                }
                else -> {
                    FrameIntervalAnalysisView(intervals = intervals, fps = videoInfo?.fps, modifier = Modifier.fillMaxSize())
                }
            }
        }
    }
}
```

- [ ] **Step 2: Add menu state and menu item in `Main.kt`**

In `app/src/main/kotlin/com/multiviewer/Main.kt`, find:

```kotlin
    Window(onCloseRequest = ::exitApplication, title = "unwrapMedia", state = windowState) {
        var themeMode by remember { mutableStateOf(loadThemeMode()) }
        MenuBar {
```

Replace with:

```kotlin
    Window(onCloseRequest = ::exitApplication, title = "unwrapMedia", state = windowState) {
        var themeMode by remember { mutableStateOf(loadThemeMode()) }
        var frameIntervalWindowOpen by remember { mutableStateOf(false) }
        MenuBar {
```

Then find:

```kotlin
            Menu("보기") {
                CheckboxItem(
                    "라이트 테마",
```

Replace with:

```kotlin
            Menu("프레임 간격 분석") {
                val currentTab = appState.tabs.getOrNull(appState.selectedTabIndex)
                val isVideo = currentTab?.type == MediaType.VIDEO
                val hasVideoTrack = isVideo && currentTab?.mediaSummary?.sections?.any { it.title == "Video" } == true
                Item(
                    "프레임 간격 분석 보기",
                    enabled = hasVideoTrack,
                    onClick = { frameIntervalWindowOpen = true },
                )
            }
            Menu("보기") {
                CheckboxItem(
                    "라이트 테마",
```

- [ ] **Step 3: Open the window when requested**

In `app/src/main/kotlin/com/multiviewer/Main.kt`, find:

```kotlin
            appState.openFileError?.let { message ->
                Dialog(onDismissRequest = { appState.openFileError = null }) {
                    Column(
                        modifier = Modifier
                            .width(400.dp)
                            .background(AppColors.Surface, RoundedCornerShape(8.dp))
                            .border(1.dp, AppColors.NeonRed, RoundedCornerShape(8.dp))
                            .padding(20.dp),
                    ) {
                        Text("파일을 열 수 없습니다", style = AppTypography.headlineSmall, color = AppColors.NeonRed)
                        Spacer(Modifier.height(12.dp))
                        Text(message, style = AppTypography.labelLarge.copy(fontSize = 12.sp, color = AppColors.TextPrimary))
                        Spacer(Modifier.height(20.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            Button(onClick = { appState.openFileError = null }) { Text("확인") }
                        }
                    }
                }
            }
            Surface(modifier = Modifier.fillMaxSize(), color = AppColors.Background) {
```

Replace with:

```kotlin
            appState.openFileError?.let { message ->
                Dialog(onDismissRequest = { appState.openFileError = null }) {
                    Column(
                        modifier = Modifier
                            .width(400.dp)
                            .background(AppColors.Surface, RoundedCornerShape(8.dp))
                            .border(1.dp, AppColors.NeonRed, RoundedCornerShape(8.dp))
                            .padding(20.dp),
                    ) {
                        Text("파일을 열 수 없습니다", style = AppTypography.headlineSmall, color = AppColors.NeonRed)
                        Spacer(Modifier.height(12.dp))
                        Text(message, style = AppTypography.labelLarge.copy(fontSize = 12.sp, color = AppColors.TextPrimary))
                        Spacer(Modifier.height(20.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            Button(onClick = { appState.openFileError = null }) { Text("확인") }
                        }
                    }
                }
            }
            if (frameIntervalWindowOpen) {
                val currentTab = appState.tabs.getOrNull(appState.selectedTabIndex)
                if (currentTab != null) {
                    FrameIntervalAnalysisWindow(appState = appState, tab = currentTab, onCloseRequest = { frameIntervalWindowOpen = false })
                } else {
                    frameIntervalWindowOpen = false
                }
            }
            Surface(modifier = Modifier.fillMaxSize(), color = AppColors.Background) {
```

- [ ] **Step 4: Compile**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Run the full test suite (regression check)**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew :app:test`
Expected: BUILD SUCCESSFUL, no regressions.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/ui/FrameIntervalAnalysisView.kt app/src/main/kotlin/com/multiviewer/Main.kt
git commit -m "feat: add 프레임 간격 분석 menu opening the frame interval analysis window"
```

---

## Task 4: Controller-performed manual verification

This task has no automated test and no subagent dispatch -- run it directly in the controlling session, matching this project's established precedent for real runtime verification (see the raw-PCM and zoom/pan plans' final tasks).

- [ ] **Step 1: Launch the app and open a real video file with more than a couple frames**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:run
```

- [ ] **Step 2: Verify against the plan's Global Constraints**

Confirm each of the following, and note any that fail:
- The "프레임 간격 분석" menu is disabled for non-video tabs / before a video's track info is available, and enabled once a video with a video track is open.
- Clicking the menu item opens a separate, independently resizable window (not a modal overlay blocking the main window) titled with the file name.
- The window shows a loading indicator, then a scatter plot (points only, no connecting lines) with the frame number on the X axis and interval (ms) on the Y axis, points colored by I/P/B frame type.
- A thin reference line appears at the expected interval (1000/fps) when the video's fps was resolved.
- The data table below lists 프레임 번호 / 타임스탬프 / 간격 / 간격 diff, is independently scrollable, and its row count matches the graph's point count.
- Clicking a point in the graph highlights (and scrolls to, if off-screen) the matching table row; clicking a table row highlights the matching graph point. Selecting a new one replaces the previous highlight.
- Closing the window and reopening it (or opening a different video tab) works without crashing; a tab with only 0-1 frames shows "간격 정보 없음" instead of a blank/crashing view.

- [ ] **Step 3: Update the progress ledger**

Append a summary line to `.git/sdd/progress.md` recording Task 1-3's commit range and the outcome of this manual verification (pass, or any issues found and how they were resolved).
