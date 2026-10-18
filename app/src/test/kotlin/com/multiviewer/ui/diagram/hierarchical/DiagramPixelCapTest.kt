package com.multiviewer.ui.diagram.hierarchical

import com.multiviewer.parser.BoxNode
import com.multiviewer.parser.diagram.hierarchical.HierarchicalDiagramBuilder
import com.multiviewer.parser.diagram.spec.SpecDiagramDispatcher
import com.multiviewer.ui.MAX_DIAGRAM_PIXELS
import com.multiviewer.ui.cappedDiagramScale
import com.multiviewer.ui.diagram.spec.SpecDiagramExporter
import com.multiviewer.ui.diagram.spec.SpecDiagramLayoutEngine
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DiagramPixelCapTest {
    @Test
    fun `scale is kept when the canvas fits and lowered to the pixel budget otherwise`() {
        assertEquals(2.0f, cappedDiagramScale(1000, 1000, 2.0f, MAX_DIAGRAM_PIXELS))
        assertEquals(32_000_000L, MAX_DIAGRAM_PIXELS)
        val s = cappedDiagramScale(1100, 41_000, 2.0f, 32_000_000L)
        assertTrue(s < 2.0f)
        assertTrue((1100 * s).toLong() * (41_000 * s).toLong() <= 32_000_000L)
    }

    @Test
    fun `a file with thousands of top-level chunks renders within the pixel budget`() {
        val chunks = (0 until 3000).map { i -> BoxNode("IDAT", 8L + i * 8204L, 8, 8204L) }
        val root = BoxNode(type = "root", offset = 0L, headerSize = 0, size = 8L + 3000 * 8204L, children = chunks)
        val layout = HierarchicalLayoutEngine.layout(HierarchicalDiagramBuilder.build(root, root.size, "many.png"))
        val budget = 4_000_000L
        assertTrue(layout.width.toLong() * layout.height * 4 > budget, "fixture must exceed the budget at 2x")

        val png = HierarchicalDiagramExporter.renderPng(layout, scale = 2.0f, maxPixels = budget)

        assertTrue(png.width.toLong() * png.height <= budget, "${png.width}x${png.height}")
        assertTrue(png.width > 0 && png.height > 0)
    }

    @Test
    fun `spec png honours the pixel budget too`() {
        val root = BoxNode(type = "root", offset = 0L, headerSize = 0, size = 100L, children = listOf(BoxNode("BM", 0L, 0, 14L)))
        val layout = SpecDiagramLayoutEngine.layout(SpecDiagramDispatcher.build(root, 100L, "x.bmp"))
        val budget = (layout.width.toLong() * layout.height) / 4
        val png = SpecDiagramExporter.renderPng(layout, maxPixels = budget)
        assertTrue(png.width.toLong() * png.height <= budget, "${png.width}x${png.height}")
    }
}
