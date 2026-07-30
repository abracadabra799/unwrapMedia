# GOP Panel Width and Frame Bar Height Tuning Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Widen the GOP panel further (shrink the player further) and cap each frame bar's max height at 60% of the panel instead of 100%.

**Architecture:** Two independent constant/formula edits in two different files -- `videoGopSplit`'s value in `VideoInspectorUI.kt`, and a new scale factor applied to `heightFraction` in `GopAnalysisView.kt`.

**Tech Stack:** Kotlin, Compose Multiplatform Desktop.

## Global Constraints

- `videoGopSplit`'s drag-clamp range (0.1-0.9, enforced inside `DraggableDivider`) is unchanged.
- The existing 2% minimum-visibility floor on frame bar height (`.coerceAtLeast(0.02f)`) is preserved -- it applies AFTER the new 60% scale factor, not instead of it.
- Relative height differences between frames (based on `frame.sizeBytes`) must still be proportionally preserved -- the 60% cap scales every bar down together, it doesn't change which frames look bigger/smaller relative to each other.

---

### Task 1: Widen GOP panel and cap frame bar height

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/ui/VideoInspectorUI.kt`
- Modify: `app/src/main/kotlin/com/multiviewer/ui/GopAnalysisView.kt`

**Interfaces:**
- Produces: nothing new -- both edits are local constant/formula changes with no new function or exposed state.

- [ ] **Step 1: Confirm current videoGopSplit value**

Run: `grep -n "videoGopSplit by remember" app/src/main/kotlin/com/multiviewer/ui/VideoInspectorUI.kt`

Expected: `var videoGopSplit by remember { mutableStateOf(0.455f) }`. If different, stop and re-read the file before editing.

- [ ] **Step 2: Edit VideoInspectorUI.kt**

Find:

```kotlin
    var videoGopSplit by remember { mutableStateOf(0.455f) }
```

Replace with:

```kotlin
    var videoGopSplit by remember { mutableStateOf(0.35f) }
```

- [ ] **Step 3: Confirm current heightFraction line in GopAnalysisView.kt**

Run: `grep -n "heightFraction = " app/src/main/kotlin/com/multiviewer/ui/GopAnalysisView.kt`

Expected: `val heightFraction = (frame.sizeBytes.toFloat() / maxSize).coerceAtLeast(0.02f)`. If different, stop and re-read the surrounding function before editing.

- [ ] **Step 4: Add the height-cap constant**

Find this exact text (the four `FRAME_BAR_*` constants):

```kotlin
private const val FRAME_BAR_WIDTH_DP = 16
private const val FRAME_BAR_MIN_WIDTH_DP = 4f
private const val FRAME_BAR_MAX_WIDTH_DP = 48f
private const val FRAME_BAR_ZOOM_STEP_DP = 2f
private const val FRAME_BAR_SPACING_DP = 2
```

Replace with:

```kotlin
private const val FRAME_BAR_WIDTH_DP = 16
private const val FRAME_BAR_MIN_WIDTH_DP = 4f
private const val FRAME_BAR_MAX_WIDTH_DP = 48f
private const val FRAME_BAR_ZOOM_STEP_DP = 2f
private const val FRAME_BAR_SPACING_DP = 2
// The tallest bar (the single biggest frame in the video) would otherwise reach 100% of the
// panel's height, which reads as too dominant -- this caps it at 60%, scaling every other bar down
// proportionally along with it so their relative size differences are preserved.
private const val FRAME_BAR_MAX_HEIGHT_FRACTION = 0.6f
```

- [ ] **Step 5: Apply the height-cap scale factor**

Find:

```kotlin
                            val heightFraction = (frame.sizeBytes.toFloat() / maxSize).coerceAtLeast(0.02f)
```

Replace with:

```kotlin
                            val heightFraction = (frame.sizeBytes.toFloat() / maxSize * FRAME_BAR_MAX_HEIGHT_FRACTION).coerceAtLeast(0.02f)
```

- [ ] **Step 6: Verify both edits**

Run:
```bash
grep -n "videoGopSplit by remember" app/src/main/kotlin/com/multiviewer/ui/VideoInspectorUI.kt
grep -n "FRAME_BAR_MAX_HEIGHT_FRACTION\|heightFraction = " app/src/main/kotlin/com/multiviewer/ui/GopAnalysisView.kt
```
Expected: `videoGopSplit` reads `mutableStateOf(0.35f)`; `FRAME_BAR_MAX_HEIGHT_FRACTION` appears twice (the `private const val` declaration and its use in the `heightFraction` formula, which now includes `* FRAME_BAR_MAX_HEIGHT_FRACTION`).

- [ ] **Step 7: Compile**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew compileKotlin`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 8: Run the full test suite (regression check)**

Run:
```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew test
find app/build/test-results -name "*.xml" | xargs grep -o 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' | awk -F'"' '{tests+=$2; fail+=$6; err+=$8} END {print "total tests:", tests, "failures:", fail, "errors:", err}'
```
Expected: `BUILD SUCCESSFUL`, `failures: 0 errors: 0`, same total test count as before this task.

- [ ] **Step 9: Manual verification**

Build and run the app (`export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew run`), open a video, click "프레임 분석 시작". Confirm the GOP panel is visibly wider (player narrower) than before, and that the tallest frame bar now stops noticeably short of the panel's top edge (around 60% up) instead of touching it, while still being clickable and still zooming correctly with the mouse wheel.

- [ ] **Step 10: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/ui/VideoInspectorUI.kt app/src/main/kotlin/com/multiviewer/ui/GopAnalysisView.kt
git commit -m "Widen GOP panel further and cap frame bar height at 60% of panel"
```

---
