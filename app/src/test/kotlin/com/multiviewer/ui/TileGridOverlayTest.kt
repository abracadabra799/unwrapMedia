package com.multiviewer.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import com.multiviewer.parser.GridLayout
import com.multiviewer.parser.TileGridInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TileGridOverlayTest {
    // A 1-row, 2-column grid of 16x16 tiles (32x16 output), fit into a 320x160 box -- 10x fit scale,
    // so each tile occupies a 160x160 screen-space rectangle at that scale.
    private val tileGrid = TileGridInfo(
        layout = GridLayout(rows = 1, columns = 2, outputWidth = 32, outputHeight = 16),
        tileItemIds = listOf(101L, 102L),
        tileWidth = 16,
        tileHeight = 16,
    )
    private val nativeSize = Size(32f, 16f)
    private val boxSize = Size(320f, 160f)

    @Test
    fun `resolveTileAt returns the first tile's item ID for a tap in the left half`() {
        assertEquals(101L, resolveTileAt(Offset(50f, 50f), nativeSize, boxSize, tileGrid))
    }

    @Test
    fun `resolveTileAt returns the second tile's item ID for a tap in the right half`() {
        assertEquals(102L, resolveTileAt(Offset(250f, 50f), nativeSize, boxSize, tileGrid))
    }

    @Test
    fun `resolveTileAt returns null for a tap outside the fitted image bounds`() {
        assertNull(resolveTileAt(Offset(-10f, 50f), nativeSize, boxSize, tileGrid))
        assertNull(resolveTileAt(Offset(50f, 5000f), nativeSize, boxSize, tileGrid))
    }
}
