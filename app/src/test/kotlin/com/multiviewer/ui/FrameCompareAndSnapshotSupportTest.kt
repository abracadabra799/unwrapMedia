package com.multiviewer.ui

import androidx.compose.ui.graphics.toComposeImageBitmap
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FrameCompareAndSnapshotSupportTest {

    private val sampleFrames = listOf(
        FrameInfo(index = 0, type = 'I', sizeBytes = 20_000, ptsSeconds = 0.000, byteOffset = 0),
        FrameInfo(index = 1, type = 'P', sizeBytes = 5_000, ptsSeconds = 0.033, byteOffset = 20_000),
        FrameInfo(index = 2, type = 'B', sizeBytes = 2_000, ptsSeconds = 0.066, byteOffset = 25_000),
        FrameInfo(index = 3, type = 'I', sizeBytes = 22_000, ptsSeconds = 0.100, byteOffset = 27_000),
        FrameInfo(index = 4, type = 'P', sizeBytes = 6_000, ptsSeconds = 0.133, byteOffset = 49_000),
    )

    @Test
    fun resolveReferenceFrameForCompare_usesPinnedOrPreviousOrGopKeyframe() {
        val cur = sampleFrames[4]
        val pinned = sampleFrames[1]

        // 1. Pinned frame takes precedence in PINNED_OR_PREV mode
        val resolvedPinned = resolveReferenceFrameForCompare(
            frames = sampleFrames,
            currentFrame = cur,
            pinnedReferenceFrame = pinned,
            target = FrameReferenceTarget.PINNED_OR_PREV,
        )
        assertEquals(1, resolvedPinned?.index)

        // 2. Falls back to N-1 when nothing is pinned
        val resolvedFallbackPrev = resolveReferenceFrameForCompare(
            frames = sampleFrames,
            currentFrame = cur,
            pinnedReferenceFrame = null,
            target = FrameReferenceTarget.PINNED_OR_PREV,
        )
        assertEquals(3, resolvedFallbackPrev?.index)

        // 3. PREVIOUS_FRAME always picks N-1 even if a pin exists
        val resolvedPrev = resolveReferenceFrameForCompare(
            frames = sampleFrames,
            currentFrame = cur,
            pinnedReferenceFrame = pinned,
            target = FrameReferenceTarget.PREVIOUS_FRAME,
        )
        assertEquals(3, resolvedPrev?.index)

        // 4. GOP_KEYFRAME resolves to the start I-frame of the current GOP (#3 for #4, #0 for #2)
        val resolvedGopKeyFor4 = resolveReferenceFrameForCompare(
            frames = sampleFrames,
            currentFrame = sampleFrames[4],
            pinnedReferenceFrame = pinned,
            target = FrameReferenceTarget.GOP_KEYFRAME,
        )
        assertEquals(3, resolvedGopKeyFor4?.index)

        val resolvedGopKeyFor2 = resolveReferenceFrameForCompare(
            frames = sampleFrames,
            currentFrame = sampleFrames[2],
            pinnedReferenceFrame = pinned,
            target = FrameReferenceTarget.GOP_KEYFRAME,
        )
        assertEquals(0, resolvedGopKeyFor2?.index)
    }

    @Test
    fun computeFramePixelDiffStats_detectsIdenticalAndChangedPixels() {
        val imgA = BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB)
        val imgB = BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until 4) {
            for (x in 0 until 4) {
                imgA.setRGB(x, y, 0x204060)
                imgB.setRGB(x, y, 0x204060)
            }
        }
        val identicalStats = computeFramePixelDiffStats(
            imgA.toComposeImageBitmap(),
            imgB.toComposeImageBitmap(),
        )
        assertNotNull(identicalStats)
        assertEquals(0.0, identicalStats.meanAbsDiff, 1e-6)
        assertEquals(0, identicalStats.maxAbsDiff)
        assertEquals(0.0, identicalStats.changedPixelPercent, 1e-6)
        assertTrue(identicalStats.psnrDb >= 99.0)

        // Modify 4 out of 16 pixels (25%) by +30 on R channel
        for (x in 0 until 4) {
            imgB.setRGB(x, 0, 0x3E4060) // 0x20 (32) -> 0x3E (62), delta = 30
        }
        val diffStats = computeFramePixelDiffStats(
            imgA.toComposeImageBitmap(),
            imgB.toComposeImageBitmap(),
        )
        assertNotNull(diffStats)
        assertEquals(30, diffStats.maxAbsDiff)
        assertEquals(25.0, diffStats.changedPixelPercent, 1e-5)
        assertTrue(diffStats.meanAbsDiff > 0.0)
        assertTrue(diffStats.psnrDb in 20.0..60.0)
    }

    @Test
    fun imageBitmapToRgbBufferedImage_producesOpaqueRgbImageWithOptionalBadge() {
        val src = BufferedImage(160, 80, BufferedImage.TYPE_INT_ARGB)
        src.setRGB(140, 70, (0xFF shl 24) or 0x123456)
        val out = imageBitmapToRgbBufferedImage(src.toComposeImageBitmap(), "#12 (I) · 0.400s")
        assertEquals(BufferedImage.TYPE_INT_RGB, out.type)
        assertEquals(160, out.width)
        assertEquals(80, out.height)
        // Pixel away from top-left badge preserves RGB channels
        assertEquals(0x123456, out.getRGB(140, 70) and 0xFFFFFF)
    }
}
