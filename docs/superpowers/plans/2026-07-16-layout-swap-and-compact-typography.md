# Layout Swap and Compact Typography Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Swap the hex dump and detail panel (field panel / table view) screen positions so the detail panel sits next to the tree and the hex dump spans the full width at the bottom, and reduce the app's default font size globally from one place.

**Architecture:** Both tasks are sequential, single-file edits to `Main.kt`. Task 1 swaps which composable renders in the top-row's second column versus the bottom full-width column, with no changes to weights or divider placement. Task 2 introduces a file-scoped `compactTypography` constant (derived from Material3's default `Typography()` with only `bodyLarge`/`labelLarge` font sizes reduced) and passes it to the existing `MaterialTheme { ... }` call, which every screen already routes its default text through.

**Tech Stack:** Kotlin, Compose Multiplatform (Desktop) — same as the rest of the UI layer.

## Global Constraints

- Design spec: `docs/superpowers/specs/2026-07-16-layout-swap-and-compact-typography-design.md`
- Weight values are unchanged: top row's two columns stay `weight(1f)` each, bottom panel stays `weight(0.3f)` — this is a content swap only, not a proportion change.
- Both existing divider lines (vertical between tree/detail, horizontal between top row/bottom panel), added by the prior feature, must remain in place, unchanged.
- Compact typography overrides only `bodyLarge` and `labelLarge`, both to `13.sp`, copied from Material3's default `Typography()` so all other properties (line height, letter spacing, weight) are preserved.
- No new tests — this project's UI layer has no automated tests by design. Verify by compiling and running.
- JAVA_HOME for command-line Gradle runs on this machine: Android Studio's bundled JBR at `/Applications/Android Studio.app/Contents/jbr/Contents/Home` (JDK 21).

---

### Task 1: Swap hex dump and detail panel positions

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/Main.kt`

**Interfaces:**
- Consumes: nothing new — same `BoxTreeView`, `HexView`, `com.multiviewer.ui.TableView`, `com.multiviewer.ui.FieldPanel` call sites and signatures as today.
- Produces: nothing new for other tasks — this is a self-contained layout change. Task 2 touches a different part of the same file (the `MaterialTheme` call and file-scope constants) and does not depend on this task's content.

- [ ] **Step 1: Replace the `when { ... }` block's `currentTab.root != null` branch**

Replace this exact block:

```kotlin
                        currentTab.root != null -> Column(modifier = Modifier.fillMaxSize()) {
                            Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                                Column(modifier = Modifier.weight(1f).fillMaxWidth()) {
                                    BoxTreeView(
                                        root = currentTab.root!!,
                                        selected = currentTab.selected,
                                        onSelect = { currentTab.selected = it },
                                    )
                                }
                                Box(modifier = Modifier.width(1.dp).fillMaxHeight().background(Color.DarkGray))
                                Column(modifier = Modifier.weight(1f).fillMaxWidth()) {
                                    HexView(
                                        file = currentTab.file,
                                        highlightRange = currentTab.selected?.let { it.offset until (it.offset + it.size) },
                                        listState = hexListState,
                                    )
                                }
                            }
                            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color.DarkGray))
                            Column(modifier = Modifier.weight(0.3f).fillMaxWidth()) {
                                val selectedNode = currentTab.selected
                                if (selectedNode?.table != null) {
                                    com.multiviewer.ui.TableView(currentTab.file, selectedNode.table!!)
                                } else {
                                    com.multiviewer.ui.FieldPanel(selectedNode)
                                }
                            }
                        }
```

with:

```kotlin
                        currentTab.root != null -> Column(modifier = Modifier.fillMaxSize()) {
                            Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                                Column(modifier = Modifier.weight(1f).fillMaxWidth()) {
                                    BoxTreeView(
                                        root = currentTab.root!!,
                                        selected = currentTab.selected,
                                        onSelect = { currentTab.selected = it },
                                    )
                                }
                                Box(modifier = Modifier.width(1.dp).fillMaxHeight().background(Color.DarkGray))
                                Column(modifier = Modifier.weight(1f).fillMaxWidth()) {
                                    val selectedNode = currentTab.selected
                                    if (selectedNode?.table != null) {
                                        com.multiviewer.ui.TableView(currentTab.file, selectedNode.table!!)
                                    } else {
                                        com.multiviewer.ui.FieldPanel(selectedNode)
                                    }
                                }
                            }
                            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color.DarkGray))
                            Column(modifier = Modifier.weight(0.3f).fillMaxWidth()) {
                                HexView(
                                    file = currentTab.file,
                                    highlightRange = currentTab.selected?.let { it.offset until (it.offset + it.size) },
                                    listState = hexListState,
                                )
                            }
                        }
```

No import changes are needed for this step — `HexView`, `Box`, `Column`, `Row`, `Color`, `dp` are all already imported, and `TableView`/`FieldPanel` are already referenced via their fully-qualified names.

- [ ] **Step 2: Verify it compiles**

Run:
```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew compileKotlin
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Run the full test suite (confirm no regression)**

Run:
```bash
./gradlew test
```
Expected: BUILD SUCCESSFUL, same test count as before this change, 0 failures (this change touches no parser code and no test files).

- [ ] **Step 4: Manual verification**

Run:
```bash
./gradlew run
```
Open a real `.mov`/`.heic` file and confirm:
- The detail panel (field panel or table view, depending on whether the selected node has table data) now appears in the top row, to the right of the tree view, in the same column that previously held the hex dump.
- The hex dump now appears as a full-width panel at the bottom, in the same position that previously held the detail panel.
- Both divider lines (vertical between tree/detail, horizontal between top row/bottom panel) are still visible in the same relative positions.
- Selecting a tree node still updates the detail panel and highlights the matching bytes in the hex dump.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/Main.kt
git commit -m "feat(ui): swap hex dump and detail panel screen positions"
```

---

### Task 2: Reduce default font size app-wide

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/Main.kt`

**Interfaces:**
- Consumes: nothing new — this task only changes the `MaterialTheme` call and adds one file-scope constant; it does not depend on Task 1's edits (different lines of the same file).
- Produces: nothing new for other tasks — this is the final task in the plan.

- [ ] **Step 1: Add the `Typography` and `sp` imports**

Add these two imports to the top of `Main.kt`, alongside the existing `androidx.compose.material3.*` and `androidx.compose.ui.unit.*` imports:

```kotlin
import androidx.compose.material3.Typography
import androidx.compose.ui.unit.sp
```

- [ ] **Step 2: Add the `compactTypography` constant**

Add this file-scope constant directly after the existing `private const val BYTES_PER_ROW = 16` line:

```kotlin
private val compactTypography = Typography().let { defaults ->
    defaults.copy(
        bodyLarge = defaults.bodyLarge.copy(fontSize = 13.sp),
        labelLarge = defaults.labelLarge.copy(fontSize = 13.sp),
    )
}
```

- [ ] **Step 3: Pass `compactTypography` to `MaterialTheme`**

Replace:

```kotlin
        MaterialTheme {
```

with:

```kotlin
        MaterialTheme(typography = compactTypography) {
```

The full top-of-file state after Steps 1-3 (imports plus the two file-scope constants) should read:

```kotlin
package com.multiviewer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.multiviewer.ui.AppState
import com.multiviewer.ui.BoxTreeView
import com.multiviewer.ui.HexView
import java.awt.FileDialog
import java.awt.Frame
import java.awt.datatransfer.DataFlavor
import java.awt.dnd.DnDConstants
import java.awt.dnd.DropTarget
import java.awt.dnd.DropTargetAdapter
import java.awt.dnd.DropTargetDropEvent
import java.io.File

private const val BYTES_PER_ROW = 16
private val compactTypography = Typography().let { defaults ->
    defaults.copy(
        bodyLarge = defaults.bodyLarge.copy(fontSize = 13.sp),
        labelLarge = defaults.labelLarge.copy(fontSize = 13.sp),
    )
}
```

- [ ] **Step 4: Verify it compiles**

Run:
```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew compileKotlin
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Run the full test suite (confirm no regression)**

Run:
```bash
./gradlew test
```
Expected: BUILD SUCCESSFUL, same test count as before this change, 0 failures.

- [ ] **Step 6: Manual verification**

Run:
```bash
./gradlew run
```
Open a real `.mov`/`.heic` file and confirm:
- All text across the app (tree labels, hex bytes/ASCII, field panel rows, table view rows, "Open File" button label, tab labels) renders visibly smaller than before this change.
- No text is clipped, overlapping, or misaligned as a result of the smaller size.
- The layout from Task 1 (detail panel top-right, hex dump bottom, dividers in place) still looks correct with the smaller text.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/Main.kt
git commit -m "feat(ui): reduce default font size app-wide via compact typography"
```
