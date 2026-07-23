package com.multiviewer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyShortcut
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.MenuBar
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.multiviewer.parser.extractEmbeddedVideo
import com.multiviewer.ui.*
import java.awt.FileDialog
import java.awt.Frame
import java.awt.datatransfer.DataFlavor
import java.awt.dnd.DnDConstants
import java.awt.dnd.DropTarget
import java.awt.dnd.DropTargetAdapter
import java.awt.dnd.DropTargetDropEvent
import java.io.File

private const val BYTES_PER_ROW = 16

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

fun main() = application {
    val appState = remember { AppState() }

    Window(onCloseRequest = ::exitApplication, title = "Modern Media Inspector") {
        MenuBar {
            Menu("File") {
                Item("Open", shortcut = KeyShortcut(Key.O, meta = true), onClick = { showOpenFileDialog(appState) })
                Item("Close", enabled = appState.tabs.isNotEmpty(), shortcut = KeyShortcut(Key.W, meta = true), onClick = { appState.closeTab(appState.selectedTabIndex) })
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

        MaterialTheme(colorScheme = darkColorScheme(background = AppColors.Background), typography = AppTypography) {
            Surface(modifier = Modifier.fillMaxSize(), color = AppColors.Background) {
                if (appState.tabs.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize().clickable { showOpenFileDialog(appState) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("📂 Drag & Drop or Click to Open", fontSize = 24.sp, color = AppColors.TextPrimary)
                    }
                } else {
                    Column {
                        TabRow(
                            selectedTabIndex = appState.selectedTabIndex,
                            containerColor = AppColors.Panel,
                            contentColor = AppColors.NeonBlue
                        ) {
                            appState.tabs.forEachIndexed { index, tab ->
                                Tab(
                                    selected = index == appState.selectedTabIndex,
                                    onClick = { appState.selectedTabIndex = index },
                                    text = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(tab.file.name, style = AppTypography.labelLarge)
                                            IconButton(onClick = { appState.closeTab(index) }, modifier = Modifier.size(24.dp)) {
                                                Text("✕", color = AppColors.NeonRed, fontSize = 10.sp)
                                            }
                                        }
                                    },
                                )
                            }
                        }

                        val currentTab = appState.tabs[appState.selectedTabIndex]
                        val hexListState = remember(currentTab) { androidx.compose.foundation.lazy.LazyListState() }

                        LaunchedEffect(currentTab.selected) {
                            currentTab.selected?.let {
                                hexListState.scrollToItem((it.offset / BYTES_PER_ROW).toInt())
                            }
                        }

                        val leftPanel: @Composable ColumnScope.() -> Unit = {
                            PanelHeader("Media Structure")
                            currentTab.root?.let { rootNode ->
                                BoxTreeView(
                                    root = rootNode,
                                    selected = currentTab.selected,
                                    onSelect = { currentTab.selected = it },
                                )
                            }
                        }

                        val bottomPanel: @Composable ColumnScope.() -> Unit = {
                            PanelHeader("Hex & Raw Data Viewer", color = AppColors.NeonGreen)
                            HexView(
                                file = currentTab.file,
                                highlightRange = currentTab.selected?.let { it.offset until (it.offset + it.size) },
                                listState = hexListState,
                            )
                        }

                        when (currentTab.type) {
                            MediaType.IMAGE -> ImageInspectorUI(currentTab, leftPanel, bottomPanel)
                            MediaType.VIDEO -> VideoInspectorUI(currentTab, leftPanel, bottomPanel)
                            else -> {
                                // Fallback to original structure view if needed
                                Text("Unsupported Format", modifier = Modifier.padding(16.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}
