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

    @Test
    fun `rotateRect passes a rect through unchanged for 0 quarter turns`() {
        assertEquals(Rect(0f, 0f, 512f, 512f), rotateRect(Rect(0f, 0f, 512f, 512f), 4000f, 2252f, quarterTurns = 0))
    }

    @Test
    fun `rotateRect maps the top-left tile of a real 4000x2252 grid to the top-right area of the rotated 2252x4000 canvas at 3 quarter turns`() {
        // Real numbers from an actual HEIC file (irot angle=3, i.e. 270 degrees counter-clockwise
        // -- HEIF's own definition of the field -- which ffmpeg reports as a -90 degree display
        // matrix and which turns a 4000x2252 pre-rotation grid canvas into the 2252x4000 bitmap
        // that's actually displayed). The pre-rotation top-left tile (the first tile, index 0)
        // must land near the top-RIGHT of the rotated canvas, matching what physically rotating a
        // landscape photo 90 degrees clockwise to portrait does to its top-left corner.
        val topLeftTile = Rect(0f, 0f, 512f, 512f)
        val rotated = rotateRect(topLeftTile, sourceWidth = 4000f, sourceHeight = 2252f, quarterTurns = 3)
        assertEquals(Rect(1740f, 0f, 2252f, 512f), rotated)
    }

    @Test
    fun `rotateRect at 1 quarter turn is the inverse of 3 quarter turns for the same rect`() {
        val original = Rect(0f, 0f, 512f, 512f)
        val rotated3 = rotateRect(original, sourceWidth = 4000f, sourceHeight = 2252f, quarterTurns = 3)
        // Rotating the already-rotated (now 2252x4000-space) rect back by 1 quarter turn (90 CCW)
        // should recover the original rect in the original 4000x2252 space.
        val rotatedBack = rotateRect(rotated3, sourceWidth = 2252f, sourceHeight = 4000f, quarterTurns = 1)
        assertEquals(original, rotatedBack)
    }

    @Test
    fun `rotateRect at 2 quarter turns keeps canvas size and flips the rect to the opposite corner`() {
        val rect = Rect(0f, 0f, 16f, 16f)
        assertEquals(Rect(16f, 16f, 32f, 32f), rotateRect(rect, sourceWidth = 32f, sourceHeight = 32f, quarterTurns = 2))
    }
}
