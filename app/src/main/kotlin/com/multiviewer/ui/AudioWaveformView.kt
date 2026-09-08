package com.multiviewer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import kotlin.math.ceil

// MM:SS.mmm, minutes zero-padded to 2 (more digits past 100 min). Negative -> 00:00.000.
fun formatMinSecMillis(seconds: Double): String {
    val totalMs = (seconds * 1000).toLong().coerceAtLeast(0L)
    return "%02d:%02d.%03d".format(totalMs / 60_000, (totalMs % 60_000) / 1000, totalMs % 1000)
}

// Time between waveform axis ticks: the smallest "nice" value (1-2-5 x 10^n, plus 0.1/0.2/0.5)
// that yields <= ~10 ticks across the visible span.
private val NICE_STEPS = doubleArrayOf(
    0.05, 0.1, 0.2, 0.5, 1.0, 2.0, 5.0, 10.0, 20.0, 30.0, 60.0, 120.0, 300.0, 600.0, 1800.0, 3600.0,
)

fun niceTimeStep(spanSeconds: Double): Double {
    val target = spanSeconds / 8.0 // aim for ~8 ticks
    return NICE_STEPS.firstOrNull { it >= target } ?: NICE_STEPS.last()
}

// ---------------------------------------------------------------------------------------------
// AudioWaveformView -- GoldWave/Audacity style waveform: L/R lanes, playhead, click-to-seek,
// wheel-to-zoom, and a nice-stepped time axis. Purely presentational: all window/zoom math is
// owned by the caller (AudioZoomPan.kt) and reached through onSeekTo / onZoom.
// ---------------------------------------------------------------------------------------------

private val WAVE_COLOR = Color(0xFF39FF14)
private val PLAYHEAD_COLOR = Color.White
private val AXIS_COLOR = Color.White.copy(alpha = 0.35f)
private val ZERO_LINE_COLOR = Color.White.copy(alpha = 0.16f)

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun AudioWaveformView(
    peaks: WaveformPeaks?,
    view: AudioViewWindow,
    totalDurationSeconds: Double,
    cursorSeconds: Double,
    onSeekTo: (seconds: Double) -> Unit,
    onZoom: (scrollDeltaY: Float, anchorSeconds: Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    // The pointerInput block re-keys only on totalDurationSeconds, and onPointerEvent's lambda is
    // captured once, so both gesture paths must read the window through these live handles rather
    // than the `view` value captured at first composition.
    val liveView by rememberUpdatedState(view)
    val liveTotal by rememberUpdatedState(totalDurationSeconds)

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .onPointerEvent(PointerEventType.Scroll, pass = PointerEventPass.Initial) { event ->
                val change = event.changes.firstOrNull() ?: return@onPointerEvent
                val w = size.width.toFloat()
                if (w > 0f) {
                    val v = liveView
                    val anchor = v.startSeconds + (change.position.x / w) * v.durationSeconds
                    onZoom(change.scrollDelta.y, anchor)
                }
                event.changes.forEach { it.consume() }
            }
            .pointerInput(totalDurationSeconds) {
                awaitEachGesture {
                    // Down-only: seek where the press landed, consume it, ignore any drag.
                    val down = awaitFirstDown()
                    val w = size.width.toFloat()
                    if (w > 0f) {
                        val v = liveView
                        onSeekTo(v.startSeconds + (down.position.x / w) * v.durationSeconds)
                    }
                    down.consume()
                }
            },
    ) {
        if (peaks == null) {
            DecodingIndicator("파형 생성 중...", modifier = Modifier.align(Alignment.Center))
            return@BoxWithConstraints
        }

        val safeTotal = if (liveTotal > 0.0) liveTotal else totalDurationSeconds
        val channels = peaks.channels.take(2)
        val visibleRange = visibleBucketRange(view, safeTotal, peaks.bucketCount)
        val ticks = remember(view.startSeconds, view.durationSeconds) { waveformTicks(view) }
        val cursorFrac = if (view.durationSeconds > 0.0) {
            ((cursorSeconds - view.startSeconds) / view.durationSeconds).toFloat()
        } else {
            Float.NaN
        }
        val playheadFrac = if (cursorFrac in 0f..1f) cursorFrac else null

        Column(Modifier.fillMaxSize()) {
            channels.forEachIndexed { index, channel ->
                val label = if (channels.size == 2) (if (index == 0) "L" else "R") else null
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    Canvas(Modifier.fillMaxSize()) {
                        drawLaneWaveform(channel, visibleRange)
                        drawAxisTicks(ticks)
                        playheadFrac?.let { drawPlayhead(it) }
                    }
                    if (label != null) {
                        Text(
                            text = label,
                            color = WAVE_COLOR.copy(alpha = 0.7f),
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.align(Alignment.TopStart).padding(2.dp),
                        )
                    }
                }
            }
        }

        // Time labels: a non-interactive layer positioned along the bottom edge. No pointerInput,
        // so clicks and wheel events still reach the waveform underneath.
        for ((frac, text) in ticks) {
            Text(
                text = text,
                color = AXIS_COLOR,
                fontSize = 8.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .offset(x = maxWidth * frac.coerceIn(0f, 1f))
                    .padding(bottom = 1.dp),
            )
        }
    }
}

// Tick fractions (0..1 within the view) paired with their axis label. Labels are M:SS, or a
// one-decimal seconds value when the step is sub-second.
private fun waveformTicks(view: AudioViewWindow): List<Pair<Float, String>> {
    if (view.durationSeconds <= 0.0) return emptyList()
    val step = niceTimeStep(view.durationSeconds)
    if (step <= 0.0) return emptyList()
    val end = view.startSeconds + view.durationSeconds
    val out = ArrayList<Pair<Float, String>>()
    var t = ceil(view.startSeconds / step) * step
    var guard = 0
    while (t <= end + 1e-6 && guard < 4096) {
        val frac = ((t - view.startSeconds) / view.durationSeconds).toFloat()
        if (frac in 0f..1f) out.add(frac to tickLabel(t, step))
        t += step
        guard++
    }
    return out
}

private fun tickLabel(seconds: Double, step: Double): String {
    if (step < 1.0) return "%.1f".format(seconds)
    val whole = seconds.toLong().coerceAtLeast(0L)
    return "%d:%02d".format(whole / 60, whole % 60)
}

private fun DrawScope.drawLaneWaveform(peaks: ChannelPeaks, visibleRange: IntRange) {
    val w = size.width
    if (w <= 0f) return
    val centerY = size.height / 2f
    drawLine(ZERO_LINE_COLOR, Offset(0f, centerY), Offset(w, centerY), strokeWidth = 1f)
    forEachPeakColumn(peaks, visibleRange, w.toInt()) { columnIndex, columnCount, min, max ->
        val x = w * columnIndex / columnCount
        drawLine(
            color = WAVE_COLOR,
            start = Offset(x, centerY - max * centerY),
            end = Offset(x, centerY - min * centerY),
            strokeWidth = 1f,
        )
    }
}

private fun DrawScope.drawAxisTicks(ticks: List<Pair<Float, String>>) {
    val w = size.width
    if (w <= 0f) return
    val h = size.height
    for ((frac, _) in ticks) {
        val x = w * frac
        drawLine(AXIS_COLOR, Offset(x, 0f), Offset(x, h * 0.08f), strokeWidth = 1f)
        drawLine(AXIS_COLOR, Offset(x, h * 0.92f), Offset(x, h), strokeWidth = 1f)
    }
}

private fun DrawScope.drawPlayhead(fraction: Float) {
    val w = size.width
    if (w <= 0f) return
    val x = w * fraction
    drawLine(PLAYHEAD_COLOR, Offset(x, 0f), Offset(x, size.height), strokeWidth = 2f)
}
