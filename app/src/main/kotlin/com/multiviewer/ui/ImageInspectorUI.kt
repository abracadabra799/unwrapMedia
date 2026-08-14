package com.multiviewer.ui

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.asSkiaBitmap
import com.multiviewer.parser.BoxNode
import com.multiviewer.parser.EmbeddedVideo
import com.multiviewer.parser.extractEmbeddedVideo
import com.multiviewer.parser.MediaCategory
import com.multiviewer.parser.ScanStatistics
import com.multiviewer.parser.computeScanStatistics
import com.multiviewer.parser.WarningEntry
import com.multiviewer.parser.collectWarnings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun ImageInspectorUI(
    appState: AppState,
    tab: TabState,
    leftPanel: @Composable ColumnScope.() -> Unit,
    bottomPanel: @Composable ColumnScope.() -> Unit
) {
    val forensic = tab.imageForensic ?: return

    LaunchedEffect(tab.file, tab.root) {
        val root = tab.root ?: return@LaunchedEffect
        tab.tileGrid = withContext(Dispatchers.IO) { com.multiviewer.parser.findHeicTileGrid(tab.file, root) }
    }

    // Reacts to the Media Structure tree selection (tab.selected, set generically by BoxTreeView's
    // onSelect for any node) rather than a gesture on the image itself: whenever the selected node
    // is one of tileGrid's own tile items (an iloc "item_<ID>" node whose ID appears in
    // tileGrid.tileItemIds), resolve that tile's real pixel-data byte range and its row-major
    // index -- both null otherwise, which is what hides the single-tile overlay (PixelInspectorPreview
    // below) and falls the Hex viewer highlight back to its normal tree-selection behavior
    // (Main.kt's tileHighlightRange ?: activeField ?: selected chain).
    LaunchedEffect(tab.selected, tab.tileGrid) {
        val root = tab.root
        val tileGrid = tab.tileGrid
        val selected = tab.selected
        val itemId = selected?.type?.takeIf { it.startsWith("item_") }?.removePrefix("item_")?.toLongOrNull()
        val tileIndex = if (root != null && tileGrid != null && itemId != null) tileGrid.tileItemIds.indexOf(itemId).takeIf { it >= 0 } else null
        if (root == null || tileIndex == null) {
            tab.tileHighlightRange = null
            tab.selectedTileIndex = null
            return@LaunchedEffect
        }
        val iloc = com.multiviewer.parser.findFirst(root) { it.type == "meta" }
            ?.let { meta -> com.multiviewer.parser.findFirst(meta) { it.type == "iloc" } }
        val extent = iloc?.children?.find { it.type == "item_$itemId" }?.children?.firstOrNull()
        val absoluteOffset = extent?.fields?.find { it.name == "offset" }?.value?.toLongOrNull()
        val idatRelativeOffset = extent?.fields?.find { it.name == "idat_relative_offset" }?.value?.toLongOrNull()
        val offset = if (absoluteOffset != null) {
            absoluteOffset
        } else if (idatRelativeOffset != null) {
            val idatBase = com.multiviewer.parser.findFirst(root) { it.type == "idat" }
                ?.let { it.offset + it.headerSize } ?: 0L
            idatBase + idatRelativeOffset
        } else {
            null
        }
        val length = extent?.fields?.find { it.name == "length" }?.value?.toLongOrNull()
        tab.tileHighlightRange = if (offset != null && length != null) offset until (offset + length) else null
        tab.selectedTileIndex = tileIndex
    }

    DashboardLayout(
        leftPanel = leftPanel,
        centerPanel = {
            Column(modifier = Modifier.fillMaxSize()) {
                tab.largeResolutionWarning?.let { warning ->
                    ResolutionWarningBanner(warning, onDismiss = { tab.largeResolutionWarning = null })
                }
                // Dual Preview -- or, for an animated GIF whose frames decoded successfully, a
                // full-width frame filmstrip instead (see
                // docs/superpowers/specs/2026-08-01-gif-animation-playback-design.md). Any other
                // case -- non-GIF file, or a GIF whose animation decode hasn't finished/failed --
                // falls through to the unchanged three-box row below. Fills the whole center
                // panel -- the panel's own allocated space is what shrank (see DashboardLayout's
                // rightPanelDefaultWidthDp/verticalSplit), not this content within it.
                val gifAnimation = tab.gifAnimation
                if (tab.file.extension.lowercase() == "gif" && gifAnimation != null) {
                    GifFilmstripPlayer(
                        tab = tab,
                        animation = gifAnimation,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                Row(modifier = Modifier.fillMaxSize()) {
                    // Left Panel: Embedded EXIF Thumbnail
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .border(0.5.dp, AppColors.Border)
                            .background(Color.Black),
                        contentAlignment = Alignment.Center
                    ) {
                        forensic.embeddedThumbnail?.let {
                            PixelInspectorPreview(it)
                        } ?: if (forensic.isDecodingFallback && forensic.hasThumbnailReference) {
                            // The file structurally has a thumbnail (hasThumbnailReference) but
                            // the fast synchronous extraction pass didn't find it -- it's still
                            // being pulled out via the async ffmpeg fallback, not genuinely absent.
                            DecodingIndicator("썸네일 로딩 중...")
                        } else if (forensic.hasThumbnailReference) {
                            // Decoding finished and the file does reference a thumbnail item, but
                            // it couldn't be extracted (e.g. an HEVC-coded HEIC "thmb" item -- this
                            // parser only decodes JPEG-coded thumbnail items today). Distinguish
                            // this from "genuinely no thumbnail" rather than showing nothing.
                            Text("Embedded Thumbnail Codec Not Supported", color = Color.Gray, fontSize = 13.sp)
                        } else {
                            Text("No Embedded Thumbnail", color = Color.Gray, fontSize = 13.sp)
                        }

                        Text("EMBEDDED EXIF THUMBNAIL",
                            modifier = Modifier.align(Alignment.TopStart).padding(4.dp),
                            style = AppTypography.labelLarge.copy(fontSize = 10.sp, color = AppColors.NeonBlue)
                        )

                        forensic.embeddedThumbnail?.let {
                            val orientationSuffix = forensic.orientation?.let { o -> " · $o" } ?: ""
                            PreviewCaption(
                                "${it.width}x${it.height}$orientationSuffix",
                                modifier = Modifier.align(Alignment.BottomStart).padding(4.dp),
                            )
                        }
                    }
                    
                    // Middle Panel: Primary Image View
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .border(0.5.dp, AppColors.Border)
                            .background(Color.Black),
                        contentAlignment = Alignment.Center
                    ) {
                        forensic.bitmap?.let {
                            PixelInspectorPreview(
                                it,
                                tileGrid = tab.tileGrid,
                                selectedTileIndex = tab.selectedTileIndex,
                                onTileClick = { index ->
                                    // Bidirectional counterpart to the tree-selection LaunchedEffect
                                    // above: rather than duplicating its offset/length resolution,
                                    // just set tab.selected to the clicked tile's own tree node --
                                    // that LaunchedEffect (keyed on tab.selected) then recomputes
                                    // tileHighlightRange/selectedTileIndex itself, and Main.kt's
                                    // existing scroll-to-item effect (also keyed on tab.selected)
                                    // reveals the node in the Media Structure tree too.
                                    val root = tab.root
                                    val itemId = tab.tileGrid?.tileItemIds?.getOrNull(index)
                                    val iloc = root?.let { r -> com.multiviewer.parser.findFirst(r) { it.type == "meta" } }
                                        ?.let { meta -> com.multiviewer.parser.findFirst(meta) { it.type == "iloc" } }
                                    val node = itemId?.let { id -> iloc?.children?.find { it.type == "item_$id" } }
                                    if (node != null) tab.selected = node
                                },
                            )
                        } ?: if (forensic.isDecodingFallback) {
                            DecodingIndicator("이미지 디코딩 중...")
                        } else {
                            Text("Primary Image Decoding Failed", color = AppColors.NeonRed, fontSize = 13.sp)
                        }

                        Text("PRIMARY IMAGE VIEW",
                            modifier = Modifier.align(Alignment.TopStart).padding(4.dp),
                            style = AppTypography.labelLarge.copy(fontSize = 10.sp, color = AppColors.NeonGreen)
                        )

                        forensic.bitmap?.let {
                            val orientationSuffix = forensic.orientation?.let { o -> " · $o" } ?: ""
                            PreviewCaption(
                                "${it.width}x${it.height}$orientationSuffix",
                                modifier = Modifier.align(Alignment.BottomStart).padding(4.dp),
                            )
                        }
                    }

                    // Right Panel: Motion Photo Video (only when the file has an embedded motion video)
                    val embeddedVideo = tab.embeddedVideo
                    if (embeddedVideo != null) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .border(0.5.dp, AppColors.Border)
                                .background(Color.Black),
                            contentAlignment = Alignment.Center
                        ) {
                            MotionPhotoVideoPreview(tab, embeddedVideo)

                            Text("MOTION PHOTO VIDEO",
                                modifier = Modifier.align(Alignment.TopStart).padding(4.dp),
                                style = AppTypography.labelLarge.copy(fontSize = 10.sp, color = AppColors.NeonPurple)
                            )
                        }
                    }
                }
                }
            }
        },
        rightPanel = {
            DetailedPropertiesPanel(appState, tab)
        },
        bottomPanel = bottomPanel
    )
}

@Composable
private fun MotionPhotoVideoPreview(tab: TabState, video: EmbeddedVideo) {
    var extractedFile by remember(tab.file, video) { mutableStateOf<File?>(null) }
    var extractError by remember(tab.file, video) { mutableStateOf<String?>(null) }

    LaunchedEffect(tab.file, video) {
        try {
            val temp = withContext(Dispatchers.IO) {
                val dest = File.createTempFile("motion-photo-preview-", ".${video.extension}")
                dest.deleteOnExit()
                extractEmbeddedVideo(tab.file, video, dest)
                dest
            }
            extractedFile = temp
        } catch (e: Exception) {
            println("MotionPhotoVideoPreview: extraction failed for ${tab.file.name}: $e")
            extractError = e.message ?: e.toString()
        }
    }

    DisposableEffect(tab.file, video) {
        onDispose { extractedFile?.delete() }
    }

    val file = extractedFile
    val error = extractError
    if (file != null) {
        FfmpegVideoPlayer(file, modifier = Modifier.fillMaxSize())
    } else if (error != null) {
        Text("Could not extract motion video: $error", color = AppColors.NeonRed, fontSize = 13.sp)
    } else {
        DecodingIndicator("모션포토 동영상 추출 중...")
    }
}

private enum class DetailPanelTab { OVERVIEW, DETAIL }

// Starts on Overview for every newly-opened file (remember(tab) resets it -- same per-file-reset
// convention used throughout this app, e.g. waveformSplit/selectedFrame), and auto-switches to
// Detail the first time the user actually selects something in the tree, so they don't have to
// manually click over after clicking a node/marker.
@Composable
fun DetailedPropertiesPanel(appState: AppState, tab: TabState) {
    var activeTab by remember(tab) { mutableStateOf(DetailPanelTab.OVERVIEW) }
    LaunchedEffect(tab.selected, tab.selectedFrame) {
        if (tab.selected != null || tab.selectedFrame != null) {
            activeTab = DetailPanelTab.DETAIL
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TabRow(
            selectedTabIndex = activeTab.ordinal,
            containerColor = AppColors.Panel,
            contentColor = AppColors.NeonBlue,
        ) {
            Tab(
                selected = activeTab == DetailPanelTab.OVERVIEW,
                onClick = { activeTab = DetailPanelTab.OVERVIEW },
                text = { Text("Overview", style = AppTypography.labelLarge) },
            )
            Tab(
                selected = activeTab == DetailPanelTab.DETAIL,
                onClick = { activeTab = DetailPanelTab.DETAIL },
                text = { Text("Detailed Properties", style = AppTypography.labelLarge) },
            )
        }

        when (activeTab) {
            DetailPanelTab.OVERVIEW -> OverviewTabContent(appState, tab)
            DetailPanelTab.DETAIL -> DetailPropertiesTabContent(tab)
        }
    }
}

// No thumbnail here -- the embedded EXIF thumbnail is already shown prominently in its own
// "EMBEDDED EXIF THUMBNAIL" preview panel above (see ImageInspectorUI's centerPanel), so
// repeating it here would just be the same bytes shown twice. Motion-photo codec details load
// automatically (no button) -- analyzeMotionPhotoCodecDetails already guards against redundant
// calls once loaded/in-flight, so this LaunchedEffect is safe to re-key on every recomposition
// where a motion-photo video section is present.
@Composable
private fun OverviewTabContent(appState: AppState, tab: TabState) {
    val summary = tab.mediaSummary ?: return
    val title = when (summary.category) {
        MediaCategory.IMAGE -> "📷 이미지 분석 요약"
        MediaCategory.VIDEO -> "🎬 동영상 분석 요약"
        MediaCategory.AUDIO -> "🎵 오디오 분석 요약"
    }
    val videoSections = summary.motionPhotoVideoSections
    if (videoSections != null) {
        LaunchedEffect(tab) { appState.analyzeMotionPhotoCodecDetails(tab) }
    }
    val listState = rememberLazyListState()
    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            item { SummaryBox(title, summary.sections) }
            if (videoSections != null) {
                item {
                    Spacer(Modifier.height(16.dp))
                    SummaryBox(
                        "🎬 동영상(모션포토) 분석 요약", videoSections,
                        titleTrailingContent = {
                            if (tab.isAnalyzingMotionPhotoCodec) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(12.dp),
                                        color = AppColors.NeonBlue,
                                        strokeWidth = 1.5.dp,
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text("코덱 분석 중...", color = AppColors.TextSecondary, fontSize = 12.sp)
                                }
                            }
                        },
                    )
                }
            }
            item { Spacer(Modifier.height(32.dp)) }
        }
        VerticalScrollbar(
            adapter = rememberScrollbarAdapter(listState),
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
        )
    }
}

@Composable
private fun DetailPropertiesTabContent(tab: TabState) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        // A single LazyColumn (with a visible scrollbar) for all three cases below, instead of a
        // plain non-scrolling Column for the frame case and two separately-scrollable LazyColumns
        // for the others -- this way every case scrolls the same way once content is longer than
        // the panel, and a visible thumb always shows there's more below (LazyColumn alone
        // scrolls fine with the wheel/trackpad, but gives no visual hint that it can).
        val listState = rememberLazyListState()
        val selectedFrame = tab.selectedFrame
        val selectedNode = tab.selected
        val root = tab.root
        // remember() needs a @Composable context, which the LazyColumn content lambda below is
        // NOT (it's a LazyListScope builder, only item {}/items {} inside it are composable) --
        // computed here instead, same as the original non-lazy version did.
        val warnings = if (selectedFrame == null && selectedNode == null && root != null) {
            remember(root) { collectWarnings(root) }
        } else {
            emptyList()
        }
        Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                when {
                    selectedFrame != null -> {
                        item {
                            PropertyRow("Frame #", selectedFrame.index.toString())
                            PropertyRow("Type", selectedFrame.type.toString())
                            PropertyRow("Size", "${selectedFrame.sizeBytes} bytes")
                            PropertyRow("PTS", "${selectedFrame.ptsSeconds}s")
                        }
                    }
                    selectedNode != null -> {
                        item {
                            PropertyRow("Type", selectedNode.type)
                            PropertyRow("Offset", "0x${selectedNode.offset.toString(16).uppercase()}")
                            PropertyRow("Size", "${selectedNode.size} bytes")
                            Spacer(Modifier.height(8.dp))
                        }
                        items(selectedNode.fields) { field ->
                            if (field.name == "xmp") {
                                XmpFieldDisplay(field.value)
                            } else {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(if (tab.selectedField == field) AppColors.Selection else Color.Transparent)
                                        .clickable { tab.selectedField = field },
                                ) {
                                    PropertyRow(field.name, field.value)
                                }
                            }
                        }
                        selectedNode.grid?.let { grid ->
                            item { GridDisplay(grid) }
                        }
                        selectedNode.table?.let { table ->
                            item { EmbeddedTableView(tab.file, table) }
                        }
                        if (selectedNode.type == "SOS") {
                            item { SosScanStatistics(tab, selectedNode) }
                        }
                        if (selectedNode.warnings.isNotEmpty()) {
                            item {
                                Spacer(Modifier.height(8.dp))
                                Text("Warnings:", style = AppTypography.labelLarge.copy(color = AppColors.NeonRed))
                                selectedNode.warnings.forEach { warning ->
                                    Text("- $warning", style = AppTypography.bodyLarge.copy(color = AppColors.NeonRed))
                                }
                            }
                        }
                    }
                    else -> {
                        if (warnings.isNotEmpty()) {
                            item {
                                Text(
                                    "⚠ ${warnings.size}개의 구조적 이상 징후",
                                    style = AppTypography.labelLarge.copy(color = AppColors.NeonRed),
                                )
                                Spacer(Modifier.height(8.dp))
                            }
                            items(warnings) { entry ->
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp)
                                        .clickable { tab.selected = entry.node },
                                ) {
                                    Column {
                                        Text(
                                            "${entry.node.type} — 0x${entry.node.offset.toString(16).uppercase()}",
                                            style = AppTypography.labelLarge.copy(color = AppColors.TextPrimary, fontSize = 12.sp),
                                        )
                                        Text(
                                            entry.warning,
                                            style = AppTypography.bodyLarge.copy(color = AppColors.NeonRed, fontSize = 12.sp),
                                        )
                                    }
                                }
                            }
                        } else {
                            item {
                                Text("✓ 구조적 이상 없음", style = AppTypography.bodyLarge.copy(color = AppColors.NeonGreen))
                            }
                        }
                    }
                }
            }
            VerticalScrollbar(
                adapter = rememberScrollbarAdapter(listState),
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
            )
        }
    }
}

@Composable
private fun SosScanStatistics(tab: TabState, selectedNode: BoxNode) {
    val forensic = tab.imageForensic
    val bitmap = forensic?.bitmap
    if (bitmap == null) {
        if (forensic?.isDecodingFallback == true) {
            Spacer(Modifier.height(8.dp))
            DecodingIndicator("이미지 디코딩 대기 중...")
        }
        return
    }

    var stats by remember(selectedNode, bitmap) { mutableStateOf<ScanStatistics?>(null) }
    LaunchedEffect(selectedNode, bitmap) {
        stats = withContext(Dispatchers.IO) { computeScanStatistics(bitmap.asSkiaBitmap()) }
    }

    Spacer(Modifier.height(8.dp))
    Text("Scan Statistics:", style = AppTypography.labelLarge.copy(color = AppColors.NeonBlue))
    val current = stats
    if (current == null) {
        DecodingIndicator("통계 계산 중...")
    } else {
        PropertyRow("Average Pixel Luminance (Y)", "%.1f (range: 0..255)".format(current.averageLuminance))
        PropertyRow(
            "Brightest Pixel",
            "RGB=[${current.brightestR}, ${current.brightestG}, ${current.brightestB}] @ (${current.brightestX}, ${current.brightestY})",
        )
    }
}
