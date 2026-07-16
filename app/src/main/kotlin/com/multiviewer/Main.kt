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
