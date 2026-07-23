package com.multiviewer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp

@Composable
fun VideoInspectorUI(
    tab: TabState,
    leftPanel: @Composable ColumnScope.() -> Unit,
    bottomPanel: @Composable ColumnScope.() -> Unit
) {
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
                        .background(Color.Black),
                    contentAlignment = Alignment.Center
                ) {
                    VlcVideoPlayer(tab.file)
                }
                
                // Resizable Divider
                DraggableDivider(
                    orientation = Orientation.Horizontal,
                    containerSizePx = containerHeightPx,
                    getSplit = { verticalSplit },
                    setSplit = { verticalSplit = it }
                )

                // Bottom: Scrollable Analysis Dashboard (Resizable)
                LazyColumn(
                    modifier = Modifier
                        .weight(1f - verticalSplit)
                        .fillMaxWidth()
                ) {
                    item {
                        if (summary != null) {
                            SummaryBox("🎬 비디오 분석 요약", summary.sections)
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
                CameraLCDPanel(
                    make = "SONY",
                    model = "A7S III",
                    lens = "FE 24-70mm f/2.8 GM",
                    settings = "1/500 | f/5.6 | 400"
                )
                
                Spacer(Modifier.height(24.dp))
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
                        
                        // Embedded Table Data (Detailed offsets, sizes, etc.)
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
                    PropertyRow("Resolution", "3840 x 2160 (4K)")
                    PropertyRow("Codec", "ProRes 422 HQ")
                    PropertyRow("Frame Rate", "23.976 fps")
                }
            }
        },
        bottomPanel = bottomPanel
    )
}
