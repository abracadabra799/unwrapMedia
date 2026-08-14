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

// Draws exactly one tile's boundary -- the one currently selected in the Media Structure tree
// (see ImageInspectorUI.kt) -- in this Canvas's own untransformed coordinate space. A caller that
// zooms (PixelInspectorPreview) applies the exact same graphicsLayer transform to this composable
// as it applies to its own Image, the same pattern PixelGridOverlay.kt already establishes, so the
// overlay tracks the zoomed image with no zoom-aware drawing logic here.
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

        val tile = tileNativeRect(selectedTileIndex, tileGrid)
        drawRect(
            color = lineColor,
            topLeft = Offset(left + tile.left * fitScale, top + tile.top * fitScale),
            size = Size((tile.right - tile.left) * fitScale, (tile.bottom - tile.top) * fitScale),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f),
        )
    }
}
