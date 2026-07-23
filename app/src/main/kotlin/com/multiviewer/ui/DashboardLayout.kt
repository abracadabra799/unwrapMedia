package com.multiviewer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun DashboardLayout(
    leftPanel: @Composable ColumnScope.() -> Unit,
    centerPanel: @Composable ColumnScope.() -> Unit,
    rightPanel: @Composable ColumnScope.() -> Unit,
    bottomPanel: @Composable ColumnScope.() -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().background(AppColors.Background)) {
        Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
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
        
        // Bottom Panel (Hex)
        Column(
            modifier = Modifier
                .height(250.dp)
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
