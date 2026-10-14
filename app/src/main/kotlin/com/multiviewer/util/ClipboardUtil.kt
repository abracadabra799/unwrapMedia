package com.multiviewer.util

import java.awt.Image
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException

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

    /**
     * Puts an image on the system clipboard for pasting into slides/documents. Use an opaque image
     * (e.g. TYPE_INT_RGB): Windows renders a transparent clipboard image's alpha as black. Returns false
     * when the clipboard is unavailable or busy (another app holding it) instead of throwing.
     */
    fun copyImage(image: Image): Boolean {
        return try {
            Toolkit.getDefaultToolkit().systemClipboard.setContents(ImageTransferable(image), null)
            true
        } catch (e: Throwable) {
            false
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

/** Clipboard payload offering exactly [DataFlavor.imageFlavor]. */
internal class ImageTransferable(private val image: Image) : Transferable {
    override fun getTransferDataFlavors(): Array<DataFlavor> = arrayOf(DataFlavor.imageFlavor)

    override fun isDataFlavorSupported(flavor: DataFlavor): Boolean = flavor == DataFlavor.imageFlavor

    override fun getTransferData(flavor: DataFlavor): Any {
        if (!isDataFlavorSupported(flavor)) throw UnsupportedFlavorException(flavor)
        return image
    }
}
