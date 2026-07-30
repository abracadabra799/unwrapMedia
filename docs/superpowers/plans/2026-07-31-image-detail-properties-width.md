# Image Tab Detailed Properties Panel Width Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Narrow the Detailed Properties panel to 280dp by default for image tabs (from the shared 350dp default).

**Architecture:** `ImageInspectorUI.kt`'s `DashboardLayout(...)` call gains the `rightPanelDefaultWidthDp` argument (the parameter already exists, added for the video-tab override earlier this session).

**Tech Stack:** Kotlin, Compose Multiplatform Desktop.

## Global Constraints

- Only `ImageInspectorUI.kt`'s `DashboardLayout(...)` call changes -- `DashboardLayout.kt` itself, `VideoInspectorUI.kt` (298f), `RawPixelInspectorUI.kt`, and `AudioInspectorUI.kt` (both still on the shared 350f default) are untouched.
- The 220-1000dp drag range is unchanged.

---

### Task 1: Add image-tab right panel width override

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/ui/ImageInspectorUI.kt`

**Interfaces:**
- Consumes: `DashboardLayout`'s existing `rightPanelDefaultWidthDp: Float = RIGHT_PANEL_DEFAULT_WIDTH_DP` parameter (added earlier this session, unchanged here).
- Produces: nothing new.

- [ ] **Step 1: Confirm current DashboardLayout call site**

Run: `grep -n "DashboardLayout(" -A 3 app/src/main/kotlin/com/multiviewer/ui/ImageInspectorUI.kt`

Expected:
```kotlin
    DashboardLayout(
        leftPanel = leftPanel,
        centerPanel = {
            Column(
```
If different, stop and re-read the surrounding function before editing.

- [ ] **Step 2: Add the width override**

Find:

```kotlin
    DashboardLayout(
        leftPanel = leftPanel,
        centerPanel = {
```

Replace with:

```kotlin
    DashboardLayout(
        leftPanel = leftPanel,
        rightPanelDefaultWidthDp = 280f,
        centerPanel = {
```

- [ ] **Step 3: Verify the edit**

Run: `grep -n "rightPanelDefaultWidthDp" app/src/main/kotlin/com/multiviewer/ui/ImageInspectorUI.kt app/src/main/kotlin/com/multiviewer/ui/VideoInspectorUI.kt`

Expected: `ImageInspectorUI.kt` shows `rightPanelDefaultWidthDp = 280f`; `VideoInspectorUI.kt` still shows `rightPanelDefaultWidthDp = 298f` (unchanged).

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

Build and run the app (`export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew run`), open an image file, confirm the Detailed Properties panel is visibly narrower than before (280dp vs. the previous 350dp), and confirm dragging its handle still works across the existing 220-1000dp range.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/ui/ImageInspectorUI.kt
git commit -m "Narrow image tab's Detailed Properties panel default width to 280dp"
```

---
