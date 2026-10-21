package com.multiviewer.ui

import androidx.compose.ui.graphics.toComposeImageBitmap
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FastStoneCompareEnhancementsTest {

    @Test
    fun computeLightweightHistogram_extractsBinsAndClippingRatios() {
        val img = BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until 16) {
            for (x in 0 until 16) {
                val color = if (y < 8) 0x000000 else 0xFFFFFF
                img.setRGB(x, y, color)
            }
        }
        val hist = computeLightweightHistogram(img.toComposeImageBitmap(), step = 1)
        assertNotNull(hist)
        assertEquals(256, hist.lumaBins.size)
        assertEquals(50.0, hist.clipBlackPercent, 1e-4)
        assertEquals(50.0, hist.clipWhitePercent, 1e-4)
        assertTrue(hist.meanLuma in 120.0..135.0)
    }

    @Test
    fun summarizeKeyMetadataDiffs_filtersOutIdenticalAndTrivialFilenameRows() {
        val rows = listOf(
            MetadataDiffRow("File", "File Name", "a.mp4", "b.mp4", isDifferent = true),
            MetadataDiffRow("Video", "Codec", "HEVC", "HEVC", isDifferent = false),
            MetadataDiffRow("Video", "Resolution", "3840x2160", "1920x1080", isDifferent = true),
            MetadataDiffRow("Video", "Bitrate", "25.0 Mbps", "12.0 Mbps", isDifferent = true),
        )
        val summary = summarizeKeyMetadataDiffs(rows)
        assertEquals(2, summary.size)
        assertEquals("Resolution", summary[0].key)
        assertEquals("Bitrate", summary[1].key)
    }

    @Test
    fun renderComparisonSnapshotBufferedImage_producesOpaqueCompositeImages() {
        val bmA = BufferedImage(60, 40, BufferedImage.TYPE_INT_RGB).apply {
            setRGB(10, 10, 0xFF0000)
        }.toComposeImageBitmap()
        val bmB = BufferedImage(60, 40, BufferedImage.TYPE_INT_RGB).apply {
            setRGB(10, 10, 0x00FF00)
        }.toComposeImageBitmap()

        val sideBySide = renderComparisonSnapshotBufferedImage(
            bitmapA = bmA,
            bitmapB = bmB,
            mode = VisualCompareMode.SIDE_BY_SIDE,
            wiperPos = 0.5f,
            diffBitmap = null,
            labelA = "A.jpg",
            labelB = "B.jpg",
        )
        assertEquals(BufferedImage.TYPE_INT_RGB, sideBySide.type)
        assertTrue(sideBySide.width >= 120)
        assertTrue(sideBySide.height > 40)

        val wiper = renderComparisonSnapshotBufferedImage(
            bitmapA = bmA,
            bitmapB = bmB,
            mode = VisualCompareMode.SPLIT_WIPER,
            wiperPos = 0.5f,
            diffBitmap = null,
            labelA = "A.jpg",
            labelB = "B.jpg",
        )
        assertEquals(BufferedImage.TYPE_INT_RGB, wiper.type)
        assertEquals(60, wiper.width)
    }
}
