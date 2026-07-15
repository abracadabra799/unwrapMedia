# Panel Dividers and Hierarchical Tree View Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add visible dividers between the tree/hex panels and between the top row and bottom panel, and give the box tree explicit expand/collapse arrows plus per-depth vertical guide lines, matching a standard file-tree sidebar (e.g. Android Studio's Project view).

**Architecture:** Two independent, single-file changes. `Main.kt` gets two static 1dp divider `Box`es inserted into its existing layout tree. `BoxTreeView.kt`'s per-row `Text` is replaced with a `Row` containing per-depth guide-line boxes, a fixed-width expand/collapse arrow box, and the existing label `Text`, with click/selection behavior unchanged.

**Tech Stack:** Kotlin, Compose Multiplatform (Desktop) — same as the rest of the UI layer.

## Global Constraints

- Design spec: `docs/superpowers/specs/2026-07-15-tree-hierarchy-and-divider-design.md`
- Divider color: `Color.DarkGray`, thickness 1dp, static (no drag/resize).
- Tree guide-line color: `Color.Gray`, thickness 1dp, drawn unconditionally for every depth box (no "last child" corner cases, no horizontal elbow connectors) — an intentional simplification per the design spec's YAGNI note.
- Arrow glyphs: `▼` expanded, `▶` collapsed, both fixed-width (16dp) boxes; leaf nodes get a same-width blank box so labels align across all rows.
- Existing click behavior (select node + toggle expansion if it has children) must be preserved exactly, now applied to the whole row instead of just the label `Text`.
- No new tests — this project's UI layer has no automated tests by design (parser is unit-tested; UI is verified by manual/process-level checks). Verify by compiling and running.
- JAVA_HOME for command-line Gradle runs on this machine: Android Studio's bundled JBR at `/Applications/Android Studio.app/Contents/jbr/Contents/Home` (JDK 21).

---

### Task 1: Add divider lines to the main layout

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/Main.kt`

**Interfaces:**
- Consumes: nothing new — same `AppState`, `BoxTreeView`, `HexView` call sites as today.
- Produces: nothing new for other tasks — this is a self-contained visual change, independent of Task 2.

- [ ] **Step 1: Replace the `when { ... }` block's `currentTab.root != null` branch**

In `Main.kt`, replace this exact block:

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
                                Column(modifier = Modifier.weight(1f).fillMaxWidth()) {
                                    HexView(
                                        file = currentTab.file,
                                        highlightRange = currentTab.selected?.let { it.offset until (it.offset + it.size) },
                                        listState = hexListState,
                                    )
                                }
                            }
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

- [ ] **Step 2: Add the new imports**

Add these seven imports to the top of `Main.kt`, alongside the existing `androidx.compose.foundation.layout.*` imports:

```kotlin
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
```

The full import block at the top of `Main.kt` should read:

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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
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
```

- [ ] **Step 3: Verify it compiles**

Run:
```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew compileKotlin
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Run the full test suite (confirm no regression)**

Run:
```bash
./gradlew test
```
Expected: BUILD SUCCESSFUL, same test count as before this change, 0 failures (this change touches no parser code and no test files).

- [ ] **Step 5: Manual verification**

Run:
```bash
./gradlew run
```
Open a real `.mov`/`.heic` file and confirm:
- A thin dark-gray vertical line separates the tree view (left) from the hex view (right).
- A thin dark-gray horizontal line separates the top row (tree + hex) from the bottom panel (field panel / table view).
- Both dividers span the full height/width of their boundary.
- Layout proportions (tree/hex 50/50 split, bottom panel ~30% height) look the same as before — only the divider lines are new.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/Main.kt
git commit -m "feat(ui): add divider lines between tree/hex and top/bottom panels"
```

---

### Task 2: Add expand/collapse arrows and depth guide lines to the box tree

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/ui/BoxTreeView.kt`

**Interfaces:**
- Consumes: nothing new — same `BoxTreeView(root: BoxNode, selected: BoxNode?, onSelect: (BoxNode) -> Unit)` public signature, same `FlatRow`/`flatten()`/`buildLabel()` helpers.
- Produces: nothing new for other tasks — this is a self-contained visual change, independent of Task 1.

- [ ] **Step 1: Replace the full file content**

Replace the entire content of `app/src/main/kotlin/com/multiviewer/ui/BoxTreeView.kt` with:

```kotlin
package com.multiviewer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.multiviewer.parser.BoxNode

private data class FlatRow(val node: BoxNode, val depth: Int)

private const val DEPTH_INDENT_DP = 16
private const val ARROW_WIDTH_DP = 16

@Composable
fun BoxTreeView(root: BoxNode, selected: BoxNode?, onSelect: (BoxNode) -> Unit) {
    val expanded = remember { mutableStateOf(setOf<BoxNode>()) }
    val rows = remember(root, expanded.value) { flatten(root, 0, expanded.value) }

    LazyColumn {
        items(rows) { row ->
            val isSelected = row.node === selected
            val isExpanded = row.node in expanded.value
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min)
                    .background(if (isSelected) Color.LightGray else Color.Transparent)
                    .clickable {
                        onSelect(row.node)
                        if (row.node.children.isNotEmpty()) {
                            expanded.value = if (row.node in expanded.value) {
                                expanded.value - row.node
                            } else {
                                expanded.value + row.node
                            }
                        }
                    }
                    .padding(top = 2.dp, bottom = 2.dp),
            ) {
                repeat(row.depth) {
                    Box(modifier = Modifier.width(DEPTH_INDENT_DP.dp).fillMaxHeight()) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.Center)
                                .width(1.dp)
                                .fillMaxHeight()
                                .background(Color.Gray),
                        )
                    }
                }
                Box(
                    modifier = Modifier.width(ARROW_WIDTH_DP.dp).fillMaxHeight(),
                    contentAlignment = Alignment.Center,
                ) {
                    if (row.node.children.isNotEmpty()) {
                        Text(if (isExpanded) "▼" else "▶")
                    }
                }
                Text(text = buildLabel(row.node))
            }
        }
    }
}

private fun flatten(node: BoxNode, depth: Int, expanded: Set<BoxNode>): List<FlatRow> {
    val rows = mutableListOf(FlatRow(node, depth))
    if (node.children.isNotEmpty() && node in expanded) {
        for (child in node.children) {
            rows.addAll(flatten(child, depth + 1, expanded))
        }
    }
    return rows
}

private fun buildLabel(node: BoxNode): String {
    val warningPrefix = if (node.warnings.isNotEmpty()) "⚠ " else ""
    val summarySuffix = node.summary?.let { " — $it" } ?: ""
    return "$warningPrefix${node.type}$summarySuffix"
}
```

Note: `"▼"` is the down-pointing triangle (`▼`) and `"▶"` is the right-pointing triangle (`▶`), written as escapes to avoid any editor/encoding ambiguity with raw Unicode glyphs in source.

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
Open a real `.mov`/`.heic` file with nested boxes (e.g. `moov` > `trak` > `mdia`) and confirm:
- Nodes with children show `▶` when collapsed and `▼` when expanded, in a fixed-width column before the label.
- Leaf nodes (no children) show no arrow, but their label still starts at the same horizontal position as sibling nodes' labels (i.e. the blank arrow-box space is preserved).
- A thin gray vertical guide line is visible at each ancestor depth level, connecting parent rows to their descendants, for every expanded subtree.
- Clicking anywhere on a row (guide rail, arrow, or label) still selects that node and, if it has children, toggles expand/collapse — exactly as before this change.
- The selected row's light-gray highlight background still covers the full row width.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/ui/BoxTreeView.kt
git commit -m "feat(ui): add expand/collapse arrows and depth guide lines to box tree"
```
