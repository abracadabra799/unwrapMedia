package com.multiviewer.parser

import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Gain map application (ISO 21496-1 / Ultra HDR), in hdrgm units: gain map min/max and HDR capacity
 * are log2, offsets linear. Missing parameters fall back to the Ultra HDR defaults.
 *
 *   logBoost = lerp(min, max, g^(1/gamma))
 *   w        = clamp((log2 H - capMin) / (capMax - capMin), 0, 1)   (1 - w when the base is HDR)
 *   hdr      = (sdr + offsetSdr) * 2^(logBoost * w) - offsetHdr       (linear light)
 */
object GainmapMath {
    private const val DEFAULT_OFFSET = 1.0 / 64

    fun srgbToLinear(v: Double): Double = if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)

    fun linearToSrgb(v: Double): Double = if (v <= 0.0031308) v * 12.92 else 1.055 * v.pow(1.0 / 2.4) - 0.055

    fun logBoost(gain: Double, p: GainmapParsedParameters): Double {
        val min = p.gainMapMin ?: 0.0
        val max = p.gainMapMax ?: 0.0
        val gamma = p.gamma?.takeIf { it > 0.0 } ?: 1.0
        val g = gain.coerceIn(0.0, 1.0).pow(1.0 / gamma)
        return min + (max - min) * g
    }

    fun weight(headroomLog2: Double, p: GainmapParsedParameters): Double {
        val capMin = p.hdrCapacityMin ?: 0.0
        val capMax = p.hdrCapacityMax ?: p.gainMapMax ?: 0.0
        val w = if (capMax <= capMin) {
            if (headroomLog2 >= capMax) 1.0 else 0.0
        } else {
            ((headroomLog2 - capMin) / (capMax - capMin)).coerceIn(0.0, 1.0)
        }
        return if (p.baseRenditionIsHdr == true) 1.0 - w else w
    }

    fun applyGain(baseLinear: Double, logBoost: Double, weight: Double, p: GainmapParsedParameters): Double {
        val offSdr = p.offsetSdr ?: DEFAULT_OFFSET
        val offHdr = p.offsetHdr ?: DEFAULT_OFFSET
        return (baseLinear + offSdr) * 2.0.pow(logBoost * weight) - offHdr
    }

    /** Stops of boost a gain map value of [gain] receives on a display with [headroomLog2]. */
    fun appliedStops(gain: Double, p: GainmapParsedParameters, headroomLog2: Double): Double =
        logBoost(gain, p) * weight(headroomLog2, p)
}

enum class HdrPreviewMode { HDR_SIMULATION, BOOST_HEATMAP, SDR_BASE }

/**
 * Renders [base] (sRGB RGBA, unpremultiplied) with [gain] (RGBA, same size) applied for a display whose
 * headroom is 2^[headroomLog2], returning RGBA bytes. The screen this runs on is SDR, so the HDR
 * simulation shows the result relative to that display's peak: SDR white lands at 1/H and only fully
 * boosted highlights reach full brightness. Gain map channels are used per colour channel (a
 * single-channel map decodes to R = G = B). The base gamut is ignored (treated as sRGB).
 */
fun renderHdrPreview(
    base: ByteArray,
    gain: ByteArray,
    width: Int,
    height: Int,
    params: GainmapParsedParameters,
    headroomLog2: Double,
    mode: HdrPreviewMode,
): ByteArray {
    val out = ByteArray(width * height * 4)
    val peak = 2.0.pow(headroomLog2)
    val w = GainmapMath.weight(headroomLog2, params)
    val maxStops = maxOf(params.gainMapMax ?: 0.0, 1e-6)
    // 256-entry tables: sRGB decode and per-gain-value boost multiplier never change within a frame.
    val toLinear = DoubleArray(256) { GainmapMath.srgbToLinear(it / 255.0) }
    // Per gain value, the multiplier 2^(logBoost * w) -- the pow leaves the per-pixel loop.
    val multiplier = DoubleArray(256) { 2.0.pow(GainmapMath.logBoost(it / 255.0, params) * w) }
    val offSdr = params.offsetSdr ?: 1.0 / 64
    val offHdr = params.offsetHdr ?: 1.0 / 64
    for (i in 0 until width * height) {
        val o = i * 4
        when (mode) {
            HdrPreviewMode.SDR_BASE -> {
                out[o] = base[o]; out[o + 1] = base[o + 1]; out[o + 2] = base[o + 2]
            }
            HdrPreviewMode.HDR_SIMULATION -> for (c in 0 until 3) {
                val lin = toLinear[base[o + c].toInt() and 0xFF]
                val hdr = (lin + offSdr) * multiplier[gain[o + c].toInt() and 0xFF] - offHdr
                val display = (hdr / peak).coerceIn(0.0, 1.0)
                out[o + c] = (GainmapMath.linearToSrgb(display) * 255.0).roundToInt().coerceIn(0, 255).toByte()
            }
            HdrPreviewMode.BOOST_HEATMAP -> {
                val g = (gain[o].toInt() and 0xFF) + (gain[o + 1].toInt() and 0xFF) + (gain[o + 2].toInt() and 0xFF)
                val stops = GainmapMath.logBoost(g / (3.0 * 255.0), params) * w
                val (r, gr, b) = heatColor((stops / maxStops).coerceIn(0.0, 1.0))
                out[o] = r.toByte(); out[o + 1] = gr.toByte(); out[o + 2] = b.toByte()
            }
        }
        out[o + 3] = 255.toByte()
    }
    return out
}

// Blue -> cyan -> green -> yellow -> red.
private fun heatColor(t: Double): Triple<Int, Int, Int> {
    val stops = arrayOf(intArrayOf(20, 30, 140), intArrayOf(0, 170, 220), intArrayOf(40, 200, 60), intArrayOf(250, 220, 30), intArrayOf(230, 40, 30))
    val x = t * (stops.size - 1)
    val i = x.toInt().coerceAtMost(stops.size - 2)
    val f = x - i
    fun mix(a: Int, b: Int) = (a + (b - a) * f).roundToInt()
    return Triple(mix(stops[i][0], stops[i + 1][0]), mix(stops[i][1], stops[i + 1][1]), mix(stops[i][2], stops[i + 1][2]))
}
