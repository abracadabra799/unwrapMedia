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

    @Test
    fun terminalFontCanRenderHangulOrIsTheLogicalMonospaceFallback() {
        val font = pickCliTerminalFont()
        assertEquals(13, font.size)
        // Either a picked font that can draw 가/─, or the "Monospaced" logical
        // composite (whose JRE fallback covers Hangul at render time even when
        // canDisplayUpTo on the base face is pessimistic).
        val drawsHangul = font.canDisplayUpTo("가─A") == -1
        assertTrue(
            drawsHangul || font.family.equals("monospaced", ignoreCase = true) || font.name == java.awt.Font.MONOSPACED,
            "font ${font.name} can't draw Hangul and isn't the Monospaced fallback",
        )
        // Never fall back to a face known to lack Hangul.
        assertNotEquals("Consolas", font.name)
    }

    @Test
    fun urlHyperlinkFilterLinkifiesAPrintedLoginUrl() {
        val filter = UrlHyperlinkFilter()
        val line = "  Open this URL to sign in: https://claude.ai/oauth/authorize?code=abc123 (or paste it)"
        val result = filter.apply(line)
        assertNotNull(result)
        val item = result!!.items.single()
        val url = "https://claude.ai/oauth/authorize?code=abc123"
        assertEquals(line.indexOf("https://"), item.startOffset)
        assertEquals(line.indexOf("https://") + url.length, item.endOffset)

        assertNull(filter.apply("no url on this line"))
        assertNull(filter.apply(""))
        assertNull(filter.apply(null))
    }
}
