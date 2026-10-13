package com.multiviewer.parser

import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GainmapMathTest {
    private val s26 = GainmapParsedParameters(
        gainMapMin = 0.0, gainMapMax = 2.3, gamma = 1.0, offsetSdr = 0.0, offsetHdr = 0.0,
        hdrCapacityMin = 0.0, hdrCapacityMax = 2.3, baseRenditionIsHdr = false,
    )

    @Test
    fun `sRGB transfer round-trips and hits the known knee values`() {
        for (v in listOf(0.0, 0.02, 0.04045, 0.2, 0.5, 1.0)) {
            assertEquals(v, GainmapMath.linearToSrgb(GainmapMath.srgbToLinear(v)), 1e-6) // the standard's two pieces meet only to ~3e-8 at the knee
        }
        assertEquals(0.2140, GainmapMath.srgbToLinear(0.5), 1e-4)
    }

    @Test
    fun `log boost interpolates between min and max through gamma`() {
        assertEquals(0.0, GainmapMath.logBoost(0.0, s26), 1e-12)
        assertEquals(2.3, GainmapMath.logBoost(1.0, s26), 1e-12)
        assertEquals(1.15, GainmapMath.logBoost(0.5, s26), 1e-12)
        val gamma2 = s26.copy(gamma = 2.0)
        assertEquals(2.3 * 0.5.pow(0.5), GainmapMath.logBoost(0.5, gamma2), 1e-12)
    }

    @Test
    fun `weight follows the display headroom between capacity min and max`() {
        assertEquals(0.0, GainmapMath.weight(0.0, s26), 1e-12)  // SDR display: no boost
        assertEquals(0.5, GainmapMath.weight(1.15, s26), 1e-12)
        assertEquals(1.0, GainmapMath.weight(2.3, s26), 1e-12)
        assertEquals(1.0, GainmapMath.weight(5.0, s26), 1e-12)  // clamped
        // A base that is already HDR applies the map in the opposite direction.
        assertEquals(1.0, GainmapMath.weight(0.0, s26.copy(baseRenditionIsHdr = true)), 1e-12)
        // Degenerate capacity range: all-or-nothing at capacity max.
        val flat = s26.copy(hdrCapacityMin = 1.0, hdrCapacityMax = 1.0)
        assertEquals(0.0, GainmapMath.weight(0.5, flat))
        assertEquals(1.0, GainmapMath.weight(1.0, flat))
    }

    @Test
    fun `applyGain uses the offsets in linear light`() {
        assertEquals(0.5 * 2.0.pow(2.3), GainmapMath.applyGain(0.5, logBoost = 2.3, weight = 1.0, s26), 1e-12)
        val withOffsets = s26.copy(offsetSdr = 1.0 / 64, offsetHdr = 1.0 / 64)
        assertEquals((0.5 + 1.0 / 64) * 2.0 - 1.0 / 64, GainmapMath.applyGain(0.5, 1.0, 1.0, withOffsets), 1e-12)
        assertEquals(0.5, GainmapMath.applyGain(0.5, 2.3, 0.0, s26), 1e-12)
    }

    private fun rgba(vararg px: Int): ByteArray = ByteArray(px.size * 4).also { out ->
        px.forEachIndexed { i, v -> out[i * 4] = v.toByte(); out[i * 4 + 1] = v.toByte(); out[i * 4 + 2] = v.toByte(); out[i * 4 + 3] = 255.toByte() }
    }

    @Test
    fun `HDR simulation at headroom 1 leaves the base untouched`() {
        val base = rgba(0, 128, 255)
        val out = renderHdrPreview(base, rgba(255, 255, 255), 3, 1, s26, headroomLog2 = 0.0, mode = HdrPreviewMode.HDR_SIMULATION)
        for (i in 0 until 3) assertEquals(base[i * 4].toInt() and 0xFF, out[i * 4].toInt() and 0xFF)
    }

    @Test
    fun `full headroom maps boosted pixels to peak and SDR white to 1 over H`() {
        // Pixel 0: SDR white, gain 1 -> boosted by 2^2.3 = H -> exactly the display peak.
        // Pixel 1: SDR white, gain 0 -> stays at SDR white = 1/H of the peak.
        val out = renderHdrPreview(rgba(255, 255), rgba(255, 0), 2, 1, s26, headroomLog2 = 2.3, mode = HdrPreviewMode.HDR_SIMULATION)
        assertEquals(255, out[0].toInt() and 0xFF)
        val expected = (GainmapMath.linearToSrgb(1.0 / 2.0.pow(2.3)) * 255).let { Math.round(it).toInt() }
        assertEquals(expected, out[4].toInt() and 0xFF)
    }

    @Test
    fun `boost heat map gets hotter with more boost`() {
        val out = renderHdrPreview(rgba(128, 128), rgba(0, 255), 2, 1, s26, headroomLog2 = 2.3, mode = HdrPreviewMode.BOOST_HEATMAP)
        val cold = out.copyOfRange(0, 3).map { it.toInt() and 0xFF }
        val hot = out.copyOfRange(4, 7).map { it.toInt() and 0xFF }
        assertTrue(hot[0] > cold[0], "red should rise with boost: $cold -> $hot")
        assertTrue(cold[2] >= hot[2], "blue should fall with boost: $cold -> $hot")
    }

    @Test
    fun `stops at a pixel is log boost times weight`() {
        assertEquals(2.3, GainmapMath.appliedStops(1.0, s26, headroomLog2 = 2.3), 1e-12)
        assertEquals(1.15, GainmapMath.appliedStops(1.0, s26, headroomLog2 = 1.15), 1e-12)
    }
}
