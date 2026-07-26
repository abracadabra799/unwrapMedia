package com.multiviewer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp

@Composable
fun DashboardLayout(
    leftPanel: @Composable ColumnScope.() -> Unit,
    centerPanel: @Composable ColumnScope.() -> Unit,
    rightPanel: @Composable ColumnScope.() -> Unit,
    bottomPanel: @Composable ColumnScope.() -> Unit
) {
    var containerHeightPx by remember { mutableStateOf(0) }
    // 0.75f roughly matches the old fixed 250dp bottom panel on a typical window size, but is now
    // a user-draggable ratio instead of a fixed pixel height.
    var verticalSplit by remember { mutableStateOf(0.75f) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AppColors.Background)
            .onGloballyPositioned { containerHeightPx = it.size.height },
    ) {
        Row(modifier = Modifier.weight(verticalSplit).fillMaxWidth()) {
            // Left Panel (Structure)
            Column(
                modifier = Modifier
                    .width(300.dp)
                    .fillMaxHeight()
                    .border(1.dp, AppColors.Border)
                    .background(AppColors.Surface)
            ) {
                leftPanel()
            }
            
            // Center Panel (Visual Canvas)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .border(1.dp, AppColors.Border)
            ) {
                centerPanel()
            }
            
            // Right Panel (Properties)
            Column(
                modifier = Modifier
                    .width(350.dp)
                    .fillMaxHeight()
                    .border(1.dp, AppColors.Border)
                    .background(AppColors.Surface)
            ) {
                rightPanel()
            }
        }
        
        DraggableDivider(
            orientation = Orientation.Horizontal,
            containerSizePx = containerHeightPx,
            getSplit = { verticalSplit },
            setSplit = { verticalSplit = it },
        )

        // Bottom Panel (Hex)
        Column(
            modifier = Modifier
                .weight(1f - verticalSplit)
                .fillMaxWidth()
                .border(1.dp, AppColors.Border)
                .background(AppColors.Panel)
        ) {
            bottomPanel()
        }
    }
}

@Composable
fun PanelHeader(title: String, color: androidx.compose.ui.graphics.Color = AppColors.TextPrimary) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(AppColors.Panel)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(
            text = title.uppercase(),
            style = AppTypography.labelLarge.copy(color = color)
        )
    }
}
