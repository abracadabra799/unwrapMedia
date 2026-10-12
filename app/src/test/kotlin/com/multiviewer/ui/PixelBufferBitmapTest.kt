package com.multiviewer.ui

import androidx.compose.ui.graphics.toPixelMap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import kotlin.test.Test
import kotlin.test.assertEquals

class PixelBufferBitmapTest {
    private fun argb(a: Int, r: Int, g: Int, b: Int) = (a shl 24) or (r shl 16) or (g shl 8) or b

    @Test
    fun `BGRA buffer converts with the right channel order`() {
        // 2x1: pure red, then pure blue -- BGRA byte order.
        val bgra = byteArrayOf(0, 0, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0, 0, 0xFF.toByte())
        val map = pixelBufferToImageBitmap(2, 1, bgra, ColorType.BGRA_8888, ColorAlphaType.PREMUL).toPixelMap()
        assertEquals(argb(0xFF, 0xFF, 0, 0), map[0, 0].toArgbInt())
        assertEquals(argb(0xFF, 0, 0, 0xFF), map[1, 0].toArgbInt())
    }

    @Test
    fun `RGBA buffer converts with the right channel order`() {
        val rgba = byteArrayOf(0, 0xFF.toByte(), 0, 0xFF.toByte())
        val map = pixelBufferToImageBitmap(1, 1, rgba, ColorType.RGBA_8888, ColorAlphaType.UNPREMUL).toPixelMap()
        assertEquals(argb(0xFF, 0, 0xFF, 0), map[0, 0].toArgbInt())
    }

    // FfmpegVideoPlayer reads every frame into the same buffer: a bitmap still on screen must not
    // change when the next frame overwrites that buffer.
    @Test
    fun `result does not alias the source buffer`() {
        val bgra = byteArrayOf(0, 0, 0xFF.toByte(), 0xFF.toByte())
        val bitmap = pixelBufferToImageBitmap(1, 1, bgra, ColorType.BGRA_8888, ColorAlphaType.PREMUL)
        bgra.fill(0)
        assertEquals(argb(0xFF, 0xFF, 0, 0), bitmap.toPixelMap()[0, 0].toArgbInt())
    }

    private fun androidx.compose.ui.graphics.Color.toArgbInt(): Int =
        (Math.round(alpha * 255) shl 24) or (Math.round(red * 255) shl 16) or
            (Math.round(green * 255) shl 8) or Math.round(blue * 255)
}
