package com.multiviewer.ui

import com.multiviewer.parser.isOpenableMediaFile
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.multiviewer.cache.ThumbnailDiskCache
import com.multiviewer.cache.ThumbnailSourceFingerprint
import com.multiviewer.parser.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext
import java.awt.EventQueue
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image as SkiaImage

// Folder explorer + thumbnail loading for the Media Comparison Analyzer (split out of ImageCompareWindow.kt).

internal class ThumbnailRequestRegistry<T> {
    private val listeners = mutableMapOf<String, MutableList<(T) -> Unit>>()

    /** Returns true only for the caller that must start the shared decode. */
    fun add(key: String, listener: (T) -> Unit): Boolean = synchronized(listeners) {
        val waiting = listeners[key]
        if (waiting != null) {
            waiting += listener
            false
        } else {
            listeners[key] = mutableListOf(listener)
            true
        }
    }

    /** Reserves a request for prefetch work that has no UI subscriber yet. */
    fun ensure(key: String): Boolean = synchronized(listeners) {
        if (listeners.containsKey(key)) {
            false
        } else {
            listeners[key] = mutableListOf()
            true
        }
    }

    fun publish(key: String, value: T, isFinal: Boolean): List<(T) -> Unit> = synchronized(listeners) {
        val waiting = if (isFinal) listeners.remove(key).orEmpty() else listeners[key].orEmpty()
        waiting.toList()
    }

    /** Compatibility adapter for callers that previously requested a one-shot completion. */
    fun complete(key: String, value: T): List<() -> Unit> =
        publish(key, value, isFinal = true).map { listener -> { listener(value) } }

    fun finish(key: String) {
        synchronized(listeners) { listeners.remove(key) }
    }
}

private object ExplorerThumbnailLoader {
    private val diskCache by lazy {
        ThumbnailDiskCache(File(System.getProperty("user.home"), ".unwrapMedia/cache/thumbnails"))
    }
    private val cache = ThumbnailMemoryCache<ImageBitmap>()
    private val requests = ThumbnailRequestRegistry<ImageBitmap>()
    private val workQueue = ThumbnailPriorityQueue()
    private val visiblePaths = mutableSetOf<String>()
    private val prefetchPaths = mutableSetOf<String>()
    private var listMode = false
    private val lookaheadScheduler = ThumbnailLookaheadScheduler<File> { index, file ->
        requestThumbnail(file, index, onLoaded = null)
    }
    private val workers = Executors.newFixedThreadPool(3) { r ->
        Thread(r).apply { isDaemon = true }
    }.also { executor ->
        repeat(3) {
            executor.submit {
                while (!Thread.currentThread().isInterrupted) {
                    try {
                        workQueue.takeNext().action()
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                }
            }
        }
    }

    fun updateGridViewport(
        files: List<File>,
        visibleIndices: Set<Int>,
        columnCount: Int = 1,
        listMode: Boolean = false,
    ) {
        synchronized(visiblePaths) {
            this.listMode = listMode
            visiblePaths.clear()
            prefetchPaths.clear()
            visibleIndices.forEach { index -> files.getOrNull(index)?.let { visiblePaths += it.absolutePath } }
            val prefetchIndices = if (listMode) emptySet() else
                thumbnailPrefetchIndices(visibleIndices, columnCount, files.size)
            prefetchIndices.forEach { index -> files.getOrNull(index)?.let { prefetchPaths += it.absolutePath } }
            workQueue.reprioritize { key -> priorityForPath(key) }
        }
        if (!listMode) lookaheadScheduler.update(files, visibleIndices, columnCount)
    }

    fun getThumbnail(file: File, itemIndex: Int? = null, onLoaded: (ImageBitmap) -> Unit): ImageBitmap? =
        requestThumbnail(file, itemIndex, onLoaded)

    private fun requestThumbnail(
        file: File,
        itemIndex: Int?,
        onLoaded: ((ImageBitmap) -> Unit)?,
    ): ImageBitmap? {
        val path = file.absolutePath
        val requestedAt = System.nanoTime()
        val ext = file.extension.lowercase(Locale.US)
        val cached = cache.get(file)
        if (cached != null) {
            ThumbnailMetrics.record(
                event = "thumbnail",
                mediaType = ext,
                elapsedMs = (System.nanoTime() - requestedAt) / 1_000_000,
                result = "cache_hit",
            )
            return cached
        }

        val shouldStart = if (onLoaded == null) requests.ensure(path) else requests.add(path, onLoaded)
        if (shouldStart) {
            val initialPriority = synchronized(visiblePaths) {
                if (itemIndex == null) 0 else thumbnailPriorityForPath(path, visiblePaths, prefetchPaths, listMode)
            }
            workQueue.enqueue(path, initialPriority) {
                val sourceFingerprint = ThumbnailSourceFingerprint.capture(file)
                // Route by content, not name (worker thread, so the sniff's read is fine): a MOV saved as .bin
                // still goes to ffmpeg, a HEIC saved as .mp4 to the image decoders.
                val kindExt = com.multiviewer.parser.contentExtension(file)
                val workerStarted = System.nanoTime()
                val queueMs = (workerStarted - requestedAt) / 1_000_000
                val sourceBytes = runCatching { file.length() }.getOrNull()
                val decodeStarted = System.nanoTime()
                var route = if (kindExt in VIDEO_EXTENSIONS) "ffmpeg" else "skia"
                var result = "failed"
                var delivered = false
                try {
                    val cachedBytes = diskCache.get(file, longestEdge = 240, fingerprint = sourceFingerprint)
                    val cachedBitmap = cachedBytes?.let { bytes ->
                        runCatching {
                            val image = SkiaImage.makeFromEncoded(bytes) ?: return@runCatching null
                            try {
                                image.toComposeImageBitmap()
                            } finally {
                                image.close()
                            }
                        }.getOrNull()
                    }
                    if (cachedBytes != null && cachedBitmap == null) {
                        diskCache.remove(file, longestEdge = 240, fingerprint = sourceFingerprint)
                    }
                    if (cachedBitmap != null) {
                        route = "disk_cache"
                        result = route
                        if (sourceFingerprint.matches(file)) {
                            cache.put(file, cachedBitmap, sourceFingerprint)
                            publishThumbnail(path, file, cachedBitmap, true, sourceFingerprint, ext, sourceBytes, queueMs, decodeStarted, requestedAt, result)
                            delivered = true
                        } else {
                            requests.finish(path)
                        }
                    } else {
                        val coordinator = ThumbnailLoadCoordinator<ImageBitmap>(
                            preview = { source ->
                                if (kindExt == "jpg" || kindExt == "jpeg") ImageAnalyzer.decodeEmbeddedJpegThumbnail(source, longestEdge = 240) else null
                            },
                            decodeFinal = { source ->
                                if (kindExt in VIDEO_EXTENSIONS) {
                                    route = "ffmpeg"
                                    FfmpegImageSnapshotDecoder.decodeSingleFrameToBitmap(
                                        listOf(FfmpegLocator.ffmpegPath(), "-y", "-ss", "0.5", "-i", source.absolutePath,
                                            "-vf", "scale=160:-1", "-frames:v", "1", "-update", "1"),
                                        tempExtension = ".jpg", timeoutMs = 8_000L,
                                    ) ?: FfmpegImageSnapshotDecoder.decodeSingleFrameToBitmap(
                                        listOf(FfmpegLocator.ffmpegPath(), "-y", "-i", source.absolutePath,
                                            "-vf", "scale=160:-1", "-frames:v", "1", "-update", "1"),
                                        tempExtension = ".jpg", timeoutMs = 8_000L,
                                    )
                                } else {
                                    val primary = ImageAnalyzer.decodeThumbnail(source, longestEdge = 240)
                                    if (primary != null) primary else {
                                        route = "ffmpeg_fallback"
                                        FfmpegImageSnapshotDecoder.decodeThumbnailToBitmap(source, longestEdge = 240)
                                    }
                                }
                            },
                            persistFinal = { source, finalBitmap, fingerprint ->
                                if (route != "disk_cache" && fingerprint.matches(source)) {
                                    encodeAndPersist(source, ext, finalBitmap, fingerprint)
                                }
                            },
                            publish = { source, loaded, isFinal ->
                                if (!sourceFingerprint.matches(source)) {
                                    requests.finish(path)
                                } else {
                                    if (isFinal) {
                                        result = route
                                        cache.put(source, loaded, sourceFingerprint)
                                        delivered = true
                                    }
                                    publishThumbnail(path, source, loaded, isFinal, sourceFingerprint, ext, sourceBytes, queueMs, decodeStarted, requestedAt, result)
                                }
                            },
                            finishWithoutBitmap = { requests.finish(path) },
                        )
                        coordinator.load(file, sourceFingerprint)
                    }
                } catch (_: Exception) {
                    result = "error_$route"
                    requests.finish(path)
                } finally {
                    if (!delivered) {
                        ThumbnailMetrics.record(
                            event = "thumbnail", mediaType = ext, sourceBytes = sourceBytes,
                            queueMs = queueMs, decodeMs = (System.nanoTime() - decodeStarted) / 1_000_000,
                            elapsedMs = (System.nanoTime() - requestedAt) / 1_000_000, result = result,
                        )
                    }
                }
            }
        }
        return null
    }

    private fun priorityForPath(path: String): Int =
        thumbnailPriorityForPath(path, visiblePaths, prefetchPaths, listMode)

    private fun publishThumbnail(
        path: String, source: File, bitmap: ImageBitmap, isFinal: Boolean,
        fingerprint: ThumbnailSourceFingerprint, ext: String,
        sourceBytes: Long?, queueMs: Long, decodeStarted: Long, requestedAt: Long, result: String,
    ) {
        val listeners = requests.publish(path, bitmap, isFinal)
        val decodeFinishedAt = System.nanoTime()
        EventQueue.invokeLater {
            if (!fingerprint.matches(source)) {
                requests.finish(path)
                return@invokeLater
            }
            val publishedAt = System.nanoTime()
            if (isFinal) ThumbnailMetrics.record(
                event = "thumbnail", mediaType = ext, sourceBytes = sourceBytes, queueMs = queueMs,
                decodeMs = (decodeFinishedAt - decodeStarted) / 1_000_000,
                publishMs = (publishedAt - decodeFinishedAt) / 1_000_000,
                elapsedMs = (publishedAt - requestedAt) / 1_000_000, result = result,
            )
            listeners.forEach { it(bitmap) }
        }
    }

    private fun encodeAndPersist(
        file: File,
        ext: String,
        bitmap: ImageBitmap,
        fingerprint: ThumbnailSourceFingerprint,
    ) {
        val skiaImage = SkiaImage.makeFromBitmap(bitmap.asSkiaBitmap())
        try {
            val format = if (ext in LOSSLESS_THUMBNAIL_EXTENSIONS) EncodedImageFormat.PNG else EncodedImageFormat.JPEG
            val encoded = skiaImage.encodeToData(format, 88) ?: return
            try {
                diskCache.put(file, longestEdge = 240, encodedThumbnail = encoded.bytes, fingerprint = fingerprint)
            } finally {
                encoded.close()
            }
        } finally {
            skiaImage.close()
        }
    }
}

private val LOSSLESS_THUMBNAIL_EXTENSIONS = setOf("png", "apng", "gif", "webp", "tif", "tiff")


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

    var compareSelection by remember {
        mutableStateOf(CompareExplorerSelection.fromFiles(initialSelected.take(2)))
    }

    var searchQuery by remember { mutableStateOf("") }
    var isGridView by remember { mutableStateOf(true) }

    // Listed on Dispatchers.IO so a big or remote folder never freezes the window; the search box filters
    // the listing in memory. The listing is tagged with its folder so the previous folder's entries are never
    // shown (or opened) under the new one while it loads.
    val loadedListing by produceState<Pair<File, FolderListing>?>(null, currentFolder) {
        val folder = currentFolder
        value = withContext(Dispatchers.IO) {
            val startedAt = System.nanoTime()
            val listing = listFolder(folder)
            ThumbnailMetrics.record(
                event = "folder_list",
                elapsedMs = (System.nanoTime() - startedAt) / 1_000_000,
                itemCount = listing.mediaFiles.size + listing.subfolders.size,
                result = if (listing.available) "ok" else "unavailable",
            )
            folder to listing.copy(subfolders = listing.subfolders.filter { it.canRead() })
        }
    }
    val folderListing = loadedListing?.takeIf { it.first == currentFolder }?.second
    // Icon/badge kind: the detected format's extension for files listed by content (no disk access).
    fun kindExtensionOf(file: File): String = folderListing?.kindExtension(file) ?: file.extension.lowercase(Locale.US)

    val subDirectories = folderListing?.subfolders ?: emptyList()

    val mediaFiles = remember(folderListing, searchQuery) {
        val startedAt = System.nanoTime()
        (folderListing?.mediaFiles ?: emptyList()).filter {
            searchQuery.isBlank() || it.name.contains(searchQuery, ignoreCase = true)
        }.also { files ->
            ThumbnailMetrics.record(
                event = "media_index",
                elapsedMs = (System.nanoTime() - startedAt) / 1_000_000,
                itemCount = files.size,
                result = "ok",
            )
        }
    }

    fun toggleFileSelection(file: File) {
        compareSelection = compareSelection.toggleThumbnail(file)
    }

    fun openCompareIfValid() {
        compareSelection.filesForCompare()?.let(onOpenCompare)
    }

    val pickScope = rememberCoroutineScope()
    fun pickFileForSlot(slotIndex: Int) {
        val selectedFile = if (slotIndex == 0) compareSelection.slotA else compareSelection.slotB
        val dialog = FileDialog(
            null as Frame?,
            if (language == AppLanguage.KO) {
                if (slotIndex == 0) "기준 미디어(A) 선택" else "비교 미디어(B) 선택"
            } else {
                if (slotIndex == 0) "Select Reference Media (A)" else "Select Comparison Media (B)"
            },
            FileDialog.LOAD,
        )
        dialog.directory = selectedFile?.parentFile?.takeIf { it.isDirectory }?.absolutePath
            ?: appState.lastOpenedDirectory?.takeIf { it.isDirectory }?.absolutePath
            ?: currentFolder.absolutePath
        dialog.isMultipleMode = false
        // No name filter: a mis-named media file must stay pickable; the content check below decides.
        dialog.isVisible = true
        val pickedFile = dialog.file?.let { name -> dialog.directory?.let { directory -> File(directory, name) } }
            ?: return
        pickScope.launch {
            if (!withContext(Dispatchers.IO) { isOpenableMediaFile(pickedFile) }) return@launch
            compareSelection = compareSelection.assign(slotIndex, pickedFile)
            appState.updateLastOpenedDirectory(pickedFile)
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
                                if (compareSelection.isReadyToCompare) {
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
            shape = RoundedCornerShape(4.dp),
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
                        shape = AppControlShape,
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
                    shape = AppControlShape,
                ) {
                    Text(
                        text = if (isGridView) "📋 목록" else "▦ 그리드",
                        fontSize = 11.sp,
                        color = AppColors.TextPrimary,
                    )
                }
            }
        }

        // Independent A/B file pickers complement the current-folder thumbnail selection below.
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
            shape = RoundedCornerShape(4.dp),
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CompareExplorerFileSlot(
                    label = if (language == AppLanguage.KO) "A · 기준 파일" else "A · Reference file",
                    file = compareSelection.slotA,
                    color = Color(0xFF61AFEF),
                    language = language,
                    modifier = Modifier.weight(1f),
                    onChoose = { pickFileForSlot(0) },
                )
                CompareExplorerFileSlot(
                    label = if (language == AppLanguage.KO) "B · 비교 파일" else "B · Comparison file",
                    file = compareSelection.slotB,
                    color = Color(0xFF98C379),
                    language = language,
                    modifier = Modifier.weight(1f),
                    onChoose = { pickFileForSlot(1) },
                )
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
                shape = RoundedCornerShape(4.dp),
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
                shape = RoundedCornerShape(4.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                if (mediaFiles.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = when {
                                folderListing == null -> if (language == AppLanguage.KO) "폴더를 읽는 중…" else "Reading folder…"
                                language == AppLanguage.KO -> "지원되는 미디어 파일이 없습니다"
                                else -> "No supported media files found"
                            },
                            fontSize = 13.sp,
                            color = AppColors.TextSecondary,
                        )
                    }
                } else {
                    val gridState = rememberLazyGridState()
                    LaunchedEffect(isGridView, gridState, mediaFiles) {
                        if (!isGridView) {
                            ExplorerThumbnailLoader.updateGridViewport(emptyList(), emptySet(), columnCount = 1, listMode = true)
                        } else {
                            snapshotFlow {
                                val visibleItems = gridState.layoutInfo.visibleItemsInfo
                                val visibleIndices = visibleItems.mapTo(mutableSetOf()) { it.index }
                                val columnCount = visibleItems.groupingBy { it.offset.y }.eachCount().values.maxOrNull() ?: 1
                                visibleIndices to columnCount
                            }.distinctUntilChanged().collect { (visibleIndices, columnCount) ->
                                ExplorerThumbnailLoader.updateGridViewport(
                                    mediaFiles,
                                    visibleIndices,
                                    columnCount,
                                )
                            }
                        }
                    }
                    if (isGridView) {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(minSize = 140.dp),
                            state = gridState,
                            modifier = Modifier.fillMaxSize().padding(6.dp),
                            contentPadding = PaddingValues(4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            itemsIndexed(mediaFiles, key = { _, file -> file.absolutePath }) { itemIndex, file ->
                                val selectedIndex = compareSelection.indexOf(file)
                                val isSelected = selectedIndex >= 0

                                var thumbnailBitmap by remember(file.absolutePath) {
                                    mutableStateOf<ImageBitmap?>(null)
                                }

                                LaunchedEffect(file.absolutePath) {
                                    val cached = ExplorerThumbnailLoader.getThumbnail(file, itemIndex) { loaded ->
                                        thumbnailBitmap = loaded
                                    }
                                    if (cached != null) {
                                        thumbnailBitmap = cached
                                    }
                                }

                                Surface(
                                    shape = RoundedCornerShape(4.dp),
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
                                                val isVid = kindExtensionOf(file) in VIDEO_EXTENSIONS
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
                                                val isVid = kindExtensionOf(file) in VIDEO_EXTENSIONS
                                                Surface(
                                                    color = Color.Black.copy(alpha = 0.65f),
                                                    shape = RoundedCornerShape(3.dp),
                                                ) {
                                                    Text(
                                                        text = if (isVid) "VIDEO" else kindExtensionOf(file).uppercase(Locale.US),
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
                                                        shape = RoundedCornerShape(4.dp),
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
                                val selectedIndex = compareSelection.indexOf(file)
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
                                                    val isVid = kindExtensionOf(file) in VIDEO_EXTENSIONS
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
        val count = compareSelection.selectedCount
        val isValidCount = compareSelection.isReadyToCompare

        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
            shape = RoundedCornerShape(4.dp),
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

                    if (count > 0) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = listOfNotNull(
                                compareSelection.slotA?.let { "① ${it.name}" },
                                compareSelection.slotB?.let { "② ${it.name}" },
                            ).joinToString(" · "),
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
                                .clickable { compareSelection = compareSelection.clear() }
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
                        shape = AppControlShape,
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

}
}

@Composable
private fun CompareExplorerFileSlot(
    label: String,
    file: File?,
    color: Color,
    language: AppLanguage,
    modifier: Modifier = Modifier,
    onChoose: () -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(4.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, color.copy(alpha = 0.55f)),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(label, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = color)
                Text(
                    text = file?.name ?: if (language == AppLanguage.KO) "파일을 선택하세요" else "Choose a file",
                    fontSize = 12.sp,
                    fontWeight = if (file != null) FontWeight.Medium else FontWeight.Normal,
                    color = if (file != null) AppColors.TextPrimary else AppColors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = file?.parentFile?.absolutePath ?: if (language == AppLanguage.KO) "폴더 미선택" else "No folder selected",
                    fontSize = 10.sp,
                    color = AppColors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            OutlinedButton(
                onClick = onChoose,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                modifier = Modifier.height(30.dp),
                shape = AppControlShape,
            ) {
                Text(if (language == AppLanguage.KO) "파일 찾기…" else "Browse…", fontSize = 11.sp)
            }
        }
    }
}
