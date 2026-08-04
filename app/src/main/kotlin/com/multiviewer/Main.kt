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
import com.multiviewer.cli.runCheckCommand
import com.multiviewer.cli.runDumpCommand
import kotlin.system.exitProcess
import com.multiviewer.parser.EmbeddedVideo
import com.multiviewer.parser.extractEmbeddedVideo
import com.multiviewer.ui.*
import java.awt.EventQueue
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

// Video/audio track extraction (see TrackExtractor.kt) shells out to ffmpeg -- unlike
// extractVideoToFile's plain byte copy above, this can take real time on a large file, so it
// runs on a background thread instead of blocking the EDT while the user picked a save location.
private fun extractVideoTrackFromCurrentFile(appState: AppState, tab: TabState) {
    val dialog = FileDialog(null as Frame?, "Save extracted video track", FileDialog.SAVE)
    dialog.file = "${tab.file.nameWithoutExtension}_video.${tab.file.extension}"
    dialog.isVisible = true
    val fileName = dialog.file
    val directory = dialog.directory
    if (fileName == null || directory == null) return
    val destination = File(directory, fileName)
    appState.statusMessage = "비디오 트랙 추출 중..."
    runInBackground {
        val success = extractVideoTrack(tab.file, destination)
        EventQueue.invokeLater {
            appState.statusMessage = if (success) "저장됨: ${destination.name}" else "비디오 트랙 추출 실패"
        }
    }
}

private fun extractAudioTrackFromCurrentFile(appState: AppState, tab: TabState) {
    val dialog = FileDialog(null as Frame?, "Save extracted audio track", FileDialog.SAVE)
    dialog.file = "${tab.file.nameWithoutExtension}_audio.m4a"
    dialog.isVisible = true
    val fileName = dialog.file
    val directory = dialog.directory
    if (fileName == null || directory == null) return
    val destination = File(directory, fileName)
    appState.statusMessage = "오디오 트랙 추출 중..."
    runInBackground {
        val success = extractAudioTrack(tab.file, destination)
        EventQueue.invokeLater {
            appState.statusMessage = if (success) "저장됨: ${destination.name}" else "오디오 트랙 추출 실패"
        }
    }
}

fun main(args: Array<String>) {
    when (args.firstOrNull()) {
        "dump" -> exitProcess(runDumpCommand(args.drop(1)))
        "check" -> exitProcess(runCheckCommand(args.drop(1)))
        else -> runGuiApplication()
    }
}

private fun runGuiApplication() = application {
    val appState = remember { AppState() }
    
    // Log environment info for native library troubleshooting
    LaunchedEffect(Unit) {
        println("Starting unwrapMedia...")
        println("OS: ${System.getProperty("os.name")} (${System.getProperty("os.arch")})")
        println("Java Home: ${System.getProperty("java.home")}")
    }

    val windowState = rememberWindowState(
        position = WindowPosition(Alignment.Center),
        // 800 -> 560 (-30%): the center preview panel no longer shares its height with the
        // analysis summary (moved to DetailedPropertiesPanel's Overview tab), so the default
        // window doesn't need to be as tall -- still freely resizable larger when needed.
        size = DpSize(1280.dp, 560.dp),
    )
    Window(onCloseRequest = ::exitApplication, title = "unwrapMedia", state = windowState) {
        var themeMode by remember { mutableStateOf(loadThemeMode()) }
        var frameIntervalWindowOpen by remember { mutableStateOf(false) }
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
            Menu("비트스트림 추출") {
                val currentTab = appState.tabs.getOrNull(appState.selectedTabIndex)
                val isVideo = currentTab?.type == MediaType.VIDEO
                // "Video"/"Audio" summary sections are only built (buildVideoSummary,
                // MediaSummaryBuilder.kt) when the corresponding track actually exists in the
                // container -- this is set synchronously when the tab opens, so it doesn't need
                // to wait for the async per-stream codec-detail probe to know track presence.
                val hasVideoTrack = isVideo && currentTab?.mediaSummary?.sections?.any { it.title == "Video" } == true
                val hasAudioTrack = isVideo && currentTab?.mediaSummary?.sections?.any { it.title == "Audio" } == true
                Item(
                    "비디오 추출 (.mp4 or .mov etc)",
                    enabled = hasVideoTrack,
                    onClick = { currentTab?.let { extractVideoTrackFromCurrentFile(appState, it) } },
                )
                Item(
                    "오디오 추출 (.m4a)",
                    enabled = hasAudioTrack,
                    onClick = { currentTab?.let { extractAudioTrackFromCurrentFile(appState, it) } },
                )
            }
            Menu("프레임 간격 분석") {
                val currentTab = appState.tabs.getOrNull(appState.selectedTabIndex)
                val isVideo = currentTab?.type == MediaType.VIDEO
                val hasVideoTrack = isVideo && currentTab?.mediaSummary?.sections?.any { it.title == "Video" } == true
                Item(
                    "프레임 간격 분석 보기",
                    enabled = hasVideoTrack,
                    onClick = { frameIntervalWindowOpen = true },
                )
            }
            Menu("보기") {
                CheckboxItem(
                    "라이트 테마",
                    checked = themeMode == ThemeMode.LIGHT,
                    onCheckedChange = { checked ->
                        themeMode = if (checked) ThemeMode.LIGHT else ThemeMode.DARK
                        saveThemeMode(themeMode)
                    },
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

        AppTheme(themeMode) {
          CompositionLocalProvider(LocalScrollbarStyle provides AppScrollbarStyle) {
            appState.pendingRawFileChoice?.let { pendingFile ->
                Dialog(onDismissRequest = { appState.cancelRawFileChoice() }) {
                    Column(
                        modifier = Modifier
                            .width(360.dp)
                            .background(AppColors.Surface, RoundedCornerShape(8.dp))
                            .border(1.dp, AppColors.Border, RoundedCornerShape(8.dp))
                            .padding(20.dp),
                    ) {
                        Text("파일 종류 선택", style = AppTypography.headlineSmall)
                        Spacer(Modifier.height(4.dp))
                        Text(pendingFile.name, style = AppTypography.labelLarge.copy(fontSize = 10.sp, color = AppColors.TextSecondary))
                        Spacer(Modifier.height(16.dp))
                        Text(
                            ".raw 파일은 이미지 또는 오디오일 수 있습니다. 어느 쪽인가요?",
                            style = AppTypography.labelLarge.copy(fontSize = 12.sp, color = AppColors.TextPrimary),
                        )
                        Spacer(Modifier.height(20.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = { appState.cancelRawFileChoice() }) { Text("취소") }
                            Spacer(Modifier.width(8.dp))
                            Button(onClick = { appState.chooseRawFileAsAudio() }) { Text("오디오") }
                            Spacer(Modifier.width(8.dp))
                            Button(onClick = { appState.chooseRawFileAsPixel() }) { Text("이미지") }
                        }
                    }
                }
            }
            appState.pendingRawPixelFile?.let { pendingFile ->
                RawPixelOpenDialog(
                    file = pendingFile,
                    onConfirm = { width, height, format, byteOrder, fps -> appState.confirmRawPixelFile(width, height, format, byteOrder, fps) },
                    onCancel = { appState.cancelRawPixelFile() },
                )
            }
            appState.pendingRawAudioFile?.let { pendingFile ->
                RawAudioOpenDialog(
                    file = pendingFile,
                    onConfirm = { params -> appState.confirmRawAudioFile(params) },
                    onCancel = { appState.cancelRawAudioFile() },
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
            if (frameIntervalWindowOpen) {
                val currentTab = appState.tabs.getOrNull(appState.selectedTabIndex)
                if (currentTab != null) {
                    FrameIntervalAnalysisWindow(appState = appState, tab = currentTab, onCloseRequest = { frameIntervalWindowOpen = false })
                } else {
                    frameIntervalWindowOpen = false
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
                        // A field selected on a PREVIOUSLY-selected node is stale once the tree
                        // selection moves on -- membership-checking against the current node's own
                        // field list here means Main.kt never has to hunt down and reset
                        // selectedField at every place currentTab.selected gets reassigned.
                        val activeField = currentTab.selected?.fields?.let { fields ->
                            currentTab.selectedField?.takeIf { it in fields }
                        }

                        LaunchedEffect(currentTab.selected, currentTab.selectedField) {
                            val field = activeField
                            if (field != null) {
                                hexListState.scrollToItem((field.offset / BYTES_PER_ROW).toInt())
                            } else {
                                currentTab.selected?.let {
                                    hexListState.scrollToItem((it.offset / BYTES_PER_ROW).toInt())
                                }
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
                                highlightRange = activeField?.let { it.offset until (it.offset + it.length) }
                                    ?: currentTab.selected?.let { it.offset until (it.offset + it.size) },
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
                                MediaType.AUDIO -> AudioInspectorUI(appState, currentTab, leftPanel, bottomPanel)
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
