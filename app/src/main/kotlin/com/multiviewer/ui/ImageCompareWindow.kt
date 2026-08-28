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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import com.multiviewer.parser.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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

enum class VisualCompareMode {
    SPLIT_WIPER,
    SIDE_BY_SIDE,
    DIFF_HEATMAP,
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
    onCloseRequest: () -> Unit,
) {
    var selectedTab by remember { mutableStateOf(MediaCompareTab.STRUCTURE) }

    var fileA by remember { mutableStateOf(initialFileA ?: appState.tabs.getOrNull(0)?.file) }
    var fileB by remember { mutableStateOf(initialFileB ?: appState.tabs.getOrNull(1)?.file) }

    var infoA by remember { mutableStateOf<CompareMediaInfo?>(null) }
    var infoB by remember { mutableStateOf<CompareMediaInfo?>(null) }

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
                val root = parseFile(file)
                val summary = buildMediaSummary(root, file)
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
                    val forensic = ImageAnalyzer.analyze(file, root)
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

    Window(
        onCloseRequest = onCloseRequest,
        title = if (language == AppLanguage.KO) "미디어 비교 분석기 (이미지/동영상)" else "Media Comparison Analyzer (Image/Video)",
        state = rememberWindowState(size = DpSize(1150.dp, 840.dp)),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                // 1. Media Selection Bar (Tabs dropdown + File Pickers)
                MediaSelectionBar(
                    appState = appState,
                    language = language,
                    openTabs = appState.tabs.map { it.file },
                    fileA = fileA,
                    fileB = fileB,
                    infoA = infoA,
                    infoB = infoB,
                    onSelectA = { fileA = it },
                    onSelectB = { fileB = it },
                    onSwap = {
                        val temp = fileA
                        fileA = fileB
                        fileB = temp
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
                        MediaCompareTab.METADATA -> MetadataDiffView(language, infoA, infoB)
                        MediaCompareTab.VISUAL -> VisualDiffView(language, infoA, infoB)
                        MediaCompareTab.HEX -> HexDiffView(language, fileA, fileB)
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
    openTabs: List<File>,
    fileA: File?,
    fileB: File?,
    infoA: CompareMediaInfo?,
    infoB: CompareMediaInfo?,
    onSelectA: (File) -> Unit,
    onSelectB: (File) -> Unit,
    onSwap: () -> Unit,
) {
    fun openFileDialog(title: String, onPicked: (File) -> Unit) {
        val dialog = FileDialog(null as Frame?, title, FileDialog.LOAD)
        appState.lastOpenedDirectory?.let { dir ->
            if (dir.exists() && dir.isDirectory) {
                dialog.directory = dir.absolutePath
            }
        }
        dialog.isVisible = true
        val name = dialog.file
        val dir = dialog.directory
        if (name != null && dir != null) {
            val file = File(dir, name)
            appState.updateLastOpenedDirectory(file)
            onPicked(file)
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Media A Selector
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (language == AppLanguage.KO) "기준 미디어 (A)" else "Reference Media (A)",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = { openFileDialog(if (language == AppLanguage.KO) "미디어 A 선택" else "Select Media A", onSelectA) },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier.height(30.dp),
                    ) {
                        Text(if (language == AppLanguage.KO) "📂 파일 찾기..." else "📂 Browse...", fontSize = 11.sp)
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                FileDropdownOrLabel(
                    selectedFile = fileA,
                    availableTabs = openTabs,
                    onSelect = onSelectA,
                )
                if (infoA != null) {
                    val typeLabel = if (infoA.isVideo) "🎬 동영상 (Video)" else "🖼️ 이미지 (Image)"
                    val durStr = if (infoA.isVideo && infoA.durationSeconds > 0) " | ${"%.2f".format(infoA.durationSeconds)}s" else ""
                    Text(
                        "$typeLabel | ${formatSize(infoA.fileSize)} | ${infoA.file.extension.uppercase(Locale.US)}$durStr",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Swap Button
            IconButton(onClick = onSwap, modifier = Modifier.padding(horizontal = 8.dp)) {
                Text("⇄", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            }

            // Media B Selector
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (language == AppLanguage.KO) "비교 미디어 (B)" else "Target Media (B)",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = { openFileDialog(if (language == AppLanguage.KO) "미디어 B 선택" else "Select Media B", onSelectB) },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier.height(30.dp),
                    ) {
                        Text(if (language == AppLanguage.KO) "📂 파일 찾기..." else "📂 Browse...", fontSize = 11.sp)
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                FileDropdownOrLabel(
                    selectedFile = fileB,
                    availableTabs = openTabs,
                    onSelect = onSelectB,
                )
                if (infoB != null) {
                    val typeLabel = if (infoB.isVideo) "🎬 동영상 (Video)" else "🖼️ 이미지 (Image)"
                    val delta = if (infoA != null) infoB.fileSize - infoA.fileSize else 0L
                    val deltaStr = if (delta > 0) " (+${formatSize(delta)})" else if (delta < 0) " (-${formatSize(-delta)})" else ""
                    val durStr = if (infoB.isVideo && infoB.durationSeconds > 0) " | ${"%.2f".format(infoB.durationSeconds)}s" else ""
                    Text(
                        "$typeLabel | ${formatSize(infoB.fileSize)}$deltaStr | ${infoB.file.extension.uppercase(Locale.US)}$durStr",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun FileDropdownOrLabel(
    selectedFile: File?,
    availableTabs: List<File>,
    onSelect: (File) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        Surface(
            modifier = Modifier.fillMaxWidth().clickable { expanded = true }.padding(vertical = 2.dp),
            shape = RoundedCornerShape(4.dp),
            color = MaterialTheme.colorScheme.surface,
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    selectedFile?.name ?: "선택된 파일 없음 (None)",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                )
                Text("▾", fontSize = 12.sp)
            }
        }

        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (availableTabs.isEmpty()) {
                DropdownMenuItem(
                    text = { Text("열려있는 탭 없음 (No open tabs)", fontSize = 12.sp) },
                    onClick = { expanded = false },
                )
            } else {
                availableTabs.forEach { tabFile ->
                    val icon = if (isVideoExtension(tabFile)) "🎬" else "🖼️"
                    DropdownMenuItem(
                        text = { Text("$icon ${tabFile.name}", fontSize = 12.sp) },
                        onClick = {
                            onSelect(tabFile)
                            expanded = false
                        },
                    )
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
private fun MetadataDiffView(language: AppLanguage, infoA: CompareMediaInfo?, infoB: CompareMediaInfo?) {
    if (infoA == null || infoB == null) {
        EmptyComparePlaceholder(language)
        return
    }

    var onlyDiffs by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    val allRows = remember(infoA, infoB) {
        extractMetadataDiffRows(infoA, infoB)
    }

    val filteredRows = remember(allRows, onlyDiffs, searchQuery) {
        allRows.filter { row ->
            (!onlyDiffs || row.isDifferent) &&
                (searchQuery.isBlank() || row.key.contains(searchQuery, ignoreCase = true) || row.valueA.contains(searchQuery, ignoreCase = true) || row.valueB.contains(searchQuery, ignoreCase = true))
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
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
    val mpA = infoA.root?.let { findEmbeddedVideo(it, ByteReader.open(infoA.file)) }
    val mpB = infoB.root?.let { findEmbeddedVideo(it, ByteReader.open(infoB.file)) }
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

private fun extractSefNames(root: BoxNode?): List<String> {
    if (root == null) return emptyList()
    val sefd = findFirst(root) { it.type == "sefd" } ?: return emptyList()
    return sefd.children.map { it.type }
}

// -------------------------------------------------------------------------------------------------
// 3. Visual Diff View (Split Wiper, Side by Side, Difference Heatmap + Video Timeline Sync)
// -------------------------------------------------------------------------------------------------

@Composable
private fun VisualDiffView(language: AppLanguage, infoA: CompareMediaInfo?, infoB: CompareMediaInfo?) {
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
                        Row(modifier = Modifier.fillMaxSize().padding(8.dp)) {
                            Box(modifier = Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                                androidx.compose.foundation.Image(bitmap = displayBitmapA, contentDescription = "Media A", modifier = Modifier.fillMaxSize())
                                Text("Media A", modifier = Modifier.align(Alignment.TopStart).background(Color.Black.copy(alpha = 0.6f)).padding(4.dp), color = Color.White, fontSize = 11.sp)
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Box(modifier = Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                                androidx.compose.foundation.Image(bitmap = displayBitmapB, contentDescription = "Media B", modifier = Modifier.fillMaxSize())
                                Text("Media B", modifier = Modifier.align(Alignment.TopStart).background(Color.Black.copy(alpha = 0.6f)).padding(4.dp), color = Color.White, fontSize = 11.sp)
                            }
                        }
                    }
                    VisualCompareMode.DIFF_HEATMAP -> {
                        val diffBitmap = remember(displayBitmapA, displayBitmapB) { computeDiffBitmap(displayBitmapA, displayBitmapB) }
                        if (diffBitmap != null) {
                            Box(modifier = Modifier.fillMaxSize().padding(8.dp), contentAlignment = Alignment.Center) {
                                androidx.compose.foundation.Image(bitmap = diffBitmap, contentDescription = "Diff Heatmap", modifier = Modifier.fillMaxSize())
                                Text(
                                    if (language == AppLanguage.KO) "🔍 차이점 마스크 (변화가 있는 픽셀이 밝게 표시됨)" else "🔍 Diff Mask (Changed pixels highlighted)",
                                    modifier = Modifier.align(Alignment.BottomCenter).background(Color.Black.copy(alpha = 0.7f)).padding(6.dp),
                                    color = Color.Yellow,
                                    fontSize = 11.sp,
                                )
                            }
                        }
                    }
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
