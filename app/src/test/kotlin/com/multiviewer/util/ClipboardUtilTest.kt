package com.multiviewer.util

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ClipboardUtilTest {

    @Test
    fun sanitizeStripsNulSoWindowsClipboardDoesNotTruncate() {
        // CF_UNICODETEXT is NUL-terminated: an interior NUL cuts every paste short.
        val out = ClipboardUtil.sanitizeForClipboard("line1\nline2\u0000line3\nline4")
        assertEquals("line1\nline2line3\nline4", out)
    }

    @Test
    fun sanitizeDropsOtherC0ControlsButKeepsTabCrLf() {
        val out = ClipboardUtil.sanitizeForClipboard("abc\u0007\td\r\ne")
        assertEquals("abc\td\r\ne", out)
    }

    @Test
    fun sanitizeReturnsCleanTextUnchanged() {
        val clean = "\uc644\uc804\ud788 \uae68\ub057\ud55c\n\ud504\ub86c\ud504\ud2b8\t\ud14d\uc2a4\ud2b8\r\n\ub05d"
        assertSame(clean, ClipboardUtil.sanitizeForClipboard(clean))
    }
}
