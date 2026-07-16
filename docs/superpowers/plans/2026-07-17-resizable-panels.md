# Resizable Panels Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let the user drag both existing divider lines (tree ↔ detail panel, top row ↔ hex dump) to resize the adjacent panels, independently per open tab, for the current app session only.

**Architecture:** Task 1 adds two per-tab `mutableStateOf` fields to `TabState` (`verticalSplit`, `horizontalSplit`) that hold each panel-pair's fractional split. Task 2 replaces the two static 1dp divider `Box`es in `Main.kt` with a shared private `DraggableDivider` composable that tracks pointer drag deltas, converts them to a fraction of the measured container size, and writes the clamped result back into the tab's split field — and rewires the four `weight(...)` call sites to read from those fields instead of hardcoded constants.

**Tech Stack:** Kotlin, Compose Multiplatform (Desktop) — same as the rest of the UI layer. Uses `androidx.compose.foundation.gestures.detectDragGestures` and `Modifier.pointerInput`, which are new patterns for this codebase (no prior drag-gesture code exists) but are the standard Compose approach for custom draggable UI.

## Global Constraints

- Design spec: `docs/superpowers/specs/2026-07-17-resizable-panels-design.md`
- `verticalSplit` default: `0.5f` (tree column's share of the top row's width; detail panel gets `1f - verticalSplit`).
- `horizontalSplit` default: `1f / 1.3f` (top row's share of the window's content height; hex dump gets `1f - horizontalSplit`) — matches today's `weight(1f)`/`weight(0.3f)` ratio exactly.
- Both split values are clamped to `0.15f..0.85f` on every drag update, so neither adjacent panel can shrink past 15% or grow past 85%.
- Split state lives on `TabState` (per-tab), is never persisted to disk, and always starts at the defaults above for a newly created `TabState`.
- The visible divider line stays 1dp thick and `Color.DarkGray`, exactly as before — only the interactive hit area (8dp) is new, not the visual appearance.
- No new automated tests — this project's UI layer has no test suite by design. Verify by compiling and running.
- JAVA_HOME for command-line Gradle runs on this machine: Android Studio's bundled JBR at `/Applications/Android Studio.app/Contents/jbr/Contents/Home` (JDK 21).

---

### Task 1: Add per-tab split state fields

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/ui/AppState.kt`

**Interfaces:**
- Consumes: nothing new — same `TabState(file: File)` constructor and existing fields (`root`, `error`, `selected`).
- Produces: `TabState.verticalSplit: Float` (mutable, default `0.5f`) and `TabState.horizontalSplit: Float` (mutable, default `1f / 1.3f`) — Task 2 reads and writes both fields directly (`currentTab.verticalSplit`, `currentTab.horizontalSplit`).

- [ ] **Step 1: Add the two fields to `TabState`**

Replace the entire content of `app/src/main/kotlin/com/multiviewer/ui/AppState.kt` with:

```kotlin
package com.multiviewer.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.multiviewer.parser.BoxNode
import com.multiviewer.parser.parseFile
import java.io.File

class TabState(val file: File) {
    var root: BoxNode? by mutableStateOf(null)
    var error: String? by mutableStateOf(null)
    var selected: BoxNode? by mutableStateOf(null)
    var verticalSplit: Float by mutableStateOf(0.5f)
    var horizontalSplit: Float by mutableStateOf(1f / 1.3f)
}

class AppState {
    val tabs = mutableStateListOf<TabState>()
    var selectedTabIndex by mutableStateOf(0)

    fun openFile(file: File) {
        val existingIndex = tabs.indexOfFirst { it.file.absolutePath == file.absolutePath }
        if (existingIndex >= 0) {
            selectedTabIndex = existingIndex
            return
        }
        val tab = TabState(file)
        tabs.add(tab)
        selectedTabIndex = tabs.size - 1
        try {
            tab.root = parseFile(file)
        } catch (e: Exception) {
            tab.error = e.message ?: "Failed to open file"
        }
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run:
```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew compileKotlin
```
Expected: BUILD SUCCESSFUL. (`Main.kt` does not yet reference the new fields, so no other file needs to change for this to compile.)

- [ ] **Step 3: Run the full test suite (confirm no regression)**

Run:
```bash
./gradlew test
```
Expected: BUILD SUCCESSFUL, same test count as before this change, 0 failures (this change touches no parser code and no test files).

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/ui/AppState.kt
git commit -m "feat(ui): add per-tab resizable-panel split state"
```

---

### Task 2: Add draggable dividers and wire them into the layout

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/Main.kt`

**Interfaces:**
- Consumes: `TabState.verticalSplit` and `TabState.horizontalSplit` from Task 1, both `Float`, both mutable (readable and writable directly as `currentTab.verticalSplit = newValue`).
- Produces: nothing new for other tasks — this is the final task in the plan.

- [ ] **Step 1: Replace the full content of `Main.kt`**

Replace the entire content of `app/src/main/kotlin/com/multiviewer/Main.kt` with:

```kotlin
package com.multiviewer

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
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
private const val MIN_SPLIT = 0.15f
private const val MAX_SPLIT = 0.85f
private val compactTypography = Typography().let { defaults ->
    defaults.copy(
        bodyLarge = defaults.bodyLarge.copy(fontSize = 13.sp),
        labelLarge = defaults.labelLarge.copy(fontSize = 13.sp),
    )
}

@Composable
private fun DraggableDivider(
    orientation: Orientation,
    containerSizePx: Int,
    tabKey: Any,
    getSplit: () -> Float,
    setSplit: (Float) -> Unit,
) {
    val handleModifier = if (orientation == Orientation.Vertical) {
        Modifier.width(8.dp).fillMaxHeight()
    } else {
        Modifier.fillMaxWidth().height(8.dp)
    }
    val lineModifier = if (orientation == Orientation.Vertical) {
        Modifier.width(1.dp).fillMaxHeight()
    } else {
        Modifier.fillMaxWidth().height(1.dp)
    }
    Box(
        modifier = handleModifier.pointerInput(orientation, containerSizePx, tabKey) {
            detectDragGestures { change, dragAmount ->
                change.consume()
                if (containerSizePx > 0) {
                    val deltaPx = if (orientation == Orientation.Vertical) dragAmount.x else dragAmount.y
                    val delta = deltaPx / containerSizePx
                    setSplit((getSplit() + delta).coerceIn(MIN_SPLIT, MAX_SPLIT))
                }
            }
        },
        contentAlignment = Alignment.Center,
    ) {
        Box(modifier = lineModifier.background(Color.DarkGray))
    }
}

fun main() = application {
    val appState = remember { AppState() }

    Window(onCloseRequest = ::exitApplication, title = "multiViewer") {
        LaunchedEffect(Unit) {
            window.contentPane.dropTarget = DropTarget(window.contentPane, object : DropTargetAdapter() {
                override fun drop(event: DropTargetDropEvent) {
                    if (!event.transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                        event.rejectDrop()
                        return
                    }
                    event.acceptDrop(DnDConstants.ACTION_COPY)
                    try {
                        @Suppress("UNCHECKED_CAST")
                        val files = event.transferable.getTransferData(DataFlavor.javaFileListFlavor) as List<File>
                        files.firstOrNull()?.let { appState.openFile(it) }
                        event.dropComplete(true)
                    } catch (e: Exception) {
                        event.dropComplete(false)
                    }
                }
            })
        }

        MaterialTheme(typography = compactTypography) {
            Column(modifier = Modifier.fillMaxSize()) {
                Button(onClick = {
                    val dialog = FileDialog(null as Frame?, "Open file", FileDialog.LOAD)
                    dialog.isVisible = true
                    val fileName = dialog.file
                    val directory = dialog.directory
                    if (fileName != null && directory != null) {
                        appState.openFile(File(directory, fileName))
                    }
                }) {
                    Text("Open File")
                }

                if (appState.tabs.isNotEmpty()) {
                    TabRow(selectedTabIndex = appState.selectedTabIndex) {
                        appState.tabs.forEachIndexed { index, tab ->
                            Tab(
                                selected = index == appState.selectedTabIndex,
                                onClick = { appState.selectedTabIndex = index },
                                text = { Text(tab.file.name) },
                            )
                        }
                    }

                    val currentTab = appState.tabs[appState.selectedTabIndex]
                    val hexListState = remember(currentTab) { androidx.compose.foundation.lazy.LazyListState() }

                    LaunchedEffect(currentTab.selected) {
                        val sel = currentTab.selected
                        if (sel != null) {
                            hexListState.scrollToItem((sel.offset / BYTES_PER_ROW).toInt())
                        }
                    }

                    when {
                        currentTab.error != null -> Text("Error: ${currentTab.error}")
                        currentTab.root != null -> {
                            var columnHeightPx by remember { mutableStateOf(0) }
                            var rowWidthPx by remember { mutableStateOf(0) }

                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .onGloballyPositioned { columnHeightPx = it.size.height },
                            ) {
                                Row(
                                    modifier = Modifier
                                        .weight(currentTab.horizontalSplit)
                                        .fillMaxWidth()
                                        .onGloballyPositioned { rowWidthPx = it.size.width },
                                ) {
                                    Column(modifier = Modifier.weight(currentTab.verticalSplit).fillMaxWidth()) {
                                        BoxTreeView(
                                            root = currentTab.root!!,
                                            selected = currentTab.selected,
                                            onSelect = { currentTab.selected = it },
                                        )
                                    }
                                    DraggableDivider(
                                        orientation = Orientation.Vertical,
                                        containerSizePx = rowWidthPx,
                                        tabKey = currentTab,
                                        getSplit = { currentTab.verticalSplit },
                                        setSplit = { currentTab.verticalSplit = it },
                                    )
                                    Column(modifier = Modifier.weight(1f - currentTab.verticalSplit).fillMaxWidth()) {
                                        val selectedNode = currentTab.selected
                                        if (selectedNode?.table != null) {
                                            com.multiviewer.ui.TableView(currentTab.file, selectedNode.table!!)
                                        } else {
                                            com.multiviewer.ui.FieldPanel(selectedNode)
                                        }
                                    }
                                }
                                DraggableDivider(
                                    orientation = Orientation.Horizontal,
                                    containerSizePx = columnHeightPx,
                                    tabKey = currentTab,
                                    getSplit = { currentTab.horizontalSplit },
                                    setSplit = { currentTab.horizontalSplit = it },
                                )
                                Column(modifier = Modifier.weight(1f - currentTab.horizontalSplit).fillMaxWidth()) {
                                    HexView(
                                        file = currentTab.file,
                                        highlightRange = currentTab.selected?.let { it.offset until (it.offset + it.size) },
                                        listState = hexListState,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
```

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
- Dragging the vertical divider (between tree and detail panel) resizes both columns smoothly, with the 1dp line tracking the cursor.
- Dragging the horizontal divider (between the top row and the hex dump) resizes both regions smoothly.
- Dragging either divider all the way in one direction stops shrinking/growing once a panel would go below ~15% or above ~85% of its container — it does not let a panel disappear or fully swallow its neighbor.
- Opening a second file (new tab) starts at the default ratios (50/50 vertical, ~77/23 horizontal) regardless of how the first tab's dividers were adjusted.
- Switching back to the first tab restores its own previously-dragged ratios.
- Resizing the app window keeps each panel's proportion stable (no snapping or jumping).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/Main.kt
git commit -m "feat(ui): make tree/detail and top/hex-dump dividers draggable"
```
