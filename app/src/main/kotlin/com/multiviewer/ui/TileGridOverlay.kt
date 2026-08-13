package com.multiviewer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import com.multiviewer.parser.TileGridInfo

// Resolves a tap (in the same Box-local coordinate space PixelInspectorPreview's own gestures use)
// to the tile item ID at that point -- null if the tap landed outside the fitted image bounds
// (letterboxed margin) or, degenerately, outside the grid's own dimensions. `scale`/`offset` invert
// the caller's current zoom/pan transform (the same inverse math panToPoint already applies) before
// the fit-scale/letterbox math runs, so hit-testing stays correct once the user has zoomed or panned.
fun resolveTileAt(
    tapPosition: Offset,
    nativeSize: Size,
    boxSize: Size,
    tileGrid: TileGridInfo,
    scale: Float = 1f,
    offset: Offset = Offset.Zero,
): Long? {
    if (nativeSize.width <= 0f || nativeSize.height <= 0f || boxSize.width <= 0f || boxSize.height <= 0f) return null
    val localTap = Offset((tapPosition.x - offset.x) / scale, (tapPosition.y - offset.y) / scale)
    val fitScale = minOf(boxSize.width / nativeSize.width, boxSize.height / nativeSize.height)
    val fittedWidth = nativeSize.width * fitScale
    val fittedHeight = nativeSize.height * fitScale
    val left = (boxSize.width - fittedWidth) / 2f
    val top = (boxSize.height - fittedHeight) / 2f

    val localX = localTap.x - left
    val localY = localTap.y - top
    if (localX < 0f || localY < 0f || localX >= fittedWidth || localY >= fittedHeight) return null

    val nativeX = localX / fitScale
    val nativeY = localY / fitScale
    val column = (nativeX / tileGrid.tileWidth).toInt()
    val row = (nativeY / tileGrid.tileHeight).toInt()
    if (row !in 0 until tileGrid.layout.rows || column !in 0 until tileGrid.layout.columns) return null

    val index = row * tileGrid.layout.columns + column
    return tileGrid.tileItemIds.getOrNull(index)
}

// Draws one rectangle per tile, in this Canvas's own untransformed coordinate space -- a caller
// that zooms (PixelInspectorPreview) applies the exact same graphicsLayer transform to this
// composable as it applies to its own Image, the same pattern PixelGridOverlay.kt already
// establishes, so the overlay tracks the zoomed image with no zoom-aware drawing logic here.
@Composable
fun TileGridOverlay(tileGrid: TileGridInfo, nativeSize: Size, modifier: Modifier = Modifier) {
    val lineColor = AppColors.NeonPurple
    Canvas(modifier = modifier.fillMaxSize()) {
        if (nativeSize.width <= 0f || nativeSize.height <= 0f) return@Canvas
        val fitScale = minOf(size.width / nativeSize.width, size.height / nativeSize.height)
        val fittedWidth = nativeSize.width * fitScale
        val fittedHeight = nativeSize.height * fitScale
        val left = (size.width - fittedWidth) / 2f
        val top = (size.height - fittedHeight) / 2f

        for (row in 0 until tileGrid.layout.rows) {
            for (column in 0 until tileGrid.layout.columns) {
                val tileLeft = left + column * tileGrid.tileWidth * fitScale
                val tileTop = top + row * tileGrid.tileHeight * fitScale
                val tileRight = (left + (column + 1) * tileGrid.tileWidth * fitScale).coerceAtMost(left + fittedWidth)
                val tileBottom = (top + (row + 1) * tileGrid.tileHeight * fitScale).coerceAtMost(top + fittedHeight)
                drawRect(
                    color = lineColor,
                    topLeft = Offset(tileLeft, tileTop),
                    size = Size(tileRight - tileLeft, tileBottom - tileTop),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5f),
                )
            }
        }
    }
}
