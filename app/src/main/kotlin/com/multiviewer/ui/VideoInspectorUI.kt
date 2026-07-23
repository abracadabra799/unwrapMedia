package com.multiviewer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun VideoInspectorUI(
    tab: TabState,
    leftPanel: @Composable ColumnScope.() -> Unit,
    bottomPanel: @Composable ColumnScope.() -> Unit
) {
    val analysis = tab.videoAnalysis ?: VideoAnalysisData()
    val summary = tab.mediaSummary

    DashboardLayout(
        leftPanel = leftPanel,
        centerPanel = {
            Column(modifier = Modifier.fillMaxSize()) {
                // Top: Visual Preview Placeholder (Fixed Height)
                Box(
                    modifier = Modifier.height(350.dp).fillMaxWidth().background(Color.Black),
                    contentAlignment = Alignment.Center
                ) {
                    Text("VIDEO PREVIEW", style = AppTypography.headlineSmall)
                }
                
                // Bottom: Scrollable Analysis Dashboard
                LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    // Item 1: Visualizers (Bitrate & Treemap)
                    item {
                        Row(modifier = Modifier.fillMaxWidth().height(250.dp)) {
                            BitrateVisualizer(analysis.bitratePoints, modifier = Modifier.weight(1.5f))
                            BoxBlockView(analysis.boxWeights, modifier = Modifier.weight(1f))
                        }
                    }

                    // Item 2: Media Summary Dashboard
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
                // Keep Camera LCD for "Modern" feel if it's a top-level summary
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

                        // Display Grid Data (if any)
                        selectedNode.grid?.let { grid ->
                            item {
                                GridDisplay(grid)
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
                    // Show some default video summary if nothing selected
                    PropertyRow("Resolution", "3840 x 2160 (4K)")
                    PropertyRow("Codec", "ProRes 422 HQ")
                    PropertyRow("Frame Rate", "23.976 fps")
                }
            }
        },
        bottomPanel = bottomPanel
    )
}
