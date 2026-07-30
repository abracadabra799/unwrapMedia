# Video Player / GOP Analysis Side-by-Side Layout Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move the GOP (frame analysis) panel from below the video player to its right, so the player uses the top region's full height instead of sharing it vertically with GOP.

**Architecture:** Convert the inner `Column` (player + GOP) in `VideoInspectorUI.kt` to a `Row`; flip the player Box and GopAnalysisView's fill modifiers from width-constrained/height-full to height-constrained/width-full; switch the divider between them from a horizontal-line (`Orientation.Horizontal`, drag up/down) to a vertical-line (`Orientation.Vertical`, drag left/right) divider; track the row's width instead of height for the divider's drag-to-fraction math.

**Tech Stack:** Kotlin, Compose Multiplatform Desktop.

## Global Constraints

- `videoGopSplit`'s value (`0.65f`) and its drag-clamped range (0.1-0.9, enforced inside `DraggableDivider` itself, not touched by this plan) are unchanged -- only the axis it splits along changes, from height to width.
- `verticalSplit` (top region vs. bottom summary dashboard, `0.7f`) and everything below the top region (the summary `LazyColumn`, `VerticalScrollbar`) are unaffected.
- `rightPanel` (`DetailedPropertiesPanel`) and `leftPanel` are unaffected -- this plan only touches `centerPanel`'s top region inside `VideoInspectorUI.kt`.
- `GopAnalysisView`'s own internals (`GopAnalysisView.kt`) are NOT modified -- it already renders correctly into whatever `modifier` it's handed (per the spec's Background section), so only its call site's modifier changes.

---

### Task 1: Convert the player/GOP split from vertical stack to side-by-side

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/ui/VideoInspectorUI.kt`

**Interfaces:**
- Consumes: `GopAnalysisView(tab: TabState, onAnalyze: () -> Unit, modifier: Modifier = Modifier)` (existing, unchanged signature), `DraggableDivider(orientation: Orientation, containerSizePx: Int, getSplit: () -> Float, setSplit: (Float) -> Unit)` (existing, unchanged signature), `FfmpegVideoPlayer(...)` (existing, unchanged).
- Produces: nothing new -- this is a layout-only change inside `VideoInspectorUI`, no new public function or parameter.

- [ ] **Step 1: Read the current file to confirm it matches this plan's assumptions**

Run: `cat -n app/src/main/kotlin/com/multiviewer/ui/VideoInspectorUI.kt`

Confirm lines 28-96 read exactly as shown in the "before" block in Step 2 below (allowing for the file having `verticalSplit` at `0.7f`, from the just-shipped preview-panel-size change, not `0.5f`). If the surrounding code differs meaningfully from this, stop and re-read the whole file before editing -- do not guess at the edit.

- [ ] **Step 2: Replace the state declarations and the top-region layout**

Find this exact text (the state declarations, from `var containerHeightPx` through `var videoGopSplit`):

```kotlin
    var containerHeightPx by remember { mutableStateOf(0) }
    var topContainerHeightPx by remember { mutableStateOf(0) }
    // Three independently resizable rows (player / GOP graph / summary) stacked via two nested
    // splits rather than one three-way ratio -- verticalSplit divides the whole column into "top"
    // (player + GOP) vs summary, and videoGopSplit divides that top region into player vs GOP.
    // GopAnalysisView previously had a hardcoded height and sat outside verticalSplit's control
    // entirely, so there was no way to shrink it to make room for the player.
    var verticalSplit by remember { mutableStateOf(0.7f) }
    var videoGopSplit by remember { mutableStateOf(0.65f) }
```

Replace with:

```kotlin
    var containerHeightPx by remember { mutableStateOf(0) }
    var topContainerWidthPx by remember { mutableStateOf(0) }
    // verticalSplit divides the whole column into "top" (player + GOP, side-by-side) vs summary.
    // Player and GOP sit side-by-side (not stacked) so the player keeps the top region's full
    // height instead of sharing it vertically with GOP -- videoGopSplit now divides that top
    // region horizontally, into player width vs GOP width.
    var verticalSplit by remember { mutableStateOf(0.7f) }
    var videoGopSplit by remember { mutableStateOf(0.65f) }
```

Then find this exact text (the top-region `Column` containing the player Box, divider, and `GopAnalysisView`):

```kotlin
                Column(
                    modifier = Modifier
                        .weight(verticalSplit)
                        .fillMaxWidth()
                        .onGloballyPositioned { topContainerHeightPx = it.size.height }
                ) {
                    // Top: Full-width Live Player
                    Box(
                        modifier = Modifier
                            .weight(videoGopSplit)
                            .fillMaxWidth()
                            .background(Color.Black),
                        contentAlignment = Alignment.Center
                    ) {
                        FfmpegVideoPlayer(
                            tab.file,
                            onElapsedChanged = { tab.playbackElapsedSeconds = it },
                            seekRequestSeconds = tab.seekTargetSeconds,
                            seekRequestTick = tab.seekRequestTick,
                        )

                        Text("LIVE PLAYER",
                            modifier = Modifier.align(Alignment.TopStart).padding(4.dp),
                            style = AppTypography.labelLarge.copy(fontSize = 10.sp, color = AppColors.NeonGreen)
                        )
                    }

                    DraggableDivider(
                        orientation = Orientation.Horizontal,
                        containerSizePx = topContainerHeightPx,
                        getSplit = { videoGopSplit },
                        setSplit = { videoGopSplit = it }
                    )

                    GopAnalysisView(
                        tab,
                        onAnalyze = { appState.analyzeFrames(tab) },
                        modifier = Modifier.weight(1f - videoGopSplit),
                    )
                }
```

Replace with:

```kotlin
                Row(
                    modifier = Modifier
                        .weight(verticalSplit)
                        .fillMaxWidth()
                        .onGloballyPositioned { topContainerWidthPx = it.size.width }
                ) {
                    // Left: Live Player (full height of the top region)
                    Box(
                        modifier = Modifier
                            .weight(videoGopSplit)
                            .fillMaxHeight()
                            .background(Color.Black),
                        contentAlignment = Alignment.Center
                    ) {
                        FfmpegVideoPlayer(
                            tab.file,
                            onElapsedChanged = { tab.playbackElapsedSeconds = it },
                            seekRequestSeconds = tab.seekTargetSeconds,
                            seekRequestTick = tab.seekRequestTick,
                        )

                        Text("LIVE PLAYER",
                            modifier = Modifier.align(Alignment.TopStart).padding(4.dp),
                            style = AppTypography.labelLarge.copy(fontSize = 10.sp, color = AppColors.NeonGreen)
                        )
                    }

                    DraggableDivider(
                        orientation = Orientation.Vertical,
                        containerSizePx = topContainerWidthPx,
                        getSplit = { videoGopSplit },
                        setSplit = { videoGopSplit = it }
                    )

                    // Right: GOP Analysis (full height of the top region)
                    GopAnalysisView(
                        tab,
                        onAnalyze = { appState.analyzeFrames(tab) },
                        modifier = Modifier.weight(1f - videoGopSplit).fillMaxHeight(),
                    )
                }
```

- [ ] **Step 3: Verify the edits landed correctly**

Run: `grep -n "topContainerWidthPx\|topContainerHeightPx\|Orientation.Vertical\|Orientation.Horizontal\|videoGopSplit" app/src/main/kotlin/com/multiviewer/ui/VideoInspectorUI.kt`

Expected: `topContainerHeightPx` no longer appears anywhere in the file; `topContainerWidthPx` appears twice (declaration + `onGloballyPositioned` read); the divider between the player and GOP uses `Orientation.Vertical` (the summary-dashboard divider further down the file, between `verticalSplit`'s two regions, correctly still uses `Orientation.Horizontal` -- that one is unrelated to this task and must NOT change).

- [ ] **Step 4: Compile**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew compileKotlin`
Expected: `BUILD SUCCESSFUL`. `Row` is already imported in this file via the `androidx.compose.foundation.layout.*` wildcard import at the top -- no new imports needed.

- [ ] **Step 5: Run the full test suite (regression check)**

Run:
```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew test
find app/build/test-results -name "*.xml" | xargs grep -o 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' | awk -F'"' '{tests+=$2; fail+=$6; err+=$8} END {print "total tests:", tests, "failures:", fail, "errors:", err}'
```
Expected: `BUILD SUCCESSFUL`, `failures: 0 errors: 0`, same total test count as before this task (this task touches only `@Composable` layout code with no automated coverage, consistent with the spec's Testing section).

- [ ] **Step 6: Manual verification**

Build and run the app (`export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew run`). Open a video file and confirm:
- The player and GOP panel now sit side-by-side (player left, GOP right), not stacked.
- The player visibly uses the full height of the top region (no longer sharing it vertically with GOP).
- Dragging the divider between them moves it left/right and resizes both panels' widths accordingly.
- Click "프레임 분석 시작" to load GOP data, confirm the frame bars/legend render in the narrower right-hand slot, clicking a frame still seeks the player, and left/right arrow keys still step between frames.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/ui/VideoInspectorUI.kt
git commit -m "Move GOP analysis panel beside the video player instead of below it"
```

---
