package com.multiviewer.parser

import kotlin.test.Test
import kotlin.test.assertEquals
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorInfo
import org.jetbrains.skia.ColorSpace
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo

class JpegScanStatisticsTest {
    private fun bitmapOf(width: Int, height: Int, bgraBytes: ByteArray): Bitmap {
        return Bitmap().apply {
            allocPixels(ImageInfo(ColorInfo(ColorType.BGRA_8888, ColorAlphaType.PREMUL, ColorSpace.sRGB), width, height))
            installPixels(imageInfo, bgraBytes, width * 4)
        }
    }

    @Test
    fun `computeScanStatistics finds the average luminance and the single brightest pixel`() {
        // 2x2 image, row-major, BGRA bytes per pixel:
        //   (0,0) gray 30              (1,0) R=200 G=100 B=50 (brightest -- distinct channels
        //                                so a channel-order bug would be caught)
        //   (0,1) gray 60              (1,1) gray 90
        val bytes = byteArrayOf(
            30, 30, 30, 255.toByte(), // (0,0): B,G,R,A
            50, 100, 200.toByte(), 255.toByte(), // (1,0): B,G,R,A
            60, 60, 60, 255.toByte(), // (0,1)
            90, 90, 90, 255.toByte(), // (1,1)
        )
        val bitmap = bitmapOf(2, 2, bytes)

        val stats = computeScanStatistics(bitmap)

        // luminances: (0,0)=30, (1,0)=0.299*200+0.587*100+0.114*50=124.2, (0,1)=60, (1,1)=90
        // average = (30 + 124.2 + 60 + 90) / 4 = 76.05
        assertEquals(76.05, stats.averageLuminance, 0.001)
        assertEquals(1, stats.brightestX)
        assertEquals(0, stats.brightestY)
        assertEquals(200, stats.brightestR)
        assertEquals(100, stats.brightestG)
        assertEquals(50, stats.brightestB)
    }
}
