# GOP Zoom/Pan and Video Layout Ratio Tuning Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Narrow the video tab's Detailed Properties panel and player width, widen the GOP panel correspondingly, and add mouse-wheel zoom to the GOP frame-bar view.

**Architecture:** Task 1 makes the right panel's default width configurable per-caller (video passes a narrower value than image) and adjusts the player/GOP width ratio -- both small, low-risk constant/parameter changes. Task 2 adds zoom state and a scroll-event interceptor to `GopAnalysisView`'s frame-bar `LazyRow` -- a self-contained, slightly higher-risk gesture change, kept in its own task/review cycle.

**Tech Stack:** Kotlin, Compose Multiplatform Desktop.

## Global Constraints

- Click-to-select-a-frame and drag-to-pan the GOP frame list already both work today via Compose's built-in `LazyRow` + `clickable` gesture arbitration (touch-slop based: a press that doesn't move fires the click, a press that moves becomes a scroll) -- **no code changes are needed for panning**, only for zoom (per the spec's Background section). Do not add any custom drag-handling code.
- The right panel's 220-1000dp drag range (`RIGHT_PANEL_MIN_WIDTH_DP`/`RIGHT_PANEL_MAX_WIDTH_DP`) and `videoGopSplit`'s 0.1-0.9 drag-clamp range (enforced inside `DraggableDivider`) are both unchanged by this plan.
- `ImageInspectorUI.kt`'s call to `DashboardLayout` must NOT change -- it keeps the existing 350dp right-panel default by omitting the new parameter (which defaults to the current constant).
- GOP bar **height** (based on frame byte size, already a fraction of container height) is unaffected by the zoom feature -- only bar width (and therefore frame density) changes with zoom.

---

### Task 1: Video-only right panel width + player/GOP ratio tuning

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/ui/DashboardLayout.kt`
- Modify: `app/src/main/kotlin/com/multiviewer/ui/VideoInspectorUI.kt`

**Interfaces:**
- Produces: `DashboardLayout`'s new optional parameter `rightPanelDefaultWidthDp: Float = RIGHT_PANEL_DEFAULT_WIDTH_DP`, inserted after the existing `bottomPanel` parameter. Any other caller of `DashboardLayout` that doesn't pass this parameter (i.e. `ImageInspectorUI.kt`) is unaffected.

- [ ] **Step 1: Confirm current DashboardLayout signature and state line**

Run: `grep -n "fun DashboardLayout" -A 10 app/src/main/kotlin/com/multiviewer/ui/DashboardLayout.kt`

Expected output includes:
```kotlin
fun DashboardLayout(
    leftPanel: @Composable ColumnScope.() -> Unit,
    centerPanel: @Composable ColumnScope.() -> Unit,
    rightPanel: @Composable ColumnScope.() -> Unit,
    bottomPanel: @Composable ColumnScope.() -> Unit
) {
    var containerHeightPx by remember { mutableStateOf(0) }
    // 0.75f roughly matches the old fixed 250dp bottom panel on a typical window size, but is now
    // a user-draggable ratio instead of a fixed pixel height.
    var verticalSplit by remember { mutableStateOf(0.75f) }
    // Left (Structure) and right (Detailed Properties) panels start at their old fixed widths but
    // the user can drag either wider -- e.g. pretty-printed XMP in the right panel needs much more
    // horizontal room than 350dp to avoid wrapping mid-line.
    var leftPanelWidthDp by remember { mutableStateOf(300f) }
    var rightPanelWidthDp by remember { mutableStateOf(RIGHT_PANEL_DEFAULT_WIDTH_DP) }
```

If this differs, stop and re-read the whole file before editing -- do not guess at the edit.

- [ ] **Step 2: Edit DashboardLayout.kt's function signature and state line**

Find:

```kotlin
fun DashboardLayout(
    leftPanel: @Composable ColumnScope.() -> Unit,
    centerPanel: @Composable ColumnScope.() -> Unit,
    rightPanel: @Composable ColumnScope.() -> Unit,
    bottomPanel: @Composable ColumnScope.() -> Unit
) {
```

Replace with:

```kotlin
fun DashboardLayout(
    leftPanel: @Composable ColumnScope.() -> Unit,
    centerPanel: @Composable ColumnScope.() -> Unit,
    rightPanel: @Composable ColumnScope.() -> Unit,
    bottomPanel: @Composable ColumnScope.() -> Unit,
    rightPanelDefaultWidthDp: Float = RIGHT_PANEL_DEFAULT_WIDTH_DP,
) {
```

Then find:

```kotlin
    var rightPanelWidthDp by remember { mutableStateOf(RIGHT_PANEL_DEFAULT_WIDTH_DP) }
```

Replace with:

```kotlin
    var rightPanelWidthDp by remember { mutableStateOf(rightPanelDefaultWidthDp) }
```

- [ ] **Step 3: Confirm current VideoInspectorUI.kt state and DashboardLayout call**

Run: `grep -n "videoGopSplit\|DashboardLayout(" app/src/main/kotlin/com/multiviewer/ui/VideoInspectorUI.kt`

Expected output includes:
```
app/src/main/kotlin/com/multiviewer/ui/VideoInspectorUI.kt:36:    var videoGopSplit by remember { mutableStateOf(0.65f) }
app/src/main/kotlin/com/multiviewer/ui/VideoInspectorUI.kt:38:    DashboardLayout(
```

If this differs, stop and re-read the whole file before editing.

- [ ] **Step 4: Edit VideoInspectorUI.kt's videoGopSplit default and DashboardLayout call**

Find:

```kotlin
    var videoGopSplit by remember { mutableStateOf(0.65f) }
```

Replace with:

```kotlin
    var videoGopSplit by remember { mutableStateOf(0.455f) }
```

Then find:

```kotlin
    DashboardLayout(
        leftPanel = leftPanel,
        centerPanel = {
```

Replace with:

```kotlin
    DashboardLayout(
        leftPanel = leftPanel,
        rightPanelDefaultWidthDp = 298f,
        centerPanel = {
```

- [ ] **Step 5: Verify both files**

Run:
```bash
grep -n "rightPanelDefaultWidthDp\|rightPanelWidthDp by remember" app/src/main/kotlin/com/multiviewer/ui/DashboardLayout.kt
grep -n "videoGopSplit by remember\|rightPanelDefaultWidthDp" app/src/main/kotlin/com/multiviewer/ui/VideoInspectorUI.kt
grep -n "DashboardLayout(" -A 3 app/src/main/kotlin/com/multiviewer/ui/ImageInspectorUI.kt
```

Expected: `DashboardLayout.kt` shows the new parameter in the signature and `mutableStateOf(rightPanelDefaultWidthDp)`; `VideoInspectorUI.kt` shows `mutableStateOf(0.455f)` and `rightPanelDefaultWidthDp = 298f` in its `DashboardLayout(...)` call; `ImageInspectorUI.kt`'s `DashboardLayout(...)` call is unchanged (no `rightPanelDefaultWidthDp` argument, so it uses the default 350dp).

- [ ] **Step 6: Compile**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew compileKotlin`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 7: Run the full test suite (regression check)**

Run:
```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew test
find app/build/test-results -name "*.xml" | xargs grep -o 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' | awk -F'"' '{tests+=$2; fail+=$6; err+=$8} END {print "total tests:", tests, "failures:", fail, "errors:", err}'
```
Expected: `BUILD SUCCESSFUL`, `failures: 0 errors: 0`, same total test count as before this task.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/ui/DashboardLayout.kt app/src/main/kotlin/com/multiviewer/ui/VideoInspectorUI.kt
git commit -m "Narrow video tab's Detailed Properties panel and player width, widen GOP"
```

---

### Task 2: Mouse-wheel zoom for the GOP frame-bar view

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/ui/GopAnalysisView.kt`

**Interfaces:**
- Consumes: nothing from Task 1 -- independent of it.
- Produces: nothing new for other files -- `frameBarWidthDp` is local composable state, not exposed outside `GopAnalysisView`.

- [ ] **Step 1: Confirm current imports, constants, and the LazyRow block**

Run: `cat -n app/src/main/kotlin/com/multiviewer/ui/GopAnalysisView.kt`

Confirm lines 1-42 (imports and the two `FRAME_BAR_*` constants) and lines 177-203 (the `LazyRow` and its `itemsIndexed` block) match what's shown in Steps 2-3 below. If they differ, stop and re-read the whole file before editing.

- [ ] **Step 2: Add imports and zoom constants/state**

Find this exact import block (the last four import lines before the `private const val FRAME_BAR_WIDTH_DP` declaration):

```kotlin
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private const val FRAME_BAR_WIDTH_DP = 16
private const val FRAME_BAR_SPACING_DP = 2
```

Replace with:

```kotlin
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Starting/default frame-bar width -- also the initial value of the per-composable frameBarWidthDp
// zoom state below. FRAME_BAR_MIN/MAX bound how far mouse-wheel zoom can shrink/grow it; STEP is
// the change applied per wheel-scroll unit.
private const val FRAME_BAR_WIDTH_DP = 16
private const val FRAME_BAR_MIN_WIDTH_DP = 4f
private const val FRAME_BAR_MAX_WIDTH_DP = 48f
private const val FRAME_BAR_ZOOM_STEP_DP = 2f
private const val FRAME_BAR_SPACING_DP = 2
```

- [ ] **Step 3: Add zoom state and wire it into the LazyRow**

Find this exact text (from the `val maxSize = ...` line through the end of the `LazyRow` block):

```kotlin
                    val maxSize = frames.maxOf { it.sizeBytes }.coerceAtLeast(1)
                    LaunchedEffect(currentFrameIndex) {
                        if (currentFrameIndex < 0) return@LaunchedEffect
                        val isVisible = listState.layoutInfo.visibleItemsInfo.any { it.index == currentFrameIndex }
                        if (!isVisible) {
                            listState.animateScrollToItem(currentFrameIndex)
                        }
                    }
                    // Keeps a keyboard/click-selected frame in view too -- without this, stepping
                    // past the visible window with the arrow keys would move the selection out of
                    // sight with no way to tell where it landed.
                    LaunchedEffect(tab.selectedFrame) {
                        val index = tab.selectedFrame?.index ?: return@LaunchedEffect
                        val isVisible = listState.layoutInfo.visibleItemsInfo.any { it.index == index }
                        if (!isVisible) {
                            listState.animateScrollToItem(index)
                        }
                    }
                    LazyRow(
                        state = listState,
                        modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(FRAME_BAR_SPACING_DP.dp),
                    ) {
                        itemsIndexed(frames) { index, frame ->
                            // A fraction of the row's own height, not a computed dp value against a
                            // fixed constant -- GopAnalysisView's container height is now
                            // drag-resizable (see VideoInspectorUI), so bar heights need to scale
                            // with whatever height it actually ends up with.
                            val heightFraction = (frame.sizeBytes.toFloat() / maxSize).coerceAtLeast(0.02f)
                            val isCurrent = index == currentFrameIndex
                            Column(
                                modifier = Modifier.width(FRAME_BAR_WIDTH_DP.dp).fillMaxSize(),
                                verticalArrangement = Arrangement.Bottom,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .width(FRAME_BAR_WIDTH_DP.dp)
                                        .fillMaxHeight(heightFraction)
                                        .background(colorForFrameType(frame.type))
                                        .let { if (isCurrent) it.border(2.dp, Color.White) else it }
                                        .clickable { selectFrame(frame) },
                                )
                            }
                        }
                    }
```

Replace with:

```kotlin
                    val maxSize = frames.maxOf { it.sizeBytes }.coerceAtLeast(1)
                    var frameBarWidthDp by remember { mutableStateOf(FRAME_BAR_WIDTH_DP.toFloat()) }
                    LaunchedEffect(currentFrameIndex) {
                        if (currentFrameIndex < 0) return@LaunchedEffect
                        val isVisible = listState.layoutInfo.visibleItemsInfo.any { it.index == currentFrameIndex }
                        if (!isVisible) {
                            listState.animateScrollToItem(currentFrameIndex)
                        }
                    }
                    // Keeps a keyboard/click-selected frame in view too -- without this, stepping
                    // past the visible window with the arrow keys would move the selection out of
                    // sight with no way to tell where it landed.
                    LaunchedEffect(tab.selectedFrame) {
                        val index = tab.selectedFrame?.index ?: return@LaunchedEffect
                        val isVisible = listState.layoutInfo.visibleItemsInfo.any { it.index == index }
                        if (!isVisible) {
                            listState.animateScrollToItem(index)
                        }
                    }
                    LazyRow(
                        state = listState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(horizontal = 8.dp)
                            // Intercepts the wheel scroll on the way down (Initial pass), before
                            // LazyRow's own internal scrollable modifier can consume it to pan the
                            // list -- plain wheel now zooms only; panning is still available via
                            // click-and-drag (already works, see Global Constraints) or the
                            // scrollbar below. Scrolling up (away from the user) reports a negative
                            // scrollDelta.y in Compose, so subtracting it increases the width --
                            // i.e. scroll up zooms in.
                            .onPointerEvent(PointerEventType.Scroll, pass = PointerEventPass.Initial) { event ->
                                val scrollDeltaY = event.changes.firstOrNull()?.scrollDelta?.y ?: return@onPointerEvent
                                frameBarWidthDp = (frameBarWidthDp - scrollDeltaY * FRAME_BAR_ZOOM_STEP_DP)
                                    .coerceIn(FRAME_BAR_MIN_WIDTH_DP, FRAME_BAR_MAX_WIDTH_DP)
                                event.changes.forEach { it.consume() }
                            },
                        horizontalArrangement = Arrangement.spacedBy(FRAME_BAR_SPACING_DP.dp),
                    ) {
                        itemsIndexed(frames) { index, frame ->
                            // A fraction of the row's own height, not a computed dp value against a
                            // fixed constant -- GopAnalysisView's container height is now
                            // drag-resizable (see VideoInspectorUI), so bar heights need to scale
                            // with whatever height it actually ends up with. Width comes from the
                            // zoom state above, not the FRAME_BAR_WIDTH_DP constant (which is now
                            // only the starting/default value).
                            val heightFraction = (frame.sizeBytes.toFloat() / maxSize).coerceAtLeast(0.02f)
                            val isCurrent = index == currentFrameIndex
                            Column(
                                modifier = Modifier.width(frameBarWidthDp.dp).fillMaxSize(),
                                verticalArrangement = Arrangement.Bottom,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .width(frameBarWidthDp.dp)
                                        .fillMaxHeight(heightFraction)
                                        .background(colorForFrameType(frame.type))
                                        .let { if (isCurrent) it.border(2.dp, Color.White) else it }
                                        .clickable { selectFrame(frame) },
                                )
                            }
                        }
                    }
```

- [ ] **Step 4: Verify the edits landed correctly**

Run: `grep -n "frameBarWidthDp\|FRAME_BAR_WIDTH_DP\|onPointerEvent\|PointerEventType.Scroll" app/src/main/kotlin/com/multiviewer/ui/GopAnalysisView.kt`

Expected: `FRAME_BAR_WIDTH_DP` appears exactly twice (the `private const val` declaration and the `frameBarWidthDp` initializer `mutableStateOf(FRAME_BAR_WIDTH_DP.toFloat())`) -- it must NOT appear in either `.width(...)` call inside the `itemsIndexed` block, those must read `.width(frameBarWidthDp.dp)`. `onPointerEvent`/`PointerEventType.Scroll` appear once, on the `LazyRow`'s modifier chain.

- [ ] **Step 5: Compile**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew compileKotlin`
Expected: `BUILD SUCCESSFUL`. `onPointerEvent`/`PointerEventType`/`PointerEventPass` are new imports added in Step 2 -- if compilation fails on an unresolved reference for any of them, double check the import lines were added exactly as shown (these are real, existing Compose APIs under `androidx.compose.ui.input.pointer`, not new project code).

- [ ] **Step 6: Run the full test suite (regression check)**

Run:
```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew test
find app/build/test-results -name "*.xml" | xargs grep -o 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' | awk -F'"' '{tests+=$2; fail+=$6; err+=$8} END {print "total tests:", tests, "failures:", fail, "errors:", err}'
```
Expected: `BUILD SUCCESSFUL`, `failures: 0 errors: 0`, same total test count as before this task (no test touches `GopAnalysisView`'s composable internals).

- [ ] **Step 7: Manual verification**

Build and run the app (`export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew run`), open a video, click "프레임 분석 시작" to load GOP data, then confirm:
- Detailed Properties panel and the player/GOP split both start at their new Task-1 sizes.
- Mouse wheel over the GOP frame area changes the frame bar width (zoom) instead of scrolling the list.
- Scrolling up makes bars bigger/fewer visible (zoom in); if it's the other way around in practice, negate `scrollDeltaY` in the `onPointerEvent` handler (i.e. change `frameBarWidthDp - scrollDeltaY * ...` to `frameBarWidthDp + scrollDeltaY * ...`) and re-verify.
- Frame width stays within the 4-48dp range even after scrolling well past either extreme (doesn't go negative or unbounded).
- Clicking a frame bar at any zoom level still selects it and seeks the player.
- Left/right arrow keys still step between frames and keep the selection scrolled into view.
- Click-and-drag directly on the frame bar area still pans the list left/right without needing the scrollbar; the horizontal scrollbar itself still works too.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/ui/GopAnalysisView.kt
git commit -m "Add mouse-wheel zoom to the GOP frame-bar view"
```

---
