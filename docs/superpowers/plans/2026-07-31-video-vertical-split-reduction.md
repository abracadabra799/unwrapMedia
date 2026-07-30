# Video Top-Region Vertical Split Reduction Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Shrink the video tab's player+GOP region to 49% of the center column's height (from 70%), growing the summary dashboard below it to 51%.

**Architecture:** One `remember { mutableStateOf(0.7f) }` initial value changes to `0.49f` in `VideoInspectorUI.kt`.

**Tech Stack:** Kotlin, Compose Multiplatform Desktop.

## Global Constraints

- Only `VideoInspectorUI.kt`'s `verticalSplit` changes, from `0.7f` to `0.49f` -- `videoGopSplit` and everything else in the file are untouched.
- `ImageInspectorUI.kt`'s own `verticalSplit` (a separate variable, also currently `0.7f`) is NOT touched -- this is video-only.

---

### Task 1: Reduce video top-region vertical split

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/ui/VideoInspectorUI.kt:35`

**Interfaces:**
- Produces: nothing new -- this is a value-only change.

- [ ] **Step 1: Confirm current value before editing**

Run: `grep -n "var verticalSplit by remember" app/src/main/kotlin/com/multiviewer/ui/VideoInspectorUI.kt`

Expected: `var verticalSplit by remember { mutableStateOf(0.7f) }`. If different, stop and re-read the file before editing.

- [ ] **Step 2: Edit VideoInspectorUI.kt**

Find:

```kotlin
    var verticalSplit by remember { mutableStateOf(0.7f) }
```

Replace with:

```kotlin
    var verticalSplit by remember { mutableStateOf(0.49f) }
```

Do NOT touch the next line (`var videoGopSplit by remember { mutableStateOf(0.35f) }`) -- unrelated to this task.

- [ ] **Step 3: Verify the edit and confirm ImageInspectorUI.kt is unaffected**

Run:
```bash
grep -n "verticalSplit by remember\|videoGopSplit by remember" app/src/main/kotlin/com/multiviewer/ui/VideoInspectorUI.kt
grep -n "verticalSplit by remember" app/src/main/kotlin/com/multiviewer/ui/ImageInspectorUI.kt
```
Expected: `VideoInspectorUI.kt` shows `verticalSplit` at `0.49f` and `videoGopSplit` still at `0.35f` (unchanged); `ImageInspectorUI.kt`'s `verticalSplit` still reads `0.7f` (unchanged -- it's a separate variable in a separate file).

- [ ] **Step 4: Compile**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew compileKotlin`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Run the full test suite (regression check)**

Run:
```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew test
find app/build/test-results -name "*.xml" | xargs grep -o 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' | awk -F'"' '{tests+=$2; fail+=$6; err+=$8} END {print "total tests:", tests, "failures:", fail, "errors:", err}'
```
Expected: `BUILD SUCCESSFUL`, `failures: 0 errors: 0`, same total test count as before this task.

- [ ] **Step 6: Manual verification**

Build and run the app (`export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew run`), open a video, confirm the player+GOP region is visibly shorter and the summary dashboard below it is visibly taller than before (roughly a 49/51 split instead of 70/30). Confirm dragging the divider between them still works.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/ui/VideoInspectorUI.kt
git commit -m "Reduce video top-region vertical split from 70% to 49%"
```

---
