# Motion Photo Menu + Video Extraction Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the "Open File" button with a native menu bar (`File`: Open/Close, `MotionPhoto`: 동영상 추출), and let the user extract a Motion Photo's embedded video to a standalone `.mp4`/`.mov` file at a path they choose.

**Architecture:** A new pure-parsing file locates the embedded video's byte range and container format from the already-parsed box tree (no new byte-level decoding); `AppState`/`Main.kt` wire that into a native `MenuBar` and an empty-state click target, replacing the old button.

**Tech Stack:** Kotlin 2.0.21, Compose Multiplatform Desktop 1.7.3 (`androidx.compose.ui.window.MenuBar`), `kotlin.test`.

## Global Constraints

- No dedicated tab for motion photo extraction — `MotionPhoto > 동영상 추출` goes directly to a save dialog.
- No video preview/playback.
- No keyboard shortcut on `MotionPhoto > 동영상 추출` (click-only). `File > Open` gets ⌘O, `File > Close` gets ⌘W.
- No change to Media Summary / Structure Analyser content, or the 2-file-open cap logic.
- The per-tab "✕" close button stays exactly as-is, coexisting with `File > Close`.
- Default save filename: `<original-name-without-extension>_motion.<ext>`.
- Extension rule: the embedded video's own `ftyp.major_brand` — `"qt  "` (trimmed: `"qt"`) → `.mov`, anything else → `.mp4`.

---

### Task 1: Motion photo detection and extraction (`MotionPhotoExtractor.kt`)

**Files:**
- Create: `app/src/main/kotlin/com/multiviewer/parser/MotionPhotoExtractor.kt`
- Modify: `app/src/main/kotlin/com/multiviewer/parser/MediaSummaryBuilder.kt:25` (remove `private` from `findFirst` so this new file can reuse it)
- Test: `app/src/test/kotlin/com/multiviewer/parser/MotionPhotoExtractorTest.kt`

**Interfaces:**
- Consumes: `BoxNode` (existing, `com.multiviewer.parser.BoxNode` — has `type`, `offset`, `headerSize`, `size`, `children`, `fields`), `BoxField` (`name`, `value`), `ByteReader.open(file: File): ByteReader` and `ByteReader.readBytes(offset: Long, len: Int): ByteArray` (existing), and `findFirst(node: BoxNode, predicate: (BoxNode) -> Boolean): BoxNode?` (existing, made non-private by this task).
- Produces: `data class EmbeddedVideo(val start: Long, val end: Long, val extension: String)`, `fun findEmbeddedVideo(root: BoxNode): EmbeddedVideo?`, `fun extractEmbeddedVideo(source: File, video: EmbeddedVideo, destination: File)` — Task 2 wires all three into `AppState`/`Main.kt`.

- [ ] **Step 1: Remove `private` from `findFirst`**

In `app/src/main/kotlin/com/multiviewer/parser/MediaSummaryBuilder.kt`, change line 25 from:

```kotlin
private fun findFirst(node: BoxNode, predicate: (BoxNode) -> Boolean): BoxNode? {
```

to:

```kotlin
fun findFirst(node: BoxNode, predicate: (BoxNode) -> Boolean): BoxNode? {
```

Run `./gradlew test --console=plain` to confirm this alone doesn't break anything (it shouldn't — it's a visibility widening).

- [ ] **Step 2: Write the failing tests**

Create `app/src/test/kotlin/com/multiviewer/parser/MotionPhotoExtractorTest.kt`:

```kotlin
package com.multiviewer.parser

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class MotionPhotoExtractorTest {
    @Test
    fun `finds embedded video via a top-level mpvd box and detects mp4 from major_brand`() {
        val nestedFtyp = BoxNode(
            type = "ftyp", offset = 24, headerSize = 8, size = 16,
            fields = listOf(BoxField("major_brand", "isom", 24, 4)),
        )
        val mpvd = BoxNode(type = "mpvd", offset = 16, headerSize = 8, size = 24, children = listOf(nestedFtyp))
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = 40, children = listOf(mpvd))

        val video = findEmbeddedVideo(root)

        assertEquals(24L, video?.start)
        assertEquals(40L, video?.end)
        assertEquals("mp4", video?.extension)
    }

    @Test
    fun `finds embedded video via a sefd field whose bytes were sniffed as an embedded MP4, and detects mov from major_brand`() {
        val nestedFtyp = BoxNode(
            type = "ftyp", offset = 104, headerSize = 8, size = 16,
            fields = listOf(BoxField("major_brand", "qt  ", 104, 4)),
        )
        val videoField = BoxNode(
            type = "MotionPhoto_Video", offset = 100, headerSize = 4, size = 20,
            children = listOf(nestedFtyp),
        )
        val sefd = BoxNode(type = "sefd", offset = 50, headerSize = 0, size = 70, children = listOf(videoField))
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = 120, children = listOf(sefd))

        val video = findEmbeddedVideo(root)

        assertEquals(104L, video?.start)
        assertEquals(120L, video?.end)
        assertEquals("mov", video?.extension)
    }

    @Test
    fun `returns null when neither mpvd nor a video-bearing sefd field is present`() {
        val ftyp = BoxNode(type = "ftyp", offset = 0, headerSize = 8, size = 16)
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = 16, children = listOf(ftyp))

        assertEquals(null, findEmbeddedVideo(root))
    }

    @Test
    fun `extractEmbeddedVideo copies exactly the requested byte range to the destination file`() {
        val sourceBytes = ByteArray(50) { it.toByte() }
        val source = File.createTempFile("motion-photo-extract-source", ".bin")
        source.deleteOnExit()
        source.writeBytes(sourceBytes)
        val destination = File.createTempFile("motion-photo-extract-dest", ".mp4")
        destination.deleteOnExit()

        extractEmbeddedVideo(source, EmbeddedVideo(start = 10, end = 30, extension = "mp4"), destination)

        assertContentEquals(sourceBytes.copyOfRange(10, 30), destination.readBytes())
    }

    @Test
    fun `extractEmbeddedVideo correctly copies a range spanning multiple 1MB chunks`() {
        val size = 3 * (1 shl 20) + 12345
        val sourceBytes = ByteArray(size) { (it % 256).toByte() }
        val source = File.createTempFile("motion-photo-extract-source-large", ".bin")
        source.deleteOnExit()
        source.writeBytes(sourceBytes)
        val destination = File.createTempFile("motion-photo-extract-dest-large", ".mp4")
        destination.deleteOnExit()

        extractEmbeddedVideo(source, EmbeddedVideo(start = 0, end = size.toLong(), extension = "mp4"), destination)

        assertContentEquals(sourceBytes, destination.readBytes())
    }
}
```

- [ ] **Step 3: Run the tests to verify they fail**

Run: `./gradlew test --tests "com.multiviewer.parser.MotionPhotoExtractorTest"`
Expected: FAIL with "Unresolved reference: findEmbeddedVideo" (and `EmbeddedVideo`, `extractEmbeddedVideo`)

- [ ] **Step 4: Implement `MotionPhotoExtractor.kt`**

Create `app/src/main/kotlin/com/multiviewer/parser/MotionPhotoExtractor.kt`:

```kotlin
package com.multiviewer.parser

import java.io.File

data class EmbeddedVideo(val start: Long, val end: Long, val extension: String)

fun findEmbeddedVideo(root: BoxNode): EmbeddedVideo? {
    val videoNode = root.children.find { it.type == "mpvd" }
        ?: findFirst(root) { it.type == "sefd" }
            ?.children
            ?.find { it.children.firstOrNull()?.type == "ftyp" }
        ?: return null
    val majorBrand = videoNode.children.find { it.type == "ftyp" }
        ?.fields?.find { it.name == "major_brand" }?.value
    val extension = if (majorBrand?.trim() == "qt") "mov" else "mp4"
    return EmbeddedVideo(videoNode.offset + videoNode.headerSize, videoNode.offset + videoNode.size, extension)
}

fun extractEmbeddedVideo(source: File, video: EmbeddedVideo, destination: File) {
    val chunkSizeLimit = 1L shl 20 // 1 MB
    ByteReader.open(source).use { reader ->
        destination.outputStream().use { out ->
            var offset = video.start
            while (offset < video.end) {
                val chunkSize = minOf(chunkSizeLimit, video.end - offset).toInt()
                out.write(reader.readBytes(offset, chunkSize))
                offset += chunkSize
            }
        }
    }
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew test --tests "com.multiviewer.parser.MotionPhotoExtractorTest"`
Expected: PASS (5 tests)

- [ ] **Step 6: Run the full suite**

Run: `./gradlew test --console=plain`
Expected: BUILD SUCCESSFUL, all tests pass (133 existing + 5 new = 138)

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/parser/MotionPhotoExtractor.kt \
        app/src/main/kotlin/com/multiviewer/parser/MediaSummaryBuilder.kt \
        app/src/test/kotlin/com/multiviewer/parser/MotionPhotoExtractorTest.kt
git commit -m "feat: detect and extract a motion photo's embedded video"
```

---

### Task 2: Menu bar, empty state, and `statusMessage` wiring

**Files:**
- Modify: `app/src/main/kotlin/com/multiviewer/ui/AppState.kt`
- Modify: `app/src/main/kotlin/com/multiviewer/Main.kt`
- Test: `app/src/test/kotlin/com/multiviewer/ui/AppStateTest.kt`

**Interfaces:**
- Consumes (from Task 1): `data class EmbeddedVideo(val start: Long, val end: Long, val extension: String)`, `fun findEmbeddedVideo(root: BoxNode): EmbeddedVideo?`, `fun extractEmbeddedVideo(source: File, video: EmbeddedVideo, destination: File)` — all in package `com.multiviewer.parser`.
- Produces: `TabState.embeddedVideo: EmbeddedVideo?`, `AppState.statusMessage: String?` (renamed from `AppState.openError`) — both are Compose state read directly by `Main.kt`.

- [ ] **Step 1: Write the failing tests**

In `app/src/test/kotlin/com/multiviewer/ui/AppStateTest.kt`, first update the two existing tests that reference `openError` to use the new name `statusMessage` (this is a pure rename, not new behavior — do this before adding new tests so the whole file compiles against the renamed field):

```kotlin
    @Test
    fun `openFile rejects a third distinct file and sets statusMessage`() {
        val appState = AppState()
        val file1 = tempFile("appstate-test-1")
        val file2 = tempFile("appstate-test-2")
        val file3 = tempFile("appstate-test-3")

        appState.openFile(file1)
        appState.openFile(file2)
        assertEquals(2, appState.tabs.size)
        assertEquals(null, appState.statusMessage)

        appState.openFile(file3)
        assertEquals(2, appState.tabs.size)
        assertEquals("You can only have 2 files open at a time.", appState.statusMessage)
    }

    @Test
    fun `openFile re-opening an already-open file switches to it without being rejected`() {
        val appState = AppState()
        val file1 = tempFile("appstate-test-a")
        val file2 = tempFile("appstate-test-b")

        appState.openFile(file1)
        appState.openFile(file2)
        appState.openFile(file1)

        assertEquals(2, appState.tabs.size)
        assertEquals(0, appState.selectedTabIndex)
        assertEquals(null, appState.statusMessage)
    }
```

(Every other existing test in this file is unaffected — leave them exactly as they are.)

Then add these two new tests to the same file (add the `File.createTempFile` helper usage exactly as shown — these write small real ISOBMFF-shaped byte sequences to disk so `AppState.openFile` exercises the real `parseFile` + `findEmbeddedVideo` path end to end, not a mocked one):

```kotlin
    @Test
    fun `openFile populates embeddedVideo when the file contains a top-level mpvd box`() {
        // A minimal ISOBMFF file: a top-level ftyp box, followed by an mpvd box
        // whose payload is itself a single nested ftyp box (major_brand "isom").
        val bytes = byteArrayOf(
            0x00, 0x00, 0x00, 0x10, 'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte(),
            'i'.code.toByte(), 's'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte(), 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x18, 'm'.code.toByte(), 'p'.code.toByte(), 'v'.code.toByte(), 'd'.code.toByte(),
            0x00, 0x00, 0x00, 0x10, 'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte(),
            'i'.code.toByte(), 's'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte(), 0x00, 0x00, 0x00, 0x00,
        )
        val file = File.createTempFile("appstate-motion-photo", ".bin")
        file.deleteOnExit()
        file.writeBytes(bytes)

        val appState = AppState()
        appState.openFile(file)

        val tab = appState.tabs.single()
        assertEquals(null, tab.error)
        assertEquals(24L, tab.embeddedVideo?.start)
        assertEquals(40L, tab.embeddedVideo?.end)
        assertEquals("mp4", tab.embeddedVideo?.extension)
    }

    @Test
    fun `openFile leaves embeddedVideo null when the file has no embedded video`() {
        val bytes = byteArrayOf(
            0x00, 0x00, 0x00, 0x10, 'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte(),
            'i'.code.toByte(), 's'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte(), 0x00, 0x00, 0x00, 0x00,
        )
        val file = File.createTempFile("appstate-no-motion-photo", ".bin")
        file.deleteOnExit()
        file.writeBytes(bytes)

        val appState = AppState()
        appState.openFile(file)

        val tab = appState.tabs.single()
        assertEquals(null, tab.error)
        assertEquals(null, tab.embeddedVideo)
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests "com.multiviewer.ui.AppStateTest"`
Expected: FAIL — `statusMessage`/`embeddedVideo` unresolved references (the two renamed tests and two new tests fail to compile until Step 3 is done).

- [ ] **Step 3: Update `AppState.kt`**

Replace the full contents of `app/src/main/kotlin/com/multiviewer/ui/AppState.kt` with:

```kotlin
package com.multiviewer.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.multiviewer.parser.BoxNode
import com.multiviewer.parser.EmbeddedVideo
import com.multiviewer.parser.MediaSummary
import com.multiviewer.parser.buildMediaSummary
import com.multiviewer.parser.findEmbeddedVideo
import com.multiviewer.parser.parseFile
import java.io.File

private const val MAX_OPEN_FILES = 2

class TabState(val file: File) {
    var root: BoxNode? by mutableStateOf(null)
    var mediaSummary: MediaSummary? by mutableStateOf(null)
    var embeddedVideo: EmbeddedVideo? by mutableStateOf(null)
    var error: String? by mutableStateOf(null)
    var selected: BoxNode? by mutableStateOf(null)
    var verticalSplit: Float by mutableStateOf(0.5f)
    var horizontalSplit: Float by mutableStateOf(1f / 1.3f)
    var summaryTabIndex: Int by mutableStateOf(0)
}

class AppState {
    val tabs = mutableStateListOf<TabState>()
    var selectedTabIndex by mutableStateOf(0)
    var statusMessage: String? by mutableStateOf(null)

    fun openFile(file: File) {
        val existingIndex = tabs.indexOfFirst { it.file.absolutePath == file.absolutePath }
        if (existingIndex >= 0) {
            selectedTabIndex = existingIndex
            statusMessage = null
            return
        }
        if (tabs.size >= MAX_OPEN_FILES) {
            statusMessage = "You can only have $MAX_OPEN_FILES files open at a time."
            return
        }
        statusMessage = null
        val tab = TabState(file)
        tabs.add(tab)
        selectedTabIndex = tabs.size - 1
        try {
            val root = parseFile(file)
            tab.root = root
            tab.mediaSummary = try {
                buildMediaSummary(root, file)
            } catch (e: Exception) {
                null
            }
            tab.embeddedVideo = try {
                findEmbeddedVideo(root)
            } catch (e: Exception) {
                null
            }
        } catch (e: Exception) {
            tab.error = e.message ?: "Failed to open file"
        }
    }

    fun closeTab(index: Int) {
        statusMessage = null
        tabs.removeAt(index)
        selectedTabIndex = when {
            tabs.isEmpty() -> 0
            index < selectedTabIndex -> selectedTabIndex - 1
            index == selectedTabIndex -> index.coerceAtMost(tabs.size - 1)
            else -> selectedTabIndex
        }
    }
}
```

- [ ] **Step 4: Run the `AppStateTest` tests to verify they pass**

Run: `./gradlew test --tests "com.multiviewer.ui.AppStateTest"`
Expected: PASS (9 tests: 7 existing + 2 new)

- [ ] **Step 5: Update `Main.kt`**

Replace the full contents of `app/src/main/kotlin/com/multiviewer/Main.kt` with:

```kotlin
package com.multiviewer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyShortcut
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.MenuBar
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.multiviewer.parser.extractEmbeddedVideo
import com.multiviewer.ui.AppState
import com.multiviewer.ui.BoxTreeView
import com.multiviewer.ui.HexView
import com.multiviewer.ui.TabState
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

private fun showOpenFileDialog(appState: AppState) {
    val dialog = FileDialog(null as Frame?, "Open file", FileDialog.LOAD)
    dialog.isVisible = true
    val fileName = dialog.file
    val directory = dialog.directory
    if (fileName != null && directory != null) {
        appState.openFile(File(directory, fileName))
    }
}

private fun extractMotionPhotoVideo(appState: AppState, tab: TabState) {
    val video = tab.embeddedVideo ?: return
    val dialog = FileDialog(null as Frame?, "Save extracted video", FileDialog.SAVE)
    dialog.file = "${tab.file.nameWithoutExtension}_motion.${video.extension}"
    dialog.isVisible = true
    val fileName = dialog.file
    val directory = dialog.directory
    if (fileName == null || directory == null) return
    val destination = File(directory, fileName)
    appState.statusMessage = try {
        extractEmbeddedVideo(tab.file, video, destination)
        "Saved to ${destination.name}"
    } catch (e: Exception) {
        "Failed to save: ${e.message}"
    }
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
        MenuBar {
            Menu("File") {
                Item(
                    "Open",
                    shortcut = KeyShortcut(Key.O, meta = true),
                    onClick = { showOpenFileDialog(appState) },
                )
                Item(
                    "Close",
                    enabled = appState.tabs.isNotEmpty(),
                    shortcut = KeyShortcut(Key.W, meta = true),
                    onClick = { appState.closeTab(appState.selectedTabIndex) },
                )
            }
            Menu("MotionPhoto") {
                val currentTab = appState.tabs.getOrNull(appState.selectedTabIndex)
                Item(
                    "동영상 추출",
                    enabled = currentTab?.embeddedVideo != null,
                    onClick = { currentTab?.let { extractMotionPhotoVideo(appState, it) } },
                )
            }
        }

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
                if (appState.tabs.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize().clickable { showOpenFileDialog(appState) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("📂 Open File", fontSize = 24.sp)
                    }
                } else {
                    appState.statusMessage?.let { message ->
                        Text(message, modifier = Modifier.padding(8.dp))
                    }

                    TabRow(selectedTabIndex = appState.selectedTabIndex) {
                        appState.tabs.forEachIndexed { index, tab ->
                            Tab(
                                selected = index == appState.selectedTabIndex,
                                onClick = { appState.selectedTabIndex = index },
                                text = {
                                    Row {
                                        Text(tab.file.name)
                                        Text(
                                            "✕",
                                            modifier = Modifier
                                                .padding(start = 8.dp)
                                                .clickable { appState.closeTab(index) },
                                        )
                                    }
                                },
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
                            TabRow(selectedTabIndex = currentTab.summaryTabIndex) {
                                Tab(
                                    selected = currentTab.summaryTabIndex == 0,
                                    onClick = { currentTab.summaryTabIndex = 0 },
                                    text = { Text("Media Summary") },
                                )
                                Tab(
                                    selected = currentTab.summaryTabIndex == 1,
                                    onClick = { currentTab.summaryTabIndex = 1 },
                                    text = { Text("Structure Analyser") },
                                )
                            }
                            if (currentTab.summaryTabIndex == 0) {
                                com.multiviewer.ui.MediaSummaryView(currentTab.mediaSummary)
                            } else {
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
}
```

- [ ] **Step 6: Run the full suite**

Run: `./gradlew test --console=plain`
Expected: BUILD SUCCESSFUL, all tests pass (138 existing + 2 new = 140)

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/com/multiviewer/ui/AppState.kt \
        app/src/main/kotlin/com/multiviewer/Main.kt \
        app/src/test/kotlin/com/multiviewer/ui/AppStateTest.kt
git commit -m "feat: add native menu bar (File, MotionPhoto) and empty-state open affordance"
```

- [ ] **Step 8: Manual verification (this app's established pattern for UI-level changes)**

Launch the app (`./gradlew :app:run`) and confirm:
- Empty state shows the centered "📂 Open File" text (no button); clicking it opens the file picker.
- `File > Open` (⌘O) opens a file; `File > Close` (⌘W) closes the selected tab; both work identically to the removed button and the existing "✕".
- Opening a real Samsung Motion Photo file enables `MotionPhoto > 동영상 추출`; clicking it opens a save dialog defaulting to `<name>_motion.mp4` (or `.mov` if the embedded video is QuickTime-branded), and the resulting file plays correctly and matches the embedded video.
- Opening a non-Motion-Photo file leaves `MotionPhoto > 동영상 추출` disabled (greyed out).
- Attempting to open a 3rd distinct file still shows the "You can only have 2 files open at a time." message (now under `statusMessage`).
