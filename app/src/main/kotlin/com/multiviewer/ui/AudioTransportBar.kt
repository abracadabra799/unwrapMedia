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
import androidx.compose.foundation.layout.widthIn
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
 * Header strip for the GoldWave-style audio player: optional `[ Open Audio ]` button on the left,
 * `cursor / total` monospaced time readout on the right. Stateless -- all interaction is delegated.
 */
@Composable
fun AudioPlayerHeader(
    cursorSeconds: Double,
    totalSeconds: Double,
    onOpenAudio: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
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
}

/**
 * Stateless GoldWave-style transport bar shown under the audio waveform.
 *
 * Layout (top to bottom):
 *  1. Transport row: rewind / play-pause / stop, three round buttons.
 *  2. Zoom row: `Zoom:` label, `[-]`, `NN%`, `[+]`.
 *  3. Channel-solo row (only when `channelMode != null`): `[ Stereo | L | R ]` segmented control.
 *
 * All interaction is delegated through the callback parameters; this Composable holds no state.
 * The top `AudioPlayerHeader` (`[ Open Audio ]` + `cursor / total`) is rendered by the caller.
 */
@Composable
fun AudioTransportBar(
    isPlaying: Boolean,
    zoomPercentValue: Int,
    canZoomOut: Boolean,
    canZoomIn: Boolean,
    channelMode: ChannelMode?,
    onChannelMode: (ChannelMode) -> Unit,
    onRewind: () -> Unit,
    onPlayPause: () -> Unit,
    onStop: () -> Unit,
    onZoomOut: () -> Unit,
    onZoomIn: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        // 1. Transport row --------------------------------------------------
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

        // 2. Zoom row -----------------------------------------------------------
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Zoom:", color = Color.White, fontSize = 11.sp)
            ZoomButton(symbol = "−", enabled = canZoomOut, onClick = onZoomOut)
            // widthIn (not a fixed width): a long file can zoom past 720000%, which overflows a
            // fixed box. Past 10000% switch to a compact "NNN×" multiplier.
            val zoomLabel = if (zoomPercentValue >= 10000) "${zoomPercentValue / 100}×" else "$zoomPercentValue%"
            Text(
                zoomLabel,
                color = Color.White,
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(min = 52.dp),
            )
            ZoomButton(symbol = "+", enabled = canZoomIn, onClick = onZoomIn)
        }

        // 3. Channel-solo row -------------------------------------------------
        if (channelMode != null) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ChannelModeSegment("Stereo", channelMode == ChannelMode.STEREO) { onChannelMode(ChannelMode.STEREO) }
                ChannelModeSegment("L", channelMode == ChannelMode.LEFT) { onChannelMode(ChannelMode.LEFT) }
                ChannelModeSegment("R", channelMode == ChannelMode.RIGHT) { onChannelMode(ChannelMode.RIGHT) }
            }
        }
    }
}

/** One segment of the `[ Stereo | L | R ]` channel-solo control. */
@Composable
private fun ChannelModeSegment(label: String, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) Color(0xFF39FF14).copy(alpha = 0.25f) else Color.White.copy(alpha = 0.10f)
    val fg = if (selected) Color(0xFF39FF14) else Color.White.copy(alpha = 0.7f)
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(3.dp))
            .background(bg)
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = fg, fontSize = 10.sp)
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
