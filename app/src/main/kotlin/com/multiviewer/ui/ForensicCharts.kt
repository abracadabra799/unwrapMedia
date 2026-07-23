package com.multiviewer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

@Composable
fun HistogramView(data: HistogramData, modifier: Modifier = Modifier) {
    Column(modifier = modifier.padding(8.dp)) {
        Text("Color Histogram", style = AppTypography.labelLarge)
        Spacer(Modifier.height(8.dp))
        Canvas(modifier = Modifier.fillMaxSize()) {
            val width = size.width
            val height = size.height
            val step = width / 256
            
            fun drawChannel(values: FloatArray, color: androidx.compose.ui.graphics.Color) {
                val path = Path()
                path.moveTo(0f, height)
                for (i in 0 until 256) {
                    val x = i * step
                    val y = height - (values[i] * height)
                    path.lineTo(x, y)
                }
                path.lineTo(width, height)
                drawPath(path, color, style = Stroke(width = 2f))
            }
            
            drawChannel(data.r, AppColors.NeonRed)
            drawChannel(data.g, AppColors.NeonGreen)
            drawChannel(data.b, AppColors.NeonBlue)
            drawChannel(data.y, AppColors.TextPrimary)
        }
    }
}

@Composable
fun QuantizationHeatmap(quality: Int, modifier: Modifier = Modifier) {
    Column(modifier = modifier.padding(8.dp)) {
        Text("Quantization Matrix (JPEG Quality: ~$quality%)", style = AppTypography.labelLarge)
        Spacer(Modifier.height(8.dp))
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val cellSize = minOf(maxWidth / 8, maxHeight / 8)
            Column {
                for (row in 0 until 8) {
                    Row {
                        for (col in 0 until 8) {
                            // Placeholder logic for heatmap color
                            val color = when {
                                quality > 90 -> AppColors.NeonGreen.copy(alpha = 0.5f)
                                quality > 70 -> AppColors.NeonYellow.copy(alpha = 0.5f)
                                else -> AppColors.NeonRed.copy(alpha = 0.5f)
                            }
                            Box(
                                modifier = Modifier
                                    .size(cellSize)
                                    .border(0.5.dp, AppColors.Border)
                                    .background(color)
                            )
                        }
                    }
                }
            }
        }
    }
}
