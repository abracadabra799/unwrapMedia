package com.multiviewer.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PixelInspectorPreviewTest {
    @Test
    fun `zoomTowardPoint increases scale on scroll-up (negative delta) and keeps the cursor point fixed`() {
        val (newScale, newOffset) = zoomTowardPoint(
            scale = 1f, offset = Offset.Zero, cursorPosition = Offset(100f, 50f), scrollDeltaY = -1f,
        )
        assertTrue(newScale > 1f, "Expected scale to increase on scroll-up, got $newScale")

        // Cursor-anchored zoom: the content point under the cursor before the zoom --
        // (cursorPosition - offset) / scale -- must be the same content point after the zoom.
        val contentPointBeforeX = (100f - 0f) / 1f
        val contentPointBeforeY = (50f - 0f) / 1f
        val contentPointAfterX = (100f - newOffset.x) / newScale
        val contentPointAfterY = (50f - newOffset.y) / newScale
        assertTrue(kotlin.math.abs(contentPointBeforeX - contentPointAfterX) < 0.01f)
        assertTrue(kotlin.math.abs(contentPointBeforeY - contentPointAfterY) < 0.01f)
    }

    @Test
    fun `zoomTowardPoint decreases scale on scroll-down (positive delta)`() {
        val (newScale, _) = zoomTowardPoint(
            scale = 2f, offset = Offset.Zero, cursorPosition = Offset(100f, 50f), scrollDeltaY = 1f,
        )
        assertTrue(newScale < 2f, "Expected scale to decrease on scroll-down, got $newScale")
    }

    @Test
    fun `zoomTowardPoint never scales below 1x`() {
        val (newScale, _) = zoomTowardPoint(
            scale = 1f, offset = Offset.Zero, cursorPosition = Offset(100f, 50f), scrollDeltaY = 100f,
        )
        assertEquals(1f, newScale)
    }

    @Test
    fun `zoomTowardPoint never scales above MAX_ZOOM_SCALE`() {
        val (newScale, _) = zoomTowardPoint(
            scale = MAX_ZOOM_SCALE, offset = Offset.Zero, cursorPosition = Offset(100f, 50f), scrollDeltaY = -100f,
        )
        assertEquals(MAX_ZOOM_SCALE, newScale)
    }

    @Test
    fun `clampPanOffset is always zero at scale 1`() {
        val clamped = clampPanOffset(Offset(500f, 500f), Size(400f, 300f), scale = 1f)
        assertEquals(Offset.Zero, clamped)
    }

    @Test
    fun `clampPanOffset bounds pan to half the overhanging scaled size`() {
        // At scale 2, a 400x300 box's content is 800x600 -- it overhangs the box by 400x300,
        // so the content can be dragged at most 200 (x) / 150 (y) in either direction before
        // its own edge would reveal empty space.
        val clamped = clampPanOffset(Offset(9999f, 9999f), Size(400f, 300f), scale = 2f)
        assertEquals(200f, clamped.x)
        assertEquals(150f, clamped.y)
    }

    @Test
    fun `clampPanOffset leaves an in-bounds offset unchanged`() {
        val clamped = clampPanOffset(Offset(50f, 30f), Size(400f, 300f), scale = 2f)
        assertEquals(Offset(50f, 30f), clamped)
    }
}
