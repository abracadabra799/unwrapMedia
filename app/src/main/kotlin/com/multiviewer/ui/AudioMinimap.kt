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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

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
    onPreviewSeek: (fraction: Float) -> Unit,
    onSeek: (fraction: Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    // The suspend pointerInput block below only restarts when its keys change, and none of them
    // track `window` -- so read the live value through rememberUpdatedState rather than the one
    // captured at first composition (otherwise every rectangle drag snaps back to (0, 5)).
    val currentWindow by rememberUpdatedState(window)

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(32.dp)
            .background(Color.Black)
            .pointerInput(totalDuration) {
                awaitEachGesture {
                    // Scrub live via onPreviewSeek (no ffmpeg-pipe restart) and commit once on
                    // release with a single onSeek at the final position -- a drag used to fire
                    // one full pipe teardown/respawn per pointer-move (30-60x/sec).
                    val down = awaitFirstDown()
                    var lastFraction = down.position.x / size.width.toFloat()
                    onPreviewSeek(lastFraction)
                    drag(down.id) { change ->
                        change.consume()
                        lastFraction = change.position.x / size.width.toFloat()
                        onPreviewSeek(lastFraction)
                    }
                    onSeek(lastFraction)
                }
            },
    ) {
        val totalWidthPx = constraints.maxWidth

        // The minimap waveform is static per file (peaks fixed, width fixed) but this composable
        // recomposes ~60x/s during playback -- memoize the columns so a long file doesn't rescan
        // its whole (multi-million-entry) peak array every frame.
        val minimapColumns = remember(peaks, totalWidthPx) {
            peaks?.channels?.firstOrNull()?.let { ch ->
                if (ch.min.isNotEmpty() && totalWidthPx > 0) downsamplePeaks(ch, 0 until ch.min.size, totalWidthPx)
                else null
            }
        }

        if (minimapColumns != null) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawMinimapWaveform(minimapColumns)
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
                                onWindowChange(clampWindow(currentWindow.startSeconds + deltaSeconds, currentWindow.durationSeconds, totalDuration))
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

private fun DrawScope.drawMinimapWaveform(columns: List<PeakColumn>) {
    val width = size.width
    val centerY = size.height / 2f
    if (columns.isEmpty() || width <= 0f) return
    for ((idx, col) in columns.withIndex()) {
        val x = width * idx / columns.size
        val yTop = centerY - col.max * centerY
        val yBottom = centerY - col.min * centerY
        drawLine(
            color = Color(0xFF39FF14).copy(alpha = 0.6f),
            start = Offset(x, yTop), end = Offset(x, yBottom), strokeWidth = 1f,
        )
    }
}
