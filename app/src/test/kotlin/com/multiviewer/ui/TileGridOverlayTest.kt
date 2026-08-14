package com.multiviewer.ui

import androidx.compose.ui.geometry.Rect
import com.multiviewer.parser.GridLayout
import com.multiviewer.parser.TileGridInfo
import kotlin.test.Test
import kotlin.test.assertEquals

class TileGridOverlayTest {
    // A 1-row, 2-column grid of 16x16 tiles (32x16 output).
    private val tileGrid = TileGridInfo(
        layout = GridLayout(rows = 1, columns = 2, outputWidth = 32, outputHeight = 16),
        tileItemIds = listOf(101L, 102L),
        tileWidth = 16,
        tileHeight = 16,
    )

    @Test
    fun `tileNativeRect returns the left tile's bounds for index 0`() {
        assertEquals(Rect(0f, 0f, 16f, 16f), tileNativeRect(0, tileGrid))
    }

    @Test
    fun `tileNativeRect returns the right tile's bounds for index 1`() {
        assertEquals(Rect(16f, 0f, 32f, 16f), tileNativeRect(1, tileGrid))
    }

    @Test
    fun `tileNativeRect clamps to the output canvas for an edge tile that would otherwise overhang`() {
        // A 2x1 grid of 16x16 tiles whose output canvas is only 24 wide -- the second column's
        // tile (native x 16..32) must clamp its right edge to the real 24px canvas boundary,
        // matching HEIF's own "crop the excess on edge tiles" behavior.
        val croppedGrid = TileGridInfo(
            layout = GridLayout(rows = 1, columns = 2, outputWidth = 24, outputHeight = 16),
            tileItemIds = listOf(201L, 202L),
            tileWidth = 16,
            tileHeight = 16,
        )
        assertEquals(Rect(16f, 0f, 24f, 16f), tileNativeRect(1, croppedGrid))
    }

    @Test
    fun `tileNativeRect computes row and column for a multi-row grid`() {
        // 2 rows x 2 columns of 8x8 tiles (16x16 output) -- index 2 is row 1, column 0.
        val multiRowGrid = TileGridInfo(
            layout = GridLayout(rows = 2, columns = 2, outputWidth = 16, outputHeight = 16),
            tileItemIds = listOf(1L, 2L, 3L, 4L),
            tileWidth = 8,
            tileHeight = 8,
        )
        assertEquals(Rect(0f, 8f, 8f, 16f), tileNativeRect(2, multiRowGrid))
    }
}
