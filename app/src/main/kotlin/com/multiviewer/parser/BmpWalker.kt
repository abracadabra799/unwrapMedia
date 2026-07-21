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

private fun decodeBitmapInfoHeader(reader: ByteReader, offset: Long, end: Long): BoxNode {
    if (end - offset < 40) {
        return BoxNode(type = "BITMAPINFOHEADER", offset = offset, headerSize = 0, size = end - offset, warnings = listOf("Truncated BITMAPINFOHEADER"))
    }
    val width = readInt32LE(reader, offset + 4)
    val height = readInt32LE(reader, offset + 8)
    val bitCount = readUInt16LE(reader, offset + 14)
    val compression = readUInt32LE(reader, offset + 16)
    return BoxNode(
        type = "BITMAPINFOHEADER", offset = offset, headerSize = 0, size = 40,
        fields = listOf(
            BoxField("width", width.toString(), offset + 4, 4),
            BoxField("height", height.toString(), offset + 8, 4),
            BoxField("bit_count", bitCount.toString(), offset + 14, 2),
            BoxField("compression", compression.toString(), offset + 16, 4),
        ),
        summary = "${width}x${height}, ${bitCount}-bit",
    )
}
