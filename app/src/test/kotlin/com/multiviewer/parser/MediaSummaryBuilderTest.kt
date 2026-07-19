package com.multiviewer.parser

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class MediaSummaryBuilderTest {
    private fun tempFile(bytes: Int = 0): File {
        val tmp = File.createTempFile("media-summary-test", ".bin")
        tmp.deleteOnExit()
        tmp.writeBytes(ByteArray(bytes))
        return tmp
    }

    @Test
    fun `a JPEG-shaped root (has an SOI child) is classified as IMAGE`() {
        val root = BoxNode(
            type = "root", offset = 0, headerSize = 0, size = 4,
            children = listOf(
                BoxNode(type = "SOI", offset = 0, headerSize = 2, size = 2),
                BoxNode(type = "EOI", offset = 2, headerSize = 2, size = 2),
            ),
        )
        assertEquals(MediaCategory.IMAGE, buildMediaSummary(root, tempFile()).category)
    }

    @Test
    fun `an ISOBMFF root with a moov track whose handler is video is classified as VIDEO`() {
        val hdlr = BoxNode(type = "hdlr", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("handler_type", "vide", 0, 4)))
        val mdia = BoxNode(type = "mdia", offset = 0, headerSize = 0, size = 0, children = listOf(hdlr))
        val trak = BoxNode(type = "trak", offset = 0, headerSize = 0, size = 0, children = listOf(mdia))
        val moov = BoxNode(type = "moov", offset = 0, headerSize = 0, size = 0, children = listOf(trak))
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = 0, children = listOf(moov))
        assertEquals(MediaCategory.VIDEO, buildMediaSummary(root, tempFile()).category)
    }

    @Test
    fun `an ISOBMFF root with no moov (HEIC-shaped) is classified as IMAGE`() {
        val meta = BoxNode(type = "meta", offset = 0, headerSize = 0, size = 0)
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = 0, children = listOf(meta))
        assertEquals(MediaCategory.IMAGE, buildMediaSummary(root, tempFile()).category)
    }

    @Test
    fun `a nested moov reachable only through non-root paths does not affect classification`() {
        val nestedHdlr = BoxNode(type = "hdlr", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("handler_type", "vide", 0, 4)))
        val nestedMdia = BoxNode(type = "mdia", offset = 0, headerSize = 0, size = 0, children = listOf(nestedHdlr))
        val nestedTrak = BoxNode(type = "trak", offset = 0, headerSize = 0, size = 0, children = listOf(nestedMdia))
        val nestedMoov = BoxNode(type = "moov", offset = 0, headerSize = 0, size = 0, children = listOf(nestedTrak))
        val mpvd = BoxNode(type = "mpvd", offset = 0, headerSize = 0, size = 0, children = listOf(nestedMoov))
        val meta = BoxNode(type = "meta", offset = 0, headerSize = 0, size = 0)
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = 0, children = listOf(meta, mpvd))
        assertEquals(MediaCategory.IMAGE, buildMediaSummary(root, tempFile()).category)
    }
}
