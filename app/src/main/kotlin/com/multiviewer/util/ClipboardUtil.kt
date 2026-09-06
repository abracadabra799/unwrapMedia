package com.multiviewer.util

import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

object ClipboardUtil {
    /**
     * Strip characters that break clipboard round-tripping. The Windows clipboard
     * stores text as a NUL-terminated `CF_UNICODETEXT` string, so an interior NUL
     * silently truncates every paste at that point — this is what made long AI
     * diagnostic prompts (whose embedded raw metadata values can carry a NUL) copy
     * only partially. Other C0 control codes have no place in copyable text either;
     * `\t`, `\n` and `\r` are kept.
     */
    internal fun sanitizeForClipboard(text: String): String {
        if (text.none { it < ' ' && it != '\t' && it != '\n' && it != '\r' }) return text
        return buildString(text.length) {
            for (c in text) {
                if (c >= ' ' || c == '\t' || c == '\n' || c == '\r') append(c)
            }
        }
    }

    fun copyToClipboard(text: String): Boolean {
        return try {
            val selection = StringSelection(sanitizeForClipboard(text))
            val clipboard = Toolkit.getDefaultToolkit().systemClipboard
            clipboard.setContents(selection, selection)
            true
        } catch (e: Throwable) {
            false
        }
    }
}
