package com.multiviewer.ui.diagram.spec

import com.multiviewer.parser.ByteReader
import com.multiviewer.parser.diagram.spec.BmpSpecDiagramBuilder
import com.multiviewer.parser.integrity.bmpBytes
import com.multiviewer.parser.parseBmpHeaders
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File

class SpecDiagramExportTest {

    private fun sampleBmpLayout(): SpecLayout {
        val bytes = bmpBytes(width = 3, height = 2)
        val file = File.createTempFile("test-spec-export", ".bmp")
        file.deleteOnExit()
        file.writeBytes(bytes)
        val root = ByteReader.open(file).use { reader ->
            val children = parseBmpHeaders(reader, 0, bytes.size.toLong())
            com.multiviewer.parser.BoxNode("root", 0, 0, bytes.size.toLong(), children = children)
        }
        val spec = BmpSpecDiagramBuilder.build(root, bytes.size.toLong(), "test.bmp")
        return SpecDiagramLayoutEngine.layout(spec)
    }

    @Test
    fun `renders well-formed SVG with title, banners, and ruler`() {
        val layout = sampleBmpLayout()
        val svg = SpecDiagramExporter.renderSvg(layout)

        assertTrue(svg.startsWith("<svg"), "Expected SVG to start with <svg")
        assertTrue(svg.endsWith("</svg>\n") || svg.endsWith("</svg>"), "Expected SVG to end with </svg>")
        assertTrue(svg.contains("test.bmp"))
        assertTrue(svg.contains("Bitmap File Header"))
        assertTrue(svg.contains("BITMAPFILEHEADER"))
        assertTrue(svg.contains("Byte offset:"))
        assertTrue(svg.contains("PixelArray[x,y]"))
        assertTrue(svg.contains("Pad row size to a multiple of 4 Bytes"))
    }

    @Test
    fun `renders valid PNG image with non-zero dimensions`() {
        val layout = sampleBmpLayout()
        val image = SpecDiagramExporter.renderPng(layout)

        assertNotNull(image)
        assertEquals(layout.width, image.width)
        assertEquals(layout.height, image.height)
        assertTrue(image.width >= 800)
        assertTrue(image.height >= 400)
    }
}
