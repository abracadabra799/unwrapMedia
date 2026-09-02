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

    // The content is drawn with transformOrigin = TransformOrigin(0f, 0f) (see the graphicsLayer
    // calls in PixelInspectorPreview/ImageCompareWindow), so a layer-local x lands on screen at
    // offset.x + scale*x and the content spans [offset.x, offset.x + scale*W]. Keeping that span
    // over the box [0, W] means offset.x is bounded by -(scale-1)*W .. 0 -- NOT the symmetric
    // ±(scale-1)*W/2 that a center pivot would give.
    @Test
    fun `clampPanOffset allows dragging left by the full overhang so the content's right edge is reachable`() {
        // At scale 2 a 400x300 box's content is 800x600, overhanging by 400x300. Dragging left must
        // be allowed all the way to -400 / -300: that is what puts the content's right/bottom edge
        // at the box's right/bottom edge. Stopping at half (-200 / -150) strands the right quarter
        // of the image off-screen with no way to reach it.
        val clamped = clampPanOffset(Offset(-9999f, -9999f), Size(400f, 300f), scale = 2f)
        assertEquals(-400f, clamped.x)
        assertEquals(-300f, clamped.y)
    }

    @Test
    fun `clampPanOffset never allows a positive offset, which would reveal empty space before the content`() {
        val clamped = clampPanOffset(Offset(9999f, 9999f), Size(400f, 300f), scale = 2f)
        assertEquals(0f, clamped.x)
        assertEquals(0f, clamped.y)
    }

    // The far corner of the image: at scale 2 the box's right edge shows content coordinate
    // (boxWidth - offset.x) / scale. Fully panned left that is (400 + 400) / 2 = 400 -- the
    // content's own width, i.e. its very right edge. Anything less leaves part of the image
    // permanently unreachable, which is the bug this guards.
    @Test
    fun `clampPanOffset lets the pan reach the content's far edge exactly`() {
        val boxSize = Size(400f, 300f)
        val scale = 2f
        val clamped = clampPanOffset(Offset(-9999f, -9999f), boxSize, scale)

        val rightmostVisibleContentX = (boxSize.width - clamped.x) / scale
        val bottommostVisibleContentY = (boxSize.height - clamped.y) / scale
        assertEquals(boxSize.width, rightmostVisibleContentX)
        assertEquals(boxSize.height, bottommostVisibleContentY)
    }

    @Test
    fun `clampPanOffset leaves an in-bounds offset unchanged`() {
        val clamped = clampPanOffset(Offset(-50f, -30f), Size(400f, 300f), scale = 2f)
        assertEquals(Offset(-50f, -30f), clamped)
    }

    @Test
    fun `panToPoint re-centers the box on the tapped content point`() {
        // scale 2, box 400x300, no existing pan: the content point under (300f, 200f) is
        // ((300-0)/2, (200-0)/2) = (150, 100). Re-centering means that content point should end
        // up at the box's center (200, 150), so offset = center - contentPoint*scale.
        val newOffset = panToPoint(offset = Offset.Zero, boxSize = Size(400f, 300f), scale = 2f, tapPosition = Offset(300f, 200f))
        assertEquals(200f - 150f * 2f, newOffset.x)
        assertEquals(150f - 100f * 2f, newOffset.y)
    }

    @Test
    fun `panToPoint result is clamped to the same pan bounds as dragging`() {
        // Already panned fully left/up (-400, -300, the scale-2 bound for this box), then tapping
        // the box's bottom-right corner asks to re-center on the content's own far corner --
        // raw result 200 - 2*400 = -600 (x) and 150 - 2*300 = -450 (y), overshooting on both axes,
        // so it must come back to exactly clampPanOffset's bound.
        val newOffset = panToPoint(offset = Offset(-400f, -300f), boxSize = Size(400f, 300f), scale = 2f, tapPosition = Offset(400f, 300f))
        assertEquals(-400f, newOffset.x)
        assertEquals(-300f, newOffset.y)
    }

    // A tall image in a wide box is letterboxed by ContentScale.Fit: it only covers a narrow
    // vertical strip of the box-sized layer, with black bars either side. Clamping the *layer* to
    // the box still lets that strip slide far out of view horizontally, while vertically (where the
    // image fills the layer edge to edge) it cannot -- the asymmetry the user hit. These bound the
    // drawn image instead, so left/right behaves like up/down.
    //
    // 2252x4000 fitted into a 1000x715 box: fitScale = 715/4000, so the image is drawn
    // 402.6 x 715 -- full height, but only ~40% of the width.
    private val tallNative = Size(2252f, 4000f)
    private val wideBox = Size(1000f, 715f)

    @Test
    fun `fittedContentSize matches what ContentScale Fit draws`() {
        val fitted = fittedContentSize(wideBox, tallNative)
        assertEquals(715f, fitted.height)
        assertTrue(kotlin.math.abs(fitted.width - 2252f * (715f / 4000f)) < 0.01f, "got ${fitted.width}")
    }

    @Test
    fun `clampPanOffset keeps a letterboxed image centered while it is still narrower than the box`() {
        val fitted = fittedContentSize(wideBox, tallNative)
        // At scale 2 the drawn width is still only ~805 of the box's 1000, so there is nothing to
        // pan to horizontally -- the image must stay centered no matter how hard it is dragged.
        val draggedLeft = clampPanOffset(Offset(-9999f, 0f), wideBox, scale = 2f, contentSize = fitted)
        val draggedRight = clampPanOffset(Offset(9999f, 0f), wideBox, scale = 2f, contentSize = fitted)

        assertEquals(draggedLeft.x, draggedRight.x)
        val imageLeftOnScreen = draggedLeft.x + 2f * (wideBox.width - fitted.width) / 2f
        val imageRightOnScreen = imageLeftOnScreen + 2f * fitted.width
        assertTrue(imageLeftOnScreen > 0f, "image's left edge should stay inside the box, got $imageLeftOnScreen")
        assertTrue(imageRightOnScreen < wideBox.width, "image's right edge should stay inside the box, got $imageRightOnScreen")
    }

    @Test
    fun `clampPanOffset never lets a letterboxed image be dragged out of view`() {
        val fitted = fittedContentSize(wideBox, tallNative)
        // Scale 3: the drawn width (~1208) now exceeds the box, so panning is possible -- but at
        // both extremes the image must still cover the box edge to edge, never leaving blank space.
        for (raw in listOf(Offset(-9999f, 0f), Offset(9999f, 0f))) {
            val clamped = clampPanOffset(raw, wideBox, scale = 3f, contentSize = fitted)
            val imageLeftOnScreen = clamped.x + 3f * (wideBox.width - fitted.width) / 2f
            val imageRightOnScreen = imageLeftOnScreen + 3f * fitted.width
            assertTrue(imageLeftOnScreen <= 0.01f, "blank space before the image at $raw: left=$imageLeftOnScreen")
            assertTrue(imageRightOnScreen >= wideBox.width - 0.01f, "blank space after the image at $raw: right=$imageRightOnScreen")
        }
    }

    @Test
    fun `clampPanOffset lets a letterboxed image reach its own right edge`() {
        val fitted = fittedContentSize(wideBox, tallNative)
        val scale = 3f
        val clamped = clampPanOffset(Offset(-9999f, 0f), wideBox, scale, contentSize = fitted)

        // Image-local coordinate visible at the box's right edge, fully panned left.
        val letterbox = scale * (wideBox.width - fitted.width) / 2f
        val rightmostVisible = (wideBox.width - (clamped.x + letterbox)) / scale
        assertTrue(kotlin.math.abs(rightmostVisible - fitted.width) < 0.01f, "expected ${fitted.width}, got $rightmostVisible")
    }

    @Test
    fun `clampPanOffset is still zero at scale 1 for a letterboxed image`() {
        val fitted = fittedContentSize(wideBox, tallNative)
        val clamped = clampPanOffset(Offset(500f, 500f), wideBox, scale = 1f, contentSize = fitted)
        assertEquals(Offset.Zero, clamped)
    }

    @Test
    fun `panToPoint is always zero at scale 1`() {
        val newOffset = panToPoint(offset = Offset.Zero, boxSize = Size(400f, 300f), scale = 1f, tapPosition = Offset(300f, 200f))
        assertEquals(Offset.Zero, newOffset)
    }
}
