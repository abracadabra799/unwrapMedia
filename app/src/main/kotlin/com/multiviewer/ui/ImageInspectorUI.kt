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
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.multiviewer.parser.BoxNode
import com.multiviewer.parser.EmbeddedVideo
import com.multiviewer.parser.extractEmbeddedVideo
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
    val summary = tab.mediaSummary
    var containerHeightPx by remember { mutableStateOf(0) }
    var verticalSplit by remember { mutableStateOf(0.5f) }
    
    DashboardLayout(
        leftPanel = leftPanel,
        centerPanel = {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .onGloballyPositioned { containerHeightPx = it.size.height }
            ) {
                tab.largeResolutionWarning?.let { warning ->
                    ResolutionWarningBanner(warning, onDismiss = { tab.largeResolutionWarning = null })
                }
                // Top: Dual Preview (50/50 Split)
                Row(
                    modifier = Modifier
                        .weight(verticalSplit)
                        .fillMaxWidth()
                ) {
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
                            PixelInspectorPreview(it)
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
                
                // Resizable Divider
                DraggableDivider(
                    orientation = Orientation.Horizontal,
                    containerSizePx = containerHeightPx,
                    getSplit = { verticalSplit },
                    setSplit = { verticalSplit = it }
                )
                
                // Bottom: Scrollable Analysis Dashboard
                val summaryScrollState = rememberLazyListState()
                Box(
                    modifier = Modifier
                        .weight(1f - verticalSplit)
                        .fillMaxWidth()
                ) {
                LazyColumn(
                    state = summaryScrollState,
                    modifier = Modifier.fillMaxSize()
                ) {
                    item {
                        if (summary != null) {
                            SummaryBox("📷 이미지 분석 요약", summary.sections)
                        }
                    }
                    item {
                        val videoSections = summary?.motionPhotoVideoSections
                        if (videoSections != null) {
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
                                    } else if (!tab.motionPhotoCodecDetailsLoaded) {
                                        OutlinedButton(onClick = { appState.analyzeMotionPhotoCodecDetails(tab) }) {
                                            Text("코덱 상세정보 보기 ▶", fontSize = 12.sp)
                                        }
                                    }
                                },
                            )
                        }
                    }
                    item { Spacer(Modifier.height(32.dp)) }
                }
                VerticalScrollbar(
                    adapter = rememberScrollbarAdapter(summaryScrollState),
                    modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                )
                }
            }
        },
        rightPanel = {
            DetailedPropertiesPanel(tab)
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

data class WarningEntry(val node: BoxNode, val warning: String)

fun collectWarnings(root: BoxNode): List<WarningEntry> {
    val entries = mutableListOf<WarningEntry>()
    fun walk(node: BoxNode) {
        node.warnings.forEach { entries.add(WarningEntry(node, it)) }
        node.children.forEach { walk(it) }
    }
    walk(root)
    return entries.sortedBy { it.node.offset }
}

@Composable
fun DetailedPropertiesPanel(tab: TabState) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        PanelHeader("Detailed Properties")
        Spacer(Modifier.height(16.dp))

        val selectedFrame = tab.selectedFrame
        if (selectedFrame != null) {
            PropertyRow("Frame #", selectedFrame.index.toString())
            PropertyRow("Type", selectedFrame.type.toString())
            PropertyRow("Size", "${selectedFrame.sizeBytes} bytes")
            PropertyRow("PTS", "${selectedFrame.ptsSeconds}s")
            return@Column
        }

        val selectedNode = tab.selected
        if (selectedNode != null) {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
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
                        PropertyRow(field.name, field.value)
                    }
                }
                selectedNode.grid?.let { grid ->
                    item { GridDisplay(grid) }
                }
                selectedNode.table?.let { table ->
                    item { EmbeddedTableView(tab.file, table) }
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
        } else {
            val root = tab.root
            val warnings = if (root != null) remember(root) { collectWarnings(root) } else emptyList()
            if (warnings.isNotEmpty()) {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
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
                }
            } else {
                Text("✓ 구조적 이상 없음", style = AppTypography.bodyLarge.copy(color = AppColors.NeonGreen))
            }
        }
    }
}
