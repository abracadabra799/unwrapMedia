package com.multiviewer.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun ImageInspectorUI(
    tab: TabState,
    leftPanel: @Composable ColumnScope.() -> Unit,
    bottomPanel: @Composable ColumnScope.() -> Unit
) {
    val forensic = tab.imageForensic ?: return
    val summary = tab.mediaSummary
    
    DashboardLayout(
        leftPanel = leftPanel,
        centerPanel = {
            Column(modifier = Modifier.fillMaxSize()) {
                // Top: Visual Preview (Main Focal Point)
                Box(modifier = Modifier.height(350.dp).fillMaxWidth()) {
                    forensic.bitmap?.let { PixelInspectorPreview(it) }
                }
                
                // Bottom: Scrollable Analysis Dashboard (Media Summary focused)
                LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    // Item 1: Image Summary Box (General, Image, Camera Info cards)
                    item {
                        if (summary != null) {
                            SummaryBox("📷 이미지", summary.sections)
                        }
                    }
                    
                    // Item 2: Motion Photo Video Summary Box
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
                        
                        // New: Display Grid Data (e.g. Quantization Tables)
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
                    Text("Select a marker to view details", style = AppTypography.bodyLarge.copy(color = AppColors.TextSecondary))
                }
            }
        },
        bottomPanel = bottomPanel
    )
}
