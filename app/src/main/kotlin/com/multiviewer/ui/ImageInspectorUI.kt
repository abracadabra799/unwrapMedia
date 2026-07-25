package com.multiviewer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
                        } ?: Text("No Embedded Thumbnail", color = Color.Gray, fontSize = 12.sp)

                        Text("EMBEDDED EXIF THUMBNAIL",
                            modifier = Modifier.align(Alignment.TopStart).padding(4.dp),
                            style = AppTypography.labelLarge.copy(fontSize = 9.sp, color = AppColors.NeonBlue)
                        )

                        forensic.embeddedThumbnail?.let {
                            Text("${it.width}x${it.height}",
                                modifier = Modifier.align(Alignment.BottomStart).padding(4.dp),
                                style = AppTypography.labelLarge.copy(fontSize = 9.sp, color = AppColors.TextSecondary)
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
                        } ?: Text(
                            if (forensic.isDecodingFallback) "Decoding via ffmpeg..." else "Primary Image Decoding Failed",
                            color = if (forensic.isDecodingFallback) AppColors.TextSecondary else AppColors.NeonRed,
                            fontSize = 12.sp,
                        )

                        Text("PRIMARY IMAGE VIEW",
                            modifier = Modifier.align(Alignment.TopStart).padding(4.dp),
                            style = AppTypography.labelLarge.copy(fontSize = 9.sp, color = AppColors.NeonGreen)
                        )

                        forensic.bitmap?.let {
                            Text("${it.width}x${it.height}",
                                modifier = Modifier.align(Alignment.BottomStart).padding(4.dp),
                                style = AppTypography.labelLarge.copy(fontSize = 9.sp, color = AppColors.TextSecondary)
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
                                style = AppTypography.labelLarge.copy(fontSize = 9.sp, color = AppColors.NeonPurple)
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
                LazyColumn(
                    modifier = Modifier
                        .weight(1f - verticalSplit)
                        .fillMaxWidth()
                ) {
                    item {
                        if (summary != null) {
                            SummaryBox("📷 이미지", summary.sections)
                        }
                    }
                    item {
                        val videoSections = summary?.motionPhotoVideoSections
                        if (videoSections != null) {
                            Spacer(Modifier.height(16.dp))
                            if (tab.isAnalyzingMotionPhotoCodec) {
                                Text("분석 중...", color = AppColors.TextSecondary, fontSize = 12.sp)
                            } else if (!tab.motionPhotoCodecDetailsLoaded) {
                                Button(onClick = { appState.analyzeMotionPhotoCodecDetails(tab) }) {
                                    Text("코덱 상세정보 분석")
                                }
                            }
                            SummaryBox("🎬 동영상 (모션포토)", videoSections)
                        }
                    }
                    item { Spacer(Modifier.height(32.dp)) }
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
        Text("Could not extract motion video: $error", color = AppColors.NeonRed, fontSize = 12.sp)
    } else {
        Text("Extracting motion video...", color = Color.Gray, fontSize = 12.sp)
    }
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
                    PropertyRow(field.name, field.value)
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
            Text("Select a marker to view details", style = AppTypography.bodyLarge.copy(color = AppColors.TextSecondary))
        }
    }
}
