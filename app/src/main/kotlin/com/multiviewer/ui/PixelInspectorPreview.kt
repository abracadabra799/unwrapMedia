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

// The size ContentScale.Fit actually draws `nativeSize` at inside `boxSize` -- uniformly scaled by
// whichever axis binds first, so one axis fills the box and the other is letterboxed. This is the
// real extent of the image on screen, which is what pan has to be measured against: the layer the
// transform is applied to is always the full box, but the picture inside it usually isn't.
fun fittedContentSize(boxSize: Size, nativeSize: Size): Size {
    if (nativeSize.width <= 0f || nativeSize.height <= 0f || boxSize.width <= 0f || boxSize.height <= 0f) {
        return boxSize
    }
    val fitScale = minOf(boxSize.width / nativeSize.width, boxSize.height / nativeSize.height)
    return Size(nativeSize.width * fitScale, nativeSize.height * fitScale)
}

// Bounds pan so the drawn image can never be dragged off the box -- neither past its own far edge
// (leaving blank space) nor out of view entirely. At scale == 1 this yields exactly (0, 0) on both
// axes, so callers need no separate "only pan when zoomed in" branch anywhere.
//
// Two things make the bounds asymmetric rather than the ±overhang/2 a center pivot would give:
//
//  - Every call site draws with transformOrigin = TransformOrigin(0f, 0f). With a top-left pivot
//    the layer grows only right/down, so a layer-local x lands on screen at offset.x + scale*x.
//    The symmetric bound is the *center*-pivot formula, and against a top-left pivot it cut the
//    reachable pan in half: the far edge stopped at (scale+1)/(2*scale) of the content's width --
//    the right quarter of the image unreachable at 2x, worse the further in you zoom.
//  - The image is letterboxed inside that box-sized layer (contentSize, from fittedContentSize),
//    so bounding the *layer* to the box still let a tall image's narrow strip slide out of view
//    sideways, even though vertically -- where it fills the layer edge to edge -- it could not.
//    Bounding the drawn image makes both axes behave the same way.
//
// zoomTowardPoint and panToPoint already use the same top-left model.
fun clampPanOffset(offset: Offset, boxSize: Size, scale: Float, contentSize: Size = boxSize): Offset =
    Offset(
        clampPanAxis(offset.x, boxSize.width, contentSize.width, scale),
        clampPanAxis(offset.y, boxSize.height, contentSize.height, scale),
    )

private fun clampPanAxis(value: Float, box: Float, drawn: Float, scale: Float): Float {
    // Screen distance from the layer's own origin to the drawn image's near edge -- the scaled
    // letterbox bar. The image occupies [value + letterbox, value + letterbox + scale*drawn].
    val letterbox = scale * (box - drawn) / 2f
    val overhang = scale * drawn - box
    // Adding +0f normalizes IEEE 754's -0f (which a coerceIn against a -0f bound can produce, and
    // which Kotlin's Float equality treats as unequal to 0f) back to 0f, without affecting any
    // other value -- Offset.Zero comparisons downstream depend on it.
    return if (overhang >= 0f) {
        // Big enough to cover the box: pan freely, but keep it covered edge to edge.
        value.coerceIn(-letterbox - overhang, -letterbox) + 0f
    } else {
        // Still smaller than the box on this axis, so there is nothing to pan to -- stay centered
        // instead of letting the image drift toward (or past) an edge.
        -letterbox - overhang / 2f + 0f
    }
}

// Re-centers the box on the tapped content point -- click-to-navigate while zoomed in, so the
// user can jump straight to a pixel/region of interest instead of only dragging there. Reuses
// clampPanOffset so the result is bounded exactly like a drag's is, and (like every other function
// here) needs no separate "only when zoomed in" branch: at scale == 1 the clamp collapses any
// result to (0, 0).
fun panToPoint(offset: Offset, boxSize: Size, scale: Float, tapPosition: Offset, contentSize: Size = boxSize): Offset {
    val contentX = (tapPosition.x - offset.x) / scale
    val contentY = (tapPosition.y - offset.y) / scale
    val recentered = Offset(
        boxSize.width / 2f - contentX * scale,
        boxSize.height / 2f - contentY * scale,
    )
    return clampPanOffset(recentered, boxSize, scale, contentSize)
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun PixelInspectorPreview(
    bitmap: ImageBitmap,
    modifier: Modifier = Modifier,
    resetKey: Any = bitmap,
    tileGrid: com.multiviewer.parser.TileGridInfo? = null,
    selectedTileIndex: Int? = null,
    onTileClick: ((Int) -> Unit)? = null,
) {
    var scale by remember(resetKey) { mutableStateOf(1f) }
    var offset by remember(resetKey) { mutableStateOf(Offset.Zero) }
    var boxSize by remember(resetKey) { mutableStateOf(Size.Zero) }
    val nativeSize = Size(bitmap.width.toFloat(), bitmap.height.toFloat())
    // What ContentScale.Fit actually draws inside the box -- pan is bounded against this, not the
    // box-sized layer, so a letterboxed image can't be dragged out of view sideways.
    val contentSize = fittedContentSize(boxSize, nativeSize)

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
                offset = clampPanOffset(rawOffset, boxSize, newScale, contentSize)
                event.changes.forEach { it.consume() }
            }
            .pointerInput(resetKey) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    offset = clampPanOffset(offset + dragAmount, boxSize, scale, contentSize)
                }
            }
            .pointerInput(resetKey) {
                detectTapGestures(
                    onTap = { tapPosition ->
                        if (tileGrid != null && onTileClick != null) {
                            resolveTileIndexAt(
                                tapPosition = tapPosition,
                                boxSize = boxSize,
                                nativeSize = nativeSize,
                                scale = scale,
                                offset = offset,
                                tileGrid = tileGrid,
                            )?.let { onTileClick(it) }
                        }
                        offset = panToPoint(offset, boxSize, scale, tapPosition, contentSize)
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
        if (tileGrid != null && selectedTileIndex != null) {
            TileGridOverlay(
                tileGrid = tileGrid,
                nativeSize = Size(bitmap.width.toFloat(), bitmap.height.toFloat()),
                selectedTileIndex = selectedTileIndex,
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
