package com.multiviewer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

// Every Nth bucket of the same 4096-bucket array WaveformDisplay already draws, sampled coarser
// since this is a whole-track overview, not a detail view -- no new computation, no ffmpeg call.
private const val MINIMAP_BUCKET_STRIDE = 8

// Always shows the WHOLE track (never zoomed itself), with a draggable rectangle for the current
// zoom window and a playhead marker. Clicking anywhere seeks the whole player, independent of
// zoom -- the one place seeking always reaches the entire file regardless of the detail panels'
// current window.
@Composable
fun AudioMinimap(
    peaks: WaveformPeaks?,
    window: AudioViewWindow,
    totalDuration: Double,
    elapsedSeconds: Double,
    onWindowChange: (AudioViewWindow) -> Unit,
    onSeek: (fraction: Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(32.dp)
            .background(Color.Black)
            .pointerInput(totalDuration) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    onSeek(down.position.x / size.width.toFloat())
                    drag(down.id) { change ->
                        change.consume()
                        onSeek(change.position.x / size.width.toFloat())
                    }
                }
            },
    ) {
        val totalWidthPx = constraints.maxWidth

        if (peaks != null) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawMinimapWaveform(peaks)
            }
        }

        if (totalDuration > 0.0) {
            val startFraction = (window.startSeconds / totalDuration).toFloat().coerceIn(0f, 1f)
            val durationFraction = (window.durationSeconds / totalDuration).toFloat().coerceIn(0.001f, 1f)
            Box(
                modifier = Modifier
                    .offset(x = maxWidth * startFraction)
                    .width(maxWidth * durationFraction)
                    .fillMaxHeight()
                    .background(Color.White.copy(alpha = 0.25f))
                    .pointerInput(totalDuration, totalWidthPx) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            if (totalWidthPx > 0) {
                                val deltaSeconds = (dragAmount.x / totalWidthPx.toFloat()) * totalDuration
                                onWindowChange(clampWindow(window.startSeconds + deltaSeconds, window.durationSeconds, totalDuration))
                            }
                        }
                    },
            )

            val playheadFraction = (elapsedSeconds / totalDuration).toFloat().coerceIn(0f, 1f)
            Box(
                modifier = Modifier
                    .offset(x = maxWidth * playheadFraction)
                    .width(2.dp)
                    .fillMaxHeight()
                    .background(Color.White),
            )
        }
    }
}

private fun DrawScope.drawMinimapWaveform(peaks: WaveformPeaks) {
    val channel = peaks.channels.firstOrNull() ?: return
    val width = size.width
    val height = size.height
    val centerY = height / 2f
    val bucketCount = channel.min.size
    if (bucketCount == 0 || width <= 0f) return
    var i = 0
    while (i < bucketCount) {
        val x = width * i / bucketCount
        val yTop = centerY - channel.max[i] * centerY
        val yBottom = centerY - channel.min[i] * centerY
        drawLine(color = Color(0xFF39FF14).copy(alpha = 0.6f), start = Offset(x, yTop), end = Offset(x, yBottom), strokeWidth = 1f)
        i += MINIMAP_BUCKET_STRIDE
    }
}
