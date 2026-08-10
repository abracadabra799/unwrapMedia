package com.multiviewer.ui

import androidx.compose.ui.geometry.Size
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PixelGridOverlayTest {
    @Test
    fun `hides the grid when fit-scale spacing is below the threshold`() {
        // A 4000px-wide native image fit into a 400px box is 0.1 screen px per source px --
        // nowhere near the 8px-per-line minimum.
        assertFalse(shouldDrawPixelGrid(nativeSize = Size(4000f, 3000f), boxSize = Size(400f, 300f), scale = 1f))
    }

    @Test
    fun `shows the grid once zoom pushes the effective spacing above the threshold`() {
        // Same image/box as above (0.1 screen px per source px at scale 1), but zoomed in 100x:
        // 0.1 * 100 = 10 screen px per source px, above the 8px minimum.
        assertTrue(shouldDrawPixelGrid(nativeSize = Size(4000f, 3000f), boxSize = Size(400f, 300f), scale = 100f))
    }

    @Test
    fun `shows the grid directly at fit-scale for a low-resolution image`() {
        // A 40x30 native image fit into a 400x300 box is 10 screen px per source px -- already
        // above the threshold with no zoom needed, matching the video-player use case (scale
        // always 1f) for small/raw test footage.
        assertTrue(shouldDrawPixelGrid(nativeSize = Size(40f, 30f), boxSize = Size(400f, 300f), scale = 1f))
    }

    @Test
    fun `hides the grid for a degenerate zero-size nativeSize`() {
        assertFalse(shouldDrawPixelGrid(nativeSize = Size.Zero, boxSize = Size(400f, 300f), scale = 1f))
    }

    @Test
    fun `hides the grid for a degenerate zero-size boxSize`() {
        assertFalse(shouldDrawPixelGrid(nativeSize = Size(400f, 300f), boxSize = Size.Zero, scale = 1f))
    }
}
