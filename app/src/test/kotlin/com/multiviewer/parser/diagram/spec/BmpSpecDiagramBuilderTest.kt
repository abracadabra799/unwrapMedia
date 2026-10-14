package com.multiviewer.parser.diagram.spec

import com.multiviewer.parser.ByteReader
import com.multiviewer.parser.diagram.RegionRole
import com.multiviewer.parser.integrity.bmpBytes
import com.multiviewer.parser.parseBmpHeaders
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File

class BmpSpecDiagramBuilderTest {

    private fun parseBmp(bytes: ByteArray) =
        File.createTempFile("test-bmp-spec", ".bmp").run {
            deleteOnExit()
            writeBytes(bytes)
            ByteReader.open(this).use { reader ->
                val children = parseBmpHeaders(reader, 0, bytes.size.toLong())
                com.multiviewer.parser.BoxNode(
                    type = "root",
                    offset = 0,
                    headerSize = 0,
                    size = bytes.size.toLong(),
                    children = children,
                )
            }
        }

    @Test
    fun `builds spec diagram for standard 24-bit BMP with padding`() {
        // width = 3, height = 2
        // 3 pixels * 3 bytes = 9 bytes per row -> stride = 12 bytes -> 3 bytes padding per row
        val bytes = bmpBytes(width = 3, height = 2)
        val root = parseBmp(bytes)
        val diagram = BmpSpecDiagramBuilder.build(root, bytes.size.toLong(), "sample.bmp")

        assertNotNull(diagram)
        assertEquals("sample.bmp", diagram.title)
        assertEquals(bytes.size.toLong(), diagram.fileSize)

        // Blocks: BITMAPFILEHEADER, DIB Header, PixelArray
        val blockNames = diagram.blocks.map { "${it.title} ${it.subtitle.orEmpty()}" }
        assertTrue(blockNames.any { it.contains("BITMAPFILEHEADER") })
        assertTrue(blockNames.any { it.contains("DIB Header") || it.contains("BITMAPINFOHEADER") })
        assertTrue(blockNames.any { it.contains("PixelArray") || it.contains("Image Data") })

        val fileHeaderBlock = diagram.blocks.first { it.title.contains("Bitmap File Header") || it.subtitle?.contains("BITMAPFILEHEADER") == true }
        val dibHeaderBlock = diagram.blocks.first { it.title.contains("DIB") || it.subtitle?.contains("BITMAPINFOHEADER") == true }
        val pixelArrayBlock = diagram.blocks.first { it.title.contains("Image Data") || it.subtitle?.contains("PixelArray") == true }

        assertEquals(RegionRole.MARKER, fileHeaderBlock.role)
        assertEquals(RegionRole.CODEC_SETUP, dibHeaderBlock.role)
        assertEquals(RegionRole.MEDIA_DATA, pixelArrayBlock.role)

        // Arrow from bfOffBits to PixelArray
        val arrow = diagram.arrows.find { it.toBlockId == pixelArrayBlock.id }
        assertNotNull(arrow, "Expected an arrow pointing to the PixelArray block")

        // File size bracket
        val bracket = diagram.brackets.find { it.toOffset == bytes.size.toLong() }
        assertNotNull(bracket, "Expected a bracket spanning the file size")

        // Padding callout
        val paddingCallout = diagram.callouts.find { it.text.contains("multiple of 4 Bytes", ignoreCase = true) }
        assertNotNull(paddingCallout, "Expected a callout about 4-byte row padding")

        // PixelArray rows should have a padding cell
        val paddingCell = pixelArrayBlock.rows.flatMap { it.cells }.find { it.isPadding }
        assertNotNull(paddingCell, "Expected a padding cell in the PixelArray rows")
        assertEquals(3L, paddingCell!!.byteLength)
    }

    @Test
    fun `builds spec diagram for 24-bit BMP without padding when width is a multiple of 4`() {
        // width = 4, height = 2 -> 4 * 3 = 12 bytes per row -> padding = 0
        val bytes = bmpBytes(width = 4, height = 2)
        val root = parseBmp(bytes)
        val diagram = BmpSpecDiagramBuilder.build(root, bytes.size.toLong(), "unpadded.bmp")

        val pixelArrayBlock = diagram.blocks.first { it.title.contains("PixelArray") || it.title.contains("Image Data") }
        val paddingCell = pixelArrayBlock.rows.flatMap { it.cells }.find { it.isPadding }
        assertNull(paddingCell, "Should not have padding cell when row bytes is already a multiple of 4")

        val paddingCallout = diagram.callouts.find { it.text.contains("multiple of 4 Bytes", ignoreCase = true) }
        assertNull(paddingCallout, "Should not have padding callout when padding is 0")
    }
}
