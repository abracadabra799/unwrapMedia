# Detailed Properties Panel Default Width Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Widen the Detailed Properties (right) panel's default width from 260dp to 350dp.

**Architecture:** Change one `private const val` in `DashboardLayout.kt`. No structural change -- the existing user-draggable `VerticalResizeHandle` mechanism and its 220-1000dp range are untouched.

**Tech Stack:** Kotlin, Compose Multiplatform Desktop.

## Global Constraints

- Only `RIGHT_PANEL_DEFAULT_WIDTH_DP` changes, from `260f` to `350f`, in `DashboardLayout.kt` -- `RIGHT_PANEL_MIN_WIDTH_DP` (220f), `RIGHT_PANEL_MAX_WIDTH_DP` (1000f), and the left-panel constants (`LEFT_PANEL_MIN_WIDTH_DP`/`LEFT_PANEL_MAX_WIDTH_DP`) are untouched.
- `leftPanelWidthDp`'s own default (`300f`, a separate `remember { mutableStateOf(300f) }` at line 68, not a named constant) is untouched -- this task only affects the right panel.

---

### Task 1: Increase default Detailed Properties panel width

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/ui/DashboardLayout.kt:28`

**Interfaces:**
- Consumes: nothing new -- `RIGHT_PANEL_DEFAULT_WIDTH_DP` is already read once, at line 69: `var rightPanelWidthDp by remember { mutableStateOf(RIGHT_PANEL_DEFAULT_WIDTH_DP) }`.
- Produces: nothing new -- this is a constant-value-only change.

- [ ] **Step 1: Confirm current value before editing**

Run: `grep -n "RIGHT_PANEL_DEFAULT_WIDTH_DP" app/src/main/kotlin/com/multiviewer/ui/DashboardLayout.kt`

Expected output:
```
app/src/main/kotlin/com/multiviewer/ui/DashboardLayout.kt:28:private const val RIGHT_PANEL_DEFAULT_WIDTH_DP = 260f
app/src/main/kotlin/com/multiviewer/ui/DashboardLayout.kt:69:    var rightPanelWidthDp by remember { mutableStateOf(RIGHT_PANEL_DEFAULT_WIDTH_DP) }
```

If the first line differs (e.g. the file has changed since this plan was written), stop and read the full surrounding context before editing -- do not guess at the edit. The second line (which reads the constant, not its own literal) must not be edited at all.

- [ ] **Step 2: Edit DashboardLayout.kt**

In `app/src/main/kotlin/com/multiviewer/ui/DashboardLayout.kt`, find:

```kotlin
private const val RIGHT_PANEL_DEFAULT_WIDTH_DP = 260f
```

Replace with:

```kotlin
private const val RIGHT_PANEL_DEFAULT_WIDTH_DP = 350f
```

- [ ] **Step 3: Verify the edit landed correctly**

Run: `grep -n "RIGHT_PANEL_MIN_WIDTH_DP\|RIGHT_PANEL_DEFAULT_WIDTH_DP\|RIGHT_PANEL_MAX_WIDTH_DP" app/src/main/kotlin/com/multiviewer/ui/DashboardLayout.kt`

Expected output:
```
app/src/main/kotlin/com/multiviewer/ui/DashboardLayout.kt:27:private const val RIGHT_PANEL_MIN_WIDTH_DP = 220f
app/src/main/kotlin/com/multiviewer/ui/DashboardLayout.kt:28:private const val RIGHT_PANEL_DEFAULT_WIDTH_DP = 350f
app/src/main/kotlin/com/multiviewer/ui/DashboardLayout.kt:29:private const val RIGHT_PANEL_MAX_WIDTH_DP = 1000f
```

- [ ] **Step 4: Compile**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew compileKotlin`
Expected: `BUILD SUCCESSFUL` (a literal float value change to an existing constant, no new symbols, no import changes needed).

- [ ] **Step 5: Run the full test suite (regression check)**

Run: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew test`
Expected: `BUILD SUCCESSFUL`. Count the actual number of tests and failures from the test result XML files (do not rely on any summary line alone):
```bash
find app/build/test-results -name "*.xml" | xargs grep -o 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' | awk -F'"' '{tests+=$2; fail+=$6; err+=$8} END {print "total tests:", tests, "failures:", fail, "errors:", err}'
```
Expected: `failures: 0 errors: 0`, and the total test count unchanged from before this task (this task does not add, remove, or affect any test).

- [ ] **Step 6: Manual verification**

Build and run the app (`export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && ./gradlew run`). Open any file, select a tree node so the Detailed Properties panel has content, and confirm the panel opens visibly wider than before (350dp vs. the previous 260dp). Confirm the drag handle on the panel's left edge still works and can still resize it across its existing 220-1000dp range.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/ui/DashboardLayout.kt
git commit -m "Increase Detailed Properties panel default width from 260dp to 350dp"
```

---
