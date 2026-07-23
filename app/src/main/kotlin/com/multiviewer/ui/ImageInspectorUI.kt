package com.multiviewer.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp

@Composable
fun ImageInspectorUI(
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
                // Top: Visual Preview (Resizable)
                Box(
                    modifier = Modifier
                        .weight(verticalSplit)
                        .fillMaxWidth()
                ) {
                    forensic.bitmap?.let { PixelInspectorPreview(it) }
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
                            SummaryBox("🎬 동영상 (모션포토)", videoSections)
                        }
                    }
                    item {
                        Spacer(Modifier.height(32.dp))
                    }
                }
            }
        },
        rightPanel = {
            Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                PanelHeader("Detailed Properties")
                Spacer(Modifier.height(16.dp))
                
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
                            item {
                                GridDisplay(grid)
                            }
                        }

                        // Embedded Table Data
                        selectedNode.table?.let { table ->
                            item {
                                EmbeddedTableView(tab.file, table)
                            }
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
        },
        bottomPanel = bottomPanel
    )
}
