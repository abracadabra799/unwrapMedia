package com.multiviewer.parser

import org.jetbrains.skia.Bitmap

data class ScanStatistics(
    val averageLuminance: Double,
    val brightestX: Int,
    val brightestY: Int,
    val brightestR: Int,
    val brightestG: Int,
    val brightestB: Int,
)

// Whole-image pixel statistics computed from an already-decoded bitmap (the same one the preview
// panel and ImageAnalyzer.calculateHistogram already use) -- benchmarked against JPEGsnoop's
// "Decoding SCAN Data" section, but reusing Skia's real decode instead of implementing our own
// JPEG entropy/IDCT decoder. Deliberately does NOT reproduce JPEGsnoop's YCC/RGB "clipping"
// statistics -- those measure quantization-coefficient overflow in JPEGsnoop's own simplified
// DC-only decoder, which has no equivalent here (see the design spec's Non-Goals section).
fun computeScanStatistics(bitmap: Bitmap): ScanStatistics {
    val width = bitmap.width
    val height = bitmap.height
    var sumLuminance = 0.0
    var maxLuminance = -1.0
    var brightestX = 0
    var brightestY = 0
    var brightestR = 0
    var brightestG = 0
    var brightestB = 0
    for (y in 0 until height) {
        for (x in 0 until width) {
            val color = bitmap.getColor(x, y)
            val r = (color shr 16) and 0xFF
            val g = (color shr 8) and 0xFF
            val b = color and 0xFF
            val luminance = 0.299 * r + 0.587 * g + 0.114 * b
            sumLuminance += luminance
            if (luminance > maxLuminance) {
                maxLuminance = luminance
                brightestX = x
                brightestY = y
                brightestR = r
                brightestG = g
                brightestB = b
            }
        }
    }
    val pixelCount = width * height
    val averageLuminance = if (pixelCount > 0) sumLuminance / pixelCount else 0.0
    return ScanStatistics(averageLuminance, brightestX, brightestY, brightestR, brightestG, brightestB)
}
