package com.multiviewer.ui.terminal

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class BracketedPasteSignalTest {

    @Test
    fun tracksCurrentStateSoPasteDisablesWhenBackAtTheBareShellPrompt() {
        val sig = BracketedPasteSignal()
        assertFalse(sig.isReady)

        sig.onBracketedPasteMode(true)   // entered a CLI's readline prompt
        assertTrue(sig.isReady)

        sig.onBracketedPasteMode(false)  // /exit -> back at PS>
        assertFalse(sig.isReady)

        sig.onBracketedPasteMode(true)   // ran another CLI
        assertTrue(sig.isReady)
    }
}
