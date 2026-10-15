package com.multiviewer.parser.diagram.spec

import com.multiviewer.parser.BoxField
import com.multiviewer.parser.BoxNode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EnhancedSpecDiagramTest {

    @Test
    fun `verifies JPEG with Gain Map produces separate gain map block and bracket`() {
        val root = BoxNode(
            type = "root", offset = 0L, headerSize = 0, size = 5000L,
            children = listOf(
                // Primary Image
                BoxNode("SOI", 0L, 0, 2L),
                BoxNode("APP1", 2L, 4, 100L, summary = "Exif"),
                BoxNode("APP2", 102L, 4, 50L, summary = "ISO 21496-1 Gain Map Metadata"),
                BoxNode("SOF0", 152L, 2, 19L, fields = listOf(BoxField("width", "4000", 156L, 2L), BoxField("height", "3000", 158L, 2L))),
                BoxNode("SOS", 171L, 2, 2000L),
                BoxNode("EOI", 2171L, 0, 2L),
                // Secondary Gain Map Image
                BoxNode("SOI", 2173L, 0, 2L),
                BoxNode("APP1", 2175L, 4, 80L, summary = "XMP Gain Map"),
                BoxNode("APP2", 2255L, 4, 40L, summary = "ISO 21496-1 Gain Map Metadata"),
                BoxNode("SOF0", 2295L, 2, 13L, fields = listOf(BoxField("width", "1000", 2297L, 2L), BoxField("height", "750", 2299L, 2L), BoxField("components", "1", 2301L, 1L))),
                BoxNode("SOS", 2308L, 2, 1000L),
                BoxNode("EOI", 3308L, 0, 2L),
            )
        )

        val diagram = JpegSpecDiagramBuilder.build(root, 5000L, "test_gainmap.jpg")
        assertNotNull(diagram)

        // Check for Primary bracket and Gain Map bracket
        val primaryBracket = diagram.brackets.find { it.label.contains("Primary Image") }
        assertNotNull(primaryBracket, "Primary image bracket should exist")
        assertEquals(0L, primaryBracket!!.fromOffset)
        assertEquals(2173L, primaryBracket.toOffset)

        val gainMapBracket = diagram.brackets.find { it.label.contains("Gain Map") }
        assertNotNull(gainMapBracket, "Gain map bracket should exist")
        assertEquals(2173L, gainMapBracket!!.fromOffset)
        assertEquals(3310L, gainMapBracket.toOffset)

        // Check for Gain Map block distinction
        val gainMapBlock = diagram.blocks.find { it.title.contains("Gain Map") }
        assertNotNull(gainMapBlock, "Gain Map block should be identified")

        // Check for reference arrow from APP2 to Gain Map
        val arrow = diagram.arrows.find { it.label?.contains("Gain Map") == true }
        assertNotNull(arrow, "Arrow pointing to gain map block should exist")
    }

    @Test
    fun `verifies Samsung Motion Photo SEF decomposes MP4 boxes with bracket and arrow`() {
        val root = BoxNode(
            type = "root", offset = 0L, headerSize = 0, size = 10000L,
            children = listOf(
                BoxNode("SOI", 0L, 0, 2L),
                BoxNode("APP1", 2L, 4, 100L, summary = "XMP", fields = listOf(BoxField("xmp", "GCamera:MotionPhoto=\"1\"", 6L, 96L))),
                BoxNode("SOS", 102L, 2, 4000L),
                BoxNode("EOI", 4102L, 0, 2L),
                BoxNode("sefd", 4104L, 0, 5896L, children = listOf(
                    BoxNode("Image_UTC_Data", 4104L, 0, 35L),
                    BoxNode("MotionPhoto_Data", 4139L, 0, 5800L, summary = "embedded MP4", children = listOf(
                        BoxNode("ftyp", 4139L, 8, 28L, fields = listOf(BoxField("major_brand", "isom", 4147L, 4L))),
                        BoxNode("mdat", 4167L, 8, 4000L),
                        BoxNode("moov", 8167L, 8, 1772L, children = listOf(
                            BoxNode("mvhd", 8175L, 8, 108L, summary = "duration=3.13s"),
                            BoxNode("trak", 8283L, 8, 1500L, children = listOf(
                                BoxNode("tkhd", 8291L, 8, 92L, fields = listOf(BoxField("width", "1920", 8370L, 4L), BoxField("height", "1080", 8374L, 4L))),
                                BoxNode("mdia", 8383L, 8, 1400L, children = listOf(
                                    BoxNode("hdlr", 8391L, 8, 33L, fields = listOf(BoxField("handler_type", "vide", 8400L, 4L))),
                                    BoxNode("minf", 8424L, 8, 1350L, children = listOf(
                                        BoxNode("stbl", 8432L, 8, 1300L, children = listOf(
                                            BoxNode("stsd", 8440L, 8, 200L, children = listOf(
                                                BoxNode("hvc1", 8448L, 8, 180L)
                                            ))
                                        ))
                                    ))
                                ))
                            ))
                        ))
                    ))
                ))
            )
        )

        val diagram = JpegSpecDiagramBuilder.build(root, 10000L, "motion_photo.jpg")
        assertNotNull(diagram)

        // Decomposed MP4 blocks should exist
        val ftypBlock = diagram.blocks.find { it.id == "block_mp4_ftyp" || it.title.contains("ftyp") }
        assertNotNull(ftypBlock, "Embedded MP4 ftyp block should be generated")

        val moovBlock = diagram.blocks.find { it.id == "block_mp4_moov" || it.title.contains("moov") }
        assertNotNull(moovBlock, "Embedded MP4 moov block should be generated")

        val mdatBlock = diagram.blocks.find { it.id == "block_mp4_mdat" || it.title.contains("mdat") }
        assertNotNull(mdatBlock, "Embedded MP4 mdat block should be generated")

        // Bracket for embedded MP4
        val mp4Bracket = diagram.brackets.find { it.label.contains("Embedded MP4") || it.label.contains("Motion Photo") }
        assertNotNull(mp4Bracket, "Embedded MP4 bracket should exist")
        assertEquals(4139L, mp4Bracket!!.fromOffset)
        assertEquals(9939L, mp4Bracket.toOffset)

        // Arrow from SEF or XMP to MP4
        val arrow = diagram.arrows.find { it.toBlockId == ftypBlock!!.id }
        assertNotNull(arrow, "Arrow pointing to embedded MP4 block should exist")
    }
}
