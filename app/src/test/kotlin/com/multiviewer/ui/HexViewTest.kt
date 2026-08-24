package com.multiviewer.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HexViewTest {
    @Test
    fun `hexZoomFontSize increases font size on scroll-up (negative delta)`() {
        val result = hexZoomFontSize(currentSp = 12f, scrollDeltaY = -1f)
        assertTrue(result > 12f, "Expected font size to increase on scroll-up, got $result")
    }

    @Test
    fun `hexZoomFontSize decreases font size on scroll-down (positive delta)`() {
        val result = hexZoomFontSize(currentSp = 12f, scrollDeltaY = 1f)
        assertTrue(result < 12f, "Expected font size to decrease on scroll-down, got $result")
    }

    @Test
    fun `hexZoomFontSize clamps at MAX_HEX_FONT_SP`() {
        val result = hexZoomFontSize(currentSp = MAX_HEX_FONT_SP, scrollDeltaY = -100f)
        assertEquals(MAX_HEX_FONT_SP, result)
    }

    @Test
    fun `hexZoomFontSize clamps at MIN_HEX_FONT_SP`() {
        val result = hexZoomFontSize(currentSp = MIN_HEX_FONT_SP, scrollDeltaY = 100f)
        assertEquals(MIN_HEX_FONT_SP, result)
    }

    @Test
    fun `formatBytesAsText returns exact UTF-8 string`() {
        val bytes = "<x:xmpmeta>모션포토</x:xmpmeta>".toByteArray(Charsets.UTF_8)
        val text = formatBytesAsText(bytes)
        assertEquals("<x:xmpmeta>모션포토</x:xmpmeta>", text)
    }

    @Test
    fun `formatBytesAsPrintableAscii replaces non-printable characters with dots`() {
        val bytes = byteArrayOf(0x45, 0x78, 0x69, 0x66, 0x00, 0x00, 0x01, 0x41)
        val ascii = formatBytesAsPrintableAscii(bytes)
        assertEquals("Exif...A", ascii)
    }

    @Test
    fun `formatBytesAsHex formats single and multi-line space-separated hex`() {
        val shortBytes = byteArrayOf(0x45, 0x78, 0x69, 0x66)
        assertEquals("45 78 69 66", formatBytesAsHex(shortBytes))

        val longBytes = ByteArray(20) { it.toByte() }
        val multiLineHex = formatBytesAsHex(longBytes, multiLine = true)
        val lines = multiLineHex.split("\n")
        assertEquals(2, lines.size)
        assertEquals("00 01 02 03 04 05 06 07 08 09 0A 0B 0C 0D 0E 0F", lines[0])
        assertEquals("10 11 12 13", lines[1])
    }

    @Test
    fun `formatBytesAsContinuousHex formats hex without spaces`() {
        val bytes = byteArrayOf(0x45, 0x78, 0x69, 0x66, 0x00, 0x00)
        assertEquals("457869660000", formatBytesAsContinuousHex(bytes))
    }

    @Test
    fun `formatBytesAsCodeArray formats C and Kotlin array items`() {
        val bytes = byteArrayOf(0x45, 0x78, 0x00, 0xFF.toByte())
        assertEquals("0x45, 0x78, 0x00, 0xFF", formatBytesAsCodeArray(bytes))
    }

    @Test
    fun `formatHexDump formats offset, hex columns, and ascii`() {
        val bytes = "Exif\u0000\u0000".toByteArray(Charsets.US_ASCII)
        val dump = formatHexDump(bytes, startOffset = 0x10L)
        assertTrue(dump.startsWith("00000010  45 78 69 66 00 00"))
        assertTrue(dump.endsWith("|Exif..|"))
    }
}
