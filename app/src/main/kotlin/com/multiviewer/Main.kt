package com.multiviewer

import androidx.compose.foundation.LocalScrollbarStyle
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.multiviewer.cli.runCheckCommand
import com.multiviewer.cli.runDumpCommand
import kotlin.system.exitProcess
import com.multiviewer.parser.EmbeddedVideo
import com.multiviewer.parser.MotionPhotoBuilder
import com.multiviewer.parser.extractEmbeddedVideo
import com.multiviewer.parser.findFirst
import com.multiviewer.ui.*
import com.multiviewer.ui.menu.AppKeyShortcut
import com.multiviewer.ui.menu.AppMenu
import com.multiviewer.ui.menu.CustomAppMenuBar
import com.multiviewer.ui.menu.NativeAppMenuBar
import com.multiviewer.ui.menu.MenuBarNavigationState
import com.multiviewer.ui.menu.buildAppMenuBar
import com.multiviewer.ui.menu.collectShortcuts
import com.multiviewer.ui.menu.matches
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import java.awt.EventQueue
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import javax.swing.JOptionPane

private const val BYTES_PER_ROW = 16

private fun showOpenFileDialog(appState: AppState) {
    val dialog = FileDialog(null as Frame?, "Open file(s)", FileDialog.LOAD)
    appState.lastOpenedDirectory?.let { dir ->
        if (dir.exists() && dir.isDirectory) {
            dialog.directory = dir.absolutePath
        }
    }
    dialog.isMultipleMode = true
    dialog.isVisible = true
    val selectedFiles = dialog.files
    val directory = dialog.directory
    if (directory != null) {
        val dir = File(directory)
        if (dir.exists() && dir.isDirectory) {
            appState.lastOpenedDirectory = dir
        }
    }
    if (!selectedFiles.isNullOrEmpty()) {
        appState.openFiles(selectedFiles.toList())
    } else {
        val fileName = dialog.file
        if (fileName != null && directory != null) {
            appState.openFile(File(directory, fileName))
        }
    }
}

private fun extractVideoToFile(appState: AppState, tab: TabState, video: EmbeddedVideo, fileNameSuffix: String) {
    val dialog = FileDialog(null as Frame?, "Save extracted video", FileDialog.SAVE)
    appState.lastOpenedDirectory?.let { dir ->
        if (dir.exists() && dir.isDirectory) {
            dialog.directory = dir.absolutePath
        }
    }
    dialog.file = "${tab.file.nameWithoutExtension}_$fileNameSuffix.${video.extension}"
    dialog.isVisible = true
    val fileName = dialog.file
    val directory = dialog.directory
    if (fileName == null || directory == null) return
    val destination = File(directory, fileName)
    appState.updateLastOpenedDirectory(destination)
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

private fun extractGainmapImage(appState: AppState, tab: TabState, language: AppLanguage) {
    val gainmap = tab.gainmapInfo ?: return
    val ext = if (gainmap.imageFormat?.contains("hvc", ignoreCase = true) == true) "png" else "jpg"
    val dialog = FileDialog(null as Frame?, I18n.saveGainmapDialogTitle(language), FileDialog.SAVE)
    appState.lastOpenedDirectory?.let { dir ->
        if (dir.exists() && dir.isDirectory) {
            dialog.directory = dir.absolutePath
        }
    }
    dialog.file = "${tab.file.nameWithoutExtension}_gainmap.$ext"
    dialog.isVisible = true
    val fileName = dialog.file
    val directory = dialog.directory
    if (fileName == null || directory == null) return
    val destination = File(directory, fileName)
    appState.updateLastOpenedDirectory(destination)

    appState.statusMessage = "게인맵 이미지 추출 중..."
    runInBackground {
        val success = com.multiviewer.parser.GainmapParser.extractGainmapImageToFile(tab.file, tab.root, gainmap, destination)
        EventQueue.invokeLater {
            appState.statusMessage = if (success) I18n.toastGainmapExtracted(language, destination.name) else I18n.toastGainmapExtractFailed(language, "Unknown error")
        }
    }
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
    appState.lastOpenedDirectory?.let { dir ->
        if (dir.exists() && dir.isDirectory) {
            videoDialog.directory = dir.absolutePath
        }
    }
    videoDialog.isVisible = true
    val videoName = videoDialog.file
    val videoDir = videoDialog.directory
    if (videoName == null || videoDir == null) return
    val videoFile = File(videoDir, videoName)
    appState.updateLastOpenedDirectory(videoFile)

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
    appState.lastOpenedDirectory?.let { dir ->
        if (dir.exists() && dir.isDirectory) {
            saveDialog.directory = dir.absolutePath
        }
    }
    saveDialog.file = "${tab.file.nameWithoutExtension}$suffix.$defaultExt"
    saveDialog.isVisible = true
    val saveName = saveDialog.file
    val saveDir = saveDialog.directory
    if (saveName == null || saveDir == null) return
    val outputFile = File(saveDir, saveName)
    appState.updateLastOpenedDirectory(outputFile)

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
        else -> runGuiApplication(args)
    }
}

private fun runGuiApplication(args: Array<String> = emptyArray()) = application {
    val appState = remember { AppState() }

    // Computed once: true on macOS (native NSMenu via NativeAppMenuBar), false on Windows/Linux
    // (CustomAppMenuBar -- the native Win32 HMENU menu Compose would otherwise render there has no
    // font/spacing control and looks dated). See docs/superpowers/specs/2026-09-30-platform-aware-menu-bar-design.md.
    // UNWRAP_CUSTOM_MENU_BAR=1 forces the Windows/Linux custom bar on macOS, to develop it here.
    val isMacOS = remember {
        System.getProperty("os.name").lowercase().contains("mac") && System.getenv("UNWRAP_CUSTOM_MENU_BAR") != "1"
    }
    // Published by the menu-building code inside Window's content below (Step 3) via SideEffect,
    // so the onKeyEvent handler (Step 5), declared as a sibling parameter of the SAME Window(...)
    // call and therefore unable to see that content lambda's own local vals, can still look up
    // shortcuts against the latest menu state.
    var menuBar by remember { mutableStateOf<List<AppMenu>>(emptyList()) }
    var menuNavigation by remember { mutableStateOf(MenuBarNavigationState()) }
    
    // Log environment info for native library troubleshooting and initialize disk cache
    LaunchedEffect(Unit) {
        val cacheDir = File(System.getProperty("user.home"), ".unwrapMedia/cache")
        com.multiviewer.cache.MediaIndexCache.initialize(cacheDir)
        println("Starting unwrapMedia...")
        println("OS: ${System.getProperty("os.name")} (${System.getProperty("os.arch")})")
        println("Java Home: ${System.getProperty("java.home")}")

        val filesToOpen = args.map { File(it) }.filter { it.exists() && it.isFile }
        if (filesToOpen.isNotEmpty()) {
            appState.openFiles(filesToOpen)
        }
    }

    val appIcon = com.multiviewer.ui.appIconPainter
    val windowState = rememberWindowState(
        position = WindowPosition(Alignment.Center),
        // 1366x768 is still a common laptop resolution (notably on Windows) -- 1280x800 leaves
        // room for the taskbar/title bar instead of the window opening larger than the screen.
        size = DpSize(1280.dp, 800.dp),
    )
    Window(
        onCloseRequest = {
            try {
                com.multiviewer.util.ProcessManager.destroyAll()
            } catch (_: Throwable) {}
            exitApplication()
            exitProcess(0)
        },
        title = "unwrapMedia v${I18n.APP_VERSION}",
        state = windowState,
        icon = appIcon,
        // Windows/Linux custom menu bar (VS Code style): Alt focus, Alt+mnemonic, arrows, Enter, Esc.
        // Preview pass, so a focused hex view / text field can't swallow the arrows while a menu is
        // active; keys the bar doesn't own fall through untouched.
        onPreviewKeyEvent = { keyEvent ->
            if (isMacOS || (keyEvent.type != KeyEventType.KeyDown && keyEvent.type != KeyEventType.KeyUp)) {
                false
            } else {
                val result = com.multiviewer.ui.menu.handleMenuKey(
                    menuNavigation, keyEvent.key, keyEvent.type == KeyEventType.KeyDown,
                    alt = keyEvent.isAltPressed, ctrl = keyEvent.isCtrlPressed || keyEvent.isMetaPressed,
                    shift = keyEvent.isShiftPressed, menus = menuBar,
                )
                if (result.state != menuNavigation) menuNavigation = result.state
                result.action?.invoke()
                result.consumed
            }
        },
        onKeyEvent = { keyEvent ->
            if (keyEvent.type == KeyEventType.KeyDown) {
                // Windows/Linux only: the custom menu bar isn't a native OS menu, so it has no
                // OS-level accelerator table of its own -- this is what makes its shortcuts work
                // even while no menu is open. macOS doesn't need this: NativeAppMenuBar's native
                // menu bar already gets shortcut dispatch for free from Cocoa's own NSMenu.
                if (!isMacOS) {
                    val matched = menuBar.collectShortcuts().find { (shortcut, _) -> shortcut.matches(keyEvent) }
                    if (matched != null) {
                        matched.second()
                        return@Window true
                    }
                }
                val isCtrlOrMeta = keyEvent.isCtrlPressed || keyEvent.isMetaPressed
                if (isCtrlOrMeta && !keyEvent.isShiftPressed && !keyEvent.isAltPressed && keyEvent.key == Key.C) {
                    val currentTab = appState.tabs.getOrNull(appState.selectedTabIndex)
                    if (currentTab != null && !currentTab.isLoading) {
                        val activeField = currentTab.selected?.fields?.let { fields -> currentTab.selectedField?.takeIf { it in fields } }
                        val hexHighlightRange = currentTab.parameterSetHighlightRange
                            ?: currentTab.tileHighlightRange
                            ?: activeField?.let { it.offset until (it.offset + it.length) }
                            ?: currentTab.selected?.let { it.offset until (it.offset + it.size) }
                            ?: currentTab.selectedFrame?.let { frame ->
                                frame.byteOffset?.let { offset -> offset until (offset + frame.sizeBytes) }
                            }
                        if (hexHighlightRange != null && hexHighlightRange.first >= 0) {
                            try {
                                java.io.RandomAccessFile(currentTab.file, "r").use { raf ->
                                    val buf = readRangeBytes(raf, hexHighlightRange)
                                    val dump = formatHexDump(buf, hexHighlightRange.first)
                                    if (com.multiviewer.util.ClipboardUtil.copyToClipboard(dump)) {
                                        appState.statusMessage = "선택된 박스/마커 데이터(Hex Dump)가 클립보드에 복사되었습니다."
                                        return@Window true
                                    }
                                }
                            } catch (_: Exception) {}
                        }
                    }
                }
            }
            false
        },
    ) {
        var themeMode by remember { mutableStateOf(loadThemeMode()) }
        var showPixelGrid by remember { mutableStateOf(loadShowPixelGrid()) }
        var language by remember { mutableStateOf(loadLanguage()) }
        var frameIntervalWindowOpen by remember { mutableStateOf(false) }
        var qualityCompareWindowOpen by remember { mutableStateOf(false) }
        var motionPhotoFrameIntervalWindowOpen by remember { mutableStateOf(false) }
        var dumpStructureWindowOpen by remember { mutableStateOf(false) }
        var aiPromptWindowOpen by remember { mutableStateOf(false) }
        var avSyncWindowOpen by remember { mutableStateOf(false) }
        var c2paTab by remember { mutableStateOf<TabState?>(null) }
        var hdrMetadataTab by remember { mutableStateOf<TabState?>(null) }
        var structureDiagramTab by remember { mutableStateOf<TabState?>(null) }
        // Supervisor scope: one failed AI request must not cancel the others or later sends.
        // Compose's own UI scope (this app has no Dispatchers.Main module — kotlinx-coroutines-swing is not on
        // the classpath), with a SupervisorJob under it so one failed request never cancels later sends; it is
        // cancelled with the composition.
        val composeScope = rememberCoroutineScope()
        val aiScope = remember(composeScope) {
            kotlinx.coroutines.CoroutineScope(
                composeScope.coroutineContext + kotlinx.coroutines.SupervisorJob(composeScope.coroutineContext[kotlinx.coroutines.Job]),
            )
        }
        val aiController = remember(aiScope) { com.multiviewer.ui.ai.aiChatController(appState, aiScope) }
        SideEffect { appState.aiLanguage = language }

        // Automatically maximize the window when AI panel opens to provide ample screen space for
        // tree, properties, hex viewer, and chat side-by-side; restore to previous floating size on close.
        var autoMaximizedByAi by remember { mutableStateOf(false) }
        LaunchedEffect(appState.aiPanelOpen) {
            if (appState.aiPanelOpen) {
                if (windowState.placement == WindowPlacement.Floating) {
                    autoMaximizedByAi = true
                    windowState.placement = WindowPlacement.Maximized
                }
            } else {
                if (autoMaximizedByAi) {
                    autoMaximizedByAi = false
                    if (windowState.placement == WindowPlacement.Maximized) {
                        windowState.placement = WindowPlacement.Floating
                    }
                }
            }
        }
        LaunchedEffect(appState.tabs.isEmpty()) {
            if (appState.tabs.isEmpty() && appState.aiPanelOpen) {
                appState.aiPanelOpen = false
            }
        }
        var hrdBufferTab by remember { mutableStateOf<TabState?>(null) }
        var spatialVideoTab by remember { mutableStateOf<TabState?>(null) }
        var timedMetadataTab by remember { mutableStateOf<TabState?>(null) }
        var sefIntegrityWindowOpen by remember { mutableStateOf(false) }
        var aboutWindowOpen by remember { mutableStateOf(false) }
        var updateWindowOpen by remember { mutableStateOf(false) }
        var hasUpdateAvailable by remember { mutableStateOf(false) }
        var latestUpdateVersion by remember { mutableStateOf("") }
        // Codec view popup window mode (Motion Vectors / QP Heatmap). null = popup closed.
        var codecViewPopupWindowMode by remember { mutableStateOf<CodecViewMode?>(null) }
        // The menu DSL lambdas aren't composable, so the codec probe is remembered here per tree.
        val selectedTabRoot = appState.tabs.getOrNull(appState.selectedTabIndex)?.root
        val selectedTabHasC2pa = remember(selectedTabRoot) { selectedTabRoot?.let { com.multiviewer.parser.c2pa.hasC2paManifestStore(it) } == true }
        val selectedTabVideoCodec = remember(selectedTabRoot) { selectedTabRoot?.let { com.multiviewer.parser.detectVideoCodec(it) } }
        val builtMenuBar = buildAppMenuBar {
            appMenu(I18n.menuFile(language), mnemonic = 'F') {
                val currentTab = appState.tabs.getOrNull(appState.selectedTabIndex)
                val isVideo = currentTab?.type == MediaType.VIDEO
                val hasVideoTrack = isVideo && currentTab?.mediaSummary?.sections?.any { it.title == "Video" } == true
                val hasAudioTrack = isVideo && currentTab?.mediaSummary?.sections?.any { it.title == "Audio" } == true
                val hasGainmap = currentTab != null && !currentTab.isLoading && currentTab.gainmapInfo?.hasGainmap == true

                item(I18n.menuOpen(language), shortcut = AppKeyShortcut(Key.O, meta = true), onClick = { showOpenFileDialog(appState) })
                item(I18n.menuClose(language), enabled = appState.tabs.isNotEmpty(), shortcut = AppKeyShortcut(Key.W, meta = true), onClick = { appState.closeTab(appState.selectedTabIndex) })
            }
            // Every gain map command in one place -- they were split between 파일 (extract) and
            // 분석 (the two viewers), which meant hunting through two menus for one feature.
            appMenu(I18n.menuGainmap(language), mnemonic = 'G') {
                val currentTab = appState.tabs.getOrNull(appState.selectedTabIndex)
                val hasGainmap = currentTab != null && !currentTab.isLoading && currentTab.gainmapInfo?.hasGainmap == true
                val hasGainmapImage = hasGainmap && currentTab?.gainmapInfo?.hasGainmapImage == true

                // One window (overview / image / HDR preview / check / raw metadata) instead of
                // separate image and XMP popups that each linked to the other.
                item(
                    I18n.menuGainmapAnalyzer(language),
                    enabled = hasGainmap,
                    shortcut = AppKeyShortcut(Key.G, meta = true),
                    onClick = { currentTab?.gainmapAnalyzerPage = GainmapAnalyzerPage.OVERVIEW },
                )
                item(
                    I18n.menuExtractGainmapImage(language),
                    enabled = hasGainmapImage,
                    onClick = { currentTab?.let { extractGainmapImage(appState, it, language) } },
                )
            }
            // Grouped by purpose: whole file / metadata (images and video) / video timing (frame ->
            // A/V -> buffer) / video bitstream and special tracks.
            appMenu(I18n.menuAnalyze(language), mnemonic = 'A') {
                val currentTab = appState.tabs.getOrNull(appState.selectedTabIndex)
                val hasActiveFile = currentTab != null && !currentTab.isLoading && currentTab.root != null
                val isVideo = currentTab?.type == MediaType.VIDEO
                val hasVideoTrack = isVideo && currentTab?.mediaSummary?.sections?.any { it.title == "Video" } == true
                val hasSefData = currentTab?.root?.let { root -> findFirst(root) { it.type == "sefd" } } != null
                val hasMetadataCodec = isVideo && selectedTabVideoCodec != null

                item(
                    I18n.menuAskAi(language) + if (appState.aiPanelOpen) " ✓" else "",
                    enabled = currentTab != null,
                    shortcut = AppKeyShortcut(Key.I, meta = true, shift = true),
                    onClick = { appState.aiPanelOpen = !appState.aiPanelOpen },
                )
                separator()
                item(
                    I18n.menuContentCheck(language),
                    enabled = hasActiveFile,
                    shortcut = AppKeyShortcut(Key.C, meta = true, shift = true),
                    onClick = { appState.openContentCheck(currentTab) },
                )
                item(
                    I18n.menuDumpStructure(language),
                    enabled = hasActiveFile,
                    shortcut = AppKeyShortcut(Key.D, meta = true, shift = true),
                    onClick = { dumpStructureWindowOpen = true },
                )
                item(
                    I18n.menuStructureDiagram(language),
                    enabled = hasActiveFile,
                    onClick = { structureDiagramTab = currentTab },
                )
                separator()
                // Every XMP packet in the file -- not gain-map specific (worth a look on files with no
                // gain map at all), so it sits with the other metadata rather than in 게인맵.
                item(
                    I18n.menuViewFileXmp(language),
                    enabled = currentTab != null && !currentTab.isLoading,
                    onClick = { currentTab?.isFileXmpPopupOpen = true },
                )
                item(I18n.menuC2pa(language), enabled = currentTab != null && !currentTab.isLoading && selectedTabHasC2pa, onClick = { c2paTab = currentTab })
                item(
                    I18n.menuSefIntegrityCheck(language),
                    enabled = hasSefData,
                    onClick = { sefIntegrityWindowOpen = true },
                )
                separator()
                item(
                    I18n.menuViewFrameIntervals(language),
                    enabled = hasVideoTrack,
                    onClick = { frameIntervalWindowOpen = true },
                )
                item(
                    I18n.menuAvSyncAnalysis(language),
                    enabled = isVideo,
                    shortcut = AppKeyShortcut(Key.S, meta = true, shift = true),
                    onClick = { avSyncWindowOpen = true },
                )
                item(
                    I18n.menuHrdBufferSimulation(language),
                    enabled = hasVideoTrack,
                    onClick = { hrdBufferTab = currentTab },
                )
                separator()
                item(
                    I18n.menuHdrSeiAnalysis(language),
                    enabled = hasMetadataCodec,
                    onClick = { hdrMetadataTab = currentTab },
                )
                item(
                    I18n.menuMotionVectors(language),
                    enabled = isVideo && codecViewSupportedFor(CodecViewMode.MOTION_VECTORS, currentTab?.videoCodecName),
                    onClick = { codecViewPopupWindowMode = CodecViewMode.MOTION_VECTORS },
                )
                item(
                    I18n.menuQpHeatmap(language),
                    enabled = isVideo && codecViewSupportedFor(CodecViewMode.QP_HEATMAP, currentTab?.videoCodecName),
                    onClick = { codecViewPopupWindowMode = CodecViewMode.QP_HEATMAP },
                )
                item(
                    I18n.menuSpatialVideoAnalysis(language),
                    enabled = hasVideoTrack,
                    onClick = { spatialVideoTab = currentTab },
                )
                item(
                    I18n.menuTimedMetadataTimeline(language),
                    enabled = hasVideoTrack,
                    onClick = { timedMetadataTab = currentTab },
                )
            }
            // Most-used first: check and analyse, then extract, then create (writes a new file).
            appMenu(I18n.menuMotionPhoto(language), mnemonic = 'M') {
                val currentTab = appState.tabs.getOrNull(appState.selectedTabIndex)
                val isImage = currentTab != null && !currentTab.isLoading && currentTab.type == MediaType.IMAGE
                val isHeic = currentTab?.file?.extension?.lowercase(java.util.Locale.US) in setOf("heic", "heif")
                val hasMotionPhoto = isImage && currentTab?.root?.let(::hasMotionPhotoData) == true
                // Integrity, still<->video frame match, Live Photo pair and compatibility all live in
                // Content Check's motion photo tab; this opens it there directly.
                item(
                    I18n.menuMotionPhotoCheck(language),
                    enabled = hasMotionPhoto,
                    onClick = { appState.openContentCheck(currentTab, ContentTab.MOTION_PHOTO) },
                )
                item(
                    I18n.menuMotionFrameDropAnalysis(language),
                    enabled = currentTab?.embeddedVideo != null,
                    onClick = { motionPhotoFrameIntervalWindowOpen = true },
                )
                separator()
                item(
                    I18n.menuExtractMotionVideo(language),
                    enabled = currentTab?.embeddedVideo != null,
                    onClick = { currentTab?.let { extractMotionPhotoVideo(appState, it) } },
                )
                item(
                    I18n.menuExtractPreviewVideo(language),
                    enabled = currentTab?.motionPhotoPreview != null,
                    onClick = { currentTab?.let { extractMotionPhotoPreviewVideo(appState, it) } },
                )
                separator()
                item(
                    I18n.menuCreateMotionPhotoV2(language),
                    enabled = isImage,
                    onClick = { currentTab?.let { createMotionPhotoFromCurrentTab(appState, it, language, com.multiviewer.parser.MotionPhotoFormatVersion.V2_MOTION_PHOTO) } },
                )
                item(
                    I18n.menuCreateMotionPhotoV1(language),
                    enabled = isImage && !isHeic,
                    onClick = { currentTab?.let { createMotionPhotoFromCurrentTab(appState, it, language, com.multiviewer.parser.MotionPhotoFormatVersion.V1_MICRO_VIDEO) } },
                )
            }
            // Comparison tools on top, then track extraction (its own 추출 menu held only these two
            // items). Motion photo extractions stay in 모션포토, gain map saving in 게인맵: they belong
            // with that format's other commands.
            appMenu(I18n.menuTools(language), mnemonic = 'T') {
                val currentTab = appState.tabs.getOrNull(appState.selectedTabIndex)
                val isVideo = currentTab?.type == MediaType.VIDEO
                val hasVideoTrack = isVideo && currentTab?.mediaSummary?.sections?.any { it.title == "Video" } == true
                val hasAudioTrack = isVideo && currentTab?.mediaSummary?.sections?.any { it.title == "Audio" } == true

                item(
                    I18n.menuCompareFiles(language),
                    shortcut = AppKeyShortcut(Key.D, meta = true),
                    onClick = { appState.openMediaCompare() },
                )
                item(
                    I18n.menuQualityBenchmark(language),
                    onClick = { qualityCompareWindowOpen = true },
                )
                separator()
                item(
                    I18n.menuExtractVideoTrack(language),
                    enabled = hasVideoTrack,
                    onClick = { currentTab?.let { extractVideoTrackFromCurrentFile(appState, it) } },
                )
                item(
                    I18n.menuExtractAudioTrack(language),
                    enabled = hasAudioTrack,
                    onClick = { currentTab?.let { extractAudioTrackFromCurrentFile(appState, it) } },
                )
            }
            // The "탐색" menu used to sit here. Removed as confusing: its panel toggle duplicated the
            // 구조 트리 / 폴더 탐색 tabs right below, and "탐색" read as being about the folder explorer
            // while actually holding file-stepping commands.
            // Display settings only: one screen toggle, then theme, then language. The codec-view
            // windows (motion vectors / QP heat map) open analyses, so they live in 분석.
            appMenu(I18n.menuView(language), mnemonic = 'V') {
                checkbox(
                    I18n.menuPixelGrid(language),
                    checked = showPixelGrid,
                    onCheckedChange = {
                        showPixelGrid = it
                        saveShowPixelGrid(it)
                    },
                )
                separator()
                checkbox(
                    I18n.menuDarkTheme(language),
                    checked = themeMode == ThemeMode.DARK,
                    onCheckedChange = {
                        themeMode = ThemeMode.DARK
                        saveThemeMode(themeMode)
                    },
                )
                checkbox(
                    I18n.menuLightTheme(language),
                    checked = themeMode == ThemeMode.LIGHT,
                    onCheckedChange = {
                        themeMode = ThemeMode.LIGHT
                        saveThemeMode(themeMode)
                    },
                )
                separator()
                checkbox(
                    I18n.menuKorean(language),
                    checked = language == AppLanguage.KO,
                    onCheckedChange = {
                        language = AppLanguage.KO
                        saveLanguage(language)
                    },
                )
                checkbox(
                    I18n.menuEnglish(language),
                    checked = language == AppLanguage.EN,
                    onCheckedChange = {
                        language = AppLanguage.EN
                        saveLanguage(language)
                    },
                )
            }
            appMenu(I18n.menuHelp(language), mnemonic = 'H') {
                item(
                    I18n.menuOnlineRepo(language),
                    onClick = {
                        try {
                            val config = com.multiviewer.update.UpdateConfig.load()
                            val uri = java.net.URI(config.repoUrl.ifBlank { com.multiviewer.update.UpdateConfig.DEFAULT_REPO_URL })
                            java.awt.Desktop.getDesktop().browse(uri)
                        } catch (_: Exception) {}
                    },
                )
                separator()
                item(
                    I18n.menuAbout(language),
                    onClick = { aboutWindowOpen = true },
                )
            }
        }
        SideEffect { menuBar = builtMenuBar }
        if (isMacOS) {
            NativeAppMenuBar(builtMenuBar)
        }

        LaunchedEffect(Unit) {
            attachFileDropTarget(window) { files, _ ->
                if (files.isNotEmpty()) {
                    if (files.size == 1 && files[0].isDirectory) {
                        appState.openFolder(files[0])
                    } else {
                        appState.openFiles(files)
                    }
                }
            }
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

            if (appState.imageCompareWindowOpen) {
                ImageCompareWindow(
                    appState = appState,
                    language = language,
                    initialFileA = appState.compareFileA ?: appState.compareTargetFiles.getOrNull(0),
                    initialFileB = appState.compareFileB ?: appState.compareTargetFiles.getOrNull(1),
                    initialFiles = appState.compareTargetFiles,
                    onCloseRequest = {
                        appState.imageCompareWindowOpen = false
                        appState.compareTargetFiles = emptyList()
                        appState.compareFileA = null
                        appState.compareFileB = null
                    },
                )
            }
            if (qualityCompareWindowOpen) {
                QualityCompareWindow(appState = appState, language = language, onCloseRequest = { qualityCompareWindowOpen = false })
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
            appState.contentCheckTab?.let { targetTab ->
                ContentCheckWindow(
                    targetTab,
                    language,
                    onAiDiagnosis = {
                        // The AI window renders for the selected tab: select the content-check tab first
                        // so it diagnoses this file (and its published Content Check snapshot).
                        val index = appState.tabs.indexOf(targetTab)
                        if (index >= 0) {
                            appState.selectedTabIndex = index
                            appState.aiPromptTargetWarning = null
                            appState.aiPromptWindowOpen = true
                        }
                    },
                    initialTab = appState.contentCheckInitialTab,
                    onCloseRequest = { appState.contentCheckTab = null },
                )
            }
            if (appState.aiPromptWindowOpen) {
                val currentTab = appState.tabs.getOrNull(appState.selectedTabIndex)
                if (currentTab != null) {
                    AiPromptPreviewWindow(
                        tab = currentTab,
                        initialTargetWarning = appState.aiPromptTargetWarning,
                        themeMode = themeMode,
                        onCloseRequest = {
                            appState.aiPromptWindowOpen = false
                            appState.aiPromptTargetWarning = null
                        },
                    )
                } else {
                    appState.aiPromptWindowOpen = false
                    appState.aiPromptTargetWarning = null
                }
            }
            if (avSyncWindowOpen) {
                val currentTab = appState.tabs.getOrNull(appState.selectedTabIndex)
                if (currentTab != null) {
                    AvSyncAnalysisWindow(tab = currentTab, themeMode = themeMode, onCloseRequest = { avSyncWindowOpen = false })
                } else {
                    avSyncWindowOpen = false
                }
            }
            // Pinned to the tab it was opened for: switching tabs must not re-target the scan.
            c2paTab?.let { t ->
                if (appState.tabs.none { it === t }) c2paTab = null
                else com.multiviewer.ui.C2paWindow(tab = t, language = language, onCloseRequest = { c2paTab = null })
            }
            structureDiagramTab?.let { t ->
                if (appState.tabs.none { it === t }) {
                    structureDiagramTab = null
                } else {
                    com.multiviewer.ui.StructureDiagramWindow(tab = t, language = language, onCloseRequest = { structureDiagramTab = null })
                }
            }
            hdrMetadataTab?.let { t ->
                if (appState.tabs.none { it === t }) {
                    hdrMetadataTab = null
                } else {
                    HdrMetadataWindow(tab = t, language = language, onCloseRequest = { hdrMetadataTab = null })
                }
            }
            hrdBufferTab?.let { t ->
                if (appState.tabs.none { it === t }) {
                    hrdBufferTab = null
                } else {
                    HrdBufferSimulationWindow(tab = t, language = language, onCloseRequest = { hrdBufferTab = null })
                }
            }
            spatialVideoTab?.let { t ->
                if (appState.tabs.none { it === t }) {
                    spatialVideoTab = null
                } else {
                    SpatialVideoAnalysisWindow(tab = t, language = language, onCloseRequest = { spatialVideoTab = null })
                }
            }
            timedMetadataTab?.let { t ->
                if (appState.tabs.none { it === t }) {
                    timedMetadataTab = null
                } else {
                    TimedMetadataTimelineWindow(tab = t, language = language, onCloseRequest = { timedMetadataTab = null })
                }
            }
            if (sefIntegrityWindowOpen) {
                val currentTab = appState.tabs.getOrNull(appState.selectedTabIndex)
                val sefdNode = currentTab?.root?.let { root -> findFirst(root) { it.type == "sefd" } }
                if (currentTab != null && sefdNode != null) {
                    SefIntegrityWindow(
                        file = currentTab.file,
                        sefdOffset = sefdNode.offset,
                        sefdHeaderSize = sefdNode.headerSize,
                        sefdSize = sefdNode.size,
                        themeMode = themeMode,
                        onCloseRequest = { sefIntegrityWindowOpen = false },
                    )
                } else {
                    sefIntegrityWindowOpen = false
                }
            }
            if (aboutWindowOpen) {
                AboutWindow(
                    language = language,
                    onCloseRequest = { aboutWindowOpen = false },
                    onCheckUpdate = {
                        aboutWindowOpen = false
                        updateWindowOpen = true
                    },
                )
            }
            if (updateWindowOpen) {
                UpdateWindow(language = language, onCloseRequest = { updateWindowOpen = false })
            }
            val popupMode = codecViewPopupWindowMode
            if (popupMode != null) {
                val currentTab = appState.tabs.getOrNull(appState.selectedTabIndex)
                if (currentTab != null) {
                    CodecViewPreviewWindow(
                        tab = currentTab,
                        mode = popupMode,
                        onCloseRequest = { codecViewPopupWindowMode = null },
                    )
                } else {
                    codecViewPopupWindowMode = null
                }
            }

            LaunchedEffect(Unit) {
                val result = com.multiviewer.update.UpdateChecker.checkForUpdates(I18n.APP_VERSION)
                result.onSuccess { info ->
                    if (info.hasUpdate) {
                        hasUpdateAvailable = true
                        latestUpdateVersion = info.latestVersion
                    }
                }
            }
            appState.tabs.forEach { tab ->
                if (tab.fullSizeFramePreviewOpen) {
                    FrameFullSizePreviewWindow(appState, tab, language, onCloseRequest = { tab.fullSizeFramePreviewOpen = false })
                }
                if (tab.isPrimaryImagePopupOpen) {
                    PrimaryImagePopupWindow(tab, onCloseRequest = { tab.isPrimaryImagePopupOpen = false })
                }
                if (tab.isFileXmpPopupOpen) {
                    FileXmpWindow(
                        tab = tab,
                        themeMode = themeMode,
                        language = language,
                        onCloseRequest = { tab.isFileXmpPopupOpen = false },
                    )
                }
                tab.gainmapAnalyzerPage?.let { page ->
                    GainmapAnalyzerWindow(
                        tab = tab,
                        page = page,
                        onPageChange = { tab.gainmapAnalyzerPage = it },
                        onSaveImage = { extractGainmapImage(appState, tab, language) },
                        onCloseRequest = { tab.gainmapAnalyzerPage = null },
                        themeMode = themeMode,
                        language = language,
                    )
                }
            }
            // Root Box: the custom menu's dropdown is drawn above the content in this same window
            // (VS Code style), so the window keeps keyboard focus while menus open and switch.
            val menuBarLayout = com.multiviewer.ui.menu.rememberMenuBarLayout()
            Box(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                if (!isMacOS) {
                    CustomAppMenuBar(
                        builtMenuBar,
                        navigationState = menuNavigation,
                        onNavigationStateChange = { menuNavigation = it },
                        layout = menuBarLayout,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    HorizontalDivider(color = AppColors.Border)
                }
                Surface(modifier = Modifier.weight(1f).fillMaxWidth(), color = AppColors.Background) {
                if (appState.tabs.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                I18n.placeholderEmptyState(language),
                                fontSize = 20.sp,
                                color = AppColors.TextPrimary,
                            )
                            Spacer(Modifier.height(16.dp))
                            // Only "open file" here. Opening a folder happens in the folder explorer
                            // panel (its 📂 button), which appears as soon as any file is open --
                            // and opening a file already selects that file's folder there, so the
                            // explorer is one step away rather than a separate starting choice.
                            OutlinedButton(
                                onClick = { showOpenFileDialog(appState) },
                                colors = ButtonDefaults.outlinedButtonColors(
                                    containerColor = AppColors.Surface.copy(alpha = 0.5f),
                                    contentColor = AppColors.NeonBlue,
                                ),
                                border = androidx.compose.foundation.BorderStroke(1.dp, AppColors.Border),
                            ) {
                                Text(if (language == AppLanguage.KO) "📂 파일 열기" else "📂 Open File")
                            }
                            Spacer(Modifier.height(14.dp))
                            Text("unwrapMedia v${I18n.APP_VERSION}", fontSize = 12.sp, color = AppColors.TextSecondary)
                        }
                    }
                } else {
                    Column {
                        val safeSelectedTabIndex = appState.selectedTabIndex.coerceIn(0, (appState.tabs.size - 1).coerceAtLeast(0))
                        Row(
                            modifier = Modifier.fillMaxWidth().background(AppColors.Panel),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            ScrollableTabRow(
                                selectedTabIndex = safeSelectedTabIndex,
                                containerColor = AppColors.Panel,
                                contentColor = AppColors.NeonBlue,
                                edgePadding = 0.dp,
                                modifier = Modifier.weight(1f),
                                indicator = { tabPositions ->
                                    if (safeSelectedTabIndex in tabPositions.indices) {
                                        TabRowDefaults.SecondaryIndicator(
                                            modifier = Modifier.tabIndicatorOffset(tabPositions[safeSelectedTabIndex]),
                                            color = AppColors.NeonBlue
                                        )
                                    }
                                }
                            ) {
                                appState.tabs.forEachIndexed { index, tab ->
                                    @OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
                                    androidx.compose.foundation.TooltipArea(
                                        tooltip = {
                                            Text(
                                                tab.file.absolutePath,
                                                style = AppTypography.labelLarge,
                                                color = AppColors.TextPrimary,
                                                modifier = Modifier
                                                    .background(AppColors.Panel, RoundedCornerShape(4.dp))
                                                    .border(1.dp, AppColors.Border, RoundedCornerShape(4.dp))
                                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                                            )
                                        },
                                    ) {
                                    Tab(
                                        selected = index == safeSelectedTabIndex,
                                        onClick = { appState.selectedTabIndex = index },
                                        modifier = Modifier
                                            .widthIn(min = 100.dp)
                                            .pointerInput(tab) {
                                                @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
                                                awaitPointerEventScope {
                                                    while (true) {
                                                        val event = awaitPointerEvent()
                                                        if (event.type == PointerEventType.Press && event.button == PointerButton.Tertiary) {
                                                            appState.closeTabByFile(tab.file)
                                                        }
                                                    }
                                                }
                                            },
                                        text = {
                                            // Tabs size to the full file name (no max width): the tab row scrolls
                                            // horizontally, and the full path is in the tooltip. No weight/fillMaxWidth
                                            // here -- under the row's unbounded width they collapse the name to 0.
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(
                                                    text = tab.file.name,
                                                    style = AppTypography.labelLarge,
                                                    maxLines = 1,
                                                    softWrap = false,
                                                )
                                                Spacer(Modifier.width(4.dp))
                                                IconButton(
                                                    onClick = { appState.closeTabByFile(tab.file) },
                                                    modifier = Modifier.size(24.dp)
                                                ) {
                                                    Text("✕", color = AppColors.NeonRed, fontSize = 10.sp)
                                                }
                                            }
                                        },
                                    )
                                    }
                                }
                            }

                            // AI Chat toggle button on the right edge of the tab row
                            val aiActive = appState.aiPanelOpen
                            val isDark = themeMode == ThemeMode.DARK
                            val btnBg = if (aiActive) {
                                AppColors.NeonPurple
                            } else {
                                if (isDark) Color(0xFF2B2038) else Color(0xFFF3E8FF)
                            }
                            val btnBorder = AppColors.NeonPurple
                            val textColor = if (aiActive) {
                                if (isDark) Color(0xFF160B22) else Color.White
                            } else {
                                if (isDark) Color.White else Color(0xFF4A1D7A)
                            }
                            val iconColor = if (aiActive) {
                                if (isDark) Color(0xFF160B22) else Color.White
                            } else {
                                AppColors.NeonPurple
                            }

                            @OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
                            androidx.compose.foundation.TooltipArea(
                                tooltip = {
                                    Text(
                                        if (language == AppLanguage.KO) "AI와 대화 토글 (⌘⇧I / Ctrl+Shift+I)" else "Toggle Ask AI (⌘⇧I / Ctrl+Shift+I)",
                                        style = AppTypography.labelLarge,
                                        color = AppColors.TextPrimary,
                                        modifier = Modifier
                                            .background(AppColors.Panel, RoundedCornerShape(4.dp))
                                            .border(1.dp, AppColors.Border, RoundedCornerShape(4.dp))
                                            .padding(horizontal = 8.dp, vertical = 4.dp),
                                    )
                                },
                                modifier = Modifier.padding(end = 12.dp, start = 6.dp),
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = btnBg,
                                    border = androidx.compose.foundation.BorderStroke(1.2.dp, btnBorder),
                                    modifier = Modifier.clickable {
                                        appState.aiPanelOpen = !appState.aiPanelOpen
                                    },
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        Text(
                                            "✦",
                                            color = iconColor,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold,
                                        )
                                        Text(
                                            if (language == AppLanguage.KO) "AI와 대화" else "Ask AI",
                                            color = textColor,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold,
                                        )
                                    }
                                }
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

                        // Hex viewer -> structure tree. Selecting bytes picks the innermost box
                        // covering them, which BoxTreeView then expands, scrolls to and highlights.
                        //
                        // The node is remembered so the scroll effect below can tell this apart from
                        // a tree-originated selection: scrolling the hex viewer to the box's first
                        // row would yank the dump away from the bytes the user just clicked, which
                        // is the opposite of following them.
                        var hexOriginatedSelection by remember(currentTab) { mutableStateOf<com.multiviewer.parser.BoxNode?>(null) }
                        val selectNodeAtHexOffset: (Long) -> Unit = { offset ->
                            val root = currentTab.root
                            if (root != null) {
                                val node = findNodeAtOffset(root, offset)
                                if (node != null && node !== currentTab.selected) {
                                    hexOriginatedSelection = node
                                    currentTab.selectedField = null
                                    currentTab.selected = node
                                }
                            }
                        }

                        LaunchedEffect(currentTab.selected, currentTab.selectedField, currentTab.tileHighlightRange, currentTab.selectedFrame, currentTab.parameterSetHighlightRange) {
                            if (currentTab.selected != null && currentTab.selected === hexOriginatedSelection) {
                                hexOriginatedSelection = null
                                return@LaunchedEffect
                            }
                            val paramRange = currentTab.parameterSetHighlightRange
                            val tileRange = currentTab.tileHighlightRange
                            val field = activeField
                            val frameOffset = currentTab.selectedFrame?.byteOffset
                            when {
                                paramRange != null -> hexListState.scrollToItem((paramRange.first / BYTES_PER_ROW).toInt())
                                field != null && field.length > 0 -> hexListState.scrollToItem((field.offset / BYTES_PER_ROW).toInt())
                                tileRange != null -> hexListState.scrollToItem((tileRange.first / BYTES_PER_ROW).toInt())
                                frameOffset != null -> hexListState.scrollToItem((frameOffset / BYTES_PER_ROW).toInt())
                                else -> currentTab.selected?.let {
                                    hexListState.scrollToItem((it.offset / BYTES_PER_ROW).toInt())
                                }
                            }
                        }

                        val leftPanel: @Composable ColumnScope.() -> Unit = {
                            val folderFilesCount = remember(appState.selectedFolder?.absolutePath, currentTab.file.parentFile?.absolutePath) {
                                appState.getFolderMediaFiles().size
                            }
                            Surface(
                                color = AppColors.Panel,
                                modifier = Modifier.fillMaxWidth().height(32.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, AppColors.Border),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp, vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Surface(
                                        color = if (appState.leftPanelMode == LeftPanelMode.STRUCTURE_TREE) AppColors.Surface else Color.Transparent,
                                        shape = RoundedCornerShape(4.dp),
                                        modifier = Modifier.weight(1f).fillMaxHeight().clickable {
                                            appState.leftPanelMode = LeftPanelMode.STRUCTURE_TREE
                                        },
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxSize(),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.Center,
                                        ) {
                                            Text(
                                                text = if (language == AppLanguage.KO) "🌲 구조 트리" else "🌲 Structure",
                                                fontSize = 11.sp,
                                                fontWeight = if (appState.leftPanelMode == LeftPanelMode.STRUCTURE_TREE) FontWeight.Bold else FontWeight.Normal,
                                                color = if (appState.leftPanelMode == LeftPanelMode.STRUCTURE_TREE) AppColors.NeonBlue else AppColors.TextSecondary,
                                            )
                                        }
                                    }
                                    Spacer(Modifier.width(4.dp))
                                    Surface(
                                        color = if (appState.leftPanelMode == LeftPanelMode.FOLDER_EXPLORER) AppColors.Surface else Color.Transparent,
                                        shape = RoundedCornerShape(4.dp),
                                        modifier = Modifier.weight(1f).fillMaxHeight().clickable {
                                            appState.leftPanelMode = LeftPanelMode.FOLDER_EXPLORER
                                        },
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxSize(),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.Center,
                                        ) {
                                            Text(
                                                text = if (language == AppLanguage.KO) "📁 폴더 탐색 ($folderFilesCount)" else "📁 Folder ($folderFilesCount)",
                                                fontSize = 11.sp,
                                                fontWeight = if (appState.leftPanelMode == LeftPanelMode.FOLDER_EXPLORER) FontWeight.Bold else FontWeight.Normal,
                                                color = if (appState.leftPanelMode == LeftPanelMode.FOLDER_EXPLORER) AppColors.NeonYellow else AppColors.TextSecondary,
                                            )
                                        }
                                    }
                                }
                            }

                            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                                when (appState.leftPanelMode) {
                                    LeftPanelMode.STRUCTURE_TREE -> {
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
                                    LeftPanelMode.FOLDER_EXPLORER -> {
                                        FolderExplorerView(appState = appState, currentTab = currentTab, language = language)
                                    }
                                }
                            }
                        }

                        val bottomPanel: @Composable ColumnScope.() -> Unit = {
                            PanelHeader("Hex & Raw Data Viewer", color = AppColors.NeonGreen)
                            val hexHighlightRange = currentTab.parameterSetHighlightRange
                                ?: activeField?.takeIf { it.length > 0 }?.let { it.offset until (it.offset + it.length) }
                                ?: currentTab.tileHighlightRange
                                ?: currentTab.selected?.let { it.offset until (it.offset + it.size) }
                                ?: currentTab.selectedFrame?.let { frame ->
                                    frame.byteOffset?.let { offset -> offset until (offset + frame.sizeBytes) }
                                }
                            HexView(
                                file = currentTab.file,
                                highlightRange = hexHighlightRange,
                                listState = hexListState,
                                onSelectionChanged = selectNodeAtHexOffset,
                            )
                        }

                        Row(Modifier.fillMaxSize()) {
                        Box(Modifier.weight(1f).fillMaxHeight()) {
                        if (currentTab.isLoading) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                DecodingIndicator("${currentTab.file.name} 분석 중...")
                            }
                        } else {
                            when (currentTab.type) {
                                MediaType.IMAGE -> ImageInspectorUI(appState, currentTab, leftPanel, bottomPanel, language)
                                MediaType.VIDEO -> VideoInspectorUI(appState, currentTab, leftPanel, bottomPanel, language)
                                MediaType.AUDIO -> AudioInspectorUI(appState, currentTab, leftPanel, bottomPanel)
                                MediaType.RAW_PIXEL -> RawPixelInspectorUI(appState, currentTab, leftPanel, bottomPanel)
                                else -> {
                                    // Fallback to original structure view if needed
                                    Text("Unsupported Format", modifier = Modifier.padding(16.dp))
                                }
                            }
                        }
                        }
                        if (appState.aiPanelOpen) {
                            // Right-side AI panel for the focused tab; its left edge drags to resize.
                            com.multiviewer.ui.ai.AiPanelResizeHandle(appState)
                            com.multiviewer.ui.ai.AiChatPanel(
                                appState = appState,
                                tab = currentTab,
                                language = language,
                                controller = aiController,
                                onOpenFallback = {
                                    appState.aiPromptTargetWarning = null
                                    appState.aiPromptWindowOpen = true
                                },
                                onClose = { appState.aiPanelOpen = false },
                                modifier = Modifier.width(appState.aiPanelWidthDp.dp).fillMaxHeight(),
                            )
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
            if (!isMacOS) {
                com.multiviewer.ui.menu.CustomMenuOverlay(
                    builtMenuBar,
                    navigationState = menuNavigation,
                    onNavigationStateChange = { menuNavigation = it },
                    layout = menuBarLayout,
                )
            }
            }
        }
      }
    }
}
