package com.multiviewer.parser.metadata

import com.multiviewer.parser.BoxField
import com.multiviewer.parser.BoxNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TimedMetadataTrackReaderTest {

    @Test
    fun `findTimedMetadataTracks detects camm handler track`() {
        val hdlrNode = BoxNode(
            type = "hdlr",
            offset = 10L,
            headerSize = 8,
            size = 32L,
            fields = listOf(
                BoxField("handler_type", "camm", 18L, 4),
            ),
        )

        val mdiaNode = BoxNode(
            type = "mdia",
            offset = 8L,
            headerSize = 8,
            size = 100L,
            children = listOf(hdlrNode),
        )

        val trakNode = BoxNode(
            type = "trak",
            offset = 0L,
            headerSize = 8,
            size = 200L,
            children = listOf(mdiaNode),
        )

        val root = BoxNode(
            type = "root",
            offset = 0L,
            headerSize = 0,
            size = 500L,
            children = listOf(trakNode),
        )

        val tracks = findTimedMetadataTracks(root)
        assertEquals(1, tracks.size)
        assertEquals("camm", tracks[0].format)
    }

    @Test
    fun `findTimedMetadataTracks detects gpmd GoPro track`() {
        val hdlrNode = BoxNode(
            type = "hdlr",
            offset = 10L,
            headerSize = 8,
            size = 32L,
            fields = listOf(
                BoxField("handler_type", "gpmd", 18L, 4),
            ),
        )

        val mdiaNode = BoxNode(
            type = "mdia",
            offset = 8L,
            headerSize = 8,
            size = 100L,
            children = listOf(hdlrNode),
        )

        val trakNode = BoxNode(
            type = "trak",
            offset = 0L,
            headerSize = 8,
            size = 200L,
            children = listOf(mdiaNode),
        )

        val root = BoxNode(
            type = "root",
            offset = 0L,
            headerSize = 0,
            size = 500L,
            children = listOf(trakNode),
        )

        val tracks = findTimedMetadataTracks(root)
        assertEquals(1, tracks.size)
        assertEquals("gpmd", tracks[0].format)
    }

    @Test
    fun `findTimedMetadataTracks returns empty when no sensor track exists`() {
        val hdlrNode = BoxNode(
            type = "hdlr",
            offset = 10L,
            headerSize = 8,
            size = 32L,
            fields = listOf(
                BoxField("handler_type", "vide", 18L, 4),
            ),
        )
        val root = BoxNode(
            type = "root",
            offset = 0L,
            headerSize = 0,
            size = 200L,
            children = listOf(BoxNode("trak", 0L, 8, 100L, children = listOf(BoxNode("mdia", 8L, 8, 80L, children = listOf(hdlrNode))))),
        )
        val tracks = findTimedMetadataTracks(root)
        assertTrue(tracks.isEmpty())
    }
}
