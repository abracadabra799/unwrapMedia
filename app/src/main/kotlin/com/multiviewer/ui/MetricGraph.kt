package com.multiviewer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp

private const val GRAPH_Y_TICK_COUNT = 4

// Renders `perFrame` (already-computed metric samples, see QualityMetrics.kt) as a connected line
// graph, frame index on X, metric value on Y. Modeled on FrameIntervalAnalysisView.kt's Canvas
// approach (gridlines + BoxWithConstraints for axis-label positioning outside the Canvas) -- this
// codebase has no charting library and no precedent for Compose's Path API, so every graph in this
// app (including this one) is built from discrete drawLine calls. Unlike FrameIntervalAnalysisView's
// unconnected scatter points, this connects consecutive samples (a continuous quality curve reads
// better than discrete points for a per-frame quality trend).
@Composable
fun MetricGraph(perFrame: List<MetricFrameSample>, lineColor: Color, modifier: Modifier = Modifier) {
    if (perFrame.isEmpty()) return
    val minValue = perFrame.minOf { it.value }
    val maxValue = perFrame.maxOf { it.value }
    val valueSpan = (maxValue - minValue).takeIf { it > 0.0 } ?: 1.0
    val minFrame = perFrame.first().frameIndex
    val maxFrame = perFrame.last().frameIndex
    val frameSpan = (maxFrame - minFrame).takeIf { it > 0 } ?: 1

    fun yFraction(value: Double): Float = ((value - minValue) / valueSpan).toFloat()
    fun xFraction(frameIndex: Int): Float = (frameIndex - minFrame).toFloat() / frameSpan

    BoxWithConstraints(modifier = modifier) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            for (tick in 0..GRAPH_Y_TICK_COUNT) {
                val tickY = size.height - size.height * (tick.toFloat() / GRAPH_Y_TICK_COUNT)
                drawLine(
                    color = Color.White.copy(alpha = 0.12f),
                    start = Offset(0f, tickY), end = Offset(size.width, tickY), strokeWidth = 1f,
                )
            }
            for (i in 0 until perFrame.size - 1) {
                val x1 = size.width * xFraction(perFrame[i].frameIndex)
                val y1 = size.height - size.height * yFraction(perFrame[i].value)
                val x2 = size.width * xFraction(perFrame[i + 1].frameIndex)
                val y2 = size.height - size.height * yFraction(perFrame[i + 1].value)
                drawLine(color = lineColor, start = Offset(x1, y1), end = Offset(x2, y2), strokeWidth = 2f)
            }
        }
        for (tick in 0..GRAPH_Y_TICK_COUNT) {
            val value = minValue + valueSpan * (tick.toDouble() / GRAPH_Y_TICK_COUNT)
            val fractionFromTop = 1f - (tick.toFloat() / GRAPH_Y_TICK_COUNT)
            Text(
                text = "%.2f".format(value),
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 10.sp,
                modifier = Modifier.offset(y = maxHeight * fractionFromTop),
            )
        }
    }
}
