package com.multiviewer.parser.diagram.spec

import com.multiviewer.parser.BoxField
import com.multiviewer.parser.BoxNode
import com.multiviewer.parser.diagram.RegionRole
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class UniversalSpecDiagramBuilderTest {

    @Test
    fun `builds universal spec diagram for mp4 container tree`() {
        val ftyp = BoxNode(
            type = "ftyp",
            offset = 0L,
            headerSize = 8,
            size = 32L,
            fields = listOf(
                BoxField("major_brand", "mp42", 8L, 4L),
                BoxField("minor_version", "0", 12L, 4L),
                BoxField("compatible_brand_0", "isom", 16L, 4L),
                BoxField("compatible_brand_1", "mp42", 20L, 4L),
            ),
        )
        val moov = BoxNode(
            type = "moov",
            offset = 32L,
            headerSize = 8,
            size = 1000L,
            children = listOf(
                BoxNode("mvhd", 40L, 8, 108L),
                BoxNode("trak", 148L, 8, 800L),
            ),
        )
        val mdat = BoxNode(
            type = "mdat",
            offset = 1032L,
            headerSize = 8,
            size = 50000L,
        )

        val root = BoxNode("root", 0L, 0, 51032L, children = listOf(ftyp, moov, mdat))
        val diagram = UniversalSpecDiagramBuilder.build(root, 51032L, "movie.mp4")

        assertEquals("movie.mp4", diagram.title)
        assertEquals(51032L, diagram.fileSize)
        assertEquals(3, diagram.blocks.size)

        val ftypBlock = diagram.blocks[0]
        assertEquals("ftyp", ftypBlock.title)
        assertEquals(RegionRole.MARKER, ftypBlock.role)
        assertTrue(ftypBlock.rows.isNotEmpty())

        val moovBlock = diagram.blocks[1]
        assertEquals("moov", moovBlock.title)
        assertEquals(RegionRole.CONTAINER, moovBlock.role)

        val mdatBlock = diagram.blocks[2]
        assertEquals("mdat", mdatBlock.title)
        assertEquals(RegionRole.MEDIA_DATA, mdatBlock.role)
    }

    @Test
    fun `dispatcher selects BMP builder for bmp files and universal builder otherwise`() {
        val root = BoxNode("root", 0L, 0, 100L)

        val mp4Diagram = SpecDiagramDispatcher.build(root, 100L, "test.mp4")
        val bmpDiagram = SpecDiagramDispatcher.build(root, 100L, "test.bmp")

        assertNotNull(mp4Diagram)
        assertNotNull(bmpDiagram)
        // BMP builder produces specific block id "block_file_header"
        assertTrue(bmpDiagram.blocks.any { it.id == "block_file_header" })
    }
}
