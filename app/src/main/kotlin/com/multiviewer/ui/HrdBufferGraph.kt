package com.multiviewer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.multiviewer.parser.hrd.HrdBufferPoint
import kotlin.math.roundToInt

@Composable
fun HrdBufferGraph(
    points: List<HrdBufferPoint>,
    selectedFrame: Int?,
    onSelectFrame: (Int) -> Unit,
    language: AppLanguage = AppLanguage.KO,
    modifier: Modifier = Modifier,
) {
    if (points.isEmpty()) return

    val curveColor = Color(0xFF29B6F6) // Light Blue
    val underflowColor = AppColors.NeonRed
    val warningLineColor = Color(0xFFFFB74D) // Amber / Orange
    val keyframeMarkerColor = AppColors.NeonYellow
    val gridColor = AppColors.TextMuted.copy(alpha = 0.25f)
    val selectedColor = Color(0xFFFFFFFF)

    val lastIndex = (points.size - 1).coerceAtLeast(1)

    BoxWithConstraints(modifier = modifier) {
        val widthPx = constraints.maxWidth.toFloat()
        val heightPx = constraints.maxHeight.toFloat()
        val padLeft = 45f
        val padRight = 20f
        val padTop = 20f
        val padBottom = 25f

        val plotWidth = (widthPx - padLeft - padRight).coerceAtLeast(1f)
        val plotHeight = (heightPx - padTop - padBottom).coerceAtLeast(1f)

        fun xFor(index: Int): Float = padLeft + (index.toFloat() / lastIndex) * plotWidth

        // Y axis: 0% at bottom, 100% at top. Can go negative for underflow
        val minY = points.minOfOrNull { it.occupancyPercent }?.coerceAtMost(0.0) ?: 0.0
        val maxY = 100.0
        val ySpan = (maxY - minY).coerceAtLeast(10.0)

        fun yFor(occupancy: Double): Float {
            val norm = (occupancy - minY) / ySpan
            return padTop + plotHeight * (1f - norm.toFloat()).coerceIn(0f, 1f)
        }

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(points) {
                    detectTapGestures { tap ->
                        val relX = (tap.x - padLeft) / plotWidth
                        val clickedIndex = (relX * lastIndex).roundToInt().coerceIn(0, points.size - 1)
                        onSelectFrame(points[clickedIndex].frameIndex)
                    }
                },
        ) {
            // Horizontal grid lines: 0%, 25%, 50%, 75%, 100%
            val ticks = listOf(0.0, 25.0, 50.0, 75.0, 100.0)
            for (tick in ticks) {
                val y = yFor(tick)
                drawLine(gridColor, Offset(padLeft, y), Offset(padLeft + plotWidth, y), strokeWidth = 1f)
            }

            // 15% Warning threshold dashed line
            val warnY = yFor(15.0)
            drawLine(
                color = warningLineColor.copy(alpha = 0.7f),
                start = Offset(padLeft, warnY),
                end = Offset(padLeft + plotWidth, warnY),
                strokeWidth = 1.5f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 4f)),
            )

            // Buffer occupancy curve
            for (i in 0 until points.size - 1) {
                val p1 = points[i]
                val p2 = points[i + 1]
                val x1 = xFor(i)
                val y1 = yFor(p1.occupancyPercent)
                val x2 = xFor(i + 1)
                val y2 = yFor(p2.occupancyPercent)

                val segmentColor = if (p1.isUnderflow || p2.isUnderflow) underflowColor else curveColor
                drawLine(segmentColor, Offset(x1, y1), Offset(x2, y2), strokeWidth = 2f)
            }

            // Draw keyframe and underflow dots
            for (i in points.indices) {
                val p = points[i]
                val x = xFor(i)
                val y = yFor(p.occupancyPercent)

                if (p.isUnderflow) {
                    drawCircle(underflowColor, radius = 4f, center = Offset(x, y))
                } else if (p.isKeyframe) {
                    drawCircle(keyframeMarkerColor, radius = 2.5f, center = Offset(x, y))
                }
            }

            // Selected frame vertical guide line
            selectedFrame?.let { sel ->
                val selIdx = points.indexOfFirst { it.frameIndex == sel }
                if (selIdx >= 0) {
                    val selX = xFor(selIdx)
                    drawLine(selectedColor.copy(alpha = 0.8f), Offset(selX, padTop), Offset(selX, padTop + plotHeight), strokeWidth = 1.5f)
                    val selY = yFor(points[selIdx].occupancyPercent)
                    drawCircle(selectedColor, radius = 5f, center = Offset(selX, selY))
                }
            }
        }

        // Y-axis tick labels
        Text("100%", color = AppColors.TextMuted, fontSize = 9.sp, modifier = Modifier.offset(x = 6.dp, y = (padTop - 6).dp))
        Text("50%", color = AppColors.TextMuted, fontSize = 9.sp, modifier = Modifier.offset(x = 12.dp, y = (yFor(50.0) - 6).dp))
        Text("0%", color = AppColors.TextMuted, fontSize = 9.sp, modifier = Modifier.offset(x = 18.dp, y = (yFor(0.0) - 6).dp))
        val warnLabel = if (language == AppLanguage.KO) "15% 임계선" else "15% Warning"
        Text(warnLabel, color = warningLineColor, fontSize = 9.sp, modifier = Modifier.offset(x = (padLeft + 4).dp, y = (yFor(15.0) - 14).dp))
    }
}
