package com.multiviewer.parser

import java.io.File

// HEIF "ImageGrid" item payload (ISO/IEC 23008-12 §6.6.3): a grid-derived image's own item data
// (referenced via iloc like any other item, but never itself a nested box) records how many
// tile rows/columns make up the image and the assembled canvas size.
data class GridLayout(val rows: Int, val columns: Int, val outputWidth: Int, val outputHeight: Int)

// byte 0 = version (unused -- this decoder, like IspeBoxDecoder, doesn't need to branch on it),
// byte 1 = flags (bit 0: 0 = 16-bit output_width/output_height fields, 1 = 32-bit),
// byte 2 = rows_minus_one, byte 3 = columns_minus_one,
// then output_width, output_height as 16-bit or 32-bit big-endian per the flags bit.
fun decodeGridItemPayload(bytes: ByteArray): GridLayout? {
    if (bytes.size < 4) return null
    val flags = bytes[1].toInt() and 0xFF
    val rows = (bytes[2].toInt() and 0xFF) + 1
    val columns = (bytes[3].toInt() and 0xFF) + 1
    val large = (flags and 1) == 1
    val fieldSize = if (large) 4 else 2
    if (bytes.size < 4 + fieldSize * 2) return null
    fun readUInt(offset: Int): Int = if (large) {
        ((bytes[offset].toInt() and 0xFF) shl 24) or ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or (bytes[offset + 3].toInt() and 0xFF)
    } else {
        ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)
    }
    val outputWidth = readUInt(4)
    val outputHeight = readUInt(4 + fieldSize)
    return GridLayout(rows, columns, outputWidth, outputHeight)
}

data class TileGridInfo(val layout: GridLayout, val tileItemIds: List<Long>, val tileWidth: Int, val tileHeight: Int)

// Combines three already-parsed pieces into one lookup: which item is the grid, what its own
// row/column/output-size payload says (decodeGridItemPayload above), and which tile items it
// references (iref's "dimg", in row-major order per the HEIF spec) -- plus each tile's pixel size
// from the first tile's own ispe property (HEIF requires uniform tile size except naturally-
// cropped right/bottom edge tiles, so the first tile is representative). Returns null at any
// missing piece, mirroring extractHevcThumbnailAnnexB's own all-or-nothing style.
fun findHeicTileGrid(file: File, root: BoxNode): TileGridInfo? {
    val meta = findFirst(root) { it.type == "meta" } ?: return null
    val iloc = findFirst(meta) { it.type == "iloc" } ?: return null
    val iinf = findFirst(meta) { it.type == "iinf" } ?: return null
    val iref = findFirst(meta) { it.type == "iref" } ?: return null

    val gridItemId = iinf.children
        .find { it.type == "infe" && it.fields.find { f -> f.name == "item_type" }?.value == "grid" }
        ?.fields?.find { it.name == "item_ID" }?.value?.toLongOrNull() ?: return null

    val tileItemIds = iref.children
        .find { it.type == "dimg" && it.fields.find { f -> f.name == "from_item_ID" }?.value?.toLongOrNull() == gridItemId }
        ?.fields?.filter { it.name.startsWith("to_item_ID") }?.mapNotNull { it.value.toLongOrNull() }
        ?: return null
    if (tileItemIds.isEmpty()) return null

    val idatBase = findFirst(root) { it.type == "idat" }?.let { it.offset + it.headerSize } ?: 0L
    val layout = try {
        ByteReader.open(file).use { reader ->
            val gridBytes = extractItemBytes(reader, iloc, gridItemId, idatBase) ?: return@use null
            decodeGridItemPayload(gridBytes)
        }
    } catch (e: Exception) {
        null
    } ?: return null

    val firstTileId = tileItemIds.first()
    val ispe = findItemProperty(meta, firstTileId, "ispe") ?: return null
    val tileWidth = ispe.fields.find { it.name == "image_width" }?.value?.toIntOrNull() ?: return null
    val tileHeight = ispe.fields.find { it.name == "image_height" }?.value?.toIntOrNull() ?: return null

    return TileGridInfo(layout, tileItemIds, tileWidth, tileHeight)
}
