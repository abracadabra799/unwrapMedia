package com.multiviewer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

@Composable
fun BitrateVisualizer(points: List<BitratePoint>, modifier: Modifier = Modifier) {
    Column(modifier = modifier.padding(8.dp)) {
        Text("Bitrate Analysis (VBR)", style = AppTypography.labelLarge)
        Spacer(Modifier.height(8.dp))
        
        if (points.isEmpty()) {
            Box(Modifier.fillMaxSize()) {
                Text("No bitrate data available", modifier = Modifier.matchParentSize())
            }
        } else {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val width = size.width
                val height = size.height
                val maxKbps = points.maxOf { it.kbps }.coerceAtLeast(1.0)
                val maxTime = points.last().timestampSeconds.coerceAtLeast(1.0)
                
                val path = Path()
                path.moveTo(0f, height)
                
                points.forEach { p ->
                    val x = (p.timestampSeconds / maxTime).toFloat() * width
                    val y = height - (p.kbps / maxKbps).toFloat() * height
                    path.lineTo(x, y)
                }
                
                drawPath(path, AppColors.NeonGreen, style = Stroke(width = 2f))
            }
        }
    }
}
