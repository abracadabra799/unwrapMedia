package com.multiviewer.util

import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

object ClipboardUtil {
    fun copyToClipboard(text: String): Boolean {
        return try {
            val selection = StringSelection(text)
            val clipboard = Toolkit.getDefaultToolkit().systemClipboard
            clipboard.setContents(selection, selection)
            true
        } catch (e: Throwable) {
            false
        }
    }
}
