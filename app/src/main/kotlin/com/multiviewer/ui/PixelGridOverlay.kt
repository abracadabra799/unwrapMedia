package com.multiviewer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color

val LocalShowPixelGrid = staticCompositionLocalOf { false }

private const val MIN_SCREEN_PX_PER_GRID_LINE = 8f
private val GRID_LINE_COLOR = Color.White.copy(alpha = 0.25f)

// Whether native-pixel-boundary grid lines would actually be legible: the on-screen spacing
// between adjacent lines (the content's own ContentScale.Fit scale, times the caller's zoom
// factor if any) must be at least MIN_SCREEN_PX_PER_GRID_LINE. Pure and unit-tested so the
// threshold behavior doesn't depend on a real Compose layout pass.
fun shouldDrawPixelGrid(nativeSize: Size, boxSize: Size, scale: Float): Boolean {
    if (nativeSize.width <= 0f || nativeSize.height <= 0f || boxSize.width <= 0f || boxSize.height <= 0f) return false
    val fitScale = minOf(boxSize.width / nativeSize.width, boxSize.height / nativeSize.height)
    return (fitScale * scale) >= MIN_SCREEN_PX_PER_GRID_LINE
}

// Draws native-pixel-boundary grid lines within this Canvas's own bounds, for content of
// nativeSize shown at ContentScale.Fit -- exactly the lines Photoshop's "Pixel Grid" would draw.
// `scale` is the CALLER's own zoom factor (1f if the caller has no zoom, like FfmpegVideoPlayer)
// -- used only for the shouldDrawPixelGrid visibility check; the lines themselves are always
// drawn at plain fit-scale, in this composable's own untransformed coordinate space. A caller
// that zooms (PixelInspectorPreview) applies the exact same graphicsLayer transform to this
// composable as it applies to its own Image, so the grid tracks the zoomed image with no
// zoom-aware drawing logic here at all.
@Composable
fun PixelGridOverlay(nativeSize: Size, scale: Float, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.fillMaxSize()) {
        if (!shouldDrawPixelGrid(nativeSize, size, scale)) return@Canvas
        val fitScale = minOf(size.width / nativeSize.width, size.height / nativeSize.height)
        val fittedWidth = nativeSize.width * fitScale
        val fittedHeight = nativeSize.height * fitScale
        val left = (size.width - fittedWidth) / 2f
        val top = (size.height - fittedHeight) / 2f

        var x = 0
        while (x <= nativeSize.width.toInt()) {
            val screenX = left + x * fitScale
            drawLine(GRID_LINE_COLOR, Offset(screenX, top), Offset(screenX, top + fittedHeight))
            x++
        }
        var y = 0
        while (y <= nativeSize.height.toInt()) {
            val screenY = top + y * fitScale
            drawLine(GRID_LINE_COLOR, Offset(left, screenY), Offset(left + fittedWidth, screenY))
            y++
        }
    }
}
