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
}
