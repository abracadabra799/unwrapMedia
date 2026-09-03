package com.multiviewer.parser

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Image
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

// rotationQuarterTurns: the grid item's own "irot" property (HEIF's "angle" field, in units of 90
// degrees counter-clockwise) -- 0 when absent (no rotation). The grid's rows/columns/outputWidth/
// outputHeight above are in the file's raw, PRE-rotation coordinate space; the bitmap this app
// actually decodes and displays already has rotation baked in (ffmpeg applies it automatically), so
// any code mapping a tile's grid-space position onto that displayed bitmap must rotate it by this
// amount first (see TileGridOverlay.kt's rotateRect).
data class TileGridInfo(val layout: GridLayout, val tileItemIds: List<Long>, val tileWidth: Int, val tileHeight: Int, val rotationQuarterTurns: Int = 0)

/**
 * Finds TileGridInfo for an arbitrary grid item ID (e.g. primary image grid or auxiliary gain map grid).
 */
fun findHeicTileGridForItem(file: File, root: BoxNode, gridItemId: Long): TileGridInfo? {
    val meta = findFirst(root) { it.type == "meta" } ?: return null
    val iloc = findFirst(meta) { it.type == "iloc" } ?: return null
    val iref = findFirst(meta) { it.type == "iref" } ?: return null

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
    } catch (_: Exception) {
        null
    } ?: return null

    val firstTileId = tileItemIds.first()
    val ispe = findItemProperty(meta, firstTileId, "ispe")
    val tileWidth = ispe?.fields?.find { it.name == "image_width" }?.value?.toIntOrNull()
        ?: if (layout.columns > 0) layout.outputWidth / layout.columns else 512
    val tileHeight = ispe?.fields?.find { it.name == "image_height" }?.value?.toIntOrNull()
        ?: if (layout.rows > 0) layout.outputHeight / layout.rows else 512

    val rotationQuarterTurns = findItemProperty(meta, gridItemId, "irot")
        ?.fields?.find { it.name == "angle" }?.value?.toIntOrNull() ?: 0

    return TileGridInfo(layout, tileItemIds, tileWidth, tileHeight, rotationQuarterTurns)
}

/**
 * Finds TileGridInfo for the primary grid image in a HEIC file.
 */
fun findHeicTileGrid(file: File, root: BoxNode): TileGridInfo? {
    val meta = findFirst(root) { it.type == "meta" } ?: return null
    val iinf = findFirst(meta) { it.type == "iinf" } ?: return null

    val gridItemId = iinf.children
        .find { it.type == "infe" && it.fields.find { f -> f.name == "item_type" }?.value == "grid" }
        ?.fields?.find { it.name == "item_ID" }?.value?.toLongOrNull() ?: return null

    return findHeicTileGridForItem(file, root, gridItemId)
}

/**
 * Stitches HEIC grid tiles into a single combined ImageBitmap.
 */
fun stitchHeicGridTiles(
    file: File,
    root: BoxNode,
    gridInfo: TileGridInfo,
    decodeTile: (ByteArray) -> ImageBitmap?,
): ImageBitmap? {
    val layout = gridInfo.layout
    if (layout.outputWidth <= 0 || layout.outputHeight <= 0 || layout.rows <= 0 || layout.columns <= 0) return null
    val expectedTiles = layout.rows * layout.columns
    if (gridInfo.tileItemIds.size < expectedTiles) return null

    val decodedTiles = mutableMapOf<Int, ImageBitmap>()
    for ((index, tileId) in gridInfo.tileItemIds.take(expectedTiles).withIndex()) {
        val annexB = extractHevcItemAnnexB(file, root, tileId) ?: continue
        val tileBitmap = decodeTile(annexB) ?: continue
        decodedTiles[index] = tileBitmap
    }
    if (decodedTiles.isEmpty()) return null

    return try {
        val skiaBitmap = Bitmap()
        skiaBitmap.allocN32Pixels(layout.outputWidth, layout.outputHeight)
        val canvas = Canvas(skiaBitmap)

        for (r in 0 until layout.rows) {
            for (c in 0 until layout.columns) {
                val idx = r * layout.columns + c
                val tile = decodedTiles[idx] ?: continue
                val skiaImage = Image.makeFromBitmap(tile.asSkiaBitmap())
                val x = (c * gridInfo.tileWidth).toFloat()
                val y = (r * gridInfo.tileHeight).toFloat()
                canvas.drawImage(skiaImage, x, y)
            }
        }

        skiaBitmap.asComposeImageBitmap()
    } catch (_: Exception) {
        null
    }
}
