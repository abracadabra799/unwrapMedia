package com.multiviewer.ui

import com.multiviewer.parser.BoxNode
import com.multiviewer.parser.EmbeddedVideo
import com.multiviewer.parser.diagram.buildStructureDiagram
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StructureDiagramExportTest {
    private fun node(type: String, offset: Long, size: Long, children: List<BoxNode> = emptyList(), headerSize: Int = 8) =
        BoxNode(type, offset, headerSize, size, children)

    private val sefRoot = node("root", 0, 1_000, listOf(
        node("SOI", 0, 2, headerSize = 2),
        node("APP1<&\"'>", 2, 98, headerSize = 4),
        node("sefd", 100, 900, listOf(node("MotionPhoto_Data", 100, 860, headerSize = 24)), headerSize = 0),
    ), headerSize = 0)
    private val diagram = buildStructureDiagram(sefRoot, 1_000, EmbeddedVideo(124, 960, "mp4"))

    @Test
    fun `svg escapes parsed labels and stays well-formed XML`() {
        val svg = renderStructureSvg(layoutStructureDiagram(diagram, depth = 1, title = "a<b>.jpg"))
        assertTrue(svg.contains("APP1&lt;&amp;&quot;&apos;&gt;"), svg)
        assertFalse(svg.contains("APP1<&"), svg)
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(InputSource(StringReader(svg)))
        assertEquals("svg", doc.documentElement.tagName)
    }

    @Test
    fun `svg and table show exact byte ranges, unverified ranges and the evidence link`() {
        val svg = renderStructureSvg(layoutStructureDiagram(diagram, depth = 1, title = "x.jpg"))
        assertTrue(svg.contains("2 – 100 (98 B)"), svg)
        assertTrue(svg.contains("960 – 1,000 (40 B)"), svg) // SEF directory bytes no node accounts for
        assertTrue(svg.contains("not broken down"), svg)
        assertTrue(svg.contains("Embedded video (mp4)"), svg)
        assertTrue(svg.contains("124 – 960"), svg)
        assertTrue(svg.contains("← sefd: SEF trailer references"), svg)
        assertTrue(svg.contains("<line "), svg)

        val table = structureDiagramMarkdownTable(diagram)
        assertTrue(table.contains("| Component | Offset | Offset (hex) | Length (B) | Role | Status |"), table)
        assertTrue(table.contains("| APP1<&\"'> | 2 | 0x2 | 98 | METADATA | VERIFIED |"), table)
        assertTrue(table.contains("| ↳ not broken down | 960 | 0x3C0 | 40 | UNPARSED | UNPARSED |"), table)
        assertTrue(table.contains("SEF_EMBEDDED_VIDEO"), table)
        assertTrue(table.contains("124"), table)
    }

    @Test
    fun `markdown table cells cannot break the table`() {
        val root = node("root", 0, 10, listOf(node("a|b\nc", 0, 10)), headerSize = 0)
        val table = structureDiagramMarkdownTable(buildStructureDiagram(root, 10, null))
        assertTrue(table.contains("| a\\|b c | 0 |"), table)
    }

    @Test
    fun `overview depth hides nested regions`() {
        val overview = layoutStructureDiagram(diagram, depth = 0, title = "x.jpg")
        assertTrue(overview.boxes.none { it.depth > 0 })
        val detail = layoutStructureDiagram(diagram, depth = 1, title = "x.jpg")
        assertTrue(detail.boxes.any { it.depth == 1 })
    }

    @Test
    fun `png has the layout size and drawn pixels`() {
        val layout = layoutStructureDiagram(diagram, depth = 1, title = "x.jpg")
        val image = renderStructurePng(layout)
        assertEquals(layout.width, image.width)
        assertEquals(layout.height, image.height)
        val colors = buildSet { for (y in 0 until image.height step 3) for (x in 0 until image.width step 3) add(image.getRGB(x, y)) }
        assertTrue(colors.size > 3, colors.size.toString())
    }

    @Test
    fun `huge trees keep a bounded canvas`() {
        val boxes = (0 until 1_000).map { i ->
            node("trak", 8L + i * 1_000, 1_000, (0 until 1_000).map { j -> node("s", 8L + i * 1_000 + 8 + j, 1, headerSize = 0) })
        }
        val root = node("root", 0, 1_000_008, listOf(node("moov", 0, 1_000_008, boxes)), headerSize = 0)
        val layout = layoutStructureDiagram(buildStructureDiagram(root, 1_000_008, null), depth = 2, title = "big.mp4")
        assertTrue(layout.boxes.size <= MAX_LAYOUT_ROWS, layout.boxes.size.toString())
        assertTrue(layout.height <= 20_000 && layout.width <= 2_000, "${layout.width}x${layout.height}")
        assertTrue(layout.notes.any { "more" in it }, layout.notes.toString())
    }
}
