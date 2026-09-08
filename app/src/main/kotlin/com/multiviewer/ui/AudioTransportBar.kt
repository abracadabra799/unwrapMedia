package com.multiviewer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Stateless GoldWave-style transport bar shown under the audio waveform.
 *
 * Layout (top to bottom):
 *  1. Header row: optional `[ Open Audio ]` button + `cursor / total` monospaced time readout.
 *  2. Transport row: rewind / play-pause / stop, three round buttons.
 *  3. Zoom row: `Zoom:` label, `[-]`, `NN%`, `[+]`.
 *
 * All interaction is delegated through the callback parameters; this Composable holds no state.
 */
@Composable
fun AudioTransportBar(
    isPlaying: Boolean,
    cursorSeconds: Double,
    totalSeconds: Double,
    zoomPercentValue: Int,
    canZoomOut: Boolean,
    canZoomIn: Boolean,
    onOpenAudio: (() -> Unit)?,
    onRewind: () -> Unit,
    onPlayPause: () -> Unit,
    onStop: () -> Unit,
    onZoomOut: () -> Unit,
    onZoomIn: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        // 1. Header row -------------------------------------------------------
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onOpenAudio != null) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .border(1.dp, Color.White.copy(alpha = 0.30f), RoundedCornerShape(4.dp))
                        .background(Color.White.copy(alpha = 0.12f))
                        .clickable { onOpenAudio() }
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Open Audio", color = Color.White, fontSize = 12.sp)
                }
            }
            Spacer(Modifier.weight(1f))
            Text(
                "${formatMinSecMillis(cursorSeconds)} / ${formatMinSecMillis(totalSeconds)}",
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                color = Color.White,
            )
        }

        // 2. Transport row --------------------------------------------------
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TransportButton(onClick = onRewind) {
                RewindGlyph(modifier = Modifier.size(14.dp))
            }
            TransportButton(onClick = onPlayPause) {
                if (isPlaying) {
                    PauseGlyph(modifier = Modifier.size(14.dp), color = Color.White)
                } else {
                    Icon(
                        Icons.Filled.PlayArrow,
                        contentDescription = "Play",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            TransportButton(onClick = onStop) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color.White),
                )
            }
        }

        // 3. Zoom row -----------------------------------------------------------
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Zoom:", color = Color.White, fontSize = 11.sp)
            ZoomButton(symbol = "−", enabled = canZoomOut, onClick = onZoomOut)
            Text(
                "$zoomPercentValue%",
                color = Color.White,
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(52.dp),
            )
            ZoomButton(symbol = "+", enabled = canZoomIn, onClick = onZoomIn)
        }
    }
}

@Composable
private fun TransportButton(onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.12f))
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

@Composable
private fun ZoomButton(symbol: String, enabled: Boolean, onClick: () -> Unit) {
    val color = if (enabled) Color.White else Color.White.copy(alpha = 0.30f)
    Box(
        modifier = Modifier
            .size(22.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(Color.White.copy(alpha = 0.12f))
            .then(if (enabled) Modifier.clickable { onClick() } else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Text(symbol, color = color, fontSize = 13.sp, textAlign = TextAlign.Center)
    }
}

/** Two small left-pointing triangles (rewind / previous). */
@Composable
private fun RewindGlyph(modifier: Modifier = Modifier, color: Color = Color.White) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val half = w / 2f
        fun triangle(right: Float) = Path().apply {
            moveTo(right, 0f)
            lineTo(right, h)
            lineTo(right - half, h / 2f)
            close()
        }
        drawPath(triangle(half), color)
        drawPath(triangle(w), color)
    }
}

/** Hand-drawn two-bar pause glyph (replacement for the removed AudioPauseIcon). */
@Composable
private fun PauseGlyph(modifier: Modifier = Modifier, color: Color = Color.White) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.fillMaxHeight().width(3.dp).background(color))
        Box(modifier = Modifier.fillMaxHeight().width(3.dp).background(color))
    }
}
