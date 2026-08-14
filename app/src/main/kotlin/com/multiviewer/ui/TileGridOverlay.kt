package com.multiviewer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
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

// Draws exactly one tile's boundary -- the one currently selected in the Media Structure tree
// (see ImageInspectorUI.kt) -- in this Canvas's own untransformed coordinate space. A caller that
// zooms (PixelInspectorPreview) applies the exact same graphicsLayer transform to this composable
// as it applies to its own Image, the same pattern PixelGridOverlay.kt already establishes, so the
// overlay tracks the zoomed image with no zoom-aware drawing logic here. nativeSize is the
// ACTUALLY-DISPLAYED bitmap's size (post-rotation); the tile's own rect is computed in the grid's
// pre-rotation space (tileNativeRect) and then rotated into that same post-rotation space via
// rotateRect before the fit-scale/letterbox math runs, so it lands on the real displayed pixels.
@Composable
fun TileGridOverlay(tileGrid: TileGridInfo, nativeSize: Size, selectedTileIndex: Int, modifier: Modifier = Modifier) {
    val lineColor = AppColors.NeonPurple
    Canvas(modifier = modifier.fillMaxSize()) {
        if (nativeSize.width <= 0f || nativeSize.height <= 0f) return@Canvas
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
        drawRect(
            color = lineColor,
            topLeft = Offset(left + tile.left * fitScale, top + tile.top * fitScale),
            size = Size((tile.right - tile.left) * fitScale, (tile.bottom - tile.top) * fitScale),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f),
        )
    }
}
