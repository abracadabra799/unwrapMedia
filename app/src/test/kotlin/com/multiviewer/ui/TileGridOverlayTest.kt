package com.multiviewer.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import com.multiviewer.parser.GridLayout
import com.multiviewer.parser.TileGridInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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

    // Same 1x2 grid of 16x16 tiles (32x16 output) as the tileNativeRect tests above -- a boxSize
    // of 64x32 is an exact 2x fit with no letterbox bars, keeping the arithmetic simple.
    @Test
    fun `resolveTileIndexAt returns the left tile's index for a tap inside its bounds`() {
        val index = resolveTileIndexAt(
            tapPosition = Offset(10f, 10f),
            boxSize = Size(64f, 32f),
            nativeSize = Size(32f, 16f),
            scale = 1f,
            offset = Offset.Zero,
            tileGrid = tileGrid,
        )
        assertEquals(0, index)
    }

    @Test
    fun `resolveTileIndexAt returns the right tile's index for a tap inside its bounds`() {
        val index = resolveTileIndexAt(
            tapPosition = Offset(40f, 10f),
            boxSize = Size(64f, 32f),
            nativeSize = Size(32f, 16f),
            scale = 1f,
            offset = Offset.Zero,
            tileGrid = tileGrid,
        )
        assertEquals(1, index)
    }

    @Test
    fun `resolveTileIndexAt returns null for a tap inside the letterbox bars, outside the fitted image`() {
        // 100x100 box fitting a 32x16 (2:1) image: fitScale = min(100/32, 100/16) = 3.125, fitted
        // height = 50, so the image is letterboxed with 25px bars above and below -- a tap at
        // y=10 lands in the top bar, not on the image itself.
        val index = resolveTileIndexAt(
            tapPosition = Offset(10f, 10f),
            boxSize = Size(100f, 100f),
            nativeSize = Size(32f, 16f),
            scale = 1f,
            offset = Offset.Zero,
            tileGrid = tileGrid,
        )
        assertNull(index)
    }

    @Test
    fun `resolveTileIndexAt inverts zoom and pan before resolving the tile`() {
        // Same right-tile tap as above (content-local point 40,10 inside a 64x32 no-letterbox
        // box), but now expressed in on-screen coordinates after a 2x zoom with a (-16,-8) pan:
        // screen = content * scale + offset, i.e. (40*2-16, 10*2-8) = (64, 12).
        val index = resolveTileIndexAt(
            tapPosition = Offset(64f, 12f),
            boxSize = Size(64f, 32f),
            nativeSize = Size(32f, 16f),
            scale = 2f,
            offset = Offset(-16f, -8f),
            tileGrid = tileGrid,
        )
        assertEquals(1, index)
    }

    @Test
    fun `resolveTileIndexAt accounts for rotation, mapping a post-rotation display tap back to the pre-rotation tile`() {
        // Real numbers from the same actual HEIC file as the rotateRect tests above (irot
        // angle=3): a 5x8 grid of 512x512 tiles over a 4000x2252 pre-rotation canvas, displayed
        // as a 2252x4000 bitmap. Tile index 0's pre-rotation rect (0,0,512,512) rotates to
        // (1740,0,2252,512) in display space (verified by the rotateRect test above) -- a tap
        // anywhere inside that displayed rect must resolve back to tile index 0.
        val realTileGrid = TileGridInfo(
            layout = GridLayout(rows = 5, columns = 8, outputWidth = 4000, outputHeight = 2252),
            tileItemIds = (1L..40L).toList(),
            tileWidth = 512,
            tileHeight = 512,
            rotationQuarterTurns = 3,
        )
        val index = resolveTileIndexAt(
            tapPosition = Offset(2000f, 100f),
            boxSize = Size(2252f, 4000f),
            nativeSize = Size(2252f, 4000f),
            scale = 1f,
            offset = Offset.Zero,
            tileGrid = realTileGrid,
        )
        assertEquals(0, index)
    }
}
