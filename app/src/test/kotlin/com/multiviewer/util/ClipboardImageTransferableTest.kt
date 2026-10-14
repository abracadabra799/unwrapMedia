package com.multiviewer.util

import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.UnsupportedFlavorException
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ClipboardImageTransferableTest {
    @Test
    fun `offers exactly the image flavor and hands back the same image`() {
        val image = BufferedImage(4, 3, BufferedImage.TYPE_INT_RGB)
        val transferable = ImageTransferable(image)
        assertContentEquals(arrayOf(DataFlavor.imageFlavor), transferable.transferDataFlavors)
        assertTrue(transferable.isDataFlavorSupported(DataFlavor.imageFlavor))
        assertFalse(transferable.isDataFlavorSupported(DataFlavor.stringFlavor))
        assertSame(image, transferable.getTransferData(DataFlavor.imageFlavor))
        assertFailsWith<UnsupportedFlavorException> { transferable.getTransferData(DataFlavor.stringFlavor) }
    }
}
