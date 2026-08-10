package com.multiviewer.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.runtime.Composable

const val MAX_ZOOM_SCALE = 8f
private const val ZOOM_STEP_FACTOR = 0.08f

// Cursor-anchored zoom: re-derives offset so the content point currently under the cursor stays
// under the cursor after the scale change, instead of zooming around the box's center (which
// would make whatever the user is actually looking at drift away as they zoom in). Matches the
// scroll-up-zooms-in sign convention already established by FfmpegAudioPlayer.kt's own zoom step.
fun zoomTowardPoint(scale: Float, offset: Offset, cursorPosition: Offset, scrollDeltaY: Float): Pair<Float, Offset> {
    val newScale = (scale * (1f - scrollDeltaY * ZOOM_STEP_FACTOR)).coerceIn(1f, MAX_ZOOM_SCALE)
    val newOffset = Offset(
        cursorPosition.x - (cursorPosition.x - offset.x) * (newScale / scale),
        cursorPosition.y - (cursorPosition.y - offset.y) * (newScale / scale),
    )
    return newScale to newOffset
}

// Bounds pan so the scaled content can never be dragged far enough to reveal empty space beyond
// its own edge -- half of however much the scaled content now overhangs the box's own bounds on
// that axis. At scale == 1 this collapses to exactly 0f..0f (no pan possible), so callers don't
// need a separate "only pan when zoomed in" branch anywhere.
fun clampPanOffset(offset: Offset, boxSize: Size, scale: Float): Offset {
    val maxX = ((boxSize.width * scale) - boxSize.width) / 2f
    val maxY = ((boxSize.height * scale) - boxSize.height) / 2f
    return Offset(
        offset.x.coerceIn(-maxX, maxX),
        offset.y.coerceIn(-maxY, maxY),
    )
}

@Composable
fun PixelInspectorPreview(bitmap: ImageBitmap, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
        Image(
            bitmap = bitmap,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit,
        )
    }
}
