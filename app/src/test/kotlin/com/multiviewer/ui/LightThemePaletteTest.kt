package com.multiviewer.ui

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals

class LightThemePaletteTest {
    @Test
    fun `light theme uses a neutral FastStone-like gray surface hierarchy`() {
        assertEquals(Color(0xFFF0F0F0), LightPalette.background)
        assertEquals(Color(0xFFFFFFFF), LightPalette.surface)
        assertEquals(Color(0xFFE6E6E6), LightPalette.panel)
        assertEquals(Color(0xFFB8B8B8), LightPalette.border)
    }

    @Test
    fun `light theme keeps text dark and selection visibly blue`() {
        assertEquals(Color(0xFF202020), LightPalette.textPrimary)
        assertEquals(Color(0xFF555555), LightPalette.textSecondary)
        assertEquals(Color(0xFFB7D7F5), LightPalette.selection)
    }
}
