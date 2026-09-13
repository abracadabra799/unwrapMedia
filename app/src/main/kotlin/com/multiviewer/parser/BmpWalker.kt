package com.multiviewer.parser

private fun readUInt16LE(reader: ByteReader, offset: Long): Int {
    val b = reader.readBytes(offset, 2)
    return (b[0].toInt() and 0xFF) or ((b[1].toInt() and 0xFF) shl 8)
}

private fun readUInt32LE(reader: ByteReader, offset: Long): Long {
    val b = reader.readBytes(offset, 4)
    return (b[0].toLong() and 0xFF) or
        ((b[1].toLong() and 0xFF) shl 8) or
        ((b[2].toLong() and 0xFF) shl 16) or
        ((b[3].toLong() and 0xFF) shl 24)
}

private fun readInt32LE(reader: ByteReader, offset: Long): Int = readUInt32LE(reader, offset).toInt()

internal val BMP_COMPRESSION_NAMES = mapOf(
    0 to "None (BI_RGB)",
    1 to "RLE 8-bit (BI_RLE8)",
    2 to "RLE 4-bit (BI_RLE4)",
    3 to "Bit Fields (BI_BITFIELDS)",
    4 to "JPEG (BI_JPEG)",
    5 to "PNG (BI_PNG)",
    6 to "Alpha Bit Fields (BI_ALPHABITFIELDS)",
    11 to "CMYK (BI_CMYK)",
    12 to "CMYK RLE 8-bit (BI_CMYKRLE8)",
    13 to "CMYK RLE 4-bit (BI_CMYKRLE4)",
)

fun parseBmpHeaders(reader: ByteReader, start: Long, end: Long): List<BoxNode> {
    if (end - start < 14) {
        return listOf(BoxNode("?", start, 0, end - start, warnings = listOf("File too short for a BITMAPFILEHEADER")))
    }
    val result = mutableListOf<BoxNode>()
    result.add(decodeBitmapFileHeader(reader, start))

    val dibStart = start + 14
    if (end - dibStart < 4) return result
    val headerSize = readUInt32LE(reader, dibStart)
    result.add(
        if (headerSize == 40L) {
            decodeBitmapInfoHeader(reader, dibStart, end)
        } else {
            BoxNode(
                type = "DIBHEADER", offset = dibStart, headerSize = 0, size = minOf(headerSize, end - dibStart),
                fields = listOf(BoxField("header_size", headerSize.toString(), dibStart, 4)),
            )
        },
    )
    return result
}

private fun decodeBitmapFileHeader(reader: ByteReader, offset: Long): BoxNode {
    val fileSize = readUInt32LE(reader, offset + 2)
    val pixelDataOffset = readUInt32LE(reader, offset + 10)
    return BoxNode(
        type = "BITMAPFILEHEADER", offset = offset, headerSize = 0, size = 14,
        fields = listOf(
            BoxField("signature", "BM", offset, 2),
            BoxField("file_size", fileSize.toString(), offset + 2, 4),
            BoxField("pixel_data_offset", pixelDataOffset.toString(), offset + 10, 4),
        ),
    )
}

// Shared by BITMAPINFOHEADER, BITMAPV4HEADER, and BITMAPV5HEADER -- all
// three start with this exact 40-byte layout (verified against Microsoft's
// Win32 API docs; see the plan's byte-offset reference table).
private fun buildBitmapInfoHeaderFields(reader: ByteReader, offset: Long): List<BoxField> {
    val width = readInt32LE(reader, offset + 4)
    val height = readInt32LE(reader, offset + 8)
    val planes = readUInt16LE(reader, offset + 12)
    val bitCount = readUInt16LE(reader, offset + 14)
    val compression = readUInt32LE(reader, offset + 16)
    val compressionLabel = BMP_COMPRESSION_NAMES[compression.toInt()] ?: "Unknown ($compression)"
    val imageSize = readUInt32LE(reader, offset + 20)
    val xPelsPerMeter = readInt32LE(reader, offset + 24)
    val yPelsPerMeter = readInt32LE(reader, offset + 28)
    val colorsUsed = readUInt32LE(reader, offset + 32)
    val colorsImportant = readUInt32LE(reader, offset + 36)
    return listOf(
        BoxField("width", width.toString(), offset + 4, 4),
        BoxField("height", height.toString(), offset + 8, 4),
        BoxField("planes", planes.toString(), offset + 12, 2),
        BoxField("bit_count", bitCount.toString(), offset + 14, 2),
        BoxField("compression", compressionLabel, offset + 16, 4),
        BoxField("image_size", imageSize.toString(), offset + 20, 4),
        BoxField("x_pixels_per_meter", xPelsPerMeter.toString(), offset + 24, 4),
        BoxField("y_pixels_per_meter", yPelsPerMeter.toString(), offset + 28, 4),
        BoxField("colors_used", colorsUsed.toString(), offset + 32, 4),
        BoxField("colors_important", colorsImportant.toString(), offset + 36, 4),
    )
}

private fun decodeBitmapInfoHeader(reader: ByteReader, offset: Long, end: Long): BoxNode {
    if (end - offset < 40) {
        return BoxNode(type = "BITMAPINFOHEADER", offset = offset, headerSize = 0, size = end - offset, warnings = listOf("Truncated BITMAPINFOHEADER"))
    }
    val fields = buildBitmapInfoHeaderFields(reader, offset)
    val width = fields.first { it.name == "width" }.value
    val height = fields.first { it.name == "height" }.value
    val bitCount = fields.first { it.name == "bit_count" }.value
    return BoxNode(
        type = "BITMAPINFOHEADER", offset = offset, headerSize = 0, size = 40,
        fields = fields,
        summary = "${width}x${height}, ${bitCount}-bit",
    )
}
