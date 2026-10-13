package com.multiviewer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.multiviewer.parser.TileGridInfo

// A tile's own bounds in native (unscaled) image pixel coordinates -- row-major index into
// tileGrid.tileItemIds, clamped to the grid's real output canvas for edge tiles that would
// otherwise overhang it (matching HEIF's own "crop the excess on edge tiles" behavior).
fun tileNativeRect(index: Int, tileGrid: TileGridInfo): Rect {
    val row = index / tileGrid.layout.columns
    val column = index % tileGrid.layout.columns
    val left = (column * tileGrid.tileWidth).toFloat()
    val top = (row * tileGrid.tileHeight).toFloat()
    val right = (left + tileGrid.tileWidth).coerceAtMost(tileGrid.layout.outputWidth.toFloat())
    val bottom = (top + tileGrid.tileHeight).coerceAtMost(tileGrid.layout.outputHeight.toFloat())
    return Rect(left, top, right, bottom)
}

// Rotates a rect by a multiple of 90 degrees COUNTER-CLOCKWISE (HEIF's own irot "angle" field
// convention: angle is in units of 90 degrees CCW) from a sourceWidth x sourceHeight canvas into
// the resulting rotated canvas -- for a 90/270 turn the canvas's own width/height swap, matching
// what ffmpeg's decoder already does when it auto-rotates a HEIC's assembled grid canvas into the
// bitmap this app actually displays. Needed because tileNativeRect above operates in the grid's
// own PRE-rotation coordinate space (straight from the file's rows/columns/output_width/height),
// which is a different coordinate space than the POST-rotation displayed bitmap whenever the file
// carries a non-zero irot -- verified against a real HEIC file (irot angle=3): the raw grid canvas
// is 4000x2252, but the actually-displayed primary image bitmap is 2252x4000.
fun rotateRect(rect: Rect, sourceWidth: Float, sourceHeight: Float, quarterTurns: Int): Rect {
    val normalized = ((quarterTurns % 4) + 4) % 4
    return when (normalized) {
        0 -> rect
        1 -> { // 90 CCW: (W,H) -> (H,W); (x,y) -> (y, W - x)
            val x1 = rect.top
            val y1 = sourceWidth - rect.right
            val x2 = rect.bottom
            val y2 = sourceWidth - rect.left
            Rect(minOf(x1, x2), minOf(y1, y2), maxOf(x1, x2), maxOf(y1, y2))
        }
        2 -> { // 180: (W,H) -> (W,H); (x,y) -> (W - x, H - y)
            Rect(sourceWidth - rect.right, sourceHeight - rect.bottom, sourceWidth - rect.left, sourceHeight - rect.top)
        }
        else -> { // 3: 270 CCW (= 90 CW): (W,H) -> (H,W); (x,y) -> (H - y, x)
            val x1 = sourceHeight - rect.bottom
            val y1 = rect.left
            val x2 = sourceHeight - rect.top
            val y2 = rect.right
            Rect(minOf(x1, x2), minOf(y1, y2), maxOf(x1, x2), maxOf(y1, y2))
        }
    }
}

// Inverse of the draw-time transform TileGridOverlay/tileNativeRect/rotateRect apply: given a tap
// position in the same coordinate space Compose delivers to a pointerInput callback on the Box
// wrapping the image (i.e. before that Box's own zoom/pan graphicsLayer, matching how
// PixelInspectorPreview.panToPoint already un-does scale/offset), first undoes zoom/pan and the
// fit-scale/letterbox math to land in POST-rotation native bitmap pixel coordinates, then undoes
// the rotation (rotateRect with the inverse quarter-turn count -- k and 4-k cancel out, see the
// "at 1 quarter turn is the inverse of 3" test above) to land in the grid's own PRE-rotation
// space, where row/column follow directly from tileWidth/tileHeight. Returns null when the tap
// misses the fitted image entirely (letterbox bars) or resolves outside the tile grid's own
// tileItemIds range.
fun resolveTileIndexAt(
    tapPosition: Offset,
    boxSize: Size,
    nativeSize: Size,
    scale: Float,
    offset: Offset,
    tileGrid: TileGridInfo,
): Int? {
    if (boxSize.width <= 0f || boxSize.height <= 0f || nativeSize.width <= 0f || nativeSize.height <= 0f) return null

    val contentX = (tapPosition.x - offset.x) / scale
    val contentY = (tapPosition.y - offset.y) / scale
    val fitScale = minOf(boxSize.width / nativeSize.width, boxSize.height / nativeSize.height)
    val left = (boxSize.width - nativeSize.width * fitScale) / 2f
    val top = (boxSize.height - nativeSize.height * fitScale) / 2f
    val nativeX = (contentX - left) / fitScale
    val nativeY = (contentY - top) / fitScale
    if (nativeX < 0f || nativeY < 0f || nativeX >= nativeSize.width || nativeY >= nativeSize.height) return null

    val inverseQuarterTurns = (4 - ((tileGrid.rotationQuarterTurns % 4) + 4) % 4) % 4
    val gridPoint = rotateRect(
        Rect(nativeX, nativeY, nativeX, nativeY),
        sourceWidth = nativeSize.width,
        sourceHeight = nativeSize.height,
        quarterTurns = inverseQuarterTurns,
    )

    val column = (gridPoint.left / tileGrid.tileWidth).toInt().coerceIn(0, tileGrid.layout.columns - 1)
    val row = (gridPoint.top / tileGrid.tileHeight).toInt().coerceIn(0, tileGrid.layout.rows - 1)
    val index = row * tileGrid.layout.columns + column
    return index.takeIf { it in tileGrid.tileItemIds.indices }
}

// "item 19 · r3, c3": the tree item the tile is (item_<ID> in iloc) and its 1-based row/column in
// the stored (pre-rotation) grid -- the coordinates the structure tree and the file use.
fun tileLabel(index: Int, tileGrid: TileGridInfo): String {
    val row = index / tileGrid.layout.columns + 1
    val column = index % tileGrid.layout.columns + 1
    val itemId = tileGrid.tileItemIds.getOrNull(index)?.toString() ?: "?"
    return "item $itemId · r$row, c$column"
}

private val TileHighlight = Color(0xFFFFC400) // amber: readable on grey, sky and skin alike
private val TileHalo = Color.Black.copy(alpha = 0.8f)
private val TileDim = Color.Black.copy(alpha = 0.42f)

// Highlights the tile currently selected in the Media Structure tree (see ImageInspectorUI.kt):
// dims the rest of the image (the tile's own pixels stay untouched for inspection), outlines it in
// amber over a black halo so it reads on bright and dark content alike, and labels it with
// [tileLabel]. Drawn in this Canvas's own untransformed space; the caller applies the image's
// zoom/pan graphicsLayer to it as well (the PixelGridOverlay.kt pattern), and passes that zoom as
// [zoomScale] so the line widths and the label stay a constant size on screen instead of growing and
// shrinking with zoom. nativeSize is the DISPLAYED (post-rotation) bitmap size; the tile's rect is
// computed in the grid's pre-rotation space and rotated into it via rotateRect.
@Composable
fun TileGridOverlay(
    tileGrid: TileGridInfo,
    nativeSize: Size,
    selectedTileIndex: Int,
    modifier: Modifier = Modifier,
    zoomScale: Float = 1f,
) {
    val textMeasurer = rememberTextMeasurer()
    val label = remember(selectedTileIndex, tileGrid) { tileLabel(selectedTileIndex, tileGrid) }
    val density = LocalDensity.current
    Canvas(modifier = modifier.fillMaxSize()) {
        if (nativeSize.width <= 0f || nativeSize.height <= 0f) return@Canvas
        val zoom = zoomScale.coerceAtLeast(0.01f)
        val fitScale = minOf(size.width / nativeSize.width, size.height / nativeSize.height)
        val fittedWidth = nativeSize.width * fitScale
        val fittedHeight = nativeSize.height * fitScale
        val left = (size.width - fittedWidth) / 2f
        val top = (size.height - fittedHeight) / 2f

        val rawTile = tileNativeRect(selectedTileIndex, tileGrid)
        val tile = rotateRect(
            rawTile,
            sourceWidth = tileGrid.layout.outputWidth.toFloat(),
            sourceHeight = tileGrid.layout.outputHeight.toFloat(),
            quarterTurns = tileGrid.rotationQuarterTurns,
        )
        val t = Rect(
            left + tile.left * fitScale, top + tile.top * fitScale,
            left + tile.right * fitScale, top + tile.bottom * fitScale,
        )
        val image = Rect(left, top, left + fittedWidth, top + fittedHeight)

        // Dim everything but the tile (four bands around it, clipped to the image).
        drawRect(TileDim, Offset(image.left, image.top), Size(image.width, (t.top - image.top).coerceAtLeast(0f)))
        drawRect(TileDim, Offset(image.left, t.bottom), Size(image.width, (image.bottom - t.bottom).coerceAtLeast(0f)))
        drawRect(TileDim, Offset(image.left, t.top), Size((t.left - image.left).coerceAtLeast(0f), t.height))
        drawRect(TileDim, Offset(t.right, t.top), Size((image.right - t.right).coerceAtLeast(0f), t.height))

        // Widths in screen pixels, divided by the zoom the graphicsLayer will apply.
        val px = with(density) { 1.dp.toPx() } / zoom
        drawRect(TileHalo, t.topLeft, t.size, style = Stroke(width = 4f * px))
        drawRect(TileHighlight, t.topLeft, t.size, style = Stroke(width = 2f * px))

        // Label above the tile's top-left corner (inside it when there's no room above), drawn
        // un-zoomed around its anchor so it stays readable at any zoom.
        val text = textMeasurer.measure(
            label,
            TextStyle(color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
        )
        val padX = with(density) { 5.dp.toPx() }
        val padY = with(density) { 2.dp.toPx() }
        val badge = Size(text.size.width + padX * 2, text.size.height + padY * 2)
        val above = t.top - badge.height * px >= image.top
        val anchor = if (above) Offset(t.left, t.top - badge.height * px) else Offset(t.left + 3f * px, t.top + 3f * px)
        withTransform({ scale(1f / zoom, 1f / zoom, pivot = anchor) }) {
            drawRoundRect(TileHighlight, anchor, badge, CornerRadius(with(density) { 3.dp.toPx() }))
            drawText(text, topLeft = Offset(anchor.x + padX, anchor.y + padY))
        }
    }
}
