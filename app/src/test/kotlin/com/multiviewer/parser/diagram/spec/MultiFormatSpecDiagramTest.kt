package com.multiviewer.parser.diagram.spec

import com.multiviewer.parser.BoxField
import com.multiviewer.parser.BoxNode
import com.multiviewer.ui.diagram.spec.SpecDiagramExporter
import com.multiviewer.ui.diagram.spec.SpecDiagramLayoutEngine
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File

class MultiFormatSpecDiagramTest {

    @Test
    fun `verifies JPEG spec diagram builder`() {
        val root = BoxNode(
            type = "root", offset = 0L, headerSize = 0, size = 10000L,
            children = listOf(
                BoxNode("SOI", 0L, 0, 2L),
                BoxNode("APP1", 2L, 0, 1000L, summary = "Exif metadata"),
                BoxNode("SOF0", 1002L, 0, 19L, fields = listOf(
                    BoxField("width", "1920", 1005L, 2L),
                    BoxField("height", "1080", 1007L, 2L),
                    BoxField("precision", "8", 1004L, 1L),
                    BoxField("components", "3", 1009L, 1L),
                )),
                BoxNode("DQT", 1021L, 0, 69L),
                BoxNode("DHT", 1090L, 0, 183L),
                BoxNode("SOS", 1273L, 0, 8700L),
                BoxNode("EOI", 9973L, 0, 2L),
                BoxNode("sefd", 9975L, 0, 25L, children = listOf(
                    BoxNode("MotionPhoto_Data", 9975L, 0, 25L, summary = "embedded MP4"),
                )),
            ),
        )

        val diagram = SpecDiagramDispatcher.build(root, 10000L, "photo.jpg")
        assertEquals("photo.jpg", diagram.title)
        assertTrue(diagram.blocks.any { it.title.contains("SOI") })
        assertTrue(diagram.blocks.any { it.title.contains("SOF") })
        assertTrue(diagram.blocks.any { it.title.contains("SOS") })
        assertTrue(diagram.blocks.any { it.title.contains("EOI") })
        assertTrue(diagram.blocks.any { it.title.contains("SEF") })

        val layout = SpecDiagramLayoutEngine.layout(diagram)
        val svg = SpecDiagramExporter.renderSvg(layout)
        val png = SpecDiagramExporter.renderPng(layout)

        assertTrue(svg.contains("Start of Image (SOI)"))
        assertNotNull(png)
    }

    @Test
    fun `verifies HEIC spec diagram builder`() {
        val root = BoxNode(
            type = "root", offset = 0L, headerSize = 0, size = 5000L,
            children = listOf(
                BoxNode("ftyp", 0L, 8, 24L, fields = listOf(BoxField("major_brand", "heic", 8L, 4L))),
                BoxNode("meta", 24L, 8, 400L, children = listOf(
                    BoxNode("hdlr", 32L, 8, 33L),
                    BoxNode("pitm", 65L, 8, 14L, fields = listOf(BoxField("item_ID", "1", 73L, 2L))),
                    BoxNode("iloc", 79L, 8, 100L),
                    BoxNode("iinf", 179L, 8, 100L),
                )),
                BoxNode("mdat", 424L, 8, 4576L),
            ),
        )

        val diagram = SpecDiagramDispatcher.build(root, 5000L, "image.heic")
        assertEquals(listOf("block_ftyp", "block_meta", "block_mdat"), diagram.blocks.map { it.id })
        assertTrue(diagram.blocks.any { it.title.contains("ftyp") })
        assertTrue(diagram.blocks.any { it.title.contains("meta") })
        assertTrue(diagram.blocks.any { it.title.contains("mdat") })
        assertTrue(diagram.arrows.isNotEmpty(), "Expected iloc to mdat pointer arrow")

        val layout = SpecDiagramLayoutEngine.layout(diagram)
        assertNotNull(SpecDiagramExporter.renderPng(layout))
    }

    @Test
    fun `verifies HEIC spec diagram builder orders mdat before meta when mdat comes first in file`() {
        // Typical Samsung Galaxy HEIC layout: ftyp -> mdat -> meta -> sefd
        val root = BoxNode(
            type = "root", offset = 0L, headerSize = 0, size = 8000L,
            children = listOf(
                BoxNode("ftyp", 0L, 8, 28L, fields = listOf(BoxField("major_brand", "heic", 8L, 4L))),
                BoxNode("mdat", 28L, 8, 4000L),
                BoxNode("meta", 4028L, 8, 500L, children = listOf(
                    BoxNode("hdlr", 4036L, 8, 33L),
                    BoxNode("pitm", 4069L, 8, 14L, fields = listOf(BoxField("item_ID", "1", 4077L, 2L))),
                    BoxNode("iloc", 4083L, 8, 100L),
                    BoxNode("iinf", 4183L, 8, 100L),
                )),
                BoxNode("sefd", 4528L, 0, 3472L),
            ),
        )

        val diagram = SpecDiagramDispatcher.build(root, 8000L, "galaxy_camera.heic")
        // Blocks must follow physical file offset order: ftyp (0) -> mdat (28) -> meta (4028) -> sefd (4528)
        assertEquals(listOf("block_ftyp", "block_mdat", "block_meta", "block_sefd"), diagram.blocks.map { it.id })

        // Arrow from iloc (in meta) should point up to mdat
        val arrow = diagram.arrows.single { it.fromCellId == "meta_iloc" }
        assertEquals("block_mdat", arrow.toBlockId)

        val layout = SpecDiagramLayoutEngine.layout(diagram)
        val lArrow = layout.arrows.single()
        // midX = 106, endX = 116 (pointing right into mdat header)
        assertEquals(106, lArrow.points[1].first)
        assertEquals(116, lArrow.points[3].first)
        // startY > endY because meta is below mdat
        assertTrue(lArrow.points[0].second > lArrow.points[3].second, "Arrow should route upward from meta to mdat")

        val png = SpecDiagramExporter.renderPng(layout)
        assertNotNull(png)
    }

    @Test
    fun `verifies real Galaxy camera HEIC fixture orders mdat before meta in spec diagram`() {
        val file = File("src/test/resources/c2pa/s26-motion-photo.heic")
        assertTrue(file.exists(), "Test fixture s26-motion-photo.heic must exist")
        val root = com.multiviewer.parser.parseFile(file)
        val diagram = SpecDiagramDispatcher.build(root, file.length(), file.name)

        val blockIds = diagram.blocks.map { it.id }
        assertTrue(blockIds.contains("block_ftyp"))
        assertTrue(blockIds.contains("block_mdat"))
        assertTrue(blockIds.contains("block_meta"))
        val mdatIdx = blockIds.indexOf("block_mdat")
        val metaIdx = blockIds.indexOf("block_meta")
        assertTrue(mdatIdx < metaIdx, "mdat must appear before meta in spec diagram for real Galaxy HEIC (mdatIdx=$mdatIdx, metaIdx=$metaIdx)")

        val layout = SpecDiagramLayoutEngine.layout(diagram)
        assertNotNull(SpecDiagramExporter.renderPng(layout))
    }

    @Test
    fun `verifies MP4 spec diagram builder orders mdat before moov when mdat comes first in file`() {
        val root = BoxNode(
            type = "root", offset = 0L, headerSize = 0, size = 10000L,
            children = listOf(
                BoxNode("ftyp", 0L, 8, 32L, fields = listOf(BoxField("major_brand", "mp42", 8L, 4L))),
                BoxNode("mdat", 32L, 8, 8000L),
                BoxNode("moov", 8032L, 8, 1968L, children = listOf(
                    BoxNode("mvhd", 8040L, 8, 108L),
                    BoxNode("trak", 8148L, 8, 1852L, children = listOf(
                        BoxNode("tkhd", 8156L, 8, 92L),
                        BoxNode("mdia", 8248L, 8, 1752L, children = listOf(
                            BoxNode("hdlr", 8256L, 8, 33L, fields = listOf(BoxField("handler_type", "vide", 8265L, 4L))),
                            BoxNode("minf", 8289L, 8, 1711L, children = listOf(
                                BoxNode("stbl", 8297L, 8, 1703L, children = listOf(
                                    BoxNode("stsd", 8305L, 8, 100L),
                                    BoxNode("stco", 8405L, 8, 500L),
                                ))
                            ))
                        ))
                    ))
                ))
            ),
        )

        val diagram = SpecDiagramDispatcher.build(root, 10000L, "recorded_video.mp4")
        assertEquals(listOf("block_ftyp", "block_mdat", "block_moov"), diagram.blocks.map { it.id })

        val layout = SpecDiagramLayoutEngine.layout(diagram)
        assertNotNull(SpecDiagramExporter.renderPng(layout))
    }

    @Test
    fun `verifies PNG spec diagram builder`() {
        val root = BoxNode(
            type = "root", offset = 0L, headerSize = 0, size = 2000L,
            children = listOf(
                BoxNode("IHDR", 8L, 0, 25L, fields = listOf(
                    BoxField("width", "500", 16L, 4L),
                    BoxField("height", "500", 20L, 4L),
                    BoxField("bit_depth", "8", 24L, 1L),
                    BoxField("color_type", "6 (RGBA)", 25L, 1L),
                )),
                BoxNode("IDAT", 33L, 0, 1950L),
                BoxNode("IEND", 1983L, 0, 12L),
            ),
        )

        val diagram = SpecDiagramDispatcher.build(root, 2000L, "graphic.png")
        assertTrue(diagram.blocks.any { it.title.contains("PNG File Signature") })
        assertTrue(diagram.blocks.any { it.title.contains("IHDR") })
        assertTrue(diagram.blocks.any { it.title.contains("IDAT") })
        assertTrue(diagram.blocks.any { it.title.contains("IEND") })

        val layout = SpecDiagramLayoutEngine.layout(diagram)
        assertNotNull(SpecDiagramExporter.renderPng(layout))
    }

    @Test
    fun `verifies TIFF spec diagram builder`() {
        val root = BoxNode(
            type = "root", offset = 0L, headerSize = 0, size = 8000L,
            children = listOf(
                BoxNode("IFD0", 8L, 0, 200L, fields = listOf(
                    BoxField("ImageWidth", "1024", 10L, 12L),
                    BoxField("ImageLength", "768", 22L, 12L),
                    BoxField("StripOffsets", "500", 34L, 12L),
                )),
                BoxNode("StripData", 500L, 0, 7500L),
            ),
        )

        val diagram = SpecDiagramDispatcher.build(root, 8000L, "scan.tiff")
        assertTrue(diagram.blocks.any { it.title.contains("TIFF File Header") })
        assertTrue(diagram.blocks.any { it.title.contains("IFD0") })
        assertTrue(diagram.arrows.isNotEmpty(), "Expected TIFF header to IFD0 arrow")

        val layout = SpecDiagramLayoutEngine.layout(diagram)
        assertNotNull(SpecDiagramExporter.renderPng(layout))
    }

    @Test
    fun `verifies WebP spec diagram builder`() {
        val root = BoxNode(
            type = "root", offset = 0L, headerSize = 0, size = 4000L,
            children = listOf(
                BoxNode("VP8X", 12L, 0, 18L, fields = listOf(
                    BoxField("canvas_width", "800", 20L, 3L),
                    BoxField("canvas_height", "600", 23L, 3L),
                    BoxField("has_alpha", "true", 16L, 1L),
                )),
                BoxNode("ALPH", 30L, 0, 200L),
                BoxNode("VP8 ", 230L, 0, 3770L),
            ),
        )

        val diagram = SpecDiagramDispatcher.build(root, 4000L, "sticker.webp")
        assertTrue(diagram.blocks.any { it.title.contains("RIFF Container Header") })
        assertTrue(diagram.blocks.any { it.title.contains("VP8X") })
        assertTrue(diagram.blocks.any { it.title.contains("VP8") })

        val layout = SpecDiagramLayoutEngine.layout(diagram)
        assertNotNull(SpecDiagramExporter.renderPng(layout))
    }
}
