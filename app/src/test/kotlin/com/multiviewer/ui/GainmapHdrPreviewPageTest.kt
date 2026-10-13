package com.multiviewer.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.IntSize
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class GainmapHdrPreviewPageTest {
    @Test
    fun `resampling keeps RGBA order and scales`() {
        // 2x1: red, blue.
        val src = pixelBufferToImageBitmap(2, 1, byteArrayOf(-1, 0, 0, -1, 0, 0, -1, -1), ColorType.RGBA_8888, ColorAlphaType.UNPREMUL)
        val same = assertNotNull(src.resampledRgba(2, 1))
        assertEquals(listOf(255, 0, 0, 255, 0, 0, 255, 255), same.map { it.toInt() and 0xFF })
        val up = assertNotNull(src.resampledRgba(8, 2))
        assertEquals(8 * 2 * 4, up.size)
        assertEquals(255, up[0].toInt() and 0xFF) // left edge stays red
        assertEquals(255, up[(8 - 1) * 4 + 2].toInt() and 0xFF) // right edge stays blue
    }

    @Test
    fun `pointer maps through a letterboxed fit`() {
        // 100x50 image in a 200x200 box: scale 2, 50px bars top and bottom.
        assertNull(pixelUnder(Offset(10f, 20f), IntSize(200, 200), 100, 50))
        assertEquals(0 to 0, pixelUnder(Offset(0f, 50f), IntSize(200, 200), 100, 50))
        assertEquals(99 to 49, pixelUnder(Offset(199f, 149f), IntSize(200, 200), 100, 50))
    }
}

class GainmapHdrPreviewRealFileTest {
    // The real S26 photo through the same path the page uses: Skia-decoded base + gain map image,
    // resampled to the preview size, rendered. At H = 1 the output must equal the base; at full
    // headroom only boosted highlights may reach peak while the overall image dims (SDR white -> 1/H).
    @Test
    fun `S26 photo renders sanely across the headroom range`() {
        val file = java.io.File("src/test/resources/c2pa/s26-camera.jpg")
        val info = com.multiviewer.parser.GainmapParser.findGainmapInfo(file, com.multiviewer.parser.parseFile(file))!!
        val bytes = file.readBytes()
        val base = org.jetbrains.skia.Image.makeFromEncoded(bytes).use { it.toComposeImageBitmap() }
        val gainBytes = bytes.copyOfRange(info.byteOffset!!.toInt(), (info.byteOffset!! + info.byteLength!!).toInt())
        val gain = org.jetbrains.skia.Image.makeFromEncoded(gainBytes).use { it.toComposeImageBitmap() }
        val w = 400; val h = 300
        val b = base.resampledRgba(w, h)!!
        val g = gain.resampledRgba(w, h)!!
        val p = info.parameters!!
        fun stats(out: ByteArray): Pair<Double, Int> {
            var sum = 0L; var max = 0
            for (i in 0 until w * h) { val v = out[i * 4 + 1].toInt() and 0xFF; sum += v; if (v > max) max = v }
            return sum.toDouble() / (w * h) to max
        }
        // Independent expectation: this photo's gain map tops out at 163/255, so the peak is set by
        // the brightest base*boost product, not by a fixed "reaches white" threshold.
        var expectedPeak = 0.0
        for (i in 0 until w * h) {
            val bv = b[i * 4 + 1].toInt() and 0xFF
            val gv = g[i * 4 + 1].toInt() and 0xFF
            val lin = com.multiviewer.parser.GainmapMath.srgbToLinear(bv / 255.0) *
                Math.pow(2.0, p.gainMapMax!! * gv / 255.0) / Math.pow(2.0, p.hdrCapacityMax!!)
            if (lin > expectedPeak) expectedPeak = lin
        }
        val expectedPeak8 = Math.round(com.multiviewer.parser.GainmapMath.linearToSrgb(minOf(1.0, expectedPeak)) * 255).toInt()
        val atOne = com.multiviewer.parser.renderHdrPreview(b, g, w, h, p, 0.0, com.multiviewer.parser.HdrPreviewMode.HDR_SIMULATION)
        assertEquals(stats(b).first, stats(atOne).first, 0.6) // rounding only
        val atMax = com.multiviewer.parser.renderHdrPreview(b, g, w, h, p, p.hdrCapacityMax!!, com.multiviewer.parser.HdrPreviewMode.HDR_SIMULATION)
        val (meanMax, peakMax) = stats(atMax)
        kotlin.test.assertTrue(meanMax < stats(b).first, "full headroom should dim the SDR range: $meanMax vs ${stats(b).first}")
        assertEquals(expectedPeak8, peakMax)
        println("S26 HDR preview: base mean=${stats(b).first} max=${stats(b).second}; H=max mean=$meanMax peak=$peakMax")
    }
}

class GainmapCodedSizeTest {
    @Test
    fun `rotated orientations swap back to the coded size`() {
        assertEquals(4000 to 3000, codedSize(3000, 4000, 6)) // S26 portrait: shown 3000x4000, coded 4000x3000
        assertEquals(4000 to 3000, codedSize(3000, 4000, 8))
        assertEquals(4000 to 3000, codedSize(4000, 3000, 1))
        assertEquals(4000 to 3000, codedSize(4000, 3000, 3)) // 180 degrees keeps the axes
        assertEquals(4000 to 3000, codedSize(4000, 3000, null))
    }
}
