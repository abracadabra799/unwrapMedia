package com.multiviewer.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import kotlin.test.Test
import kotlin.test.assertEquals

class ImageCompareZoomTest {
    @Test
    fun `screenPointToNativePixel at 1x zoom with no letterbox maps the box center to the native center`() {
        val result = screenPointToNativePixel(
            pointerPos = Offset(50f, 50f),
            boxSize = Size(100f, 100f),
            nativeSize = Size(100f, 100f),
            scale = 1f,
            offset = Offset.Zero,
        )
        assertEquals(50 to 50, result)
    }

    @Test
    fun `screenPointToNativePixel accounts for letterboxing on a wider-than-box image`() {
        // box=100x100, native=200x100 (2:1) -- fitScale=min(100/200, 100/100)=0.5, so the image
        // draws at 100x50 within the 100x100 box, letterboxed by 25px top and bottom.
        val boxSize = Size(100f, 100f)
        val nativeSize = Size(200f, 100f)

        // Center of the box is also the center of the letterboxed image -> native center (100, 50).
        val center = screenPointToNativePixel(Offset(50f, 50f), boxSize, nativeSize, scale = 1f, offset = Offset.Zero)
        assertEquals(100 to 50, center)

        // y=10 falls inside the top letterbox bar (image starts at y=25) -> out of bounds.
        val inLetterbox = screenPointToNativePixel(Offset(50f, 10f), boxSize, nativeSize, scale = 1f, offset = Offset.Zero)
        assertEquals(null, inLetterbox)
    }

    @Test
    fun `screenPointToNativePixel accounts for zoom and pan`() {
        // Same letterboxed 200x100-in-100x100 setup as above, now zoomed 2x with an arbitrary pan.
        // Hand-derived and round-tripped forward through the same transform to confirm: a native
        // point (110, 15) maps forward to screen (60, 40) at scale=2, offset=(-50,-25) via
        // screenX = offset.x + scale*(letterboxX0 + nativeX*fitScale), and this test checks the
        // inverse direction.
        val result = screenPointToNativePixel(
            pointerPos = Offset(60f, 40f),
            boxSize = Size(100f, 100f),
            nativeSize = Size(200f, 100f),
            scale = 2f,
            offset = Offset(-50f, -25f),
        )
        assertEquals(110 to 15, result)
    }
}
