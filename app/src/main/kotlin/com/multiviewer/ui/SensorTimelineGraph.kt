package com.multiviewer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.multiviewer.parser.metadata.SensorSamplePoint
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

@Composable
fun SensorTimelineGraph(
    points: List<SensorSamplePoint>,
    selectedSampleIndex: Int?,
    onSelectSample: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (points.isEmpty()) return

    val xColor = Color(0xFF29B6F6) // Blue
    val yColor = Color(0xFF66BB6A) // Green
    val zColor = Color(0xFFFF7043) // Coral / Orange-Red
    val zeroLineColor = AppColors.TextMuted.copy(alpha = 0.5f)
    val gridColor = AppColors.TextMuted.copy(alpha = 0.2f)
    val selectedLineColor = Color(0xFFFFFFFF)

    val lastIndex = (points.size - 1).coerceAtLeast(1)

    // Calculate symmetrical Y range
    var maxMagnitude = 1.0
    for (p in points) {
        maxMagnitude = max(maxMagnitude, abs(p.x))
        maxMagnitude = max(maxMagnitude, abs(p.y))
        maxMagnitude = max(maxMagnitude, abs(p.z))
    }
    // Round up for neat grid ticks
    val maxVal = maxMagnitude * 1.15

    BoxWithConstraints(modifier = modifier) {
        val widthPx = constraints.maxWidth.toFloat()
        val heightPx = constraints.maxHeight.toFloat()
        val padLeft = 55f
        val padRight = 20f
        val padTop = 20f
        val padBottom = 25f

        val plotWidth = (widthPx - padLeft - padRight).coerceAtLeast(1f)
        val plotHeight = (heightPx - padTop - padBottom).coerceAtLeast(1f)

        fun xFor(index: Int): Float = padLeft + (index.toFloat() / lastIndex) * plotWidth

        fun yFor(v: Double): Float {
            val norm = (v + maxVal) / (2.0 * maxVal) // 0.0 at -maxVal, 1.0 at +maxVal
            return padTop + plotHeight * (1f - norm.toFloat()).coerceIn(0f, 1f)
        }

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(points) {
                    detectTapGestures { tap ->
                        val relX = (tap.x - padLeft) / plotWidth
                        val clickedIndex = (relX * lastIndex).roundToInt().coerceIn(0, points.size - 1)
                        onSelectSample(points[clickedIndex].sampleIndex)
                    }
                },
        ) {
            // Horizontal grid lines: +Max, +Half, 0, -Half, -Max
            val zeroY = yFor(0.0)
            val topY = yFor(maxVal)
            val botY = yFor(-maxVal)
            val halfTopY = yFor(maxVal / 2.0)
            val halfBotY = yFor(-maxVal / 2.0)

            for (gy in listOf(topY, halfTopY, halfBotY, botY)) {
                drawLine(gridColor, Offset(padLeft, gy), Offset(padLeft + plotWidth, gy), strokeWidth = 1f)
            }

            // Zero baseline
            drawLine(
                color = zeroLineColor,
                start = Offset(padLeft, zeroY),
                end = Offset(padLeft + plotWidth, zeroY),
                strokeWidth = 1.5f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f)),
            )

            // Draw X, Y, Z lines
            val step = max(1, points.size / 2000) // downsample if extreme points
            var i = 0
            while (i < points.size - 1) {
                val nextI = minOf(i + step, points.size - 1)
                val p1 = points[i]
                val p2 = points[nextI]

                val px1 = xFor(i)
                val px2 = xFor(nextI)

                drawLine(xColor, Offset(px1, yFor(p1.x)), Offset(px2, yFor(p2.x)), strokeWidth = 1.8f)
                drawLine(yColor, Offset(px1, yFor(p1.y)), Offset(px2, yFor(p2.y)), strokeWidth = 1.8f)
                drawLine(zColor, Offset(px1, yFor(p1.z)), Offset(px2, yFor(p2.z)), strokeWidth = 1.8f)

                i = nextI
            }

            // Draw Selected Guide
            selectedSampleIndex?.let { sel ->
                val selIdx = points.indexOfFirst { it.sampleIndex == sel }
                if (selIdx >= 0) {
                    val selP = points[selIdx]
                    val selX = xFor(selIdx)
                    drawLine(selectedLineColor.copy(alpha = 0.8f), Offset(selX, padTop), Offset(selX, padTop + plotHeight), strokeWidth = 1.5f)

                    drawCircle(xColor, radius = 4.5f, center = Offset(selX, yFor(selP.x)))
                    drawCircle(yColor, radius = 4.5f, center = Offset(selX, yFor(selP.y)))
                    drawCircle(zColor, radius = 4.5f, center = Offset(selX, yFor(selP.z)))
                }
            }
        }

        // Y-axis labels
        Text(String.format(Locale.US, "+%.1f", maxVal), color = AppColors.TextMuted, fontSize = 9.sp, modifier = Modifier.offset(x = 6.dp, y = (padTop - 6).dp))
        Text("0.0", color = AppColors.TextMuted, fontSize = 9.sp, modifier = Modifier.offset(x = 18.dp, y = (yFor(0.0) - 6).dp))
        Text(String.format(Locale.US, "-%.1f", maxVal), color = AppColors.TextMuted, fontSize = 9.sp, modifier = Modifier.offset(x = 8.dp, y = (padTop + plotHeight - 10).dp))
    }
}
