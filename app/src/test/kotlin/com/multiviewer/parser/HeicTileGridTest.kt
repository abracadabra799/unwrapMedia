package com.multiviewer.parser

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HeicTileGridTest {
    @Test
    fun `decodeGridItemPayload reads a 1x2 grid with 16-bit output dimensions`() {
        // version=0, flags=0 (16-bit fields), rows_minus_one=0 (1 row), columns_minus_one=1 (2 cols),
        // output_width=32 (0x0020), output_height=16 (0x0010).
        val bytes = byteArrayOf(0, 0, 0, 1, 0x00, 0x20, 0x00, 0x10)
        val layout = decodeGridItemPayload(bytes)
        assertEquals(GridLayout(rows = 1, columns = 2, outputWidth = 32, outputHeight = 16), layout)
    }

    @Test
    fun `decodeGridItemPayload reads a 3x1 grid with 32-bit output dimensions`() {
        // version=0, flags=1 (32-bit fields), rows_minus_one=2 (3 rows), columns_minus_one=0 (1 col),
        // output_width=300 (0x0000012C), output_height=200 (0x000000C8).
        val bytes = byteArrayOf(
            0, 1, 2, 0,
            0x00, 0x00, 0x01, 0x2C.toByte(),
            0x00, 0x00, 0x00, 0xC8.toByte(),
        )
        val layout = decodeGridItemPayload(bytes)
        assertEquals(GridLayout(rows = 3, columns = 1, outputWidth = 300, outputHeight = 200), layout)
    }

    @Test
    fun `decodeGridItemPayload returns null for input too short to contain the fixed header`() {
        assertNull(decodeGridItemPayload(byteArrayOf(0, 0, 0)))
    }

    @Test
    fun `decodeGridItemPayload returns null when 32-bit fields are declared but truncated`() {
        assertNull(decodeGridItemPayload(byteArrayOf(0, 1, 0, 0, 0, 0, 0, 0)))
    }

    @Test
    fun `findHeicTileGrid resolves a 1x2 grid's layout, tile item IDs, and tile size`() {
        // Grid item (item_ID=3, type "grid") whose own iloc extent points at grid-payload bytes;
        // iref's dimg says 3 -> [1, 2] (row-major tile order); item 1 has an ispe of 16x16.
        // Tile items' own picture data doesn't matter here -- findHeicTileGrid never decodes
        // pixels, only structure (extractHevcItemAnnexB, tested separately in Task 2, covers
        // actually decoding a tile).
        val gridPayload = byteArrayOf(0, 0, 0, 1, 0x00, 0x20, 0x00, 0x10) // 1 row, 2 cols, 32x16 output
        val file = File.createTempFile("heic-tile-grid-fixture-", ".heic")
        file.deleteOnExit()
        file.writeBytes(gridPayload)

        val ispe = BoxNode(
            type = "ispe", offset = 0, headerSize = 0, size = 0,
            fields = listOf(BoxField("image_width", "16", 0, 0), BoxField("image_height", "16", 0, 0)),
        )
        val ipco = BoxNode(type = "ipco", offset = 0, headerSize = 0, size = 0, children = listOf(ispe))
        val ipmaItem1 = BoxNode(
            type = "item_1", offset = 0, headerSize = 0, size = 0,
            fields = listOf(BoxField("property_index", "1", 0, 0)),
        )
        val ipma = BoxNode(type = "ipma", offset = 0, headerSize = 0, size = 0, children = listOf(ipmaItem1))
        val iprp = BoxNode(type = "iprp", offset = 0, headerSize = 0, size = 0, children = listOf(ipco, ipma))

        val gridExtent = BoxNode(
            type = "extent", offset = 0, headerSize = 0, size = 0,
            fields = listOf(BoxField("offset", "0", 0, 0), BoxField("length", gridPayload.size.toString(), 0, 0)),
        )
        val ilocItem3 = BoxNode(
            type = "item_3", offset = 0, headerSize = 0, size = 0,
            fields = listOf(BoxField("construction_method", "0", 0, 0)),
            children = listOf(gridExtent),
        )
        val iloc = BoxNode(type = "iloc", offset = 0, headerSize = 0, size = 0, children = listOf(ilocItem3))

        val infeGrid = BoxNode(
            type = "infe", offset = 0, headerSize = 0, size = 0,
            fields = listOf(BoxField("item_ID", "3", 0, 0), BoxField("item_type", "grid", 0, 0)),
        )
        val iinf = BoxNode(type = "iinf", offset = 0, headerSize = 0, size = 0, children = listOf(infeGrid))

        val dimg = BoxNode(
            type = "dimg", offset = 0, headerSize = 0, size = 0,
            fields = listOf(
                BoxField("from_item_ID", "3", 0, 0),
                BoxField("to_item_ID[0]", "1", 0, 0),
                BoxField("to_item_ID[1]", "2", 0, 0),
            ),
        )
        val iref = BoxNode(type = "iref", offset = 0, headerSize = 0, size = 0, children = listOf(dimg))

        val meta = BoxNode(type = "meta", offset = 0, headerSize = 0, size = 0, children = listOf(iloc, iinf, iref, iprp))
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = file.length(), children = listOf(meta))

        val result = findHeicTileGrid(file, root)

        assertEquals(
            TileGridInfo(
                layout = GridLayout(rows = 1, columns = 2, outputWidth = 32, outputHeight = 16),
                tileItemIds = listOf(1L, 2L),
                tileWidth = 16,
                tileHeight = 16,
            ),
            result,
        )
        file.delete()
    }

    @Test
    fun `findHeicTileGrid returns null when the file has no grid item`() {
        val meta = BoxNode(type = "meta", offset = 0, headerSize = 0, size = 0, children = emptyList())
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = 0, children = listOf(meta))
        assertNull(findHeicTileGrid(File("/nonexistent/does-not-matter.heic"), root))
    }
}
