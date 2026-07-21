package com.multiviewer.parser

val PNG_COLOR_TYPE_NAMES = mapOf(
    0 to "Grayscale",
    2 to "Truecolor",
    3 to "Indexed",
    4 to "Grayscale+Alpha",
    6 to "Truecolor+Alpha",
)

fun parsePngChunks(reader: ByteReader, start: Long, end: Long): List<BoxNode> {
    val result = mutableListOf<BoxNode>()
    var pos = start
    while (pos < end) {
        if (pos + 8 > end) {
            result.add(BoxNode("?", pos, 0, end - pos, warnings = listOf("Trailing ${end - pos} byte(s): too short for a chunk header")))
            break
        }
        val length = reader.readUInt32(pos)
        val type = reader.readFourCC(pos + 4)
        val dataStart = pos + 8
        val chunkTotalSize = 8L + length + 4L
        if (pos + chunkTotalSize > end) {
            result.add(BoxNode(type, pos, 8, end - pos, warnings = listOf("Chunk declares length $length but only ${end - pos - 8} byte(s) remain")))
            break
        }
        result.add(decodePngChunk(reader, type, pos, dataStart, length, chunkTotalSize))
        pos += chunkTotalSize
    }
    return result
}

private fun decodePngChunk(reader: ByteReader, type: String, offset: Long, dataStart: Long, length: Long, totalSize: Long): BoxNode =
    when (type) {
        "IHDR" -> decodeIhdr(reader, offset, dataStart, totalSize)
        "pHYs" -> decodePhys(reader, offset, dataStart, totalSize)
        "tEXt" -> decodeText(reader, offset, dataStart, length, totalSize)
        "eXIf" -> decodeExifChunk(reader, offset, dataStart, dataStart + length, totalSize)
        else -> BoxNode(type = type, offset = offset, headerSize = 8, size = totalSize)
    }

private fun decodeIhdr(reader: ByteReader, offset: Long, dataStart: Long, totalSize: Long): BoxNode {
    if (totalSize < 25) { // 8 (length+type) + 13 (IHDR body) + 4 (crc)
        return BoxNode(type = "IHDR", offset = offset, headerSize = 8, size = totalSize, warnings = listOf("IHDR chunk too short to contain all fields"))
    }
    val width = reader.readUInt32(dataStart)
    val height = reader.readUInt32(dataStart + 4)
    val bitDepth = reader.readUInt8(dataStart + 8)
    val colorType = reader.readUInt8(dataStart + 9)
    val compressionMethod = reader.readUInt8(dataStart + 10)
    val filterMethod = reader.readUInt8(dataStart + 11)
    val interlaceMethod = reader.readUInt8(dataStart + 12)
    val colorTypeName = PNG_COLOR_TYPE_NAMES[colorType] ?: "Unknown"
    return BoxNode(
        type = "IHDR", offset = offset, headerSize = 8, size = totalSize,
        fields = listOf(
            BoxField("width", width.toString(), dataStart, 4),
            BoxField("height", height.toString(), dataStart + 4, 4),
            BoxField("bit_depth", bitDepth.toString(), dataStart + 8, 1),
            BoxField("color_type", colorType.toString(), dataStart + 9, 1),
            BoxField("compression_method", compressionMethod.toString(), dataStart + 10, 1),
            BoxField("filter_method", filterMethod.toString(), dataStart + 11, 1),
            BoxField("interlace_method", interlaceMethod.toString(), dataStart + 12, 1),
        ),
        summary = "${width}x${height}, $colorTypeName, ${bitDepth}-bit",
    )
}

private fun decodePhys(reader: ByteReader, offset: Long, dataStart: Long, totalSize: Long): BoxNode {
    if (totalSize < 21) { // 8 (length+type) + 9 (pHYs body) + 4 (crc)
        return BoxNode(type = "pHYs", offset = offset, headerSize = 8, size = totalSize, warnings = listOf("pHYs chunk too short to contain all fields"))
    }
    val ppuX = reader.readUInt32(dataStart)
    val ppuY = reader.readUInt32(dataStart + 4)
    val unitSpecifier = reader.readUInt8(dataStart + 8)
    val unitLabel = if (unitSpecifier == 1) "meter" else "unknown"
    return BoxNode(
        type = "pHYs", offset = offset, headerSize = 8, size = totalSize,
        fields = listOf(
            BoxField("pixels_per_unit_x", ppuX.toString(), dataStart, 4),
            BoxField("pixels_per_unit_y", ppuY.toString(), dataStart + 4, 4),
            BoxField("unit_specifier", unitLabel, dataStart + 8, 1),
        ),
        summary = "${ppuX}x${ppuY} px/$unitLabel",
    )
}

private fun decodeText(reader: ByteReader, offset: Long, dataStart: Long, length: Long, totalSize: Long): BoxNode {
    val bytes = reader.readBytes(dataStart, length.toInt())
    val nullIndex = bytes.indexOf(0)
    if (nullIndex < 0) {
        return BoxNode(type = "tEXt", offset = offset, headerSize = 8, size = totalSize, warnings = listOf("Missing keyword/text separator"))
    }
    val keyword = String(bytes, 0, nullIndex, Charsets.ISO_8859_1)
    val text = String(bytes, nullIndex + 1, bytes.size - nullIndex - 1, Charsets.ISO_8859_1)
    return BoxNode(
        type = "tEXt", offset = offset, headerSize = 8, size = totalSize,
        fields = listOf(
            BoxField("keyword", keyword, dataStart, nullIndex.toLong()),
            BoxField("text", text, dataStart + nullIndex + 1, (bytes.size - nullIndex - 1).toLong()),
        ),
        summary = "$keyword: $text",
    )
}

private fun decodeExifChunk(reader: ByteReader, offset: Long, dataStart: Long, dataEnd: Long, totalSize: Long): BoxNode {
    val children = decodeTiff(reader, dataStart, dataEnd)
    return BoxNode(type = "eXIf", offset = offset, headerSize = 8, size = totalSize, children = children, summary = "Exif metadata")
}
