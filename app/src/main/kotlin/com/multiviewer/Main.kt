package com.multiviewer

import androidx.compose.foundation.LocalScrollbarStyle
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyShortcut
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.MenuBar
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.multiviewer.parser.EmbeddedVideo
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

private fun extractVideoToFile(appState: AppState, tab: TabState, video: EmbeddedVideo, fileNameSuffix: String) {
    val dialog = FileDialog(null as Frame?, "Save extracted video", FileDialog.SAVE)
    dialog.file = "${tab.file.nameWithoutExtension}_$fileNameSuffix.${video.extension}"
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

private fun extractMotionPhotoVideo(appState: AppState, tab: TabState) {
    val video = tab.embeddedVideo ?: return
    extractVideoToFile(appState, tab, video, "motion")
}

private fun extractMotionPhotoPreviewVideo(appState: AppState, tab: TabState) {
    val video = tab.motionPhotoPreview ?: return
    extractVideoToFile(appState, tab, video, "preview")
}

fun main() = application {
    val appState = remember { AppState() }
    
    // Log environment info for native library troubleshooting
    LaunchedEffect(Unit) {
        println("Starting unwrapMedia...")
        println("OS: ${System.getProperty("os.name")} (${System.getProperty("os.arch")})")
        println("Java Home: ${System.getProperty("java.home")}")
    }

    val windowState = rememberWindowState(
        position = WindowPosition(Alignment.Center),
        // 1366x768 is still a common laptop resolution (notably on Windows) -- 1280x800 leaves
        // room for the taskbar/title bar instead of the window opening larger than the screen.
        size = DpSize(1280.dp, 800.dp),
    )
    Window(onCloseRequest = ::exitApplication, title = "unwrapMedia", state = windowState) {
        MenuBar {
            Menu("File") {
                Item("Open", shortcut = KeyShortcut(Key.O, meta = true), onClick = { showOpenFileDialog(appState) })
                Item("Close", enabled = appState.tabs.isNotEmpty(), shortcut = KeyShortcut(Key.W, meta = true), onClick = { appState.closeTab(appState.selectedTabIndex) })
            }
            Menu("모션포토") {
                val currentTab = appState.tabs.getOrNull(appState.selectedTabIndex)
                Item(
                    "모션포토 동영상 추출",
                    enabled = currentTab?.embeddedVideo != null,
                    onClick = { currentTab?.let { extractMotionPhotoVideo(appState, it) } },
                )
                Item(
                    "모션포토 미리보기 재생용 비디오 추출",
                    enabled = currentTab?.motionPhotoPreview != null,
                    onClick = { currentTab?.let { extractMotionPhotoPreviewVideo(appState, it) } },
                )
            }
        }

        LaunchedEffect(Unit) {
            // Compose Desktop renders into a deeply-nested Skiko SkiaLayer several levels below
            // `window` (window -> JRootPane -> JLayeredPane -> ... -> SkiaLayer) -- that SkiaLayer
            // is the only real heavyweight/native surface actually receiving OS drag events.
            // Attaching a DropTarget to `window` or `window.contentPane` alone never sees a drag
            // at all (confirmed: dragEnter never fired). Attaching recursively to every component
            // in the tree reaches the SkiaLayer regardless of Compose Desktop's internal structure,
            // without depending on that structure by name/type.
            val listener = object : DropTargetAdapter() {
                override fun dragEnter(dtde: java.awt.dnd.DropTargetDragEvent) {
                    if (dtde.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) dtde.acceptDrag(DnDConstants.ACTION_COPY) else dtde.rejectDrag()
                }
                override fun dragOver(dtde: java.awt.dnd.DropTargetDragEvent) {
                    if (dtde.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) dtde.acceptDrag(DnDConstants.ACTION_COPY) else dtde.rejectDrag()
                }
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
            }

            fun attachRecursively(component: java.awt.Component) {
                component.dropTarget = DropTarget(component, listener)
                if (component is java.awt.Container) {
                    for (child in component.components) attachRecursively(child)
                }
            }
            attachRecursively(window)
        }

        MaterialTheme(colorScheme = darkColorScheme(background = AppColors.Background), typography = AppTypography) {
          CompositionLocalProvider(LocalScrollbarStyle provides AppScrollbarStyle) {
            appState.pendingRawPixelFile?.let { pendingFile ->
                RawPixelOpenDialog(
                    file = pendingFile,
                    onConfirm = { width, height, format, byteOrder, fps -> appState.confirmRawPixelFile(width, height, format, byteOrder, fps) },
                    onCancel = { appState.cancelRawPixelFile() },
                )
            }
            // Blocking popup for openFile() outright refusals (unsupported extension, or a
            // declared resolution above the hard limit) -- see AppState.openFile.
            appState.openFileError?.let { message ->
                Dialog(onDismissRequest = { appState.openFileError = null }) {
                    Column(
                        modifier = Modifier
                            .width(400.dp)
                            .background(AppColors.Surface, RoundedCornerShape(8.dp))
                            .border(1.dp, AppColors.NeonRed, RoundedCornerShape(8.dp))
                            .padding(20.dp),
                    ) {
                        Text("파일을 열 수 없습니다", style = AppTypography.headlineSmall, color = AppColors.NeonRed)
                        Spacer(Modifier.height(12.dp))
                        Text(message, style = AppTypography.labelLarge.copy(fontSize = 12.sp, color = AppColors.TextPrimary))
                        Spacer(Modifier.height(20.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            Button(onClick = { appState.openFileError = null }) { Text("확인") }
                        }
                    }
                }
            }
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
                                    onSelect = {
                                        currentTab.selected = it
                                        currentTab.selectedFrame = null
                                    },
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

                        if (currentTab.isLoading) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                DecodingIndicator("${currentTab.file.name} 분석 중...")
                            }
                        } else {
                            when (currentTab.type) {
                                MediaType.IMAGE -> ImageInspectorUI(appState, currentTab, leftPanel, bottomPanel)
                                MediaType.VIDEO -> VideoInspectorUI(appState, currentTab, leftPanel, bottomPanel)
                                MediaType.RAW_PIXEL -> RawPixelInspectorUI(appState, currentTab, leftPanel, bottomPanel)
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
    }
}
