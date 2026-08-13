package com.multiviewer.parser

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
