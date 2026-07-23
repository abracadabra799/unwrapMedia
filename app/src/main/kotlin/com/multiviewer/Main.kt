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
        "Failed to save: ${e.message ?: e.toString()}"
    }
}

private fun extractMotionPhotoPreview(appState: AppState, tab: TabState) {
    val video = tab.motionPhotoPreview ?: return
    val dialog = FileDialog(null as Frame?, "Save extracted preview video", FileDialog.SAVE)
    dialog.file = "${tab.file.nameWithoutExtension}_preview.${video.extension}"
    dialog.isVisible = true
    val fileName = dialog.file
    val directory = dialog.directory
    if (fileName == null || directory == null) return
    val destination = File(directory, fileName)
    appState.statusMessage = try {
        extractEmbeddedVideo(tab.file, video, destination)
        "Saved to ${destination.name}"
    } catch (e: Exception) {
        "Failed to save: ${e.message ?: e.toString()}"
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

    Window(onCloseRequest = ::exitApplication, title = "unwrapMedia") {
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
                Item(
                    "미리보기 동영상 추출",
                    enabled = currentTab?.motionPhotoPreview != null,
                    onClick = { currentTab?.let { extractMotionPhotoPreview(appState, it) } },
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
