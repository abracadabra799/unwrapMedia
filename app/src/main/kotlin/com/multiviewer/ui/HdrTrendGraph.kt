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
import androidx.compose.ui.unit.sp
import com.multiviewer.parser.FrameHdrRecord
import java.util.Locale
import kotlin.math.roundToInt

private const val HDR_GRAPH_Y_TICKS = 4

// Modelled on MetricGraph.kt (discrete drawLine calls, BoxWithConstraints for axis labels), but with
// three HDR10+ series, dashed horizontal reference lines (MDCV max, MaxCLL, MaxFALL), vertical
// markers for anomaly frames and the selected frame, and click-to-select. Series break (no line) on
// frames without HDR10+, so missing ranges are visible as gaps.
@Composable
fun HdrTrendGraph(
    records: List<FrameHdrRecord>,
    referenceLines: List<Pair<String, Double>>,
    markedFrames: Set<Int>,
    selectedFrame: Int?,
    onSelectFrame: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (records.isEmpty()) return
    val maxSclColor = AppColors.NeonOrange
    val averageColor = AppColors.NeonGreen
    val targetColor = AppColors.NeonPurple
    val referenceColor = AppColors.NeonYellow
    val markerColor = AppColors.NeonRed
    val selectedColor = AppColors.NeonBlue
    val gridColor = AppColors.TextMuted.copy(alpha = 0.25f)
    val labelColor = AppColors.TextSecondary

    val series: List<Pair<Color, (FrameHdrRecord) -> Double?>> = listOf(
        maxSclColor to { r -> r.hdr10Plus?.windows?.firstOrNull()?.maxSclNits },
        averageColor to { r -> r.hdr10Plus?.windows?.firstOrNull()?.averageMaxrgbNits },
        targetColor to { r -> r.hdr10Plus?.targetedSystemDisplayMaximumLuminance?.toDouble() },
    )
    val values = records.flatMap { r -> series.mapNotNull { (_, value) -> value(r) } } + referenceLines.map { it.second }
    val maxValue = (values.maxOrNull() ?: 1.0).coerceAtLeast(1.0) * 1.05
    val lastIndex = (records.size - 1).coerceAtLeast(1)

    BoxWithConstraints(modifier = modifier) {
        Canvas(
            modifier = Modifier.fillMaxSize().pointerInput(records.size) {
                detectTapGestures { tap ->
                    onSelectFrame(((tap.x / size.width) * lastIndex).roundToInt().coerceIn(0, records.size - 1))
                }
            },
        ) {
            fun x(i: Int) = size.width * i / lastIndex
            fun y(v: Double) = (size.height - size.height * (v / maxValue)).toFloat()
            for (tick in 0..HDR_GRAPH_Y_TICKS) {
                val ty = size.height - size.height * tick / HDR_GRAPH_Y_TICKS
                drawLine(gridColor, Offset(0f, ty), Offset(size.width, ty), strokeWidth = 1f)
            }
            markedFrames.forEach { f -> drawLine(markerColor.copy(alpha = 0.5f), Offset(x(f), 0f), Offset(x(f), size.height), strokeWidth = 2f) }
            selectedFrame?.let { f -> drawLine(selectedColor, Offset(x(f), 0f), Offset(x(f), size.height), strokeWidth = 2f) }
            referenceLines.forEach { (_, v) ->
                drawLine(
                    referenceColor, Offset(0f, y(v)), Offset(size.width, y(v)), strokeWidth = 1.5f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f)),
                )
            }
            series.forEach { (color, value) ->
                for (i in 0 until records.size - 1) {
                    val a = value(records[i]) ?: continue
                    val b = value(records[i + 1]) ?: continue
                    drawLine(color, Offset(x(i), y(a)), Offset(x(i + 1), y(b)), strokeWidth = 2f)
                }
            }
        }
        for (tick in 0..HDR_GRAPH_Y_TICKS) {
            val value = maxValue * tick / HDR_GRAPH_Y_TICKS
            Text(
                String.format(Locale.US, "%.0f", value),
                color = labelColor,
                fontSize = 10.sp,
                modifier = Modifier.offset(y = maxHeight * (1f - tick.toFloat() / HDR_GRAPH_Y_TICKS)),
            )
        }
        referenceLines.forEach { (label, v) ->
            Text(
                String.format(Locale.US, "%s %.0f", label, v),
                color = referenceColor,
                fontSize = 10.sp,
                modifier = Modifier.offset(x = maxWidth * 0.8f, y = maxHeight * (1f - (v / maxValue).toFloat())),
            )
        }
    }
}
