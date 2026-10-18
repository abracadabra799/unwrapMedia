package com.multiviewer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import com.multiviewer.cache.ThumbnailDiskCache
import com.multiviewer.cache.ThumbnailSourceFingerprint
import com.multiviewer.parser.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.EventQueue
import java.awt.FileDialog
import java.awt.Frame
import java.awt.image.BufferedImage
import java.io.File
import java.io.RandomAccessFile
import java.util.Locale
import java.util.concurrent.Executors
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image as SkiaImage

private val compareExecutor = Executors.newFixedThreadPool(4) { runnable ->
    Thread(runnable).apply { isDaemon = true }
}

private val progressiveCompareImageLoader = ProgressiveCompareImageLoader(
    decodePreview = { encoded, edge -> ImageAnalyzer.decodeThumbnail(encoded, edge) },
    decodeFinal = { encoded -> ImageAnalyzer.decodePrimaryBitmap(encoded) },
)

enum class MediaCompareTab {
    VISUAL,
    STRUCTURE,
    METADATA,
    HEX,
}

/**
 * Which of the two compare slots a browse produced. A null slot is left as it was.
 *
 * [refusedCount] is set instead when more files were picked than a comparison can use; both slots
 * are then null and the caller shows that count rather than applying anything.
 */
data class ComparePick(val fileA: File?, val fileB: File?, val refusedCount: Int? = null)

/** A comparison names exactly two sides, so a browse can contribute at most this many files. */
const val MAX_COMPARE_SELECTION = 2

/**
 * Works out which slots a browse fills, so one trip through the file dialog can set up both sides.
 *
 * Selecting two files fills A and B together -- the case this exists for, since comparing means
 * naming two files and doing that in two separate dialogs is the friction being removed. They are
 * assigned in name order rather than selection order: the order the OS reports a multi-selection in
 * isn't visible to the user afterwards, so name order at least makes the same two files land the
 * same way round every time, and the window's ⇄ button fixes it when the guess is backwards.
 *
 * Selecting one file fills only [targetIsA]'s slot, exactly as before multi-select existed.
 *
 * Selecting more than [MAX_COMPARE_SELECTION] is refused rather than trimmed. The native dialog
 * cannot cap a selection (java.awt.FileDialog offers only setMultipleMode(boolean)), so refusing is
 * the closest thing to preventing it -- and quietly keeping two of three would drop the rest with
 * no explanation, which is precisely the surprise worth avoiding.
 */
fun resolveComparePick(picked: List<File>, targetIsA: Boolean): ComparePick {
    if (picked.size > MAX_COMPARE_SELECTION) return ComparePick(null, null, refusedCount = picked.size)
    val sorted = picked.sortedBy { it.name.lowercase(Locale.US) }
    return when {
        sorted.isEmpty() -> ComparePick(null, null)
        sorted.size == 1 -> if (targetIsA) ComparePick(sorted[0], null) else ComparePick(null, sorted[0])
        else -> ComparePick(sorted[0], sorted[1])
    }
}

/**
 * Same output shape as [resolveComparePick], but for a drag-and-drop rather than a
 * dialog browse: a single dropped file's slot is decided by where it landed instead
 * of which button was clicked. [xFraction] is the drop's horizontal position as a
 * fraction of the window's width (0f = left edge, 1f = right edge) -- left half lands
 * in A (matching where the A panel is drawn), right half (including exactly the
 * midpoint) lands in B.
 *
 * Two or more dropped files behave identically to [resolveComparePick]: sorted by
 * name for exactly two, refused outright for more than [MAX_COMPARE_SELECTION].
 */
fun resolveDroppedFiles(picked: List<File>, xFraction: Float): ComparePick {
    if (picked.size > MAX_COMPARE_SELECTION) return ComparePick(null, null, refusedCount = picked.size)
    val sorted = picked.sortedBy { it.name.lowercase(Locale.US) }
    return when {
        sorted.isEmpty() -> ComparePick(null, null)
        sorted.size == 1 -> if (xFraction < 0.5f) ComparePick(sorted[0], null) else ComparePick(null, sorted[0])
        else -> ComparePick(sorted[0], sorted[1])
    }
}

enum class VisualCompareMode {
    SPLIT_WIPER,
    SIDE_BY_SIDE,
    DIFF_HEATMAP,
    BLINK,
}

enum class CompareWindowMode {
    EXPLORER,
    COMPARE,
}

fun isValidCompareCount(count: Int): Boolean = count == 2

internal fun formatCompareFileSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 * 1024 -> "%.2f GB".format(Locale.US, bytes / (1024.0 * 1024.0 * 1024.0))
    bytes >= 1024 * 1024 -> "%.2f MB".format(Locale.US, bytes / (1024.0 * 1024.0))
    bytes >= 1024 -> "%.1f KB".format(Locale.US, bytes / 1024.0)
    else -> "$bytes B"
}

enum class CompareSlot {
    SLOT_A,
    SLOT_B,
    SLOT_C,
    SLOT_D,
}

data class CompareMediaInfo(
    val file: File,
    val root: BoxNode?,
    val forensic: ImageForensicData?,
    val bitmap: ImageBitmap?,
    val summary: MediaSummary?,
    val fileSize: Long,
    val isVideo: Boolean = false,
    val durationSeconds: Double = 0.0,
    val isLoading: Boolean = false,
    val error: String? = null,
)

data class MetadataDiffRow(
    val category: String,
    val key: String,
    val valueA: String,
    val valueB: String,
    val isDifferent: Boolean,
)

data class StructureDiffRow(
    val path: String,
    val name: String,
    val sizeA: Long?,
    val sizeB: Long?,
    val offsetA: Long?,
    val offsetB: Long?,
    val status: DiffStatus,
    val summaryA: String?,
    val summaryB: String?,
)

enum class DiffStatus {
    MATCH,
    MODIFIED,
    ADDED_IN_B,
    REMOVED_IN_B,
}

@Composable
fun ImageCompareWindow(
    appState: AppState,
    language: AppLanguage = loadLanguage(),
    initialFileA: File? = null,
    initialFileB: File? = null,
    initialFiles: List<File> = emptyList(),
    onCloseRequest: () -> Unit,
) {
    val startingFiles = remember {
        val merged = if (initialFiles.isNotEmpty()) {
            initialFiles
        } else {
            listOfNotNull(initialFileA, initialFileB)
        }
        merged.take(2)
    }

    var windowMode by remember {
        mutableStateOf(
            if (startingFiles.size == 2) CompareWindowMode.COMPARE else CompareWindowMode.EXPLORER
        )
    }
    var compareFiles by remember { mutableStateOf(startingFiles) }

    var selectedTab by remember { mutableStateOf(MediaCompareTab.VISUAL) }
    var fileA by remember { mutableStateOf(compareFiles.getOrNull(0) ?: initialFileA) }
    var fileB by remember { mutableStateOf(compareFiles.getOrNull(1) ?: initialFileB) }
    var fileC by remember { mutableStateOf(compareFiles.getOrNull(2)) }
    var fileD by remember { mutableStateOf(compareFiles.getOrNull(3)) }
    var activeSlot by remember { mutableStateOf(CompareSlot.SLOT_A) }
    var tooManyPickedCount by remember { mutableStateOf<Int?>(null) }
    var folderA by remember { mutableStateOf<File?>(fileA?.parentFile) }
    var folderB by remember { mutableStateOf<File?>(fileB?.parentFile) }

    var infoA by remember { mutableStateOf<CompareMediaInfo?>(null) }
    var infoB by remember { mutableStateOf<CompareMediaInfo?>(null) }
    var infoC by remember { mutableStateOf<CompareMediaInfo?>(null) }
    var infoD by remember { mutableStateOf<CompareMediaInfo?>(null) }

    // Hoisted once here (instead of computed independently in MetadataDiffView and
    // VisualDiffView) so extractMetadataDiffRows's file I/O -- motion-photo/SEF probing via
    // ByteReader.open -- runs a single time per file pair, not once per tab. On IO, not inside
    // remember{} on the UI thread.
    val metadataRows by produceState(emptyList<MetadataDiffRow>(), infoA, infoB) {
        val a = infoA
        val b = infoB
        value = if (a == null || b == null) emptyList() else withContext(Dispatchers.IO) { extractMetadataDiffRows(a, b) }
    }
    val captureMismatches = remember(metadataRows, infoA, infoB) {
        if (infoA?.isVideo != false || infoB?.isVideo != false) emptyList() else captureConditionMismatches(metadataRows)
    }

    fun loadInfo(file: File?, session: CompareLoadSession, onLoaded: (CompareMediaInfo?) -> Unit) {
        if (file == null || !file.exists()) {
            session.deliver { onLoaded(null) }
            return
        }

        // Check if file is already loaded in one of the AppState tabs
        val matchingTab = appState.tabs.find { it.file.absolutePath == file.absolutePath }
        if (matchingTab != null && matchingTab.root != null) {
            val isVid = matchingTab.type == MediaType.VIDEO || isVideoExtension(file)
            val dur = extractVideoDuration(matchingTab.root, matchingTab.mediaSummary)
            session.deliver { onLoaded(
                CompareMediaInfo(
                    file = file,
                    root = matchingTab.root,
                    forensic = matchingTab.imageForensic,
                    bitmap = matchingTab.imageForensic?.bitmap,
                    summary = matchingTab.mediaSummary,
                    fileSize = file.length(),
                    isVideo = isVid,
                    durationSeconds = dur,
                    isLoading = false,
                )
            ) }
            return
        }

        // Otherwise load & parse in background
        session.deliver { onLoaded(CompareMediaInfo(file = file, root = null, forensic = null, bitmap = null, summary = null, fileSize = file.length(), isLoading = true)) }
        session.track(submitDiscardingCancelled(compareExecutor) {
            try {
                ByteReader.open(file).use { reader ->
                    val root = parseFile(file, reader)
                    val summary = buildMediaSummary(root, file, reader)
                    val isVid = summary.category == MediaCategory.VIDEO || isVideoExtension(file)
                    val dur = extractVideoDuration(root, summary)

                    if (isVid) {
                        session.track(FrameFullSizeDecoder.decodeFrameAsync(file, 0.0) { firstFrame ->
                            session.post {
                                onLoaded(
                                    CompareMediaInfo(
                                        file = file,
                                        root = root,
                                        forensic = null,
                                        bitmap = firstFrame,
                                        summary = summary,
                                        fileSize = file.length(),
                                        isVideo = true,
                                        durationSeconds = dur,
                                        isLoading = false,
                                    )
                                )
                            }
                        })
                    } else {
                        val forensic = ImageAnalyzer.analyze(file, root, reader)
                        var previewBitmap = forensic.embeddedThumbnail
                        val decodedBitmap = progressiveCompareImageLoader.load(file, previewBitmap) { preview ->
                            previewBitmap = preview
                            session.post {
                                onLoaded(
                                    CompareMediaInfo(
                                        file = file,
                                        root = root,
                                        forensic = forensic.copy(bitmap = preview),
                                        bitmap = preview,
                                        summary = summary,
                                        fileSize = file.length(),
                                        isVideo = false,
                                        durationSeconds = 0.0,
                                        isLoading = true,
                                    )
                                )
                            }
                        }

                        if (decodedBitmap != null) {
                            session.post {
                                onLoaded(
                                    CompareMediaInfo(
                                        file = file,
                                        root = root,
                                        forensic = forensic.copy(bitmap = decodedBitmap),
                                        bitmap = decodedBitmap,
                                        summary = summary,
                                        fileSize = file.length(),
                                        isVideo = false,
                                        durationSeconds = 0.0,
                                        isLoading = false,
                                    )
                                )
                            }
                        } else {
                            session.track(FfmpegImageSnapshotDecoder.decodeFirstFrameAsync(file) { fallbackBitmap ->
                                session.post {
                                    onLoaded(
                                        CompareMediaInfo(
                                            file = file,
                                            root = root,
                                            forensic = forensic.copy(bitmap = fallbackBitmap ?: previewBitmap),
                                            bitmap = fallbackBitmap ?: previewBitmap,
                                            summary = summary,
                                            fileSize = file.length(),
                                            isVideo = false,
                                            durationSeconds = 0.0,
                                            isLoading = false,
                                        )
                                    )
                                }
                            })
                        }
                    }
                }
            } catch (e: Exception) {
                session.post {
                    onLoaded(
                        CompareMediaInfo(
                            file = file,
                            root = null,
                            forensic = null,
                            bitmap = null,
                            summary = null,
                            fileSize = file.length(),
                            isLoading = false,
                            error = e.message ?: e.toString(),
                        )
                    )
                }
            }
        })
    }

    DisposableEffect(fileA) {
        val session = CompareLoadSession()
        loadInfo(fileA, session) { infoA = it }
        onDispose { session.close() }
    }
    DisposableEffect(fileB) {
        val session = CompareLoadSession()
        loadInfo(fileB, session) { infoB = it }
        onDispose { session.close() }
    }
    DisposableEffect(fileC) {
        val session = CompareLoadSession()
        loadInfo(fileC, session) { infoC = it }
        onDispose { session.close() }
    }
    DisposableEffect(fileD) {
        val session = CompareLoadSession()
        loadInfo(fileD, session) { infoD = it }
        onDispose { session.close() }
    }

    LaunchedEffect(compareFiles) {
        fileA = compareFiles.getOrNull(0)
        fileB = compareFiles.getOrNull(1)
        fileC = compareFiles.getOrNull(2)
        fileD = compareFiles.getOrNull(3)
        folderA = fileA?.parentFile
        folderB = fileB?.parentFile
    }

    fun applyPick(pick: ComparePick) {
        tooManyPickedCount = pick.refusedCount
        pick.fileA?.let { fileA = it; folderA = it.parentFile }
        pick.fileB?.let { fileB = it; folderB = it.parentFile }
    }

    Window(
        onCloseRequest = onCloseRequest,
        title = if (language == AppLanguage.KO) "미디어 비교 (A/B) · 이미지/동영상" else "Media Compare (A/B) · Image/Video",
        state = rememberWindowState(size = DpSize(1150.dp, 840.dp)),
    ) {
        var dragHoverSide by remember { mutableStateOf<Boolean?>(null) }

        LaunchedEffect(Unit) {
            fun xFractionOf(point: java.awt.Point): Float =
                if (window.width > 0) point.x.toFloat() / window.width else 0f

            attachFileDropTarget(
                window = window,
                onDragPosition = { point ->
                    dragHoverSide = point?.let { xFractionOf(it) < 0.5f }
                },
                onFilesDropped = { files, point ->
                    val mediaFiles = files.filter {
                        it.isFile && it.extension.lowercase(Locale.US) in ALL_SUPPORTED_MEDIA_EXTENSIONS
                    }
                    if (mediaFiles.isNotEmpty()) {
                        applyPick(resolveDroppedFiles(mediaFiles, xFractionOf(point)))
                    }
                    dragHoverSide = null
                },
            )
        }

        val activeFolder = folderA ?: folderB ?: fileA?.parentFile ?: fileB?.parentFile
        // Listed off the UI thread; empty (so navigateSlot does nothing) until the listing arrives.
        val siblingMediaFiles = rememberFolderListing(activeFolder)?.mediaFiles ?: emptyList()

        fun navigateSlot(slot: CompareSlot, delta: Int) {
            if (siblingMediaFiles.isEmpty()) return
            if (slot == CompareSlot.SLOT_A) {
                val currentIdx = siblingMediaFiles.indexOfFirst { it.absolutePath == fileA?.absolutePath }
                val nextIdx = if (currentIdx >= 0) (currentIdx + delta).mod(siblingMediaFiles.size) else 0
                fileA = siblingMediaFiles[nextIdx]
                folderA = fileA?.parentFile
            } else {
                val currentIdx = siblingMediaFiles.indexOfFirst { it.absolutePath == fileB?.absolutePath }
                val nextIdx = if (currentIdx >= 0) (currentIdx + delta).mod(siblingMediaFiles.size) else 0
                fileB = siblingMediaFiles[nextIdx]
                folderB = fileB?.parentFile
            }
        }

        val focusRequester = remember { FocusRequester() }
        LaunchedEffect(Unit) {
            focusRequester.requestFocus()
        }

        Surface(
            modifier = Modifier
                .fillMaxSize()
                .focusRequester(focusRequester)
                .focusable()
                .onKeyEvent { keyEvent ->
                    if (keyEvent.type != KeyEventType.KeyDown) return@onKeyEvent false
                    when (keyEvent.key) {
                        Key.Escape -> {
                            if (windowMode == CompareWindowMode.COMPARE) {
                                windowMode = CompareWindowMode.EXPLORER
                                true
                            } else {
                                false
                            }
                        }
                        Key.One -> {
                            activeSlot = CompareSlot.SLOT_A
                            true
                        }
                        Key.Two -> {
                            activeSlot = CompareSlot.SLOT_B
                            true
                        }
                        Key.Tab -> {
                            activeSlot = if (activeSlot == CompareSlot.SLOT_A) CompareSlot.SLOT_B else CompareSlot.SLOT_A
                            true
                        }
                        Key.DirectionLeft -> {
                            if (keyEvent.isShiftPressed) {
                                navigateSlot(CompareSlot.SLOT_A, -1)
                                navigateSlot(CompareSlot.SLOT_B, -1)
                            } else {
                                navigateSlot(activeSlot, -1)
                            }
                            true
                        }
                        Key.DirectionRight -> {
                            if (keyEvent.isShiftPressed) {
                                navigateSlot(CompareSlot.SLOT_A, 1)
                                navigateSlot(CompareSlot.SLOT_B, 1)
                            } else {
                                navigateSlot(activeSlot, 1)
                            }
                            true
                        }
                        else -> false
                    }
                },
            color = MaterialTheme.colorScheme.background,
        ) {
            if (windowMode == CompareWindowMode.EXPLORER) {
                FastStoneExplorerView(
                    appState = appState,
                    language = language,
                    initialSelected = compareFiles,
                    onOpenCompare = { files ->
                        compareFiles = files.take(2)
                        fileA = compareFiles.getOrNull(0)
                        fileB = compareFiles.getOrNull(1)
                        fileC = null
                        fileD = null
                        folderA = fileA?.parentFile
                        folderB = fileB?.parentFile
                        selectedTab = MediaCompareTab.VISUAL
                        windowMode = CompareWindowMode.COMPARE
                    },
                )
            } else {
                Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                    // FastStone Top Mode Bar: Back to Explorer & Compare Info
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Button(
                                    onClick = { windowMode = CompareWindowMode.EXPLORER },
                                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.NeonBlue),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                    modifier = Modifier.height(28.dp),
                                ) {
                                    Text(
                                        text = if (language == AppLanguage.KO) "📂 파일 탐색기 (ESC)" else "📂 File Explorer (ESC)",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.Black,
                                    )
                                }
                                Spacer(Modifier.width(10.dp))
                                val fileCount = listOfNotNull(fileA, fileB, fileC, fileD).size
                                Text(
                                    text = if (fileCount == 4) {
                                        if (language == AppLanguage.KO) "4분할 비교 모드 (2x2 Quad Grid)" else "4-Split Quad Compare Mode"
                                    } else {
                                        if (language == AppLanguage.KO) "2분할 비교 모드" else "2-Split Compare Mode"
                                    },
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = AppColors.NeonGreen,
                                )
                            }
                            Text(
                                text = if (language == AppLanguage.KO) "ESC 키를 누르면 탐색기로 돌아갑니다" else "Press ESC to return to Explorer",
                                fontSize = 11.sp,
                                color = AppColors.TextSecondary,
                            )
                        }
                    }

                    // 1. Media Selection Bar (Tabs dropdown + File Pickers)
                    MediaSelectionBar(
                        appState = appState,
                        language = language,
                        fileA = fileA,
                        fileB = fileB,
                        folderA = folderA,
                        folderB = folderB,
                        infoA = infoA,
                        infoB = infoB,
                        activeSlot = activeSlot,
                        onSetActiveSlot = { activeSlot = it },
                        openTabFiles = appState.tabs.map { it.file },
                        tooManyPickedCount = tooManyPickedCount,
                        dragHoverSide = dragHoverSide,
                        onPick = ::applyPick,
                        onSelectA = { fileA = it },
                        onSelectB = { fileB = it },
                        onNavigateSlotA = { navigateSlot(CompareSlot.SLOT_A, it) },
                        onNavigateSlotB = { navigateSlot(CompareSlot.SLOT_B, it) },
                        onSwap = {
                            val tempFile = fileA
                            fileA = fileB
                            fileB = tempFile
                            val tempFolder = folderA
                            folderA = folderB
                            folderB = tempFolder
                        },
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    // 2. Navigation Tabs
                    TabRow(
                        selectedTabIndex = selectedTab.ordinal,
                        modifier = Modifier.fillMaxWidth().height(42.dp),
                    ) {
                        Tab(
                            selected = selectedTab == MediaCompareTab.VISUAL,
                            onClick = { selectedTab = MediaCompareTab.VISUAL },
                            modifier = Modifier.padding(horizontal = 2.dp, vertical = 2.dp).border(
                                width = 1.dp,
                                color = if (selectedTab == MediaCompareTab.VISUAL) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                shape = RoundedCornerShape(4.dp),
                            ),
                            text = { Text(if (language == AppLanguage.KO) "시각적 프레임/픽셀 비교 (Visual)" else "Visual Diff") },
                        )
                        Tab(
                            selected = selectedTab == MediaCompareTab.STRUCTURE,
                            onClick = { selectedTab = MediaCompareTab.STRUCTURE },
                            modifier = Modifier.padding(horizontal = 2.dp, vertical = 2.dp).border(
                                width = 1.dp,
                                color = if (selectedTab == MediaCompareTab.STRUCTURE) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                shape = RoundedCornerShape(4.dp),
                            ),
                            text = { Text(if (language == AppLanguage.KO) "구조 트리 비교 (Structure)" else "Structure Diff") },
                        )
                        Tab(
                            selected = selectedTab == MediaCompareTab.METADATA,
                            onClick = { selectedTab = MediaCompareTab.METADATA },
                            modifier = Modifier.padding(horizontal = 2.dp, vertical = 2.dp).border(
                                width = 1.dp,
                                color = if (selectedTab == MediaCompareTab.METADATA) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                shape = RoundedCornerShape(4.dp),
                            ),
                            text = { Text(if (language == AppLanguage.KO) "메타데이터 비교 (Metadata)" else "Metadata Diff") },
                        )
                        Tab(
                            selected = selectedTab == MediaCompareTab.HEX,
                            onClick = { selectedTab = MediaCompareTab.HEX },
                            modifier = Modifier.padding(horizontal = 2.dp, vertical = 2.dp).border(
                                width = 1.dp,
                                color = if (selectedTab == MediaCompareTab.HEX) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                shape = RoundedCornerShape(4.dp),
                            ),
                            text = { Text(if (language == AppLanguage.KO) "Hex 바이너리 비교 (Hex)" else "Hex Diff") },
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // 3. Comparison Content Views
                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        when (selectedTab) {
                            MediaCompareTab.STRUCTURE -> StructureDiffView(language, infoA, infoB)
                            MediaCompareTab.METADATA -> MetadataDiffView(language, infoA, infoB, metadataRows, captureMismatches)
                            MediaCompareTab.VISUAL -> VisualDiffView(
                                language = language,
                                infoA = infoA,
                                infoB = infoB,
                                quadInfos = if (listOfNotNull(fileA, fileB, fileC, fileD).size == 4) listOf(infoA, infoB, infoC, infoD) else emptyList(),
                                captureMismatches = captureMismatches,
                                activeSlot = activeSlot,
                                onSetActiveSlot = { activeSlot = it },
                            )
                            MediaCompareTab.HEX -> HexDiffView(language, fileA, fileB)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MediaSelectionBar(
    appState: AppState,
    language: AppLanguage,
    fileA: File?,
    fileB: File?,
    folderA: File?,
    folderB: File?,
    infoA: CompareMediaInfo?,
    infoB: CompareMediaInfo?,
    activeSlot: CompareSlot,
    onSetActiveSlot: (CompareSlot) -> Unit,
    openTabFiles: List<File>,
    tooManyPickedCount: Int?,
    dragHoverSide: Boolean?,
    onPick: (ComparePick) -> Unit,
    onSelectA: (File) -> Unit,
    onSelectB: (File) -> Unit,
    onNavigateSlotA: (Int) -> Unit,
    onNavigateSlotB: (Int) -> Unit,
    onSwap: () -> Unit,
) {
    fun openFileDialog(title: String, targetIsA: Boolean, onPicked: (ComparePick) -> Unit) {
        val dialog = FileDialog(null as Frame?, title, FileDialog.LOAD)
        appState.lastOpenedDirectory?.let { dir ->
            if (dir.exists() && dir.isDirectory) {
                dialog.directory = dir.absolutePath
            }
        }
        dialog.isMultipleMode = true
        dialog.isVisible = true
        val picked = dialog.files?.toList()?.takeIf { it.isNotEmpty() }
            ?: listOfNotNull(dialog.file?.let { name -> dialog.directory?.let { dir -> File(dir, name) } })
        if (picked.isEmpty()) return
        picked.firstOrNull()?.let { appState.updateLastOpenedDirectory(it) }
        onPicked(resolveComparePick(picked, targetIsA))
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
    ) {
        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
            if (tooManyPickedCount != null) {
                Surface(
                    color = AppColors.NeonRed.copy(alpha = 0.15f),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                ) {
                    Text(
                        text = if (language == AppLanguage.KO) {
                            "⚠ 비교는 최대 2개까지만 선택할 수 있습니다 (${tooManyPickedCount}개 선택됨). 다시 선택해 주세요."
                        } else {
                            "⚠ A comparison takes at most 2 files ($tooManyPickedCount selected). Please pick again."
                        },
                        fontSize = 11.sp,
                        color = AppColors.NeonRed,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Media A Slot Pill
                Surface(
                    color = if (activeSlot == CompareSlot.SLOT_A) Color(0xFF61AFEF).copy(alpha = 0.18f) else Color.Transparent,
                    shape = RoundedCornerShape(6.dp),
                    border = androidx.compose.foundation.BorderStroke(
                        if (activeSlot == CompareSlot.SLOT_A || dragHoverSide == true) 1.5.dp else 1.dp,
                        if (activeSlot == CompareSlot.SLOT_A || dragHoverSide == true) Color(0xFF61AFEF) else AppColors.Border,
                    ),
                    modifier = Modifier.weight(1f).clickable { onSetActiveSlot(CompareSlot.SLOT_A) },
                ) {
                    Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                color = Color(0xFF61AFEF),
                                shape = RoundedCornerShape(3.dp),
                                modifier = Modifier.padding(end = 6.dp),
                            ) {
                                Text(
                                    text = "A (1)",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.Black,
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                                )
                            }
                            Text(
                                text = if (language == AppLanguage.KO) "기준 미디어" else "Reference",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF61AFEF),
                                modifier = Modifier.weight(1f),
                            )
                            // Slot A quick prev / next
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = { onNavigateSlotA(-1) }, modifier = Modifier.size(20.dp)) {
                                    Text("◀", fontSize = 10.sp, color = AppColors.TextSecondary)
                                }
                                IconButton(onClick = { onNavigateSlotA(1) }, modifier = Modifier.size(20.dp)) {
                                    Text("▶", fontSize = 10.sp, color = AppColors.TextSecondary)
                                }
                                Spacer(Modifier.width(4.dp))
                                OutlinedButton(
                                    onClick = { openFileDialog(if (language == AppLanguage.KO) "미디어 A 선택" else "Select Media A", targetIsA = true, onPicked = onPick) },
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                                    modifier = Modifier.height(22.dp),
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        contentColor = AppColors.TextPrimary,
                                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                                    ),
                                    border = androidx.compose.foundation.BorderStroke(0.5.dp, AppColors.Border),
                                    shape = RoundedCornerShape(4.dp),
                                ) {
                                    Text("📂", fontSize = 11.sp)
                                }
                            }
                        }

                        Spacer(Modifier.height(3.dp))

                        FileDropdownOrLabel(
                            selectedFile = fileA,
                            selectedFolder = folderA,
                            language = language,
                            openTabFiles = openTabFiles,
                            onSelect = onSelectA,
                            onOpenBrowse = { openFileDialog(if (language == AppLanguage.KO) "미디어 A 선택" else "Select Media A", targetIsA = true, onPicked = onPick) },
                        )

                        if (infoA != null) {
                            val typeLabel = if (infoA.isVideo) "🎬 동영상" else "🖼️ 이미지"
                            val durStr = if (infoA.isVideo && infoA.durationSeconds > 0) " | ${"%.2f".format(infoA.durationSeconds)}s" else ""
                            Text(
                                "$typeLabel | ${formatSize(infoA.fileSize)} | ${infoA.file.extension.uppercase(Locale.US)}$durStr",
                                fontSize = 10.sp,
                                color = AppColors.TextSecondary,
                                maxLines = 1,
                            )
                        }
                    }
                }

                // Swap Button
                IconButton(
                    onClick = onSwap,
                    modifier = Modifier.size(32.dp),
                ) {
                    Text("⇄", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = AppColors.NeonBlue)
                }

                // Media B Slot Pill
                Surface(
                    color = if (activeSlot == CompareSlot.SLOT_B) Color(0xFF98C379).copy(alpha = 0.18f) else Color.Transparent,
                    shape = RoundedCornerShape(6.dp),
                    border = androidx.compose.foundation.BorderStroke(
                        if (activeSlot == CompareSlot.SLOT_B || dragHoverSide == false) 1.5.dp else 1.dp,
                        if (activeSlot == CompareSlot.SLOT_B || dragHoverSide == false) Color(0xFF98C379) else AppColors.Border,
                    ),
                    modifier = Modifier.weight(1f).clickable { onSetActiveSlot(CompareSlot.SLOT_B) },
                ) {
                    Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                color = Color(0xFF98C379),
                                shape = RoundedCornerShape(3.dp),
                                modifier = Modifier.padding(end = 6.dp),
                            ) {
                                Text(
                                    text = "B (2)",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.Black,
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                                )
                            }
                            Text(
                                text = if (language == AppLanguage.KO) "비교 미디어" else "Target",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF98C379),
                                modifier = Modifier.weight(1f),
                            )
                            // Slot B quick prev / next
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = { onNavigateSlotB(-1) }, modifier = Modifier.size(20.dp)) {
                                    Text("◀", fontSize = 10.sp, color = AppColors.TextSecondary)
                                }
                                IconButton(onClick = { onNavigateSlotB(1) }, modifier = Modifier.size(20.dp)) {
                                    Text("▶", fontSize = 10.sp, color = AppColors.TextSecondary)
                                }
                                Spacer(Modifier.width(4.dp))
                                OutlinedButton(
                                    onClick = { openFileDialog(if (language == AppLanguage.KO) "미디어 B 선택" else "Select Media B", targetIsA = false, onPicked = onPick) },
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                                    modifier = Modifier.height(22.dp),
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        contentColor = AppColors.TextPrimary,
                                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                                    ),
                                    border = androidx.compose.foundation.BorderStroke(0.5.dp, AppColors.Border),
                                    shape = RoundedCornerShape(4.dp),
                                ) {
                                    Text("📂", fontSize = 11.sp)
                                }
                            }
                        }

                        Spacer(Modifier.height(3.dp))

                        FileDropdownOrLabel(
                            selectedFile = fileB,
                            selectedFolder = folderB,
                            language = language,
                            openTabFiles = openTabFiles,
                            onSelect = onSelectB,
                            onOpenBrowse = { openFileDialog(if (language == AppLanguage.KO) "미디어 B 선택" else "Select Media B", targetIsA = false, onPicked = onPick) },
                        )

                        if (infoB != null) {
                            val typeLabel = if (infoB.isVideo) "🎬 동영상" else "🖼️ 이미지"
                            val delta = if (infoA != null) infoB.fileSize - infoA.fileSize else 0L
                            val deltaStr = if (delta > 0) " (+${formatSize(delta)})" else if (delta < 0) " (-${formatSize(-delta)})" else ""
                            val durStr = if (infoB.isVideo && infoB.durationSeconds > 0) " | ${"%.2f".format(infoB.durationSeconds)}s" else ""
                            Text(
                                "$typeLabel | ${formatSize(infoB.fileSize)}$deltaStr | ${infoB.file.extension.uppercase(Locale.US)}$durStr",
                                fontSize = 10.sp,
                                color = AppColors.TextSecondary,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FileDropdownOrLabel(
    selectedFile: File?,
    selectedFolder: File?,
    language: AppLanguage,
    // Files already open in the main window. Listed above the folder contents because they are the
    // one source that spans folders -- the two files being compared often live nowhere near each
    // other, and picking them from here skips browsing entirely.
    openTabFiles: List<File>,
    onSelect: (File) -> Unit,
    onOpenBrowse: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    // When folder is chosen via browse dialog, scan all supported media files in that specific folder
    // Listed off the UI thread (empty until it arrives).
    val folderFiles = rememberFolderListing(selectedFolder)?.mediaFiles ?: emptyList()

    Box {
        Surface(
            modifier = Modifier.fillMaxWidth().clickable {
                if (selectedFolder == null && openTabFiles.isEmpty()) {
                    onOpenBrowse()
                } else {
                    expanded = true
                }
            }.padding(vertical = 2.dp),
            shape = RoundedCornerShape(4.dp),
            color = MaterialTheme.colorScheme.surface,
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val icon = if (selectedFile != null) {
                    val ext = selectedFile.extension.lowercase(Locale.US)
                    if (ext in VIDEO_EXTENSIONS) "🎬" else if (ext in AUDIO_EXTENSIONS) "🎵" else "🖼️"
                } else ""
                Text(
                    text = if (selectedFile != null) {
                        "$icon ${selectedFile.name}"
                    } else {
                        // Clicking the slot now opens the dropdown whenever there are tabs to offer,
                        // so the hint has to name that path too rather than only the Browse button.
                        if (openTabFiles.isNotEmpty()) {
                            if (language == AppLanguage.KO) "📑 클릭하여 열린 탭에서 선택 · 또는 [파일 찾기]" else "📑 Click to pick an open tab · or [Browse...]"
                        } else {
                            if (language == AppLanguage.KO) "📂 [파일 찾기]를 눌러 파일을 선택하세요" else "📂 Click [Browse...] to select file"
                        }
                    },
                    fontSize = 12.sp,
                    fontWeight = if (selectedFile != null) FontWeight.Medium else FontWeight.Normal,
                    color = if (selectedFile != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                )
                if (selectedFolder != null || openTabFiles.isNotEmpty()) {
                    Text("▾", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        if (selectedFolder != null || openTabFiles.isNotEmpty()) {
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.heightIn(max = 420.dp).widthIn(min = 320.dp, max = 540.dp),
            ) {
                if (openTabFiles.isNotEmpty()) {
                    Surface(color = Color(0xFF1E2838), modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = if (language == AppLanguage.KO) "📑 현재 열린 탭 (${openTabFiles.size}개)" else "📑 Open tabs (${openTabFiles.size})",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = AppColors.NeonGreen,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                        )
                    }
                    openTabFiles.forEach { file ->
                        val isCurrent = file.absolutePath == selectedFile?.absolutePath
                        val ext = file.extension.lowercase(Locale.US)
                        val icon = if (ext in VIDEO_EXTENSIONS) "🎬" else if (ext in AUDIO_EXTENSIONS) "🎵" else "🖼️"
                        DropdownMenuItem(
                            text = {
                                Text(
                                    "$icon ${file.name}",
                                    fontSize = 12.sp,
                                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isCurrent) AppColors.NeonGreen else MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                )
                            },
                            onClick = {
                                onSelect(file)
                                expanded = false
                            },
                        )
                    }
                }
                if (selectedFolder == null) {
                    // Nothing more to list until a folder has been browsed to.
                } else if (folderFiles.isEmpty()) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                if (language == AppLanguage.KO) "해당 폴더에 지원되는 미디어 파일 없음" else "No supported media files in this folder",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        onClick = { expanded = false },
                    )
                } else {
                    Surface(
                        color = Color(0xFF1E2838),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = if (language == AppLanguage.KO) "📁 ${selectedFolder.name} 폴더 내 파일 (${folderFiles.size}개)" else "📁 Folder: ${selectedFolder.name} (${folderFiles.size} files)",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = AppColors.NeonBlue,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                        )
                    }
                    folderFiles.forEach { file ->
                        val isCurrent = file.absolutePath == selectedFile?.absolutePath
                        val ext = file.extension.lowercase(Locale.US)
                        val icon = if (ext in VIDEO_EXTENSIONS) "🎬" else if (ext in AUDIO_EXTENSIONS) "🎵" else "🖼️"
                        DropdownMenuItem(
                            text = {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        "$icon ${file.name}",
                                        fontSize = 12.sp,
                                        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                                        color = if (isCurrent) AppColors.NeonGreen else MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.weight(1f),
                                        maxLines = 1,
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        formatSize(file.length()),
                                        fontSize = 10.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    if (isCurrent) {
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("✓", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = AppColors.NeonGreen)
                                    }
                                }
                            },
                            onClick = {
                                onSelect(file)
                                expanded = false
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun EmptyComparePlaceholder(language: AppLanguage) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            if (language == AppLanguage.KO) "비교할 두 미디어(이미지 또는 동영상)를 상단에서 선택해 주세요." else "Please select two media files (images or videos) to compare above.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.sp,
        )
    }
}

internal fun formatSize(bytes: Long): String {
    return when {
        bytes >= 1024 * 1024 -> "%.2f MB".format(bytes / (1024.0 * 1024.0))
        bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }
}

private fun isVideoExtension(file: File): Boolean {
    val ext = file.extension.lowercase(Locale.US)
    return ext in setOf("mp4", "mov", "mkv", "webm", "avi", "ts", "m4v", "flv", "wmv", "3gp")
}

private fun extractVideoDuration(root: BoxNode?, summary: MediaSummary?): Double {
    if (summary != null) {
        for (sec in summary.sections) {
            val durField = sec.fields.find { it.label.equals("Duration", ignoreCase = true) }?.value
            if (durField != null) {
                val match = Regex("""([\d.]+)""").find(durField)
                if (match != null) {
                    val d = match.groupValues[1].toDoubleOrNull()
                    if (d != null && d > 0) return d
                }
            }
        }
    }
    if (root != null) {
        val moov = findFirst(root) { it.type == "moov" }
        val mvhd = if (moov != null) findFirst(moov) { it.type == "mvhd" } else findFirst(root) { it.type == "mvhd" }
        if (mvhd != null) {
            val timescale = mvhd.fields.find { it.name == "timescale" }?.value?.toDoubleOrNull()
            val duration = mvhd.fields.find { it.name == "duration" }?.value?.toDoubleOrNull()
            if (timescale != null && duration != null && timescale > 0) {
                return duration / timescale
            }
        }
    }
    return 0.0
}
