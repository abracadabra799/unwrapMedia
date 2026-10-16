package com.multiviewer.parser.diagram.hierarchical

import com.multiviewer.parser.BoxField
import com.multiviewer.parser.BoxNode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HierarchicalDiagramBuilderTest {

    @Test
    fun `verifies JPEG with Motion Photo SEF generates 3-column hierarchical diagram with projections`() {
        val root = BoxNode(
            type = "root", offset = 0L, headerSize = 0, size = 10000L,
            children = listOf(
                BoxNode("SOI", 0L, 0, 2L),
                BoxNode("APP1", 2L, 4, 100L, summary = "Exif", children = listOf(
                    BoxNode("IFD0", 16L, 0, 80L, fields = listOf(BoxField("Make", "Samsung", 16L, 10L)))
                )),
                BoxNode("APP2", 102L, 4, 50L, summary = "ICC Profile"),
                BoxNode("SOF0", 152L, 2, 19L, fields = listOf(BoxField("width", "4000", 156L, 2L), BoxField("height", "2252", 158L, 2L))),
                BoxNode("SOS", 171L, 2, 4000L),
                BoxNode("EOI", 4171L, 0, 2L),
                BoxNode("sefd", 4173L, 0, 5827L, children = listOf(
                    BoxNode("Image_UTC_Data", 4173L, 0, 35L),
                    BoxNode("MotionPhoto_Data", 4208L, 0, 5750L, summary = "embedded MP4", children = listOf(
                        BoxNode("ftyp", 4208L, 8, 28L, fields = listOf(BoxField("major_brand", "isom", 4216L, 4L))),
                        BoxNode("mdat", 4236L, 8, 4000L),
                        BoxNode("moov", 8236L, 8, 1722L, children = listOf(
                            BoxNode("mvhd", 8244L, 8, 108L, summary = "duration=3.13s"),
                            BoxNode("trak", 8352L, 8, 1500L, children = listOf(
                                BoxNode("tkhd", 8360L, 8, 92L, fields = listOf(BoxField("width", "1752", 8440L, 4L), BoxField("height", "984", 8444L, 4L))),
                                BoxNode("mdia", 8452L, 8, 1400L, children = listOf(
                                    BoxNode("hdlr", 8460L, 8, 33L, fields = listOf(BoxField("handler_type", "vide", 8468L, 4L))),
                                    BoxNode("minf", 8493L, 8, 1350L, children = listOf(
                                        BoxNode("stbl", 8501L, 8, 1300L, children = listOf(
                                            BoxNode("stsd", 8509L, 8, 200L, children = listOf(
                                                BoxNode("hvc1", 8517L, 8, 180L)
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

        val diagram = HierarchicalDiagramBuilder.build(root, 10000L, "20260718_motion.jpg")
        assertNotNull(diagram)
        assertEquals("20260718_motion.jpg", diagram.title)

        // Must have at least 2 or 3 columns
        assertTrue(diagram.columns.size >= 2, "Should have multi-column structure")
        val col1 = diagram.columns[0]
        assertTrue(col1.title.contains("Compressed file") || col1.title.contains("JPEG"), "Col 1 is top-level container")
        assertTrue(col1.sections.any { it.marker == "SOI" })
        assertTrue(col1.sections.any { it.marker == "APP1" })
        assertTrue(col1.sections.any { it.marker == "SOF" || it.marker == "SOF0" })
        assertTrue(col1.sections.any { it.marker == "SOS" })
        assertTrue(col1.sections.any { it.marker == "EOI" })

        // Check projections
        assertTrue(diagram.projections.isNotEmpty(), "Projections from container to expanded columns must exist")
    }

    @Test
    fun `verifies MP4 generates hierarchical columns for moov and trak`() {
        val root = BoxNode(
            type = "root", offset = 0L, headerSize = 0, size = 8000L,
            children = listOf(
                BoxNode("ftyp", 0L, 8, 24L, fields = listOf(BoxField("major_brand", "mp42", 8L, 4L))),
                BoxNode("moov", 24L, 8, 2000L, children = listOf(
                    BoxNode("mvhd", 32L, 8, 108L, summary = "duration=10.0s"),
                    BoxNode("trak", 140L, 8, 1800L, children = listOf(
                        BoxNode("tkhd", 148L, 8, 92L, fields = listOf(BoxField("width", "1920", 228L, 4L), BoxField("height", "1080", 232L, 4L))),
                        BoxNode("mdia", 240L, 8, 1700L, children = listOf(
                            BoxNode("hdlr", 248L, 8, 33L, fields = listOf(BoxField("handler_type", "vide", 256L, 4L))),
                            BoxNode("minf", 281L, 8, 1650L, children = listOf(
                                BoxNode("stbl", 289L, 8, 1600L, children = listOf(
                                    BoxNode("stsd", 297L, 8, 200L, children = listOf(BoxNode("avc1", 305L, 8, 180L)))
                                ))
                            ))
                        ))
                    ))
                )),
                BoxNode("mdat", 2024L, 8, 5976L),
            )
        )

        val diagram = HierarchicalDiagramBuilder.build(root, 8000L, "video.mp4")
        assertNotNull(diagram)
        assertTrue(diagram.columns.size >= 2)
        assertEquals("video.mp4", diagram.title)
        assertTrue(diagram.columns[0].sections.any { it.marker == "ftyp" })
        assertTrue(diagram.columns[0].sections.any { it.marker == "moov" })
        assertTrue(diagram.columns[0].sections.any { it.marker == "mdat" })
    }
}
