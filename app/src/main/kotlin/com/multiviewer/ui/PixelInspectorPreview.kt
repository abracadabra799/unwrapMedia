package com.multiviewer.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.toSize

// 64x (not the original 8x) so the pixel grid overlay (PixelGridOverlay.kt) can actually
// activate on real high-resolution photos: it requires fitScale * scale >= 8, and at a typical
// ~1000px panel a 4000-8000px-wide source image has fitScale as low as ~0.125-0.25, needing
// scale up to ~64 to cross that threshold. 8x was never enough to reach it for real photos.
const val MAX_ZOOM_SCALE = 64f
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
    // At scale == 1, maxX/maxY are exactly 0f, and IEEE 754 negation of +0f yields -0f -- so a
    // negative input coerced against a lower bound of -0f comes out as -0f, not 0f. Kotlin's
    // Float equality (unlike primitive IEEE compare) treats -0f and 0f as unequal, which broke
    // Offset.Zero comparisons downstream. Adding +0f normalizes -0f back to 0f (IEEE 754:
    // -0f + 0f == 0f) without affecting any other value.
    return Offset(
        offset.x.coerceIn(-maxX, maxX) + 0f,
        offset.y.coerceIn(-maxY, maxY) + 0f,
    )
}

// Re-centers the box on the tapped content point -- click-to-navigate while zoomed in, so the
// user can jump straight to a pixel/region of interest instead of only dragging there. Reuses
// clampPanOffset so the result is bounded exactly like a drag's is, and (like every other function
// here) needs no separate "only when zoomed in" branch: at scale == 1 the clamp collapses any
// result to (0, 0).
fun panToPoint(offset: Offset, boxSize: Size, scale: Float, tapPosition: Offset): Offset {
    val contentX = (tapPosition.x - offset.x) / scale
    val contentY = (tapPosition.y - offset.y) / scale
    val recentered = Offset(
        boxSize.width / 2f - contentX * scale,
        boxSize.height / 2f - contentY * scale,
    )
    return clampPanOffset(recentered, boxSize, scale)
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun PixelInspectorPreview(
    bitmap: ImageBitmap,
    modifier: Modifier = Modifier,
    resetKey: Any = bitmap,
    tileGrid: com.multiviewer.parser.TileGridInfo? = null,
    onTileClick: ((itemId: Long) -> Unit)? = null,
) {
    var scale by remember(resetKey) { mutableStateOf(1f) }
    var offset by remember(resetKey) { mutableStateOf(Offset.Zero) }
    var boxSize by remember(resetKey) { mutableStateOf(Size.Zero) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .clipToBounds()
            .background(Color.Black)
            .onGloballyPositioned { boxSize = it.size.toSize() }
            .onPointerEvent(PointerEventType.Scroll, pass = PointerEventPass.Initial) { event ->
                val change = event.changes.firstOrNull() ?: return@onPointerEvent
                val (newScale, rawOffset) = zoomTowardPoint(scale, offset, change.position, change.scrollDelta.y)
                scale = newScale
                offset = clampPanOffset(rawOffset, boxSize, newScale)
                event.changes.forEach { it.consume() }
            }
            .pointerInput(resetKey) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    offset = clampPanOffset(offset + dragAmount, boxSize, scale)
                }
            }
            .pointerInput(resetKey) {
                detectTapGestures(
                    onTap = { tapPosition ->
                        if (tileGrid != null) {
                            resolveTileAt(tapPosition, Size(bitmap.width.toFloat(), bitmap.height.toFloat()), boxSize, tileGrid, scale, offset)
                                ?.let { onTileClick?.invoke(it) }
                        }
                        offset = panToPoint(offset, boxSize, scale, tapPosition)
                    },
                    onDoubleTap = {
                        scale = 1f
                        offset = Offset.Zero
                    },
                )
            },
    ) {
        Image(
            bitmap = bitmap,
            contentDescription = null,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offset.x,
                    translationY = offset.y,
                    transformOrigin = TransformOrigin(0f, 0f),
                ),
            contentScale = ContentScale.Fit,
        )
        if (LocalShowPixelGrid.current) {
            PixelGridOverlay(
                nativeSize = Size(bitmap.width.toFloat(), bitmap.height.toFloat()),
                scale = scale,
                modifier = Modifier.graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offset.x,
                    translationY = offset.y,
                    transformOrigin = TransformOrigin(0f, 0f),
                ),
            )
        }
        if (tileGrid != null) {
            TileGridOverlay(
                tileGrid = tileGrid,
                nativeSize = Size(bitmap.width.toFloat(), bitmap.height.toFloat()),
                modifier = Modifier.graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offset.x,
                    translationY = offset.y,
                    transformOrigin = TransformOrigin(0f, 0f),
                ),
            )
        }
    }
}
