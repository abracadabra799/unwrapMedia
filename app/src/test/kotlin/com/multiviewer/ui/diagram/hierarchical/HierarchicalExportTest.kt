package com.multiviewer.ui.diagram.hierarchical

import com.multiviewer.parser.BoxField
import com.multiviewer.parser.BoxNode
import com.multiviewer.parser.diagram.hierarchical.HierarchicalDiagramBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HierarchicalExportTest {

    @Test
    fun `verifies hierarchical layout engine and export to SVG and PNG`() {
        val root = BoxNode(
            type = "root", offset = 0L, headerSize = 0, size = 5000L,
            children = listOf(
                BoxNode("SOI", 0L, 0, 2L),
                BoxNode("APP1", 2L, 4, 100L, summary = "Exif", children = listOf(
                    BoxNode("IFD0", 16L, 0, 80L, fields = listOf(BoxField("Make", "Samsung", 16L, 10L)))
                )),
                BoxNode("SOF0", 102L, 2, 19L, fields = listOf(BoxField("width", "4000", 106L, 2L), BoxField("height", "3000", 108L, 2L))),
                BoxNode("SOS", 121L, 2, 2000L),
                BoxNode("EOI", 2121L, 0, 2L),
            )
        )

        val diagram = HierarchicalDiagramBuilder.build(root, 5000L, "sample.jpg")
        val layout = HierarchicalLayoutEngine.layout(diagram)

        assertNotNull(layout)
        assertTrue(layout.width >= 800)
        assertTrue(layout.height >= 300)
        assertEquals(2, layout.columns.size)
        assertTrue(layout.projections.isNotEmpty())

        val svg = HierarchicalDiagramExporter.renderSvg(layout)
        assertNotNull(svg)
        assertTrue(svg.contains("<svg"))
        assertTrue(svg.contains("Structure of Compressed file") || svg.contains("Structure of"))
        assertTrue(svg.contains("Structure of APP1"))
        assertTrue(svg.contains("</svg>"))

        val png = HierarchicalDiagramExporter.renderPng(layout, scale = 2.0f)
        assertNotNull(png)
        assertTrue(png.width >= 1600, "2x scale PNG width should be >= 1600")
        assertTrue(png.height >= 600, "2x scale PNG height should be >= 600")
    }

    @Test
    fun `long section name and description wrap to multiline with expanded row height`() {
        val root = BoxNode(
            type = "root", offset = 0L, headerSize = 0, size = 10000L,
            children = listOf(
                BoxNode("SOI", 0L, 0, 2L),
                BoxNode("APP1", 2L, 4, 1000L, summary = "Exif Very Long Description That Will Definitely Exceed Single Line Limit With Lots Of Details", children = listOf(
                    BoxNode("IFD0", 16L, 0, 800L, fields = listOf(BoxField("Make", "Samsung Electronics Very Long Camera Sensor Details", 16L, 10L)))
                )),
                BoxNode("EOI", 1002L, 0, 2L),
            )
        )
        val diagram = HierarchicalDiagramBuilder.build(root, 10000L, "test_long.jpg")
        val layout = HierarchicalLayoutEngine.layout(diagram)

        val app1Row = layout.columns.first().rows.find { it.marker == "APP1" }
        assertNotNull(app1Row)
        // Check that long name/desc wrapped or caused multiline layout
        assertTrue(app1Row!!.h >= 32)
        assertNotNull(HierarchicalDiagramExporter.renderSvg(layout))
        assertNotNull(HierarchicalDiagramExporter.renderPng(layout))
    }
}
