package com.multiviewer.ui.ai

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AiVisualContextTest {
    // 400x200 image in a 400x400 box: letterboxed 100 px top and bottom.
    private val box = Size(400f, 400f)
    private val native = Size(400f, 200f)

    @Test
    fun `letterbox bars are outside the image`() {
        assertNull(viewToImagePoint(Offset(200f, 50f), box, native, 1f, Offset.Zero))
        assertEquals(ImagePoint(200, 0), viewToImagePoint(Offset(200f, 100f), box, native, 1f, Offset.Zero))
        assertEquals(ImagePoint(399, 199), viewToImagePoint(Offset(399.9f, 299.9f), box, native, 1f, Offset.Zero))
        assertNull(viewToImagePoint(Offset(-1f, 150f), box, native, 1f, Offset.Zero))
        assertNull(viewToImagePoint(Offset(200f, 300f), box, native, 1f, Offset.Zero))
    }

    @Test
    fun `zoom and pan map back to native pixels`() {
        // 2x zoom with the top-left pivot, panned so the image's (100, 50) native point sits at the box origin.
        val offset = Offset(-200f, -300f) // layer x' = offset + 2*x; letterbox top = 100 → content y 100+50=150 → 300
        assertEquals(ImagePoint(100, 50), viewToImagePoint(Offset(0f, 0f), box, native, 2f, offset))
        val visible = visibleImageRegion(box, native, 2f, offset)
        assertEquals(ImageRegion(100, 50, 200, 150), visible)
    }

    @Test
    fun `hidpi pixels map to the same image point`() {
        val at1x = viewToImagePoint(Offset(123f, 150f), box, native, 1f, Offset.Zero)
        val at2x = viewToImagePoint(Offset(246f, 300f), Size(800f, 800f), native, 1f, Offset.Zero)
        assertEquals(at1x, at2x)
    }

    @Test
    fun `a marked rectangle is clamped to the image and empty marks are dropped`() {
        val region = viewRectToImageRegion(Offset(-50f, 50f), Offset(100f, 200f), box, native, 1f, Offset.Zero)
        assertEquals(ImageRegion(0, 0, 100, 100), region)
        assertNull(viewRectToImageRegion(Offset(10f, 10f), Offset(390f, 90f), box, native, 1f, Offset.Zero)) // all in the bar
        assertNull(viewRectToImageRegion(Offset(10f, 150f), Offset(10.2f, 150.2f), box, native, 1f, Offset.Zero))
    }

    @Test
    fun `the crop is bounded, opaque and carries no metadata`() {
        val source = BufferedImage(4000, 3000, BufferedImage.TYPE_INT_ARGB)
        val crop = cropForAi(source, ImageRegion(100, 100, 3000, 1500))
        assertTrue(crop.width <= MAX_AI_CROP_EDGE && crop.height <= MAX_AI_CROP_EDGE, "${crop.width}x${crop.height}")
        assertEquals(2.0, crop.width.toDouble() / crop.height, 0.01)
        assertEquals(BufferedImage.TYPE_INT_RGB, crop.type)
        val png = ByteArrayOutputStream().also { ImageIO.write(crop, "png", it) }.toString(Charsets.ISO_8859_1)
        assertTrue("Exif" !in png && "GPS" !in png && "tEXt" !in png && "iTXt" !in png)
    }

    @Test
    fun `visual context renders coordinates and states when no image is attached`() {
        val visual = AiVisualContext("primary image", 400, 200, ImageRegion(0, 0, 400, 200), ImagePoint(10, 20), ImageRegion(5, 6, 7, 8))
        val text = renderVisualContext(visual, attachedImage = null, imageOmittedReason = "image input not verified for Claude Code")
        assertTrue(text.contains("(10, 20)"), text)
        assertTrue(text.contains("x=5, y=6, w=7, h=8"), text)
        assertTrue(text.contains("image input not verified"), text)
    }
}
