package com.multiviewer.ui.terminal

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CliTerminalSettingsTest {

    private val settings = CliTerminalSettings(BracketedPasteSignal())

    @Test
    fun terminalIsLightOnDarkNotJediTermsDefaultBlackOnWhite() {
        // JediTerm 3.74's DefaultSettingsProvider yields TextStyle(BLACK, WHITE) --
        // a white console background. The embedded CLI terminal must be dark.
        val bg = settings.defaultBackground.toColor()
        val luminance = (bg.red + bg.green + bg.blue) / 3
        assertTrue(luminance < 64, "terminal background should be dark, was rgb(${bg.red},${bg.green},${bg.blue})")

        val fg = settings.defaultForeground.toColor()
        val fgLuminance = (fg.red + fg.green + fg.blue) / 3
        assertTrue(fgLuminance > 128, "terminal foreground should be light, was rgb(${fg.red},${fg.green},${fg.blue})")
    }

    @Test
    fun defaultStyleAndDelegatingGettersAgree() {
        assertEquals(settings.defaultStyle.background, settings.defaultBackground)
        assertEquals(settings.defaultStyle.foreground, settings.defaultForeground)
    }
}
