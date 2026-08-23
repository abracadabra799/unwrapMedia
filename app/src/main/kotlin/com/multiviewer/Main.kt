package com.multiviewer

import androidx.compose.foundation.LocalScrollbarStyle
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
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
import com.multiviewer.parser.MotionPhotoBuilder
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
import javax.swing.JOptionPane

private const val BYTES_PER_ROW = 16

private fun showOpenFileDialog(appState: AppState) {
    val dialog = FileDialog(null as Frame?, "Open file(s)", FileDialog.LOAD)
    dialog.isMultipleMode = true
    dialog.isVisible = true
    val selectedFiles = dialog.files
    if (!selectedFiles.isNullOrEmpty()) {
        appState.openFiles(selectedFiles.toList())
    } else {
        val fileName = dialog.file
        val directory = dialog.directory
        if (fileName != null && directory != null) {
            appState.openFile(File(directory, fileName))
        }
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

private fun validateTabForMotionPhoto(
    tab: TabState,
    language: AppLanguage,
    version: com.multiviewer.parser.MotionPhotoFormatVersion,
): String? {
    if (tab.isLoading) {
        return I18n.errMotionPhotoImageLoading(language)
    }
    if (tab.error != null || tab.root == null || !tab.file.exists() || tab.file.length() <= 0L) {
        return I18n.errMotionPhotoInvalidImage(language)
    }
    if (tab.type != MediaType.IMAGE) {
        return I18n.errMotionPhotoUnsupportedImage(language)
    }
    if (tab.embeddedVideo != null || tab.motionPhotoPreview != null) {
        return I18n.errMotionPhotoAlreadyExists(language)
    }
    if (tab.gifAnimation != null) {
        return I18n.errMotionPhotoAnimatedGif(language)
    }
    val ext = tab.file.extension.lowercase(java.util.Locale.US)
    val isHeic = ext in setOf("heic", "heif")
    if (version == com.multiviewer.parser.MotionPhotoFormatVersion.V1_MICRO_VIDEO && isHeic) {
        return I18n.errMotionPhotoV1HeicNotSupported(language)
    }
    val supportedExts = setOf("jpg", "jpeg", "heic", "heif", "avif")
    if (ext !in supportedExts) {
        return I18n.errMotionPhotoUnsupportedImage(language)
    }
    return null
}

private fun createMotionPhotoFromCurrentTab(
    appState: AppState,
    tab: TabState,
    language: AppLanguage,
    version: com.multiviewer.parser.MotionPhotoFormatVersion = com.multiviewer.parser.MotionPhotoFormatVersion.V2_MOTION_PHOTO,
) {
    val validationError = validateTabForMotionPhoto(tab, language, version)
    if (validationError != null) {
        appState.statusMessage = I18n.toastMotionPhotoFailed(language, validationError)
        JOptionPane.showMessageDialog(
            null,
            validationError,
            I18n.dialogTitleMotionPhotoCannotCreate(language),
            JOptionPane.WARNING_MESSAGE,
        )
        return
    }

    val selectVideoTitle = if (language == AppLanguage.KO) "모션포토로 합성할 동영상 선택" else "Select video file to attach"
    val videoDialog = FileDialog(null as Frame?, selectVideoTitle, FileDialog.LOAD)
    videoDialog.isVisible = true
    val videoName = videoDialog.file
    val videoDir = videoDialog.directory
    if (videoName == null || videoDir == null) return
    val videoFile = File(videoDir, videoName)

    if (!videoFile.exists() || videoFile.length() <= 0L) {
        val err = I18n.errMotionPhotoInvalidVideo(language)
        appState.statusMessage = I18n.toastMotionPhotoFailed(language, err)
        JOptionPane.showMessageDialog(
            null,
            err,
            I18n.dialogTitleMotionPhotoCannotCreate(language),
            JOptionPane.WARNING_MESSAGE,
        )
        return
    }

    val isHeic = tab.file.extension.lowercase(java.util.Locale.US) in setOf("heic", "heif")
    val defaultExt = if (isHeic) "heic" else "jpg"
    val suffix = if (version == com.multiviewer.parser.MotionPhotoFormatVersion.V1_MICRO_VIDEO) "_microvideo" else "_motion"
    val saveTitle = if (language == AppLanguage.KO) "모션포토 저장" else "Save Motion Photo"
    val saveDialog = FileDialog(null as Frame?, saveTitle, FileDialog.SAVE)
    saveDialog.file = "${tab.file.nameWithoutExtension}$suffix.$defaultExt"
    saveDialog.isVisible = true
    val saveName = saveDialog.file
    val saveDir = saveDialog.directory
    if (saveName == null || saveDir == null) return
    val outputFile = File(saveDir, saveName)

    appState.statusMessage = I18n.toastCreatingMotionPhoto(language)
    runInBackground {
        try {
            com.multiviewer.parser.MotionPhotoBuilder.createMotionPhoto(tab.file, videoFile, outputFile, version)
            EventQueue.invokeLater {
                appState.statusMessage = I18n.toastMotionPhotoCreated(language, outputFile.name)
                appState.openFile(outputFile)
            }
        } catch (e: Exception) {
            val errDetail = e.message ?: e.toString()
            EventQueue.invokeLater {
                appState.statusMessage = I18n.toastMotionPhotoFailed(language, errDetail)
                JOptionPane.showMessageDialog(
                    null,
                    errDetail,
                    I18n.dialogTitleMotionPhotoCannotCreate(language),
                    JOptionPane.ERROR_MESSAGE,
                )
            }
        }
    }
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
    com.multiviewer.util.GlobalExceptionHandler.install()
    when (args.firstOrNull()) {
        "dump" -> exitProcess(runDumpCommand(args.drop(1)))
        "check" -> exitProcess(runCheckCommand(args.drop(1)))
        else -> runGuiApplication()
    }
}

private fun runGuiApplication() = application {
    val appState = remember { AppState() }
    
    // Log environment info for native library troubleshooting and initialize disk cache
    LaunchedEffect(Unit) {
        val cacheDir = File(System.getProperty("user.home"), ".unwrapMedia/cache")
        com.multiviewer.cache.MediaIndexCache.initialize(cacheDir)
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
        var themeMode by remember { mutableStateOf(loadThemeMode()) }
        var showPixelGrid by remember { mutableStateOf(loadShowPixelGrid()) }
        var language by remember { mutableStateOf(loadLanguage()) }
        var frameIntervalWindowOpen by remember { mutableStateOf(false) }
        var qualityCompareWindowOpen by remember { mutableStateOf(false) }
        var imageCompareWindowOpen by remember { mutableStateOf(false) }
        var motionPhotoFrameIntervalWindowOpen by remember { mutableStateOf(false) }
        var dumpStructureWindowOpen by remember { mutableStateOf(false) }
        var checkStructureWindowOpen by remember { mutableStateOf(false) }
        var aiPromptWindowOpen by remember { mutableStateOf(false) }
        // App-level, not per-tab -- matches showPixelGrid's own precedent (switching tabs keeps
        // whichever mode is checked; unlike a per-panel button, this is a "lens" the user turns on
        // rather than a per-video setting). null = neither mode active.
        var codecViewMode by remember { mutableStateOf<CodecViewMode?>(null) }
        MenuBar {
            Menu(I18n.menuFile(language)) {
                Item(I18n.menuOpen(language), shortcut = KeyShortcut(Key.O, meta = true), onClick = { showOpenFileDialog(appState) })
                Item(I18n.menuClose(language), enabled = appState.tabs.isNotEmpty(), shortcut = KeyShortcut(Key.W, meta = true), onClick = { appState.closeTab(appState.selectedTabIndex) })
            }
            Menu(I18n.menuAnalyze(language)) {
                val currentTab = appState.tabs.getOrNull(appState.selectedTabIndex)
                val hasActiveFile = currentTab != null && !currentTab.isLoading && currentTab.root != null
                Item(
                    I18n.menuDumpStructure(language),
                    enabled = hasActiveFile,
                    shortcut = KeyShortcut(Key.D, meta = true, shift = true),
                    onClick = { dumpStructureWindowOpen = true },
                )
                Item(
                    I18n.menuCheckStructure(language),
                    enabled = hasActiveFile,
                    shortcut = KeyShortcut(Key.C, meta = true, shift = true),
                    onClick = { checkStructureWindowOpen = true },
                )
                Separator()
                Item(
                    I18n.menuGenerateAiPrompt(language),
                    enabled = hasActiveFile,
                    shortcut = KeyShortcut(Key.P, meta = true, shift = true),
                    onClick = { aiPromptWindowOpen = true },
                )
                Item(
                    I18n.menuGenerateAiPromptAndCopy(language),
                    enabled = hasActiveFile,
                    shortcut = KeyShortcut(Key.P, meta = true, alt = true),
                    onClick = {
                        currentTab?.let { tab ->
                            val root = tab.root
                            if (root != null) {
                                val warnings = com.multiviewer.parser.collectWarnings(root)
                                val prompt = com.multiviewer.cli.AiDiagnosticPromptBuilder.buildPrompt(tab.file, root, warnings)
                                if (com.multiviewer.util.ClipboardUtil.copyToClipboard(prompt)) {
                                    appState.statusMessage = I18n.toastPromptCopied(language)
                                } else {
                                    appState.statusMessage = I18n.toastClipboardFailed(language)
                                }
                            }
                        }
                    },
                )
            }
            Menu(I18n.menuMotionPhoto(language)) {
                val currentTab = appState.tabs.getOrNull(appState.selectedTabIndex)
                val isImage = currentTab != null && !currentTab.isLoading && currentTab.type == MediaType.IMAGE
                val isHeic = currentTab?.file?.extension?.lowercase(java.util.Locale.US) in setOf("heic", "heif")
                Item(
                    I18n.menuCreateMotionPhotoV2(language),
                    enabled = isImage,
                    onClick = { currentTab?.let { createMotionPhotoFromCurrentTab(appState, it, language, com.multiviewer.parser.MotionPhotoFormatVersion.V2_MOTION_PHOTO) } },
                )
                Item(
                    I18n.menuCreateMotionPhotoV1(language),
                    enabled = isImage && !isHeic,
                    onClick = { currentTab?.let { createMotionPhotoFromCurrentTab(appState, it, language, com.multiviewer.parser.MotionPhotoFormatVersion.V1_MICRO_VIDEO) } },
                )
                Separator()
                Item(
                    I18n.menuExtractMotionVideo(language),
                    enabled = currentTab?.embeddedVideo != null,
                    onClick = { currentTab?.let { extractMotionPhotoVideo(appState, it) } },
                )
                Item(
                    I18n.menuExtractPreviewVideo(language),
                    enabled = currentTab?.motionPhotoPreview != null,
                    onClick = { currentTab?.let { extractMotionPhotoPreviewVideo(appState, it) } },
                )
                Item(
                    I18n.menuMotionFrameDropAnalysis(language),
                    enabled = currentTab?.embeddedVideo != null,
                    onClick = { motionPhotoFrameIntervalWindowOpen = true },
                )
            }
            Menu(I18n.menuBitstream(language)) {
                val currentTab = appState.tabs.getOrNull(appState.selectedTabIndex)
                val isVideo = currentTab?.type == MediaType.VIDEO
                // "Video"/"Audio" summary sections are only built (buildVideoSummary,
                // MediaSummaryBuilder.kt) when the corresponding track actually exists in the
                // container -- this is set synchronously when the tab opens, so it doesn't need
                // to wait for the async per-stream codec-detail probe to know track presence.
                val hasVideoTrack = isVideo && currentTab?.mediaSummary?.sections?.any { it.title == "Video" } == true
                val hasAudioTrack = isVideo && currentTab?.mediaSummary?.sections?.any { it.title == "Audio" } == true
                Item(
                    I18n.menuExtractVideoTrack(language),
                    enabled = hasVideoTrack,
                    onClick = { currentTab?.let { extractVideoTrackFromCurrentFile(appState, it) } },
                )
                Item(
                    I18n.menuExtractAudioTrack(language),
                    enabled = hasAudioTrack,
                    onClick = { currentTab?.let { extractAudioTrackFromCurrentFile(appState, it) } },
                )
            }
            Menu(I18n.menuFrameInterval(language)) {
                val currentTab = appState.tabs.getOrNull(appState.selectedTabIndex)
                val isVideo = currentTab?.type == MediaType.VIDEO
                val hasVideoTrack = isVideo && currentTab?.mediaSummary?.sections?.any { it.title == "Video" } == true
                Item(
                    I18n.menuViewFrameIntervals(language),
                    enabled = hasVideoTrack,
                    onClick = { frameIntervalWindowOpen = true },
                )
            }
            Menu(I18n.menuCompareAndAnalysis(language)) {
                Item(
                    I18n.menuCompareFiles(language),
                    shortcut = KeyShortcut(Key.D, meta = true),
                    onClick = { imageCompareWindowOpen = true },
                )
                Item(
                    I18n.menuQualityBenchmark(language),
                    onClick = { qualityCompareWindowOpen = true },
                )
            }
            Menu(I18n.menuView(language)) {
                CheckboxItem(
                    I18n.menuDarkTheme(language),
                    checked = themeMode == ThemeMode.DARK,
                    onCheckedChange = {
                        themeMode = ThemeMode.DARK
                        saveThemeMode(themeMode)
                    },
                )
                CheckboxItem(
                    I18n.menuLightTheme(language),
                    checked = themeMode == ThemeMode.LIGHT,
                    onCheckedChange = {
                        themeMode = ThemeMode.LIGHT
                        saveThemeMode(themeMode)
                    },
                )
                CheckboxItem(
                    I18n.menuPixelGrid(language),
                    checked = showPixelGrid,
                    onCheckedChange = {
                        showPixelGrid = it
                        saveShowPixelGrid(it)
                    },
                )
                val codecViewCurrentTab = appState.tabs.getOrNull(appState.selectedTabIndex)
                CheckboxItem(
                    I18n.menuMotionVectors(language),
                    checked = codecViewMode == CodecViewMode.MOTION_VECTORS,
                    enabled = codecViewCurrentTab?.type == MediaType.VIDEO &&
                        codecViewSupportedFor(CodecViewMode.MOTION_VECTORS, codecViewCurrentTab.videoCodecName),
                    onCheckedChange = { codecViewMode = if (it) CodecViewMode.MOTION_VECTORS else null },
                )
                CheckboxItem(
                    I18n.menuQpHeatmap(language),
                    checked = codecViewMode == CodecViewMode.QP_HEATMAP,
                    enabled = codecViewCurrentTab?.type == MediaType.VIDEO &&
                        codecViewSupportedFor(CodecViewMode.QP_HEATMAP, codecViewCurrentTab.videoCodecName),
                    onCheckedChange = { codecViewMode = if (it) CodecViewMode.QP_HEATMAP else null },
                )
                Separator()
                CheckboxItem(
                    I18n.menuKorean(language),
                    checked = language == AppLanguage.KO,
                    onCheckedChange = {
                        language = AppLanguage.KO
                        saveLanguage(language)
                    },
                )
                CheckboxItem(
                    I18n.menuEnglish(language),
                    checked = language == AppLanguage.EN,
                    onCheckedChange = {
                        language = AppLanguage.EN
                        saveLanguage(language)
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
                        if (files.isNotEmpty()) {
                            appState.openFiles(files)
                        }
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

        AppTheme(themeMode, showPixelGrid) {
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

            if (imageCompareWindowOpen) {
                ImageCompareWindow(appState = appState, language = language, onCloseRequest = { imageCompareWindowOpen = false })
            }
            if (qualityCompareWindowOpen) {
                QualityCompareWindow(onCloseRequest = { qualityCompareWindowOpen = false })
            }
            if (motionPhotoFrameIntervalWindowOpen) {
                val currentTab = appState.tabs.getOrNull(appState.selectedTabIndex)
                if (currentTab != null) {
                    MotionPhotoFrameIntervalAnalysisWindow(appState = appState, tab = currentTab, onCloseRequest = { motionPhotoFrameIntervalWindowOpen = false })
                } else {
                    motionPhotoFrameIntervalWindowOpen = false
                }
            }
            if (dumpStructureWindowOpen) {
                val currentTab = appState.tabs.getOrNull(appState.selectedTabIndex)
                if (currentTab != null) {
                    StructureDumpWindow(tab = currentTab, themeMode = themeMode, onCloseRequest = { dumpStructureWindowOpen = false })
                } else {
                    dumpStructureWindowOpen = false
                }
            }
            if (checkStructureWindowOpen) {
                val currentTab = appState.tabs.getOrNull(appState.selectedTabIndex)
                if (currentTab != null) {
                    StructureCheckWindow(tab = currentTab, themeMode = themeMode, onCloseRequest = { checkStructureWindowOpen = false })
                } else {
                    checkStructureWindowOpen = false
                }
            }
            if (aiPromptWindowOpen) {
                val currentTab = appState.tabs.getOrNull(appState.selectedTabIndex)
                if (currentTab != null) {
                    AiPromptPreviewWindow(tab = currentTab, themeMode = themeMode, onCloseRequest = { aiPromptWindowOpen = false })
                } else {
                    aiPromptWindowOpen = false
                }
            }
            appState.tabs.forEach { tab ->
                if (tab.fullSizeFramePreviewOpen) {
                    FrameFullSizePreviewWindow(tab, onCloseRequest = { tab.fullSizeFramePreviewOpen = false })
                }
                if (tab.isPrimaryImagePopupOpen) {
                    PrimaryImagePopupWindow(tab, onCloseRequest = { tab.isPrimaryImagePopupOpen = false })
                }
            }
            Surface(modifier = Modifier.fillMaxSize(), color = AppColors.Background) {
                if (appState.tabs.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize().clickable { showOpenFileDialog(appState) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(I18n.placeholderEmptyState(language), fontSize = 22.sp, color = AppColors.TextPrimary)
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

                        LaunchedEffect(currentTab.selected, currentTab.selectedField, currentTab.tileHighlightRange, currentTab.selectedFrame, currentTab.parameterSetHighlightRange) {
                            val paramRange = currentTab.parameterSetHighlightRange
                            val tileRange = currentTab.tileHighlightRange
                            val field = activeField
                            val frameOffset = currentTab.selectedFrame?.byteOffset
                            when {
                                paramRange != null -> hexListState.scrollToItem((paramRange.first / BYTES_PER_ROW).toInt())
                                tileRange != null -> hexListState.scrollToItem((tileRange.first / BYTES_PER_ROW).toInt())
                                field != null -> hexListState.scrollToItem((field.offset / BYTES_PER_ROW).toInt())
                                frameOffset != null -> hexListState.scrollToItem((frameOffset / BYTES_PER_ROW).toInt())
                                else -> currentTab.selected?.let {
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

                        // Codec-view preview (motion vectors / QP heatmap) sits beside the hex
                        // grid rather than in the GOP column (see VideoInspectorUI.kt) -- the hex
                        // grid's own row width is fixed by its byte-per-row count, so on any
                        // window wider than that it already leaves empty space in this panel to
                        // reuse, and this panel is also taller than the GOP column, where the same
                        // content was too small to make the overlays legible even zoomed in. Only
                        // shown when the "보기" menu's codecViewMode is explicitly turned on for a
                        // supported (H.264) video tab -- unlike v1's always-visible-when-supported
                        // buttons, this panel takes real estate away from the hex grid, so it stays
                        // out of the way until the user opts in via the menu.
                        var hexCodecViewSplit by remember(currentTab) { mutableStateOf(0.6f) }
                        var hexRowWidthPx by remember(currentTab) { mutableStateOf(0) }
                        val bottomPanel: @Composable ColumnScope.() -> Unit = {
                            PanelHeader("Hex & Raw Data Viewer", color = AppColors.NeonGreen)
                            // Local val, not the outer by-delegate property directly -- codecViewMode
                            // is a `by remember { mutableStateOf(...) }` property with a custom
                            // getter, so Kotlin can't smart-cast it to non-null across this check.
                            val activeCodecViewMode = codecViewMode
                            val codecViewAvailable = currentTab.type == MediaType.VIDEO && activeCodecViewMode != null &&
                                codecViewSupportedFor(activeCodecViewMode, currentTab.videoCodecName)
                            // Shared by both HexView call sites below (codec-view-panel and plain)
                            // instead of duplicating the same fallback chain twice. selectedFrame's
                            // own byteOffset (ffprobe's pkt_pos) is the last fallback -- lets
                            // selecting a frame in the GOP timeline/filmstrip jump the hex viewer to
                            // that frame's actual bytes, the same way tile/tree-node selection
                            // already does.
                            val hexHighlightRange = currentTab.parameterSetHighlightRange
                                ?: currentTab.tileHighlightRange
                                ?: activeField?.let { it.offset until (it.offset + it.length) }
                                ?: currentTab.selected?.let { it.offset until (it.offset + it.size) }
                                ?: currentTab.selectedFrame?.let { frame ->
                                    frame.byteOffset?.let { offset -> offset until (offset + frame.sizeBytes) }
                                }
                            if (codecViewAvailable) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .onGloballyPositioned { hexRowWidthPx = it.size.width },
                                ) {
                                    Box(modifier = Modifier.weight(hexCodecViewSplit).fillMaxHeight()) {
                                        HexView(
                                            file = currentTab.file,
                                            highlightRange = hexHighlightRange,
                                            listState = hexListState,
                                        )
                                    }

                                    DraggableDivider(
                                        orientation = Orientation.Vertical,
                                        containerSizePx = hexRowWidthPx,
                                        getSplit = { hexCodecViewSplit },
                                        setSplit = { hexCodecViewSplit = it },
                                    )

                                    CodecViewPreview(
                                        currentTab,
                                        mode = activeCodecViewMode,
                                        modifier = Modifier.weight(1f - hexCodecViewSplit).fillMaxHeight(),
                                    )
                                }
                            } else {
                                HexView(
                                    file = currentTab.file,
                                    highlightRange = hexHighlightRange,
                                    listState = hexListState,
                                )
                            }
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
                appState.statusMessage?.let { msg ->
                    LaunchedEffect(msg) {
                        kotlinx.coroutines.delay(3000)
                        if (appState.statusMessage == msg) {
                            appState.statusMessage = null
                        }
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(bottom = 24.dp),
                        contentAlignment = Alignment.BottomCenter,
                    ) {
                        Surface(
                            color = AppColors.Surface,
                            shape = RoundedCornerShape(8.dp),
                            shadowElevation = 8.dp,
                            modifier = Modifier.border(1.dp, AppColors.NeonBlue, RoundedCornerShape(8.dp)),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    msg,
                                    style = AppTypography.bodyMedium.copy(color = AppColors.TextPrimary, fontSize = 13.sp),
                                )
                                Spacer(Modifier.width(12.dp))
                                IconButton(onClick = { appState.statusMessage = null }, modifier = Modifier.size(18.dp)) {
                                    Text("✕", color = AppColors.TextSecondary, fontSize = 10.sp)
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
