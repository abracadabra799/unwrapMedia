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
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
import com.multiviewer.parser.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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

private val compareExecutor = Executors.newFixedThreadPool(4) { runnable ->
    Thread(runnable).apply { isDaemon = true }
}

enum class MediaCompareTab {
    STRUCTURE,
    METADATA,
    VISUAL,
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

private fun formatCompareFileSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 * 1024 -> "%.2f GB".format(Locale.US, bytes / (1024.0 * 1024.0 * 1024.0))
    bytes >= 1024 * 1024 -> "%.2f MB".format(Locale.US, bytes / (1024.0 * 1024.0))
    bytes >= 1024 -> "%.1f KB".format(Locale.US, bytes / 1024.0)
    else -> "$bytes B"
}

private object ExplorerThumbnailLoader {
    private val cache = object : java.util.LinkedHashMap<String, ImageBitmap>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?): Boolean {
            return size > 200
        }
    }
    private val inFlight = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val thumbExecutor = Executors.newFixedThreadPool(3) { r ->
        Thread(r).apply { isDaemon = true }
    }

    fun getThumbnail(file: File, onLoaded: (ImageBitmap) -> Unit): ImageBitmap? {
        val path = file.absolutePath
        synchronized(cache) {
            val cached = cache[path]
            if (cached != null) return cached
        }

        if (inFlight.add(path)) {
            thumbExecutor.submit {
                try {
                    val ext = file.extension.lowercase(Locale.US)
                    val isVid = ext in VIDEO_EXTENSIONS
                    var bitmap: ImageBitmap? = null

                    if (isVid) {
                        bitmap = FfmpegImageSnapshotDecoder.decodeSingleFrameToBitmap(
                            listOf(
                                FfmpegLocator.ffmpegPath(), "-y",
                                "-ss", "0.5",
                                "-i", file.absolutePath,
                                "-vf", "scale=160:-1",
                                "-frames:v", "1",
                                "-update", "1"
                            ),
                            tempExtension = ".jpg",
                            timeoutMs = 8_000L,
                        )
                        if (bitmap == null) {
                            bitmap = FfmpegImageSnapshotDecoder.decodeSingleFrameToBitmap(
                                listOf(
                                    FfmpegLocator.ffmpegPath(), "-y",
                                    "-i", file.absolutePath,
                                    "-vf", "scale=160:-1",
                                    "-frames:v", "1",
                                    "-update", "1"
                                ),
                                tempExtension = ".jpg",
                                timeoutMs = 8_000L,
                            )
                        }
                    } else {
                        // Image file: try fast primary decode with Skia first
                        val (primary, _) = ImageAnalyzer.decodePrimaryBitmapAndHistogram(file)
                        if (primary != null) {
                            bitmap = primary
                        } else {
                            // HEIC / RAW / Other: use ffmpeg scaled decode
                            bitmap = FfmpegImageSnapshotDecoder.decodeSingleFrameToBitmap(
                                listOf(
                                    FfmpegLocator.ffmpegPath(), "-y",
                                    "-i", file.absolutePath,
                                    "-vf", "scale='min(240,iw)':-1",
                                    "-frames:v", "1",
                                    "-update", "1"
                                ),
                                tempExtension = ".png",
                                timeoutMs = 10_000L,
                            )
                        }
                    }

                    if (bitmap != null) {
                        synchronized(cache) {
                            cache[path] = bitmap
                        }
                        EventQueue.invokeLater {
                            onLoaded(bitmap)
                        }
                    }
                } catch (_: Exception) {
                } finally {
                    inFlight.remove(path)
                }
            }
        }
        return null
    }
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

    var selectedTab by remember { mutableStateOf(MediaCompareTab.STRUCTURE) }
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
    // ByteReader.open -- runs a single time per file pair, not once per tab.
    val metadataRows = remember(infoA, infoB) {
        val a = infoA
        val b = infoB
        if (a == null || b == null) emptyList() else extractMetadataDiffRows(a, b)
    }
    val captureMismatches = remember(metadataRows, infoA, infoB) {
        if (infoA?.isVideo != false || infoB?.isVideo != false) emptyList() else captureConditionMismatches(metadataRows)
    }

    fun loadInfo(file: File?, onLoaded: (CompareMediaInfo?) -> Unit) {
        if (file == null || !file.exists()) {
            onLoaded(null)
            return
        }

        // Check if file is already loaded in one of the AppState tabs
        val matchingTab = appState.tabs.find { it.file.absolutePath == file.absolutePath }
        if (matchingTab != null && matchingTab.root != null) {
            val isVid = matchingTab.type == MediaType.VIDEO || isVideoExtension(file)
            val dur = extractVideoDuration(matchingTab.root, matchingTab.mediaSummary)
            onLoaded(
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
            )
            return
        }

        // Otherwise load & parse in background
        onLoaded(CompareMediaInfo(file = file, root = null, forensic = null, bitmap = null, summary = null, fileSize = file.length(), isLoading = true))
        compareExecutor.execute {
            try {
                ByteReader.open(file).use { reader ->
                    val root = parseFile(file, reader)
                    val summary = buildMediaSummary(root, file, reader)
                    val isVid = summary.category == MediaCategory.VIDEO || isVideoExtension(file)
                    val dur = extractVideoDuration(root, summary)

                    if (isVid) {
                        FrameFullSizeDecoder.decodeFrameAsync(file, 0.0) { firstFrame ->
                            EventQueue.invokeLater {
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
                        }
                    } else {
                        val forensic = ImageAnalyzer.analyze(file, root, reader)
                        val (decodedBitmap, _) = ImageAnalyzer.decodePrimaryBitmapAndHistogram(file)

                        if (decodedBitmap != null) {
                            EventQueue.invokeLater {
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
                            FfmpegImageSnapshotDecoder.decodeFirstFrameAsync(file) { fallbackBitmap ->
                                EventQueue.invokeLater {
                                    onLoaded(
                                        CompareMediaInfo(
                                            file = file,
                                            root = root,
                                            forensic = forensic.copy(bitmap = fallbackBitmap),
                                            bitmap = fallbackBitmap,
                                            summary = summary,
                                            fileSize = file.length(),
                                            isVideo = false,
                                            durationSeconds = 0.0,
                                            isLoading = false,
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                EventQueue.invokeLater {
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
        }
    }

    LaunchedEffect(fileA) { loadInfo(fileA) { infoA = it } }
    LaunchedEffect(fileB) { loadInfo(fileB) { infoB = it } }
    LaunchedEffect(fileC) { loadInfo(fileC) { infoC = it } }
    LaunchedEffect(fileD) { loadInfo(fileD) { infoD = it } }

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
        title = if (language == AppLanguage.KO) "미디어 비교 분석기 (이미지/동영상)" else "Media Comparison Analyzer (Image/Video)",
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
        val siblingMediaFiles = remember(activeFolder?.absolutePath) {
            if (activeFolder != null && activeFolder.exists() && activeFolder.isDirectory) {
                try {
                    activeFolder.listFiles { f ->
                        f.isFile && !f.isHidden && f.extension.lowercase(Locale.US) in ALL_SUPPORTED_MEDIA_EXTENSIONS
                    }?.sortedBy { it.name.lowercase(Locale.US) }?.toList() ?: emptyList()
                } catch (_: Exception) {
                    emptyList()
                }
            } else emptyList()
        }

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
                            selected = selectedTab == MediaCompareTab.STRUCTURE,
                            onClick = { selectedTab = MediaCompareTab.STRUCTURE },
                            text = { Text(if (language == AppLanguage.KO) "구조 트리 비교 (Structure)" else "Structure Diff") },
                        )
                        Tab(
                            selected = selectedTab == MediaCompareTab.METADATA,
                            onClick = { selectedTab = MediaCompareTab.METADATA },
                            text = { Text(if (language == AppLanguage.KO) "메타데이터 비교 (Metadata)" else "Metadata Diff") },
                        )
                        Tab(
                            selected = selectedTab == MediaCompareTab.VISUAL,
                            onClick = { selectedTab = MediaCompareTab.VISUAL },
                            text = { Text(if (language == AppLanguage.KO) "시각적 프레임/픽셀 비교 (Visual)" else "Visual Diff") },
                        )
                        Tab(
                            selected = selectedTab == MediaCompareTab.HEX,
                            onClick = { selectedTab = MediaCompareTab.HEX },
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
fun FastStoneExplorerView(
    appState: AppState,
    language: AppLanguage,
    initialSelected: List<File>,
    onOpenCompare: (List<File>) -> Unit,
) {
    var currentFolder by remember {
        mutableStateOf(
            initialSelected.firstOrNull()?.parentFile
                ?: appState.selectedFolder
                ?: appState.lastOpenedDirectory
                ?: File(System.getProperty("user.home"))
        )
    }

    var selectedFiles by remember {
        mutableStateOf(initialSelected.take(2))
    }

    var toastMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(toastMessage) {
        if (toastMessage != null) {
            delay(2800)
            toastMessage = null
        }
    }

    var searchQuery by remember { mutableStateOf("") }
    var isGridView by remember { mutableStateOf(true) }

    val folderFiles = remember(currentFolder) {
        try {
            if (currentFolder.exists() && currentFolder.isDirectory) {
                currentFolder.listFiles()?.toList() ?: emptyList()
            } else {
                emptyList()
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    val subDirectories = remember(folderFiles) {
        folderFiles.filter { it.isDirectory && !it.isHidden && it.canRead() }
            .sortedBy { it.name.lowercase(Locale.US) }
    }

    val mediaFiles = remember(folderFiles, searchQuery) {
        folderFiles.filter {
            it.isFile && !it.isHidden && it.extension.lowercase(Locale.US) in ALL_SUPPORTED_MEDIA_EXTENSIONS &&
                    (searchQuery.isBlank() || it.name.contains(searchQuery, ignoreCase = true))
        }.sortedBy { it.name.lowercase(Locale.US) }
    }

    fun toggleFileSelection(file: File) {
        val existingIndex = selectedFiles.indexOfFirst { it.absolutePath == file.absolutePath }
        if (existingIndex >= 0) {
            selectedFiles = selectedFiles.filterIndexed { index, _ -> index != existingIndex }
        } else {
            if (selectedFiles.size < 2) {
                selectedFiles = selectedFiles + file
            } else {
                toastMessage = if (language == AppLanguage.KO) {
                    "⚠️ 최대 2개의 파일만 선택할 수 있습니다. 기존 선택을 해제하고 다시 선택하세요."
                } else {
                    "⚠️ You can select at most 2 files. Uncheck a file to select another."
                }
            }
        }
    }

    fun openCompareIfValid() {
        if (selectedFiles.size == 2) {
            onOpenCompare(selectedFiles)
        }
    }

    val explorerFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        explorerFocusRequester.requestFocus()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .focusRequester(explorerFocusRequester)
                .focusable()
                .onKeyEvent { keyEvent ->
                    if (keyEvent.type == KeyEventType.KeyDown) {
                        when (keyEvent.key) {
                            Key.Spacebar, Key.Enter -> {
                                if (selectedFiles.size == 2) {
                                    openCompareIfValid()
                                    true
                                } else {
                                    false
                                }
                            }
                            else -> false
                        }
                    } else {
                        false
                    }
                }
                .padding(10.dp)
        ) {
        // 1. Top Navigation Bar: Breadcrumb + Search + View Mode
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                // Folder navigation: Parent button & Breadcrumb path
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(
                        onClick = {
                            currentFolder.parentFile?.let { parent ->
                                if (parent.exists() && parent.canRead()) {
                                    currentFolder = parent
                                }
                            }
                        },
                        enabled = currentFolder.parentFile != null,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.height(28.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    ) {
                        Text(
                            text = "⬆ 상위 폴더",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }

                    Spacer(Modifier.width(8.dp))

                    Text(
                        text = "📁 ${currentFolder.absolutePath}",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = AppColors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(Modifier.width(12.dp))

                // Search box
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .width(180.dp)
                        .height(28.dp)
                        .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(4.dp))
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp),
                ) {
                    Text("🔍", fontSize = 11.sp)
                    Spacer(Modifier.width(4.dp))
                    BasicTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                        decorationBox = { innerTextField ->
                            if (searchQuery.isEmpty()) {
                                Text(
                                    text = if (language == AppLanguage.KO) "파일 검색..." else "Search files...",
                                    fontSize = 11.sp,
                                    color = AppColors.TextSecondary,
                                )
                            }
                            innerTextField()
                        }
                    )
                }

                Spacer(Modifier.width(8.dp))

                // Grid / List View Toggle
                Button(
                    onClick = { isGridView = !isGridView },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    modifier = Modifier.height(28.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surface),
                ) {
                    Text(
                        text = if (isGridView) "📋 목록" else "▦ 그리드",
                        fontSize = 11.sp,
                        color = AppColors.TextPrimary,
                    )
                }
            }
        }

        // 2. Main Content Split: Subdirectory navigation sidebar + Media files explorer
        Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
            // Sidebar: Subfolders
            Surface(
                modifier = Modifier
                    .width(180.dp)
                    .fillMaxHeight(),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
                shape = RoundedCornerShape(6.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Column(modifier = Modifier.fillMaxSize().padding(6.dp)) {
                    Text(
                        text = if (language == AppLanguage.KO) "하위 폴더 (${subDirectories.size})" else "Folders (${subDirectories.size})",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = AppColors.TextSecondary,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )

                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(subDirectories, key = { it.absolutePath }) { folder ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clipToBounds()
                                    .clickable { currentFolder = folder }
                                    .padding(vertical = 3.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text("📁", fontSize = 11.sp)
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    text = folder.name,
                                    fontSize = 11.sp,
                                    color = AppColors.TextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.width(8.dp))

            // Main Explorer Area: Media files grid/list
            Surface(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
                shape = RoundedCornerShape(6.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                if (mediaFiles.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = if (language == AppLanguage.KO) "지원되는 미디어 파일이 없습니다" else "No supported media files found",
                            fontSize = 13.sp,
                            color = AppColors.TextSecondary,
                        )
                    }
                } else {
                    if (isGridView) {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(minSize = 140.dp),
                            modifier = Modifier.fillMaxSize().padding(6.dp),
                            contentPadding = PaddingValues(4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(mediaFiles, key = { it.absolutePath }) { file ->
                                val selectedIndex = selectedFiles.indexOfFirst { it.absolutePath == file.absolutePath }
                                val isSelected = selectedIndex >= 0

                                var thumbnailBitmap by remember(file.absolutePath) {
                                    mutableStateOf<ImageBitmap?>(null)
                                }

                                LaunchedEffect(file.absolutePath) {
                                    val cached = ExplorerThumbnailLoader.getThumbnail(file) { loaded ->
                                        thumbnailBitmap = loaded
                                    }
                                    if (cached != null) {
                                        thumbnailBitmap = cached
                                    }
                                }

                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f) else MaterialTheme.colorScheme.surface,
                                    border = androidx.compose.foundation.BorderStroke(
                                        width = if (isSelected) 2.dp else 1.dp,
                                        color = if (isSelected) AppColors.NeonBlue else MaterialTheme.colorScheme.outlineVariant,
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(145.dp)
                                        .clickable { toggleFileSelection(file) },
                                ) {
                                    Column(
                                        modifier = Modifier.fillMaxSize().padding(6.dp),
                                        verticalArrangement = Arrangement.SpaceBetween,
                                    ) {
                                        // Thumbnail / Preview Area
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(85.dp)
                                                .clipToBounds()
                                                .background(Color.Black.copy(alpha = 0.2f), RoundedCornerShape(4.dp)),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            val thumb = thumbnailBitmap
                                            if (thumb != null) {
                                                androidx.compose.foundation.Image(
                                                    bitmap = thumb,
                                                    contentDescription = file.name,
                                                    contentScale = ContentScale.Fit,
                                                    modifier = Modifier.fillMaxSize(),
                                                )
                                            } else {
                                                val isVid = file.extension.lowercase(Locale.US) in VIDEO_EXTENSIONS
                                                Text(
                                                    text = if (isVid) "🎬" else "🖼️",
                                                    fontSize = 28.sp,
                                                )
                                            }

                                            // Top Badge: Selection badge or Type badge
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .align(Alignment.TopStart)
                                                    .padding(4.dp),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.Top,
                                            ) {
                                                val isVid = file.extension.lowercase(Locale.US) in VIDEO_EXTENSIONS
                                                Surface(
                                                    color = Color.Black.copy(alpha = 0.65f),
                                                    shape = RoundedCornerShape(3.dp),
                                                ) {
                                                    Text(
                                                        text = if (isVid) "VIDEO" else file.extension.uppercase(Locale.US),
                                                        fontSize = 8.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = if (isVid) AppColors.NeonPurple else AppColors.NeonBlue,
                                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                                                    )
                                                }

                                                if (isSelected) {
                                                    val badge = if (selectedIndex == 0) "①" else "②"
                                                    Surface(
                                                        color = AppColors.NeonBlue,
                                                        shape = RoundedCornerShape(10.dp),
                                                    ) {
                                                        Text(
                                                            text = " $badge ",
                                                            fontSize = 11.sp,
                                                            fontWeight = FontWeight.Bold,
                                                            color = Color.Black,
                                                            modifier = Modifier.padding(horizontal = 3.dp),
                                                        )
                                                    }
                                                }
                                            }
                                        }

                                        // File info
                                        Column(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                                            Text(
                                                text = file.name,
                                                fontSize = 11.sp,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                                color = if (isSelected) AppColors.NeonBlue else AppColors.TextPrimary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            Text(
                                                text = formatCompareFileSize(file.length()),
                                                fontSize = 9.sp,
                                                color = AppColors.TextSecondary,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        // List View with thumbnail preview
                        LazyColumn(
                            modifier = Modifier.fillMaxSize().padding(6.dp),
                            contentPadding = PaddingValues(2.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            items(mediaFiles, key = { it.absolutePath }) { file ->
                                val selectedIndex = selectedFiles.indexOfFirst { it.absolutePath == file.absolutePath }
                                val isSelected = selectedIndex >= 0

                                var thumbnailBitmap by remember(file.absolutePath) {
                                    mutableStateOf<ImageBitmap?>(null)
                                }

                                LaunchedEffect(file.absolutePath) {
                                    val cached = ExplorerThumbnailLoader.getThumbnail(file) { loaded ->
                                        thumbnailBitmap = loaded
                                    }
                                    if (cached != null) {
                                        thumbnailBitmap = cached
                                    }
                                }

                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f) else MaterialTheme.colorScheme.surface.copy(alpha = 0.4f),
                                    border = androidx.compose.foundation.BorderStroke(
                                        width = if (isSelected) 1.5.dp else 1.dp,
                                        color = if (isSelected) AppColors.NeonBlue else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { toggleFileSelection(file) }
                                        .padding(horizontal = 2.dp, vertical = 1.dp),
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.weight(1f),
                                        ) {
                                            if (isSelected) {
                                                val badge = if (selectedIndex == 0) "①" else "②"
                                                Text(
                                                    text = badge,
                                                    fontSize = 13.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = AppColors.NeonBlue,
                                                )
                                                Spacer(Modifier.width(8.dp))
                                            }

                                            // Thumbnail icon / small preview
                                            Box(
                                                modifier = Modifier
                                                    .size(36.dp, 28.dp)
                                                    .clipToBounds()
                                                    .background(Color.Black.copy(alpha = 0.25f), RoundedCornerShape(3.dp)),
                                                contentAlignment = Alignment.Center,
                                            ) {
                                                val thumb = thumbnailBitmap
                                                if (thumb != null) {
                                                    androidx.compose.foundation.Image(
                                                        bitmap = thumb,
                                                        contentDescription = file.name,
                                                        contentScale = ContentScale.Crop,
                                                        modifier = Modifier.fillMaxSize(),
                                                    )
                                                } else {
                                                    val isVid = file.extension.lowercase(Locale.US) in VIDEO_EXTENSIONS
                                                    Text(if (isVid) "🎬" else "🖼️", fontSize = 12.sp)
                                                }
                                            }

                                            Spacer(Modifier.width(8.dp))

                                            Text(
                                                text = file.name,
                                                fontSize = 12.sp,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                                color = if (isSelected) AppColors.NeonBlue else AppColors.TextPrimary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }

                                        Text(
                                            text = formatCompareFileSize(file.length()),
                                            fontSize = 11.sp,
                                            color = AppColors.TextSecondary,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // 3. Bottom Action Bar: Selection count, clear button, and Open Compare button
        val count = selectedFiles.size
        val isValidCount = count == 2 || count == 4

        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (language == AppLanguage.KO) "선택된 파일: " else "Selected files: ",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = AppColors.TextPrimary,
                    )
                    Text(
                        text = "$count / 2",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isValidCount) AppColors.NeonGreen else if (count > 0) AppColors.NeonYellow else AppColors.TextSecondary,
                    )

                    if (selectedFiles.isNotEmpty()) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "( " + selectedFiles.mapIndexed { idx, f ->
                                val badge = if (idx == 0) "①" else "②"
                                "$badge ${f.name}"
                            }.joinToString(", ") + " )",
                            fontSize = 11.sp,
                            color = AppColors.TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 400.dp)
                        )

                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = if (language == AppLanguage.KO) "전체 취소" else "Clear",
                            fontSize = 11.sp,
                            color = AppColors.NeonRed,
                            modifier = Modifier
                                .clickable { selectedFiles = emptyList() }
                                .padding(2.dp)
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!isValidCount && count == 1) {
                        Text(
                            text = if (language == AppLanguage.KO) {
                                "비교할 1개의 파일을 더 선택하세요 (최대 2개)"
                            } else {
                                "Select 1 more file to compare (max 2)"
                            },
                            fontSize = 11.sp,
                            color = AppColors.NeonYellow,
                            modifier = Modifier.padding(end = 12.dp),
                        )
                    }

                    Button(
                        onClick = { openCompareIfValid() },
                        enabled = isValidCount,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AppColors.NeonGreen,
                            disabledContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                        ),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                        modifier = Modifier.height(34.dp),
                    ) {
                        Text(
                            text = if (language == AppLanguage.KO) "⚖️ 2분할 비교 열기 (Space / Enter)" else "⚖️ Open 2-Split Compare (Space / Enter)",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isValidCount) Color.Black else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                        )
                    }
                }
            }
        }
    }

    // Warning Toast Popup
    if (toastMessage != null) {
        Surface(
            color = Color(0xFF2B1D0C),
            shape = RoundedCornerShape(8.dp),
            border = androidx.compose.foundation.BorderStroke(1.5.dp, AppColors.NeonYellow),
            shadowElevation = 8.dp,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 60.dp),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = toastMessage ?: "",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = AppColors.NeonYellow,
                )
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
    val folderFiles = remember(selectedFolder?.absolutePath) {
        if (selectedFolder != null && selectedFolder.exists() && selectedFolder.isDirectory) {
            try {
                selectedFolder.listFiles { f ->
                    f.isFile && !f.isHidden && f.extension.lowercase(Locale.US) in ALL_SUPPORTED_MEDIA_EXTENSIONS
                }?.sortedBy { it.name.lowercase(Locale.US) }?.toList() ?: emptyList()
            } catch (_: Exception) {
                emptyList()
            }
        } else {
            emptyList()
        }
    }

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

// -------------------------------------------------------------------------------------------------
// 1. Structure Diff View
// -------------------------------------------------------------------------------------------------

@Composable
private fun StructureDiffView(language: AppLanguage, infoA: CompareMediaInfo?, infoB: CompareMediaInfo?) {
    if (infoA == null || infoB == null) {
        EmptyComparePlaceholder(language)
        return
    }

    val rows = remember(infoA.root, infoB.root) {
        computeStructureDiff(infoA.root, infoB.root)
    }

    var selectedRow by remember { mutableStateOf<StructureDiffRow?>(null) }
    var isDetailExpanded by remember { mutableStateOf(true) }

    Column(modifier = Modifier.fillMaxSize()) {
        val addedCount = rows.count { it.status == DiffStatus.ADDED_IN_B }
        val removedCount = rows.count { it.status == DiffStatus.REMOVED_IN_B }
        val modifiedCount = rows.count { it.status == DiffStatus.MODIFIED }

        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
            shape = RoundedCornerShape(4.dp),
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        ) {
            Row(modifier = Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (language == AppLanguage.KO)
                        "📊 박스 비교 요약: 변경 ${modifiedCount}개 | 추가 ${addedCount}개 | 제거 ${removedCount}개 (💡 행을 클릭하면 상세 Hex/ASCII 데이터를 비교할 수 있습니다)"
                    else
                        "📊 Box Diff Summary: Modified $modifiedCount | Added $addedCount | Removed $removedCount (💡 Click a row to inspect detailed Hex/ASCII diff)",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        // Table Header
        Row(
            modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Box / Marker Name", modifier = Modifier.weight(1.2f), fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Text("Media A (Offset / Size)", modifier = Modifier.weight(1.4f), fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Text("Media B (Offset / Size)", modifier = Modifier.weight(1.4f), fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Text("Status", modifier = Modifier.weight(0.8f), fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }

        HorizontalDivider()

        val listState = rememberLazyListState()
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                items(rows) { row ->
                    val isSelected = selectedRow == row
                    val defaultBgColor = when (row.status) {
                        DiffStatus.ADDED_IN_B -> Color(0xFF1B5E20).copy(alpha = 0.15f)
                        DiffStatus.REMOVED_IN_B -> Color(0xFFB71C1C).copy(alpha = 0.15f)
                        DiffStatus.MODIFIED -> Color(0xFFE65100).copy(alpha = 0.15f)
                        DiffStatus.MATCH -> Color.Transparent
                    }
                    val bgColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f) else defaultBgColor

                    val statusText = when (row.status) {
                        DiffStatus.ADDED_IN_B -> "➕ B에 추가됨"
                        DiffStatus.REMOVED_IN_B -> "➖ A에만 존재"
                        DiffStatus.MODIFIED -> "⚡ 크기/내용 변경"
                        DiffStatus.MATCH -> "✓ 일치"
                    }
                    val statusColor = when (row.status) {
                        DiffStatus.ADDED_IN_B -> Color(0xFF2E7D32)
                        DiffStatus.REMOVED_IN_B -> Color(0xFFC62828)
                        DiffStatus.MODIFIED -> Color(0xFFEF6C00)
                        DiffStatus.MATCH -> Color.Gray
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(bgColor)
                            .then(
                                if (isSelected) Modifier.border(1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp))
                                else Modifier
                            )
                            .clickable {
                                if (selectedRow == row) {
                                    isDetailExpanded = !isDetailExpanded
                                } else {
                                    selectedRow = row
                                    isDetailExpanded = true
                                }
                            }
                            .padding(horizontal = 8.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(row.name, modifier = Modifier.weight(1.2f), fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium)
                        Text(
                            if (row.sizeA != null) "0x${row.offsetA?.toString(16) ?: "0"} (${formatSize(row.sizeA)}) ${row.summaryA ?: ""}" else "-",
                            modifier = Modifier.weight(1.4f),
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                        Text(
                            if (row.sizeB != null) "0x${row.offsetB?.toString(16) ?: "0"} (${formatSize(row.sizeB)}) ${row.summaryB ?: ""}" else "-",
                            modifier = Modifier.weight(1.4f),
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                        Text(statusText, modifier = Modifier.weight(0.8f), fontSize = 11.sp, color = statusColor, fontWeight = FontWeight.Bold)
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                }
            }
            VerticalScrollbar(adapter = rememberScrollbarAdapter(listState), modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight())
        }

        // Collapsible Detail Hex & ASCII Diff Panel
        selectedRow?.let { row ->
            BoxHexDetailDiffPanel(
                language = language,
                fileA = infoA.file,
                fileB = infoB.file,
                row = row,
                isExpanded = isDetailExpanded,
                onToggleExpand = { isDetailExpanded = !isDetailExpanded },
                onClose = { selectedRow = null },
            )
        }
    }
}

@Composable
private fun BoxHexDetailDiffPanel(
    language: AppLanguage,
    fileA: File?,
    fileB: File?,
    row: StructureDiffRow,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    onClose: () -> Unit,
) {
    val rafA = remember(fileA) { if (fileA != null && fileA.exists()) RandomAccessFile(fileA, "r") else null }
    val rafB = remember(fileB) { if (fileB != null && fileB.exists()) RandomAccessFile(fileB, "r") else null }
    DisposableEffect(rafA, rafB) {
        onDispose {
            rafA?.close()
            rafB?.close()
        }
    }

    val offsetA = row.offsetA
    val sizeA = row.sizeA ?: 0L
    val offsetB = row.offsetB
    val sizeB = row.sizeB ?: 0L
    val maxSize = maxOf(sizeA, sizeB)
    val rowCount = if (maxSize > 0) ((maxSize + 15) / 16).toInt() else 0

    val listState = rememberLazyListState()

    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)),
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Header Bar
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth().clickable { onToggleExpand() },
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (isExpanded) "▼" else "▶",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(end = 6.dp),
                    )
                    Text(
                        "🔍 [${row.name.trim()}] 상세 Hex / String 데이터 비교",
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "A: 0x${row.offsetA?.toString(16) ?: "0"} (${formatSize(sizeA)})  vs  B: 0x${row.offsetB?.toString(16) ?: "0"} (${formatSize(sizeB)})",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    val statusText = when (row.status) {
                        DiffStatus.ADDED_IN_B -> "➕ B에 추가됨"
                        DiffStatus.REMOVED_IN_B -> "➖ A에만 존재"
                        DiffStatus.MODIFIED -> "⚡ 내용/크기 차이"
                        DiffStatus.MATCH -> "✓ 일치"
                    }
                    val statusColor = when (row.status) {
                        DiffStatus.ADDED_IN_B -> Color(0xFF2E7D32)
                        DiffStatus.REMOVED_IN_B -> Color(0xFFC62828)
                        DiffStatus.MODIFIED -> Color(0xFFEF6C00)
                        DiffStatus.MATCH -> Color.Gray
                    }
                    Text(statusText, fontSize = 11.sp, color = statusColor, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.width(8.dp))
                    IconButton(onClick = onClose, modifier = Modifier.size(24.dp)) {
                        Text("✕", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            if (isExpanded) {
                if (rowCount == 0) {
                    Box(modifier = Modifier.fillMaxWidth().height(60.dp), contentAlignment = Alignment.Center) {
                        Text("데이터가 비어있습니다 (0 bytes)", fontSize = 11.sp, color = Color.Gray)
                    }
                } else {
                    // Sub-Header
                    Row(
                        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)).padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Rel Offset", modifier = Modifier.width(75.dp), fontSize = 10.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                        Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                            Text("Media A (Hex)", modifier = Modifier.weight(1f), fontSize = 10.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                            Text("Media A (ASCII)", modifier = Modifier.width(130.dp), fontSize = 10.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                            Text("Media B (Hex)", modifier = Modifier.weight(1f), fontSize = 10.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                            Text("Media B (ASCII)", modifier = Modifier.width(130.dp), fontSize = 10.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                        }
                    }
                    HorizontalDivider()

                    Box(modifier = Modifier.fillMaxWidth().height(230.dp)) {
                        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                            items(rowCount) { rowIndex ->
                                val relOffset = rowIndex.toLong() * 16L
                                val (bytesA, bytesB, isDiff) = readBoxHexDiffRow(rafA, rafB, offsetA, sizeA, offsetB, sizeB, relOffset)

                                val bgColor = if (isDiff) Color(0xFFEF6C00).copy(alpha = 0.15f) else Color.Transparent
                                Row(
                                    modifier = Modifier.fillMaxWidth().background(bgColor).padding(horizontal = 8.dp, vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        "+%04X".format(relOffset),
                                        modifier = Modifier.width(75.dp),
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                    Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            if (bytesA.isNotEmpty()) formatHexBytes(bytesA) else "-",
                                            modifier = Modifier.weight(1f),
                                            fontSize = 11.sp,
                                            fontFamily = FontFamily.Monospace,
                                            color = if (isDiff && bytesA.isNotEmpty()) Color(0xFFFFB74D) else Color.Unspecified,
                                        )
                                        Text(
                                            if (bytesA.isNotEmpty()) formatAsciiBytes(bytesA) else "-",
                                            modifier = Modifier.width(130.dp),
                                            fontSize = 11.sp,
                                            fontFamily = FontFamily.Monospace,
                                            color = if (isDiff && bytesA.isNotEmpty()) Color(0xFFFFB74D) else MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            if (bytesB.isNotEmpty()) formatHexBytes(bytesB) else "-",
                                            modifier = Modifier.weight(1f),
                                            fontSize = 11.sp,
                                            fontFamily = FontFamily.Monospace,
                                            color = if (isDiff && bytesB.isNotEmpty()) Color(0xFFFF8A65) else Color.Unspecified,
                                        )
                                        Text(
                                            if (bytesB.isNotEmpty()) formatAsciiBytes(bytesB) else "-",
                                            modifier = Modifier.width(130.dp),
                                            fontSize = 11.sp,
                                            fontFamily = FontFamily.Monospace,
                                            color = if (isDiff && bytesB.isNotEmpty()) Color(0xFFFF8A65) else MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                        VerticalScrollbar(adapter = rememberScrollbarAdapter(listState), modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight())
                    }
                }
            }
        }
    }
}

internal fun readBoxHexDiffRow(
    rafA: RandomAccessFile?,
    rafB: RandomAccessFile?,
    offsetA: Long?,
    sizeA: Long,
    offsetB: Long?,
    sizeB: Long,
    relOffset: Long,
): Triple<ByteArray, ByteArray, Boolean> {
    val bA = if (offsetA != null && relOffset < sizeA && rafA != null) {
        val count = minOf(16L, sizeA - relOffset).toInt()
        val buf = ByteArray(count)
        synchronized(rafA) {
            rafA.seek(offsetA + relOffset)
            val read = rafA.read(buf, 0, count).coerceAtLeast(0)
            if (read == count) buf else buf.copyOf(read)
        }
    } else {
        byteArrayOf()
    }

    val bB = if (offsetB != null && relOffset < sizeB && rafB != null) {
        val count = minOf(16L, sizeB - relOffset).toInt()
        val buf = ByteArray(count)
        synchronized(rafB) {
            rafB.seek(offsetB + relOffset)
            val read = rafB.read(buf, 0, count).coerceAtLeast(0)
            if (read == count) buf else buf.copyOf(read)
        }
    } else {
        byteArrayOf()
    }

    val isDiff = !bA.contentEquals(bB)
    return Triple(bA, bB, isDiff)
}

internal data class FlatBoxItem(
    val path: String,
    val name: String,
    val node: BoxNode,
)

internal fun flattenBoxes(root: BoxNode?): List<FlatBoxItem> {
    if (root == null) return emptyList()
    val list = mutableListOf<FlatBoxItem>()
    fun traverse(node: BoxNode, currentPath: String, depth: Int) {
        if (node.type != "root") {
            val indent = "  ".repeat(depth)
            val displayName = "$indent${node.type}"
            val newPath = if (currentPath.isEmpty()) node.type else "$currentPath / ${node.type}"
            list.add(FlatBoxItem(path = newPath, name = displayName, node = node))
            node.children.forEach { traverse(it, newPath, depth + 1) }
        } else {
            node.children.forEach { traverse(it, "", 0) }
        }
    }
    traverse(root, "", 0)
    return list
}

internal fun computeStructureDiff(rootA: BoxNode?, rootB: BoxNode?): List<StructureDiffRow> {
    val listA = flattenBoxes(rootA)
    val listB = flattenBoxes(rootB)

    val n = listA.size
    val m = listB.size

    // dp[i][j] stores the max alignment score between listA[0 until i] and listB[0 until j]
    val dp = Array(n + 1) { IntArray(m + 1) }

    for (i in 1..n) {
        for (j in 1..m) {
            val a = listA[i - 1]
            val b = listB[j - 1]
            if (a.path == b.path) {
                val score = if (a.node.size == b.node.size && a.node.summary == b.node.summary) 4 else 3
                dp[i][j] = dp[i - 1][j - 1] + score
            } else if (a.node.type == b.node.type) {
                val score = if (a.node.size == b.node.size && a.node.summary == b.node.summary) 2 else 1
                dp[i][j] = dp[i - 1][j - 1] + score
            } else {
                dp[i][j] = maxOf(dp[i - 1][j], dp[i][j - 1])
            }
        }
    }

    // Backtrack to assemble aligned sequence
    var i = n
    var j = m
    val aligned = mutableListOf<StructureDiffRow>()

    while (i > 0 || j > 0) {
        val a = if (i > 0) listA[i - 1] else null
        val b = if (j > 0) listB[j - 1] else null

        val isMatchByPath = a != null && b != null && a.path == b.path
        val isMatchByType = a != null && b != null && a.node.type == b.node.type

        val matchScore = when {
            isMatchByPath -> if (a!!.node.size == b!!.node.size && a.node.summary == b.node.summary) 4 else 3
            isMatchByType -> if (a!!.node.size == b!!.node.size && a.node.summary == b.node.summary) 2 else 1
            else -> -1
        }

        if (a != null && b != null && matchScore > 0 && dp[i][j] == dp[i - 1][j - 1] + matchScore) {
            val isDiff = a.node.size != b.node.size || a.node.summary != b.node.summary
            aligned.add(
                StructureDiffRow(
                    path = a.path,
                    name = a.name,
                    sizeA = a.node.size,
                    sizeB = b.node.size,
                    offsetA = a.node.offset,
                    offsetB = b.node.offset,
                    status = if (isDiff) DiffStatus.MODIFIED else DiffStatus.MATCH,
                    summaryA = a.node.summary,
                    summaryB = b.node.summary,
                ),
            )
            i--
            j--
        } else if (j > 0 && (i == 0 || dp[i][j - 1] >= dp[i - 1][j])) {
            aligned.add(
                StructureDiffRow(
                    path = b!!.path,
                    name = b.name,
                    sizeA = null,
                    sizeB = b.node.size,
                    offsetA = null,
                    offsetB = b.node.offset,
                    status = DiffStatus.ADDED_IN_B,
                    summaryA = null,
                    summaryB = b.node.summary,
                ),
            )
            j--
        } else if (i > 0) {
            aligned.add(
                StructureDiffRow(
                    path = a!!.path,
                    name = a.name,
                    sizeA = a.node.size,
                    sizeB = null,
                    offsetA = a.node.offset,
                    offsetB = null,
                    status = DiffStatus.REMOVED_IN_B,
                    summaryA = a.node.summary,
                    summaryB = null,
                ),
            )
            i--
        }
    }

    return aligned.reversed()
}

// -------------------------------------------------------------------------------------------------
// 2. Metadata Diff View
// -------------------------------------------------------------------------------------------------

@Composable
private fun MetadataDiffView(
    language: AppLanguage,
    infoA: CompareMediaInfo?,
    infoB: CompareMediaInfo?,
    metadataRows: List<MetadataDiffRow>,
    captureMismatches: List<String>,
) {
    if (infoA == null || infoB == null) {
        EmptyComparePlaceholder(language)
        return
    }

    var onlyDiffs by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    val filteredRows = remember(metadataRows, onlyDiffs, searchQuery) {
        metadataRows.filter { row ->
            (!onlyDiffs || row.isDifferent) &&
                (searchQuery.isBlank() || row.key.contains(searchQuery, ignoreCase = true) || row.valueA.contains(searchQuery, ignoreCase = true) || row.valueB.contains(searchQuery, ignoreCase = true))
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        if (captureMismatches.isNotEmpty()) {
            Surface(
                color = Color(0xFFEF6C00).copy(alpha = 0.18f),
                shape = RoundedCornerShape(6.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFEF6C00)),
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            ) {
                Text(
                    if (language == AppLanguage.KO) {
                        "⚠️ 촬영조건이 다릅니다: ${captureMismatches.joinToString(", ")} — 화질 비교 결과가 왜곡될 수 있습니다"
                    } else {
                        "⚠️ Capture conditions differ: ${captureMismatches.joinToString(", ")} — quality comparison may be misleading"
                    },
                    modifier = Modifier.padding(8.dp),
                    color = Color(0xFFEF6C00),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text(if (language == AppLanguage.KO) "🔍 메타데이터/트랙 정보 검색..." else "🔍 Search metadata/tracks...", fontSize = 11.sp) },
                modifier = Modifier.weight(1f).height(42.dp),
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp),
            )
            Spacer(modifier = Modifier.width(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = onlyDiffs, onCheckedChange = { onlyDiffs = it })
                Text(if (language == AppLanguage.KO) "차이점만 보기" else "Show Differences Only", fontSize = 12.sp)
            }
        }

        // Table Header
        Row(
            modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Category", modifier = Modifier.weight(0.8f), fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Text("Property Name", modifier = Modifier.weight(1.2f), fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Text("Media A Value", modifier = Modifier.weight(1.4f), fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Text("Media B Value", modifier = Modifier.weight(1.4f), fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Text("Diff", modifier = Modifier.weight(0.4f), fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }

        HorizontalDivider()

        val listState = rememberLazyListState()
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                items(filteredRows) { row ->
                    val bgColor = if (row.isDifferent) Color(0xFFEF6C00).copy(alpha = 0.12f) else Color.Transparent
                    Row(
                        modifier = Modifier.fillMaxWidth().background(bgColor).padding(horizontal = 8.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(row.category, modifier = Modifier.weight(0.8f), fontSize = 11.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
                        Text(row.key, modifier = Modifier.weight(1.2f), fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium)
                        Text(row.valueA.ifEmpty { "(none)" }, modifier = Modifier.weight(1.4f), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        Text(row.valueB.ifEmpty { "(none)" }, modifier = Modifier.weight(1.4f), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        Text(
                            if (row.isDifferent) "≠ DIFF" else "✓",
                            modifier = Modifier.weight(0.4f),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (row.isDifferent) Color(0xFFEF6C00) else Color.Gray,
                        )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                }
            }
            VerticalScrollbar(adapter = rememberScrollbarAdapter(listState), modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight())
        }
    }
}

internal fun extractMetadataDiffRows(infoA: CompareMediaInfo, infoB: CompareMediaInfo): List<MetadataDiffRow> {
    val rows = mutableListOf<MetadataDiffRow>()
    val seenKeys = mutableSetOf<Pair<String, String>>()

    fun add(category: String, key: String, valA: String?, valB: String?) {
        if (!seenKeys.add(category to key)) return
        val a = valA ?: ""
        val b = valB ?: ""
        rows.add(MetadataDiffRow(category, key, a, b, a != b))
    }

    // 1. Basic Media Summary
    add("General", "File Name", infoA.file.name, infoB.file.name)
    add("General", "Media Type", if (infoA.isVideo) "Video" else "Image", if (infoB.isVideo) "Video" else "Image")
    add("General", "File Size", formatSize(infoA.fileSize), formatSize(infoB.fileSize))
    add("General", "Format", infoA.file.extension.uppercase(Locale.US), infoB.file.extension.uppercase(Locale.US))
    if (infoA.isVideo || infoB.isVideo) {
        add("General", "Duration", if (infoA.durationSeconds > 0) "${"%.2f".format(infoA.durationSeconds)}s" else "(none)", if (infoB.durationSeconds > 0) "${"%.2f".format(infoB.durationSeconds)}s" else "(none)")
    }

    // 2. Sections from MediaSummary
    val secA = infoA.summary?.sections ?: emptyList()
    val secB = infoB.summary?.sections ?: emptyList()
    val allTitles = (secA.map { it.title } + secB.map { it.title }).distinct()

    for (title in allTitles) {
        val fieldsA = secA.find { it.title == title }?.fields ?: emptyList()
        val fieldsB = secB.find { it.title == title }?.fields ?: emptyList()
        val allLabels = (fieldsA.map { it.label } + fieldsB.map { it.label }).distinct()

        for (label in allLabels) {
            val vA = fieldsA.find { it.label == label }?.value
            val vB = fieldsB.find { it.label == label }?.value
            add(title, label, vA, vB)
        }
    }

    // 3. Motion Photo Metadata (if any)
    val mpA = infoA.root?.let { root -> try { ByteReader.open(infoA.file).use { reader -> findEmbeddedVideo(root, reader) } } catch (_: Exception) { null } }
    val mpB = infoB.root?.let { root -> try { ByteReader.open(infoB.file).use { reader -> findEmbeddedVideo(root, reader) } } catch (_: Exception) { null } }
    if (mpA != null || mpB != null) {
        add("Motion Photo", "Has Motion Video", (mpA != null).toString(), (mpB != null).toString())
        add("Motion Photo", "Video Offset Range", mpA?.let { "${it.start}..${it.end} (${formatSize(it.end - it.start)})" }, mpB?.let { "${it.start}..${it.end} (${formatSize(it.end - it.start)})" })
        add("Motion Photo", "Embedded Video Format", mpA?.extension, mpB?.extension)
    }

    // 4. EXIF & Forensic
    if (infoA.forensic != null || infoB.forensic != null) {
        add("Exif", "Software", infoA.forensic?.software, infoB.forensic?.software)
        add("Exif", "Orientation", infoA.forensic?.orientation, infoB.forensic?.orientation)
        add("Exif", "DQT Quality Estimate", infoA.forensic?.dqtQuality?.takeIf { it > 0 }?.let { "$it%" }, infoB.forensic?.dqtQuality?.takeIf { it > 0 }?.let { "$it%" })
    }

    // 5. Samsung SEF Blocks
    val sefA = extractSefNames(infoA.root)
    val sefB = extractSefNames(infoB.root)
    if (sefA.isNotEmpty() || sefB.isNotEmpty()) {
        add("SEF Trailer", "SEF Block Count", sefA.size.toString(), sefB.size.toString())
        add("SEF Trailer", "SEF Block Names", sefA.joinToString(", "), sefB.joinToString(", "))
    }

    return rows
}

private val CAPTURE_CONDITION_LABELS = setOf("ISO", "Exposure Time", "F-Number", "Aperture", "Focal Length", "White Balance")

// Fields whose mismatch specifically invalidates a pixel-level quality comparison between two
// images (as opposed to any other metadata difference, e.g. file name or GPS, which doesn't).
// Two label spellings exist for the same underlying value across MediaSummaryBuilder.kt's two
// summary-building code paths ("F-Number" vs "Aperture") -- both are recognized.
// One side missing the field is *unknown*, not *matching* -- suppressing the warning here is
// deliberate, not accidental; don't "fix" this back to flagging one-sided presence as a mismatch.
internal fun captureConditionMismatches(rows: List<MetadataDiffRow>): List<String> {
    return rows.filter {
        it.key in CAPTURE_CONDITION_LABELS && it.isDifferent &&
            it.valueA.isNotBlank() && it.valueB.isNotBlank()
    }.map { it.key }.distinct()
}

private fun extractSefNames(root: BoxNode?): List<String> {
    if (root == null) return emptyList()
    val sefd = findFirst(root) { it.type == "sefd" } ?: return emptyList()
    return sefd.children.map { it.type }
}

// -------------------------------------------------------------------------------------------------
// 3. Visual Diff View (Split Wiper, Side by Side, Difference Heatmap + Video Timeline Sync)
// -------------------------------------------------------------------------------------------------

@Composable
private fun VisualDiffView(
    language: AppLanguage,
    infoA: CompareMediaInfo?,
    infoB: CompareMediaInfo?,
    quadInfos: List<CompareMediaInfo?> = emptyList(),
    captureMismatches: List<String>,
    activeSlot: CompareSlot = CompareSlot.SLOT_A,
    onSetActiveSlot: (CompareSlot) -> Unit = {},
) {
    if (quadInfos.size >= 4 && quadInfos[0] != null && quadInfos[1] != null && quadInfos[2] != null && quadInfos[3] != null) {
        val bitmaps = quadInfos.map { it?.bitmap }
        val labels = listOf("1 (A)", "2 (B)", "3 (C)", "4 (D)")
        QuadCompareView(
            bitmaps = bitmaps,
            infos = quadInfos,
            labels = labels,
            activeSlot = activeSlot,
            onSetActiveSlot = onSetActiveSlot,
            language = language,
        )
        return
    }

    if (infoA == null || infoB == null) {
        EmptyComparePlaceholder(language)
        return
    }

    val isVideoCompare = infoA.isVideo || infoB.isVideo
    val maxDuration = maxOf(infoA.durationSeconds, infoB.durationSeconds).coerceAtLeast(0.1)

    var mode by remember { mutableStateOf(VisualCompareMode.SPLIT_WIPER) }
    var wiperPos by remember { mutableStateOf(0.5f) }
    var currentPts by remember { mutableStateOf(0.0) }
    var isPlaying by remember { mutableStateOf(false) }

    var frameBitmapA by remember { mutableStateOf(infoA.bitmap) }
    var frameBitmapB by remember { mutableStateOf(infoB.bitmap) }

    // Synchronized Video playback timer
    LaunchedEffect(isPlaying, maxDuration) {
        if (isPlaying) {
            while (true) {
                delay(40L)
                val nextPts = currentPts + 0.04
                if (nextPts >= maxDuration) {
                    currentPts = 0.0
                    isPlaying = false
                    break
                } else {
                    currentPts = nextPts
                }
            }
        }
    }

    // Video frame decoder when PTS changes
    LaunchedEffect(currentPts, infoA.file, infoB.file) {
        if (infoA.isVideo) {
            FrameFullSizeDecoder.decodeFrameAsync(infoA.file, currentPts) { bm ->
                if (bm != null) frameBitmapA = bm
            }
        } else {
            frameBitmapA = infoA.bitmap
        }

        if (infoB.isVideo) {
            FrameFullSizeDecoder.decodeFrameAsync(infoB.file, currentPts) { bm ->
                if (bm != null) frameBitmapB = bm
            }
        } else {
            frameBitmapB = infoB.bitmap
        }
    }

    val displayBitmapA = frameBitmapA ?: infoA.bitmap
    val displayBitmapB = frameBitmapB ?: infoB.bitmap

    var metrics by remember(infoA.file, infoB.file) { mutableStateOf<StillImageQualityMetrics?>(null) }
    var metricsLoading by remember(infoA.file, infoB.file) { mutableStateOf(false) }
    var metricsFailed by remember(infoA.file, infoB.file) { mutableStateOf(false) }
    // Latched true once Diff Heatmap mode has actually been selected for this file pair, so the
    // PSNR/SSIM ffmpeg passes below don't run on every Visual-tab entry -- only when the mode that
    // actually needs them has been opened at least once.
    var metricsRequested by remember(infoA.file, infoB.file) { mutableStateOf(false) }

    // Side-effect, not an inline composition-time write: setting metricsRequested directly in the
    // `when (mode)` block below would be a backwards write (reading it via this LaunchedEffect's
    // key list in the same composition pass that wrote it).
    LaunchedEffect(mode, infoA.file, infoB.file) {
        if (mode == VisualCompareMode.DIFF_HEATMAP) metricsRequested = true
    }

    LaunchedEffect(infoA.file, infoB.file, isVideoCompare, metricsRequested) {
        if (isVideoCompare || !metricsRequested) return@LaunchedEffect
        metricsLoading = true
        metricsFailed = false
        val result = withContext(Dispatchers.IO) { computeStillImageQualityMetrics(infoA.file, infoB.file) { !isActive } }
        metrics = result
        metricsFailed = result == null
        metricsLoading = false
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Mode Selector Bar
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterChip(
                selected = mode == VisualCompareMode.SPLIT_WIPER,
                onClick = { mode = VisualCompareMode.SPLIT_WIPER },
                label = { Text(if (language == AppLanguage.KO) "좌우 분할 슬라이더 (Wiper)" else "Split Wiper") },
            )
            Spacer(modifier = Modifier.width(8.dp))
            FilterChip(
                selected = mode == VisualCompareMode.SIDE_BY_SIDE,
                onClick = { mode = VisualCompareMode.SIDE_BY_SIDE },
                label = { Text(if (language == AppLanguage.KO) "좌우 나란히 보기 (Side-by-Side)" else "Side by Side") },
            )
            Spacer(modifier = Modifier.width(8.dp))
            FilterChip(
                selected = mode == VisualCompareMode.DIFF_HEATMAP,
                onClick = { mode = VisualCompareMode.DIFF_HEATMAP },
                label = { Text(if (language == AppLanguage.KO) "차이점 마스크 (Diff Heatmap)" else "Diff Heatmap") },
            )
            Spacer(modifier = Modifier.width(8.dp))
            FilterChip(
                selected = mode == VisualCompareMode.BLINK,
                onClick = { mode = VisualCompareMode.BLINK },
                label = { Text(if (language == AppLanguage.KO) "깜빡임 비교 (Blink)" else "Blink / Flicker") },
            )
        }

        // Synchronized Video Timeline Controller (Displayed when video is present)
        if (isVideoCompare) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { isPlaying = !isPlaying }, modifier = Modifier.size(32.dp)) {
                        Text(if (isPlaying) "⏸" else "▶", fontSize = 16.sp)
                    }
                    IconButton(
                        onClick = { currentPts = (currentPts - 0.04).coerceAtLeast(0.0) },
                        modifier = Modifier.size(32.dp),
                    ) {
                        Text("◀", fontSize = 12.sp)
                    }
                    IconButton(
                        onClick = { currentPts = (currentPts + 0.04).coerceAtMost(maxDuration) },
                        modifier = Modifier.size(32.dp),
                    ) {
                        Text("▶", fontSize = 12.sp)
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Slider(
                        value = currentPts.toFloat(),
                        onValueChange = { currentPts = it.toDouble() },
                        valueRange = 0f..maxDuration.toFloat(),
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "%02d:%05.2f / %02d:%05.2f".format((currentPts / 60).toInt(), currentPts % 60, (maxDuration / 60).toInt(), maxDuration % 60),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }

        // View Area
        Box(
            modifier = Modifier.weight(1f).fillMaxWidth().background(Color(0xFF1E1E1E), RoundedCornerShape(6.dp)).border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (displayBitmapA == null || displayBitmapB == null) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(if (language == AppLanguage.KO) "미디어 프레임 디코딩 중..." else "Decoding media frames...", fontSize = 12.sp, color = Color.White)
                }
            } else {
                when (mode) {
                    VisualCompareMode.SPLIT_WIPER -> {
                        WiperCanvas(displayBitmapA, displayBitmapB, wiperPos, onWiperChanged = { wiperPos = it })
                    }
                    VisualCompareMode.SIDE_BY_SIDE -> {
                        SideBySideCompareView(
                            bitmapA = displayBitmapA,
                            bitmapB = displayBitmapB,
                            infoA = infoA,
                            infoB = infoB,
                            labelA = if (infoA.isVideo) "Video A" else "Image A",
                            labelB = if (infoB.isVideo) "Video B" else "Image B",
                            activeSlot = activeSlot,
                            onSetActiveSlot = onSetActiveSlot,
                            language = language,
                        )
                    }
                    VisualCompareMode.BLINK -> {
                        BlinkCompareView(
                            bitmapA = displayBitmapA,
                            bitmapB = displayBitmapB,
                            infoA = infoA,
                            infoB = infoB,
                            labelA = if (infoA.isVideo) "Video A" else "Image A",
                            labelB = if (infoB.isVideo) "Video B" else "Image B",
                            language = language,
                        )
                    }
                    VisualCompareMode.DIFF_HEATMAP -> {
                        val diffBitmap = remember(displayBitmapA, displayBitmapB) { computeDiffBitmap(displayBitmapA, displayBitmapB) }

                        if (diffBitmap != null) {
                            Box(modifier = Modifier.fillMaxSize().padding(8.dp), contentAlignment = Alignment.Center) {
                                androidx.compose.foundation.Image(bitmap = diffBitmap, contentDescription = "Diff Heatmap", modifier = Modifier.fillMaxSize())
                                Column(
                                    modifier = Modifier.align(Alignment.BottomCenter).background(Color.Black.copy(alpha = 0.7f)).padding(8.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    Text(
                                        if (language == AppLanguage.KO) "🔍 차이점 마스크 (변화가 있는 픽셀이 밝게 표시됨)" else "🔍 Diff Mask (Changed pixels highlighted)",
                                        color = Color.Yellow,
                                        fontSize = 11.sp,
                                    )
                                    if (!isVideoCompare) {
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            val metricsText = when {
                                                metricsLoading -> if (language == AppLanguage.KO) "PSNR/SSIM 계산 중..." else "Computing PSNR/SSIM..."
                                                metricsFailed -> if (language == AppLanguage.KO) "PSNR/SSIM 계산 실패" else "PSNR/SSIM computation failed"
                                                metrics != null -> "PSNR: ${"%.2f".format(metrics!!.psnrDb)} dB | SSIM: ${"%.4f".format(metrics!!.ssim)}"
                                                else -> ""
                                            }
                                            if (metricsText.isNotEmpty()) {
                                                Text(metricsText, color = Color.White, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                                            }
                                            if (captureMismatches.isNotEmpty()) {
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text(
                                                    if (language == AppLanguage.KO) "⚠️ 촬영조건 다름" else "⚠️ Capture conditions differ",
                                                    color = Color(0xFFFFB74D),
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Medium,
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
    }
}

@Composable
private fun MediaOsdBadge(
    label: String,
    info: CompareMediaInfo?,
    nativeSize: Pair<Int, Int>?,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = Color.Black.copy(alpha = 0.70f),
        shape = RoundedCornerShape(bottomEnd = 6.dp),
        modifier = modifier,
    ) {
        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = label,
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                )
                if (info != null) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = info.file.name,
                        color = Color(0xFF61AFEF),
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            val details = buildList {
                if (nativeSize != null && nativeSize.first > 0 && nativeSize.second > 0) {
                    add("${nativeSize.first}x${nativeSize.second}")
                }
                if (info != null && info.fileSize > 0) {
                    add("%.2f MB".format(info.fileSize / (1024.0 * 1024.0)))
                }
                val ext = info?.file?.extension?.uppercase()
                if (!ext.isNullOrBlank()) add(ext)
            }
            if (details.isNotEmpty()) {
                Text(
                    text = details.joinToString(" • "),
                    color = Color(0xFFABB2BF),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun SideBySideCompareView(
    bitmapA: ImageBitmap,
    bitmapB: ImageBitmap,
    infoA: CompareMediaInfo?,
    infoB: CompareMediaInfo?,
    labelA: String,
    labelB: String,
    activeSlot: CompareSlot = CompareSlot.SLOT_A,
    onSetActiveSlot: (CompareSlot) -> Unit = {},
    language: AppLanguage,
) {
    var scale by remember(bitmapA, bitmapB) { mutableStateOf(1f) }
    var offset by remember(bitmapA, bitmapB) { mutableStateOf(Offset.Zero) }
    var paneSize by remember { mutableStateOf(Size.Zero) }
    var hoverNativePixel by remember(bitmapA, bitmapB) { mutableStateOf<Pair<Int, Int>?>(null) }
    // What ContentScale.Fit actually draws in each pane -- pan is bounded against this rather than
    // the pane-sized layer, so a letterboxed image can't be dragged out of view (see clampPanOffset).
    // The two panes share one scale/offset but can hold differently-shaped images, so each pane
    // clamps against its own fitted size.
    val fittedSizeA = fittedContentSize(paneSize, Size(bitmapA.width.toFloat(), bitmapA.height.toFloat()))
    val fittedSizeB = fittedContentSize(paneSize, Size(bitmapB.width.toFloat(), bitmapB.height.toFloat()))

    fun applyZoomPreset(targetScale: Float) {
        scale = targetScale
        offset = clampPanOffset(offset, paneSize, scale, fittedSizeA)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(8.dp)
            .clipToBounds(),
    ) {
        Row(modifier = Modifier.fillMaxSize()) {
            // Pane A
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clipToBounds()
                    .clickable { onSetActiveSlot(CompareSlot.SLOT_A) }
                    .border(
                        if (activeSlot == CompareSlot.SLOT_A) 2.dp else 1.dp,
                        if (activeSlot == CompareSlot.SLOT_A) Color(0xFF61AFEF) else AppColors.Border.copy(alpha = 0.5f),
                        RoundedCornerShape(4.dp),
                    )
                    .onGloballyPositioned { paneSize = it.size.toSize() }
                    .onPointerEvent(PointerEventType.Scroll, pass = PointerEventPass.Initial) { event ->
                        val change = event.changes.firstOrNull() ?: return@onPointerEvent
                        val (newScale, rawOffset) = zoomTowardPoint(scale, offset, change.position, change.scrollDelta.y)
                        scale = newScale
                        offset = clampPanOffset(rawOffset, paneSize, newScale, fittedSizeA)
                        event.changes.forEach { it.consume() }
                    }
                    .onPointerEvent(PointerEventType.Move, pass = PointerEventPass.Initial) { event ->
                        val pos = event.changes.firstOrNull()?.position
                        hoverNativePixel = pos?.let {
                            screenPointToNativePixel(it, paneSize, Size(bitmapA.width.toFloat(), bitmapA.height.toFloat()), scale, offset)
                        }
                    }
                    .onPointerEvent(PointerEventType.Exit, pass = PointerEventPass.Initial) {
                        hoverNativePixel = null
                    }
                    .pointerInput(bitmapA, bitmapB) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            offset = clampPanOffset(offset + dragAmount, paneSize, scale, fittedSizeA)
                        }
                    }
                    .pointerInput(bitmapA, bitmapB) {
                        detectTapGestures(
                            onTap = { tapPosition ->
                                offset = panToPoint(offset, paneSize, scale, tapPosition, fittedSizeA)
                            },
                            onDoubleTap = {
                                scale = 1f
                                offset = Offset.Zero
                            },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                androidx.compose.foundation.Image(
                    bitmap = bitmapA,
                    contentDescription = labelA,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer(
                            scaleX = scale,
                            scaleY = scale,
                            translationX = offset.x,
                            translationY = offset.y,
                            transformOrigin = TransformOrigin(0f, 0f),
                        ),
                    contentScale = ContentScale.Fit,
                )
                if (LocalShowPixelGrid.current) {
                    PixelGridOverlay(
                        nativeSize = Size(bitmapA.width.toFloat(), bitmapA.height.toFloat()),
                        scale = scale,
                        modifier = Modifier.graphicsLayer(
                            scaleX = scale,
                            scaleY = scale,
                            translationX = offset.x,
                            translationY = offset.y,
                            transformOrigin = TransformOrigin(0f, 0f),
                        ),
                    )
                }
                MediaOsdBadge(
                    label = labelA,
                    info = infoA,
                    nativeSize = bitmapA.width to bitmapA.height,
                    modifier = Modifier.align(Alignment.TopStart),
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Pane B
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clipToBounds()
                    .clickable { onSetActiveSlot(CompareSlot.SLOT_B) }
                    .border(
                        if (activeSlot == CompareSlot.SLOT_B) 2.dp else 1.dp,
                        if (activeSlot == CompareSlot.SLOT_B) Color(0xFF98C379) else AppColors.Border.copy(alpha = 0.5f),
                        RoundedCornerShape(4.dp),
                    )
                    .onGloballyPositioned { paneSize = it.size.toSize() }
                    .onPointerEvent(PointerEventType.Scroll, pass = PointerEventPass.Initial) { event ->
                        val change = event.changes.firstOrNull() ?: return@onPointerEvent
                        val (newScale, rawOffset) = zoomTowardPoint(scale, offset, change.position, change.scrollDelta.y)
                        scale = newScale
                        offset = clampPanOffset(rawOffset, paneSize, newScale, fittedSizeB)
                        event.changes.forEach { it.consume() }
                    }
                    .onPointerEvent(PointerEventType.Move, pass = PointerEventPass.Initial) { event ->
                        val pos = event.changes.firstOrNull()?.position
                        hoverNativePixel = pos?.let {
                            screenPointToNativePixel(it, paneSize, Size(bitmapB.width.toFloat(), bitmapB.height.toFloat()), scale, offset)
                        }
                    }
                    .onPointerEvent(PointerEventType.Exit, pass = PointerEventPass.Initial) {
                        hoverNativePixel = null
                    }
                    .pointerInput(bitmapA, bitmapB) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            offset = clampPanOffset(offset + dragAmount, paneSize, scale, fittedSizeB)
                        }
                    }
                    .pointerInput(bitmapA, bitmapB) {
                        detectTapGestures(
                            onTap = { tapPosition ->
                                offset = panToPoint(offset, paneSize, scale, tapPosition, fittedSizeB)
                            },
                            onDoubleTap = {
                                scale = 1f
                                offset = Offset.Zero
                            },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                androidx.compose.foundation.Image(
                    bitmap = bitmapB,
                    contentDescription = labelB,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer(
                            scaleX = scale,
                            scaleY = scale,
                            translationX = offset.x,
                            translationY = offset.y,
                            transformOrigin = TransformOrigin(0f, 0f),
                        ),
                    contentScale = ContentScale.Fit,
                )
                if (LocalShowPixelGrid.current) {
                    PixelGridOverlay(
                        nativeSize = Size(bitmapB.width.toFloat(), bitmapB.height.toFloat()),
                        scale = scale,
                        modifier = Modifier.graphicsLayer(
                            scaleX = scale,
                            scaleY = scale,
                            translationX = offset.x,
                            translationY = offset.y,
                            transformOrigin = TransformOrigin(0f, 0f),
                        ),
                    )
                }
                MediaOsdBadge(
                    label = labelB,
                    info = infoB,
                    nativeSize = bitmapB.width to bitmapB.height,
                    modifier = Modifier.align(Alignment.TopStart),
                )
            }
        }

        hoverNativePixel?.let { (nx, ny) ->
            val skiaA = bitmapA.asSkiaBitmap()
            val skiaB = bitmapB.asSkiaBitmap()
            val colorA = if (nx < skiaA.width && ny < skiaA.height) skiaA.getColor(nx, ny) else null
            val colorB = if (nx < skiaB.width && ny < skiaB.height) skiaB.getColor(nx, ny) else null
            Surface(
                color = Color.Black.copy(alpha = 0.75f),
                shape = RoundedCornerShape(4.dp),
                border = androidx.compose.foundation.BorderStroke(0.5.dp, Color.White.copy(alpha = 0.3f)),
                modifier = Modifier.align(Alignment.BottomStart).padding(8.dp),
            ) {
                Text(
                    buildString {
                        append("(%d, %d)  ".format(nx, ny))
                        colorA?.let { append("A: #%06X  ".format(it and 0xFFFFFF)) }
                        colorB?.let { append("B: #%06X".format(it and 0xFFFFFF)) }
                    },
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    color = Color.White,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }

        // FastStone Style Zoom Control Toolbar (Fit, 100%, 200%, 400%)
        Surface(
            color = Color.Black.copy(alpha = 0.80f),
            shape = RoundedCornerShape(6.dp),
            border = androidx.compose.foundation.BorderStroke(0.5.dp, Color.White.copy(alpha = 0.3f)),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(8.dp),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "🔍 %.1fx".format(scale),
                    fontSize = 11.sp,
                    color = Color.Yellow,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(end = 6.dp),
                )
                val presets = listOf("Fit" to 1f, "100%" to 1f, "200%" to 2f, "400%" to 4f)
                presets.forEach { (text, s) ->
                    Text(
                        text = text,
                        fontSize = 10.sp,
                        color = if (scale == s && text != "Fit") Color.Yellow else Color.White,
                        modifier = Modifier
                            .clickable {
                                if (text == "Fit") {
                                    scale = 1f
                                    offset = Offset.Zero
                                } else {
                                    applyZoomPreset(s)
                                }
                            }
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                    )
                }
            }
        }
    }
}

/**
 * FastStone-style Blink / Flicker comparison view that rapidly or manually alternates
 * between Bitmap A and Bitmap B to expose minute visual compression/alignment differences.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun BlinkCompareView(
    bitmapA: ImageBitmap,
    bitmapB: ImageBitmap,
    infoA: CompareMediaInfo?,
    infoB: CompareMediaInfo?,
    labelA: String,
    labelB: String,
    language: AppLanguage,
) {
    var showA by remember { mutableStateOf(true) }
    var autoBlink by remember { mutableStateOf(false) }
    var blinkSpeedMs by remember { mutableStateOf(300L) }
    var scale by remember(bitmapA, bitmapB) { mutableStateOf(1f) }
    var offset by remember(bitmapA, bitmapB) { mutableStateOf(Offset.Zero) }
    var paneSize by remember { mutableStateOf(Size.Zero) }

    val currentBitmap = if (showA) bitmapA else bitmapB
    val currentInfo = if (showA) infoA else infoB
    val currentLabel = if (showA) labelA else labelB

    val fittedSize = fittedContentSize(paneSize, Size(currentBitmap.width.toFloat(), currentBitmap.height.toFloat()))

    LaunchedEffect(autoBlink, blinkSpeedMs) {
        while (autoBlink) {
            delay(blinkSpeedMs)
            showA = !showA
        }
    }

    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Column(modifier = Modifier.fillMaxSize().padding(8.dp)) {
        // Blink Control Toolbar
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            shape = RoundedCornerShape(6.dp),
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = { showA = !showA },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (showA) Color(0xFF61AFEF) else Color(0xFF98C379),
                    ),
                    modifier = Modifier.height(32.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp),
                ) {
                    Text(
                        text = if (showA) "현재: [A] 전환(Space)" else "현재: [B] 전환(Space)",
                        fontSize = 11.sp,
                        color = Color.Black,
                        fontWeight = FontWeight.Bold,
                    )
                }

                Spacer(Modifier.width(12.dp))

                FilterChip(
                    selected = autoBlink,
                    onClick = { autoBlink = !autoBlink },
                    label = { Text(if (autoBlink) "⏹ 자동 깜빡임 정지" else "▶ 자동 깜빡임 시작") },
                    modifier = Modifier.height(32.dp),
                )

                if (autoBlink) {
                    Spacer(Modifier.width(16.dp))
                    Text("주기: ${blinkSpeedMs}ms", fontSize = 11.sp, color = AppColors.TextSecondary)
                    Spacer(Modifier.width(8.dp))
                    Slider(
                        value = blinkSpeedMs.toFloat(),
                        onValueChange = { blinkSpeedMs = it.toLong() },
                        valueRange = 100f..1000f,
                        modifier = Modifier.width(150.dp),
                    )
                }

                Spacer(Modifier.weight(1f))

                Text(
                    text = if (language == AppLanguage.KO) "💡 스페이스바를 눌러 수동 교차 비교 가능" else "💡 Tap Spacebar to toggle A/B",
                    fontSize = 11.sp,
                    color = AppColors.TextSecondary,
                )
            }
        }

        // View Area
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clipToBounds()
                .focusRequester(focusRequester)
                .focusable()
                .onKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.Spacebar) {
                        showA = !showA
                        true
                    } else false
                }
                .onGloballyPositioned { paneSize = it.size.toSize() }
                .onPointerEvent(PointerEventType.Scroll, pass = PointerEventPass.Initial) { event ->
                    val change = event.changes.firstOrNull() ?: return@onPointerEvent
                    val (newScale, rawOffset) = zoomTowardPoint(scale, offset, change.position, change.scrollDelta.y)
                    scale = newScale
                    offset = clampPanOffset(rawOffset, paneSize, newScale, fittedSize)
                    event.changes.forEach { it.consume() }
                }
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        offset = clampPanOffset(offset + dragAmount, paneSize, scale, fittedSize)
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onDoubleTap = {
                            scale = 1f
                            offset = Offset.Zero
                        }
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            androidx.compose.foundation.Image(
                bitmap = currentBitmap,
                contentDescription = currentLabel,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offset.x,
                        translationY = offset.y,
                        transformOrigin = TransformOrigin(0f, 0f),
                    ),
                contentScale = ContentScale.Fit,
            )

            if (LocalShowPixelGrid.current) {
                PixelGridOverlay(
                    nativeSize = Size(currentBitmap.width.toFloat(), currentBitmap.height.toFloat()),
                    scale = scale,
                    modifier = Modifier.graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offset.x,
                        translationY = offset.y,
                        transformOrigin = TransformOrigin(0f, 0f),
                    ),
                )
            }

            MediaOsdBadge(
                label = currentLabel,
                info = currentInfo,
                nativeSize = currentBitmap.width to currentBitmap.height,
                modifier = Modifier.align(Alignment.TopStart),
            )

            // Zoom Presets
            Surface(
                color = Color.Black.copy(alpha = 0.80f),
                shape = RoundedCornerShape(6.dp),
                border = androidx.compose.foundation.BorderStroke(0.5.dp, Color.White.copy(alpha = 0.3f)),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "🔍 %.1fx".format(scale),
                        fontSize = 11.sp,
                        color = Color.Yellow,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(end = 6.dp),
                    )
                    val presets = listOf("Fit" to 1f, "100%" to 1f, "200%" to 2f, "400%" to 4f)
                    presets.forEach { (text, s) ->
                        Text(
                            text = text,
                            fontSize = 10.sp,
                            color = if (scale == s && text != "Fit") Color.Yellow else Color.White,
                            modifier = Modifier
                                .clickable {
                                    if (text == "Fit") {
                                        scale = 1f
                                        offset = Offset.Zero
                                    } else {
                                        scale = s
                                        offset = clampPanOffset(offset, paneSize, scale, fittedSize)
                                    }
                                }
                                .padding(horizontal = 4.dp, vertical = 2.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * FastStone-style Quad Compare View (4-split 2x2 grid) with synchronized zoom,
 * pan, native pixel inspector, and timeline playback.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun QuadCompareView(
    bitmaps: List<ImageBitmap?>,
    infos: List<CompareMediaInfo?>,
    labels: List<String>,
    activeSlot: CompareSlot,
    onSetActiveSlot: (CompareSlot) -> Unit,
    language: AppLanguage,
) {
    val nonNullBitmaps = bitmaps.filterNotNull()
    var scale by remember(nonNullBitmaps) { mutableStateOf(1f) }
    var offset by remember(nonNullBitmaps) { mutableStateOf(Offset.Zero) }
    var paneSize by remember { mutableStateOf(Size.Zero) }
    var hoverNativePixel by remember(nonNullBitmaps) { mutableStateOf<Pair<Int, Int>?>(null) }

    val fittedSizes = bitmaps.map { bm ->
        if (bm != null) fittedContentSize(paneSize, Size(bm.width.toFloat(), bm.height.toFloat())) else paneSize
    }
    val primaryFittedSize = fittedSizes.firstOrNull() ?: paneSize

    fun applyZoomPreset(targetScale: Float) {
        scale = targetScale
        offset = clampPanOffset(offset, paneSize, scale, primaryFittedSize)
    }

    val slots = listOf(CompareSlot.SLOT_A, CompareSlot.SLOT_B, CompareSlot.SLOT_C, CompareSlot.SLOT_D)
    val slotColors = listOf(Color(0xFF61AFEF), Color(0xFF98C379), Color(0xFFE5C07B), Color(0xFFE06C75))

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(8.dp)
            .clipToBounds(),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            for (row in 0..1) {
                Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    for (col in 0..1) {
                        val index = row * 2 + col
                        val slot = slots.getOrElse(index) { CompareSlot.SLOT_A }
                        val bitmap = bitmaps.getOrNull(index)
                        val info = infos.getOrNull(index)
                        val label = labels.getOrElse(index) { "Media ${index + 1}" }
                        val slotColor = slotColors.getOrElse(index) { Color.Cyan }
                        val fitted = fittedSizes.getOrElse(index) { paneSize }

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .clipToBounds()
                                .clickable { onSetActiveSlot(slot) }
                                .border(
                                    if (activeSlot == slot) 2.dp else 1.dp,
                                    if (activeSlot == slot) slotColor else AppColors.Border.copy(alpha = 0.5f),
                                    RoundedCornerShape(4.dp),
                                )
                                .onGloballyPositioned { paneSize = it.size.toSize() }
                                .onPointerEvent(PointerEventType.Scroll, pass = PointerEventPass.Initial) { event ->
                                    val change = event.changes.firstOrNull() ?: return@onPointerEvent
                                    val (newScale, rawOffset) = zoomTowardPoint(scale, offset, change.position, change.scrollDelta.y)
                                    scale = newScale
                                    offset = clampPanOffset(rawOffset, paneSize, newScale, fitted)
                                    event.changes.forEach { it.consume() }
                                }
                                .onPointerEvent(PointerEventType.Move, pass = PointerEventPass.Initial) { event ->
                                    val pos = event.changes.firstOrNull()?.position
                                    if (bitmap != null && pos != null) {
                                        hoverNativePixel = screenPointToNativePixel(pos, paneSize, Size(bitmap.width.toFloat(), bitmap.height.toFloat()), scale, offset)
                                    }
                                }
                                .onPointerEvent(PointerEventType.Exit, pass = PointerEventPass.Initial) {
                                    hoverNativePixel = null
                                }
                                .pointerInput(bitmap) {
                                    detectDragGestures { change, dragAmount ->
                                        change.consume()
                                        offset = clampPanOffset(offset + dragAmount, paneSize, scale, fitted)
                                    }
                                }
                                .pointerInput(bitmap) {
                                    detectTapGestures(
                                        onTap = { tapPosition ->
                                            offset = panToPoint(offset, paneSize, scale, tapPosition, fitted)
                                        },
                                        onDoubleTap = {
                                            scale = 1f
                                            offset = Offset.Zero
                                        },
                                    )
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (bitmap != null) {
                                androidx.compose.foundation.Image(
                                    bitmap = bitmap,
                                    contentDescription = label,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .graphicsLayer(
                                            scaleX = scale,
                                            scaleY = scale,
                                            translationX = offset.x,
                                            translationY = offset.y,
                                            transformOrigin = TransformOrigin(0f, 0f),
                                        ),
                                    contentScale = ContentScale.Fit,
                                )
                                if (LocalShowPixelGrid.current) {
                                    PixelGridOverlay(
                                        nativeSize = Size(bitmap.width.toFloat(), bitmap.height.toFloat()),
                                        scale = scale,
                                        modifier = Modifier.graphicsLayer(
                                            scaleX = scale,
                                            scaleY = scale,
                                            translationX = offset.x,
                                            translationY = offset.y,
                                            transformOrigin = TransformOrigin(0f, 0f),
                                        ),
                                    )
                                }
                            } else {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    CircularProgressIndicator(color = slotColor, modifier = Modifier.size(28.dp))
                                    Spacer(Modifier.height(8.dp))
                                    Text(
                                        text = info?.file?.name ?: if (language == AppLanguage.KO) "로딩 중..." else "Loading...",
                                        fontSize = 11.sp,
                                        color = AppColors.TextSecondary,
                                    )
                                }
                            }

                            MediaOsdBadge(
                                label = label,
                                info = info,
                                nativeSize = bitmap?.let { it.width to it.height },
                                modifier = Modifier.align(Alignment.TopStart),
                            )
                        }

                        if (col == 0) {
                            Spacer(Modifier.width(6.dp))
                        }
                    }
                }
                if (row == 0) {
                    Spacer(Modifier.height(6.dp))
                }
            }
        }

        hoverNativePixel?.let { (nx, ny) ->
            Surface(
                color = Color.Black.copy(alpha = 0.85f),
                shape = RoundedCornerShape(4.dp),
                border = androidx.compose.foundation.BorderStroke(0.5.dp, Color.White.copy(alpha = 0.3f)),
                modifier = Modifier.align(Alignment.BottomStart).padding(8.dp),
            ) {
                Text(
                    buildString {
                        append("(%d, %d)  ".format(nx, ny))
                        bitmaps.forEachIndexed { i, bm ->
                            if (bm != null) {
                                val skia = bm.asSkiaBitmap()
                                val color = if (nx < skia.width && ny < skia.height) skia.getColor(nx, ny) else null
                                val slotLetter = listOf("A", "B", "C", "D").getOrElse(i) { "${i+1}" }
                                color?.let { append("%s: #%06X  ".format(slotLetter, it and 0xFFFFFF)) }
                            }
                        }
                    },
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    color = Color.White,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }

        // FastStone Style Zoom Toolbar
        Surface(
            color = Color.Black.copy(alpha = 0.80f),
            shape = RoundedCornerShape(6.dp),
            border = androidx.compose.foundation.BorderStroke(0.5.dp, Color.White.copy(alpha = 0.3f)),
            modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "🔍 %.1fx".format(scale),
                    fontSize = 11.sp,
                    color = Color.Yellow,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(end = 6.dp),
                )
                val presets = listOf("Fit" to 1f, "100%" to 1f, "200%" to 2f, "400%" to 4f)
                presets.forEach { (text, s) ->
                    Text(
                        text = text,
                        fontSize = 10.sp,
                        color = if (scale == s && text != "Fit") Color.Yellow else Color.White,
                        modifier = Modifier
                            .clickable {
                                if (text == "Fit") {
                                    scale = 1f
                                    offset = Offset.Zero
                                } else {
                                    applyZoomPreset(s)
                                }
                            }
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun WiperCanvas(
    bitmapA: ImageBitmap,
    bitmapB: ImageBitmap,
    wiperPos: Float,
    onWiperChanged: (Float) -> Unit,
) {
    Canvas(
        modifier = Modifier.fillMaxSize().pointerInput(Unit) {
            detectDragGestures { change, _ ->
                val newPos = (change.position.x / size.width).coerceIn(0f, 1f)
                onWiperChanged(newPos)
            }
        }
    ) {
        val w = size.width
        val h = size.height
        val splitX = w * wiperPos

        drawImage(bitmapB, dstSize = androidx.compose.ui.unit.IntSize(w.toInt(), h.toInt()))

        clipRect(left = 0f, top = 0f, right = splitX, bottom = h) {
            drawImage(bitmapA, dstSize = androidx.compose.ui.unit.IntSize(w.toInt(), h.toInt()))
        }

        drawLine(
            color = Color.Cyan,
            start = Offset(splitX, 0f),
            end = Offset(splitX, h),
            strokeWidth = 2.5f,
        )

        drawCircle(
            color = Color.Cyan,
            radius = 12f,
            center = Offset(splitX, h / 2f),
        )
        drawCircle(
            color = Color.Black,
            radius = 6f,
            center = Offset(splitX, h / 2f),
        )
    }
}

// Maps a pointer position (in box-local coordinates -- the same space Compose pointer events
// already report relative to the Box they're attached to) through the fitted-content + user-zoom/
// pan transform this view's graphicsLayer applies, down to a native pixel coordinate in the
// displayed bitmap -- or null if the pointer is outside the drawn image (a letterbox bar, or an
// unmeasured box). Uses the same (pointerPos - offset) / scale inversion `panToPoint`
// (PixelInspectorPreview.kt) already establishes to recover a pre-zoom, box-local content point;
// this additionally subtracts the letterbox origin and divides by fitScale to reach native pixels.
// Hand-verified: at scale=1/offset=Zero with no letterboxing this is the identity mapping; with a
// 200x100 image letterboxed into a 100x100 box, the box center (50,50) correctly resolves to the
// native center (100,50), and a point in the letterbox margin resolves to null; at scale=2 with an
// arbitrary pan (offset=(-50,-25)), pointer (60,40) resolves to native (110,15), and forward-
// transforming (110,15) through the same formula (screenX = offset.x + scale*(letterboxX0 +
// nativeX*fitScale)) returns exactly (60,40), confirming the inversion round-trips correctly.
internal fun screenPointToNativePixel(pointerPos: Offset, boxSize: Size, nativeSize: Size, scale: Float, offset: Offset): Pair<Int, Int>? {
    if (nativeSize.width <= 0f || nativeSize.height <= 0f || boxSize.width <= 0f || boxSize.height <= 0f) return null
    val contentSize = fittedContentSize(boxSize, nativeSize)
    val fitScale = contentSize.width / nativeSize.width
    if (fitScale <= 0f) return null
    val letterboxX0 = (boxSize.width - contentSize.width) / 2f
    val letterboxY0 = (boxSize.height - contentSize.height) / 2f
    val lx = (pointerPos.x - offset.x) / scale
    val ly = (pointerPos.y - offset.y) / scale
    val nativeX = ((lx - letterboxX0) / fitScale).toInt()
    val nativeY = ((ly - letterboxY0) / fitScale).toInt()
    if (nativeX < 0 || nativeX >= nativeSize.width.toInt() || nativeY < 0 || nativeY >= nativeSize.height.toInt()) return null
    return nativeX to nativeY
}

private fun computeDiffBitmap(bmA: ImageBitmap, bmB: ImageBitmap): ImageBitmap? {
    return try {
        val skiaA = bmA.asSkiaBitmap()
        val skiaB = bmB.asSkiaBitmap()
        val w = minOf(skiaA.width, skiaB.width)
        val h = minOf(skiaA.height, skiaB.height)

        val bufferedImage = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)

        for (y in 0 until h) {
            for (x in 0 until w) {
                val pA = skiaA.getColor(x, y)
                val pB = skiaB.getColor(x, y)

                val rA = (pA shr 16) and 0xFF
                val gA = (pA shr 8) and 0xFF
                val bA = pA and 0xFF

                val rB = (pB shr 16) and 0xFF
                val gB = (pB shr 8) and 0xFF
                val bB = pB and 0xFF

                val diffR = Math.abs(rA - rB)
                val diffG = Math.abs(gA - gB)
                val diffB = Math.abs(bA - bB)
                val totalDiff = minOf(255, (diffR + diffG + diffB) * 3)

                val color = if (totalDiff > 0) {
                    (0xFF shl 24) or (totalDiff shl 16) or ((totalDiff / 2) shl 8) or 0x00
                } else {
                    (0xFF shl 24) or 0x101010
                }
                bufferedImage.setRGB(x, y, color)
            }
        }
        bufferedImage.toComposeImageBitmap()
    } catch (e: Exception) {
        null
    }
}

// -------------------------------------------------------------------------------------------------
// 4. Hex Diff View
// -------------------------------------------------------------------------------------------------

data class HexDiffChunk(
    val index: Int, // 1-based index (1, 2, 3...)
    val startRow: Int,
    val endRow: Int,
    val startOffset: Long,
    val endOffset: Long,
    val sizeBytes: Long,
)

internal fun scanHexDifferences(fileA: File, fileB: File): List<HexDiffChunk> {
    val lenA = fileA.length()
    val lenB = fileB.length()
    val maxLen = maxOf(lenA, lenB)
    if (maxLen == 0L) return emptyList()

    val diffChunks = mutableListOf<HexDiffChunk>()
    val bufferSize = 65536 // 64KB chunks
    val bufA = ByteArray(bufferSize)
    val bufB = ByteArray(bufferSize)

    var currentChunkStartRow = -1
    var currentChunkEndRow = -1
    var chunkIndex = 1

    fun commitCurrentChunk() {
        if (currentChunkStartRow >= 0) {
            val startOff = currentChunkStartRow.toLong() * 16L
            val endOff = minOf((currentChunkEndRow.toLong() + 1L) * 16L, maxLen)
            diffChunks.add(
                HexDiffChunk(
                    index = chunkIndex++,
                    startRow = currentChunkStartRow,
                    endRow = currentChunkEndRow,
                    startOffset = startOff,
                    endOffset = endOff,
                    sizeBytes = endOff - startOff,
                )
            )
            currentChunkStartRow = -1
            currentChunkEndRow = -1
        }
    }

    try {
        fileA.inputStream().buffered(bufferSize).use { streamA ->
            fileB.inputStream().buffered(bufferSize).use { streamB ->
                var globalOffset = 0L

                while (globalOffset < maxLen) {
                    val readA = if (globalOffset < lenA) streamA.read(bufA).coerceAtLeast(0) else 0
                    val readB = if (globalOffset < lenB) streamB.read(bufB).coerceAtLeast(0) else 0
                    val bytesInBlock = maxOf(readA, readB)
                    if (bytesInBlock <= 0) break

                    var blockOffset = 0
                    while (blockOffset < bytesInBlock) {
                        val rowLen = minOf(16, bytesInBlock - blockOffset)
                        val rowIndex = ((globalOffset + blockOffset) / 16).toInt()

                        var isRowDiff = false
                        for (i in 0 until rowLen) {
                            val byteA = if (blockOffset + i < readA) bufA[blockOffset + i] else null
                            val byteB = if (blockOffset + i < readB) bufB[blockOffset + i] else null
                            if (byteA != byteB) {
                                isRowDiff = true
                                break
                            }
                        }

                        if (isRowDiff) {
                            if (currentChunkStartRow == -1) {
                                currentChunkStartRow = rowIndex
                                currentChunkEndRow = rowIndex
                            } else {
                                if (rowIndex - currentChunkEndRow <= 2) {
                                    currentChunkEndRow = rowIndex
                                } else {
                                    commitCurrentChunk()
                                    currentChunkStartRow = rowIndex
                                    currentChunkEndRow = rowIndex
                                }
                            }
                        }

                        blockOffset += 16
                    }
                    globalOffset += bytesInBlock
                }
                commitCurrentChunk()
            }
        }
    } catch (e: Exception) {
        // Fallback on read error
    }

    return diffChunks
}

@Composable
private fun HexDiffView(language: AppLanguage, fileA: File?, fileB: File?) {
    if (fileA == null || fileB == null || !fileA.exists() || !fileB.exists()) {
        EmptyComparePlaceholder(language)
        return
    }

    val rafA = remember(fileA) { if (fileA.exists()) RandomAccessFile(fileA, "r") else null }
    val rafB = remember(fileB) { if (fileB.exists()) RandomAccessFile(fileB, "r") else null }
    DisposableEffect(rafA, rafB) {
        onDispose {
            rafA?.close()
            rafB?.close()
        }
    }

    val fileALen = fileA.length()
    val fileBLen = fileB.length()
    val maxLen = maxOf(fileALen, fileBLen)
    val rowCount = ((maxLen + 15) / 16).toInt()

    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }

    var diffChunks by remember(fileA, fileB) { mutableStateOf<List<HexDiffChunk>>(emptyList()) }
    var isScanningDiffs by remember(fileA, fileB) { mutableStateOf(true) }
    var currentDiffIndex by remember(fileA, fileB) { mutableStateOf(0) }
    var isDiffDropdownOpen by remember { mutableStateOf(false) }

    LaunchedEffect(fileA, fileB) {
        isScanningDiffs = true
        val chunks = withContext(Dispatchers.IO) {
            scanHexDifferences(fileA, fileB)
        }
        diffChunks = chunks
        currentDiffIndex = if (chunks.isNotEmpty()) 0 else -1
        isScanningDiffs = false
    }

    fun jumpToDiff(index: Int) {
        if (diffChunks.isEmpty()) return
        val targetIdx = index.coerceIn(0, diffChunks.size - 1)
        currentDiffIndex = targetIdx
        val chunk = diffChunks[targetIdx]
        coroutineScope.launch {
            listState.animateScrollToItem(chunk.startRow)
        }
    }

    fun nextDiff() {
        if (diffChunks.isEmpty()) return
        val nextIdx = (currentDiffIndex + 1) % diffChunks.size
        jumpToDiff(nextIdx)
    }

    fun prevDiff() {
        if (diffChunks.isEmpty()) return
        val prevIdx = if (currentDiffIndex <= 0) diffChunks.size - 1 else currentDiffIndex - 1
        jumpToDiff(prevIdx)
    }

    val activeChunk = diffChunks.getOrNull(currentDiffIndex)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(focusRequester)
            .focusable()
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (event.key) {
                    Key.F7 -> { prevDiff(); true }
                    Key.F8 -> { nextDiff(); true }
                    Key.DirectionUp -> if (event.isAltPressed) { prevDiff(); true } else false
                    Key.DirectionDown -> if (event.isAltPressed) { nextDiff(); true } else false
                    else -> false
                }
            }
    ) {
        // Toolbar with Diff Navigation Controls (Beyond Compare / Araxis style)
        Surface(
            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
            shape = RoundedCornerShape(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                // Left: File sizes info
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "File A: ${formatSize(fileALen)} | File B: ${formatSize(fileBLen)} (Δ: ${formatSize(Math.abs(fileBLen - fileALen))})",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // Right: Diff Navigator Controls
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (isScanningDiffs) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                        Text(
                            if (language == AppLanguage.KO) "차이점 분석 중..." else "Scanning diffs...",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else if (diffChunks.isEmpty()) {
                        Text(
                            if (language == AppLanguage.KO) "✓ 100% 바이너리 일치 (차이 없음)" else "✓ 100% Binary Match (0 diffs)",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = AppColors.NeonGreen,
                        )
                    } else {
                        // First diff button
                        IconButton(
                            onClick = { jumpToDiff(0) },
                            modifier = Modifier.size(24.dp),
                            enabled = diffChunks.isNotEmpty(),
                        ) {
                            Text("⇤", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }

                        // Prev diff button
                        Button(
                            onClick = { prevDiff() },
                            modifier = Modifier.height(26.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                        ) {
                            Text(
                                if (language == AppLanguage.KO) "◀ 이전 차이 (F7)" else "◀ Prev Diff (F7)",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }

                        // Jump to Diff Dropdown Menu / Pill
                        Box {
                            OutlinedButton(
                                onClick = { isDiffDropdownOpen = true },
                                modifier = Modifier.height(26.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                shape = RoundedCornerShape(4.dp),
                            ) {
                                val currentOffsetStr = activeChunk?.let { " (0x%08X)".format(it.startOffset) } ?: ""
                                Text(
                                    "${currentDiffIndex + 1} / ${diffChunks.size}$currentOffsetStr ▼",
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFFFF9500),
                                )
                            }

                            DropdownMenu(
                                expanded = isDiffDropdownOpen,
                                onDismissRequest = { isDiffDropdownOpen = false },
                            ) {
                                Text(
                                    if (language == AppLanguage.KO) " 차이점 목록 (총 ${diffChunks.size}개 구간)" else " Diff Blocks (${diffChunks.size} total)",
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                HorizontalDivider()
                                diffChunks.forEachIndexed { idx, chunk ->
                                    val isCurrent = idx == currentDiffIndex
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                "#${chunk.index}: 0x%08X ~ 0x%08X (%s)".format(chunk.startOffset, chunk.endOffset, formatSize(chunk.sizeBytes)),
                                                fontSize = 11.sp,
                                                fontFamily = FontFamily.Monospace,
                                                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                                                color = if (isCurrent) Color(0xFFFF9500) else MaterialTheme.colorScheme.onSurface,
                                            )
                                        },
                                        onClick = {
                                            jumpToDiff(idx)
                                            isDiffDropdownOpen = false
                                        },
                                    )
                                }
                            }
                        }

                        // Next diff button
                        Button(
                            onClick = { nextDiff() },
                            modifier = Modifier.height(26.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        ) {
                            Text(
                                if (language == AppLanguage.KO) "다음 차이 ▶ (F8)" else "Next Diff ▶ (F8)",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                        }

                        // Last diff button
                        IconButton(
                            onClick = { jumpToDiff(diffChunks.size - 1) },
                            modifier = Modifier.size(24.dp),
                            enabled = diffChunks.isNotEmpty(),
                        ) {
                            Text("⇥", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // Table Header
        Row(
            modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Offset", modifier = Modifier.width(75.dp), fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Text("Media A (Hex)", modifier = Modifier.weight(1f), fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                Text("Media A (ASCII)", modifier = Modifier.width(130.dp), fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            }
            Spacer(modifier = Modifier.width(8.dp))
            Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Text("Media B (Hex)", modifier = Modifier.weight(1f), fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                Text("Media B (ASCII)", modifier = Modifier.width(130.dp), fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            }
            Spacer(modifier = Modifier.width(28.dp)) // Reserve space for Scrollbar & Minimap
        }

        HorizontalDivider()

        // Table + Scrollbar + Diff Minimap
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                items(rowCount) { rowIndex ->
                    val offset = rowIndex.toLong() * 16L
                    val (bytesA, bytesB, isDiff) = readHexDiffRow(rafA, rafB, offset, fileALen, fileBLen)

                    val isActiveChunk = activeChunk != null && rowIndex in activeChunk.startRow..activeChunk.endRow
                    val bgColor = when {
                        isActiveChunk -> Color(0xFFEF6C00).copy(alpha = 0.35f)
                        isDiff -> Color(0xFFEF6C00).copy(alpha = 0.15f)
                        else -> Color.Transparent
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(bgColor)
                            .let {
                                if (isActiveChunk && rowIndex == activeChunk.startRow) {
                                    it.border(1.dp, Color(0xFFFF9500))
                                } else it
                            }
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "%08X".format(offset),
                            modifier = Modifier.width(75.dp),
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = if (isActiveChunk) Color(0xFFFF9500) else MaterialTheme.colorScheme.primary,
                            fontWeight = if (isActiveChunk) FontWeight.Bold else FontWeight.Normal,
                        )
                        Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                formatHexBytes(bytesA),
                                modifier = Modifier.weight(1f),
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = if (isDiff && bytesA.isNotEmpty()) Color(0xFFFFB74D) else Color.Unspecified,
                            )
                            Text(
                                formatAsciiBytes(bytesA),
                                modifier = Modifier.width(130.dp),
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = if (isDiff && bytesA.isNotEmpty()) Color(0xFFFFB74D) else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                formatHexBytes(bytesB),
                                modifier = Modifier.weight(1f),
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = if (isDiff && bytesB.isNotEmpty()) Color(0xFFFF8A65) else Color.Unspecified,
                            )
                            Text(
                                formatAsciiBytes(bytesB),
                                modifier = Modifier.width(130.dp),
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = if (isDiff && bytesB.isNotEmpty()) Color(0xFFFF8A65) else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(modifier = Modifier.width(28.dp))
                    }
                }
            }

            // Right side: Araxis/Beyond Compare style Diff Minimap Gutter + Vertical Scrollbar
            Row(
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Interactive Diff Minimap Gutter (14dp width)
                if (rowCount > 0) {
                    Canvas(
                        modifier = Modifier
                            .width(14.dp)
                            .fillMaxHeight()
                            .background(Color(0xFF1E2228))
                            .pointerInput(rowCount, diffChunks) {
                                detectTapGestures { tapOffset ->
                                    val ratio = (tapOffset.y / size.height).coerceIn(0f, 1f)
                                    val targetRow = (ratio * rowCount).toInt().coerceIn(0, rowCount - 1)
                                    coroutineScope.launch {
                                        listState.scrollToItem(targetRow)
                                    }
                                }
                            }
                    ) {
                        val canvasHeight = size.height
                        val canvasWidth = size.width

                        // Draw diff markers on minimap
                        for (chunk in diffChunks) {
                            val startY = (chunk.startRow.toFloat() / rowCount) * canvasHeight
                            val endY = ((chunk.endRow + 1).toFloat() / rowCount) * canvasHeight
                            val markerHeight = maxOf(2.5f, endY - startY)
                            val isCurrent = activeChunk != null && chunk.index == activeChunk.index
                            drawRect(
                                color = if (isCurrent) Color(0xFFFF3131) else Color(0xFFFF9500),
                                topLeft = Offset(1f, startY),
                                size = androidx.compose.ui.geometry.Size(canvasWidth - 2f, markerHeight),
                            )
                        }

                        // Draw current viewport indicator on minimap
                        val visibleInfo = listState.layoutInfo.visibleItemsInfo
                        if (visibleInfo.isNotEmpty()) {
                            val firstVisible = visibleInfo.first().index
                            val visibleCount = visibleInfo.size
                            val viewStartY = (firstVisible.toFloat() / rowCount) * canvasHeight
                            val viewHeight = maxOf(6f, (visibleCount.toFloat() / rowCount) * canvasHeight)
                            drawRect(
                                color = Color.White.copy(alpha = 0.25f),
                                topLeft = Offset(0f, viewStartY),
                                size = androidx.compose.ui.geometry.Size(canvasWidth, viewHeight),
                            )
                            drawRect(
                                color = Color.White.copy(alpha = 0.8f),
                                topLeft = Offset(0f, viewStartY),
                                size = androidx.compose.ui.geometry.Size(canvasWidth, viewHeight),
                                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1f),
                            )
                        }
                    }
                }

                VerticalScrollbar(
                    adapter = rememberScrollbarAdapter(listState),
                    modifier = Modifier.fillMaxHeight(),
                )
            }
        }
    }
}

private fun readHexDiffRow(
    rafA: RandomAccessFile?,
    rafB: RandomAccessFile?,
    offset: Long,
    fileALength: Long,
    fileBLength: Long,
): Triple<ByteArray, ByteArray, Boolean> {
    val bA = ByteArray(16)
    val bB = ByteArray(16)
    var readA = 0
    var readB = 0

    if (rafA != null && offset < fileALength) {
        synchronized(rafA) {
            rafA.seek(offset)
            readA = rafA.read(bA).coerceAtLeast(0)
        }
    }
    if (rafB != null && offset < fileBLength) {
        synchronized(rafB) {
            rafB.seek(offset)
            readB = rafB.read(bB).coerceAtLeast(0)
        }
    }

    val actualA = bA.copyOf(readA)
    val actualB = bB.copyOf(readB)
    val isDiff = !actualA.contentEquals(actualB)
    return Triple(actualA, actualB, isDiff)
}

private fun formatHexBytes(bytes: ByteArray): String {
    if (bytes.isEmpty()) return "(EOF)"
    val sb = StringBuilder()
    for (i in 0 until 16) {
        if (i < bytes.size) {
            sb.append("%02X ".format(bytes[i]))
        } else {
            sb.append("   ")
        }
        if (i == 7) sb.append(" ")
    }
    return sb.toString()
}

private fun formatAsciiBytes(bytes: ByteArray): String {
    if (bytes.isEmpty()) return ""
    val sb = StringBuilder()
    for (i in 0 until bytes.size) {
        val b = bytes[i].toInt() and 0xFF
        sb.append(if (b in 0x20..0x7E) b.toChar() else '.')
    }
    return sb.toString()
}

@Composable
private fun EmptyComparePlaceholder(language: AppLanguage) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            if (language == AppLanguage.KO) "비교할 두 미디어(이미지 또는 동영상)를 상단에서 선택해 주세요." else "Please select two media files (images or videos) to compare above.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.sp,
        )
    }
}

private fun formatSize(bytes: Long): String {
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
