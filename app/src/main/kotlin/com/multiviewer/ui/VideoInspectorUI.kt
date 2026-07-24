package com.multiviewer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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
                // Top: Full-width Live Player
                Box(
                    modifier = Modifier
                        .weight(verticalSplit)
                        .fillMaxWidth()
                        .background(Color.Black),
                    contentAlignment = Alignment.Center
                ) {
                    VlcVideoPlayer(tab.file)
                    
                    Text("LIVE PLAYER", 
                        modifier = Modifier.align(Alignment.TopStart).padding(4.dp), 
                        style = AppTypography.labelLarge.copy(fontSize = 9.sp, color = AppColors.NeonGreen)
                    )
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
                            SummaryBox("🎬 비디오 분석 요약", summary.sections)
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
