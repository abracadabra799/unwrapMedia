package com.multiviewer.parser

private const val EXTENSION_INTRODUCER = 0x21
private const val IMAGE_DESCRIPTOR_INTRODUCER = 0x2C
private const val TRAILER = 0x3B
private const val COMMENT_LABEL = 0xFE
private const val PLAIN_TEXT_LABEL = 0x01

fun parseGifBlocks(reader: ByteReader, start: Long, end: Long): List<BoxNode> {
    val result = mutableListOf<BoxNode>()
    if (start + 7 > end) {
        result.add(BoxNode("?", start, 0, end - start, warnings = listOf("Trailing ${end - start} byte(s): too short for a Logical Screen Descriptor")))
        return result
    }

    val packed = reader.readUInt8(start + 4)
    val globalColorTableFlag = (packed shr 7) and 0x01
    result.add(decodeLogicalScreenDescriptor(reader, start))
    var pos = start + 7

    if (globalColorTableFlag == 1) {
        val globalColorTableSize = packed and 0x07
        val tableBytes = 3L * (1L shl (globalColorTableSize + 1))
        if (pos + tableBytes > end) {
            result.add(BoxNode("GlobalColorTable", pos, 0, end - pos, warnings = listOf("Global Color Table declares $tableBytes byte(s) but only ${end - pos} remain")))
            return result
        }
        result.add(BoxNode("GlobalColorTable", pos, 0, tableBytes, fields = listOf(BoxField("size", tableBytes.toString(), pos, tableBytes))))
        pos += tableBytes
    }

    while (pos < end) {
        val introducer = reader.readUInt8(pos)
        if (introducer == TRAILER) {
            result.add(BoxNode("Trailer", pos, 1, 1))
            return result
        }
        if (introducer == EXTENSION_INTRODUCER) {
            if (pos + 2 > end) {
                result.add(BoxNode("?", pos, 0, end - pos, warnings = listOf("Trailing ${end - pos} byte(s): too short for an extension label")))
                return result
            }
            val label = reader.readUInt8(pos + 1)
            val decoded = decodeExtension(reader, label, pos, end)
            if (decoded == null) {
                result.add(BoxNode("?", pos, 0, end - pos, warnings = listOf("Malformed extension at offset $pos")))
                return result
            }
            result.add(decoded.first)
            pos = decoded.second
        } else if (introducer == IMAGE_DESCRIPTOR_INTRODUCER) {
            val decoded = decodeImageDescriptor(reader, pos, end)
            if (decoded == null) {
                result.add(BoxNode("?", pos, 0, end - pos, warnings = listOf("Malformed image descriptor at offset $pos")))
                return result
            }
            result.add(decoded.first)
            pos = decoded.second
        } else {
            result.add(BoxNode("?", pos, 0, end - pos, warnings = listOf("Unexpected byte 0x${introducer.toString(16).padStart(2, '0')} where a block introducer was expected")))
            return result
        }
    }
    return result
}

private fun decodeExtension(reader: ByteReader, label: Int, offset: Long, end: Long): Pair<BoxNode, Long>? =
    when (label) {
        COMMENT_LABEL -> decodeGenericSubBlockExtension(reader, "CommentExtension", offset, end)
        PLAIN_TEXT_LABEL -> decodeGenericSubBlockExtension(reader, "PlainTextExtension", offset, end)
        else -> decodeGenericSubBlockExtension(reader, "Extension_0x${label.toString(16).padStart(2, '0').uppercase()}", offset, end)
    }

private fun decodeGenericSubBlockExtension(reader: ByteReader, type: String, offset: Long, end: Long): Pair<BoxNode, Long>? {
    val (_, nextPos) = readSubBlocks(reader, offset + 2, end) ?: return null
    return BoxNode(type = type, offset = offset, headerSize = 2, size = nextPos - offset) to nextPos
}

private fun decodeLogicalScreenDescriptor(reader: ByteReader, offset: Long): BoxNode {
    val width = readUInt16LE(reader, offset)
    val height = readUInt16LE(reader, offset + 2)
    val packed = reader.readUInt8(offset + 4)
    val globalColorTableFlag = (packed shr 7) and 0x01
    val colorResolution = (packed shr 4) and 0x07
    val sortFlag = (packed shr 3) and 0x01
    val globalColorTableSize = packed and 0x07
    val backgroundColorIndex = reader.readUInt8(offset + 5)
    val pixelAspectRatio = reader.readUInt8(offset + 6)
    return BoxNode(
        type = "LogicalScreenDescriptor", offset = offset, headerSize = 0, size = 7,
        fields = listOf(
            BoxField("width", width.toString(), offset, 2),
            BoxField("height", height.toString(), offset + 2, 2),
            BoxField("global_color_table_flag", globalColorTableFlag.toString(), offset + 4, 1),
            BoxField("color_resolution", colorResolution.toString(), offset + 4, 1),
            BoxField("sort_flag", sortFlag.toString(), offset + 4, 1),
            BoxField("global_color_table_size", globalColorTableSize.toString(), offset + 4, 1),
            BoxField("background_color_index", backgroundColorIndex.toString(), offset + 5, 1),
            BoxField("pixel_aspect_ratio", pixelAspectRatio.toString(), offset + 6, 1),
        ),
        summary = "${width}x${height}",
    )
}

private fun decodeImageDescriptor(reader: ByteReader, offset: Long, end: Long): Pair<BoxNode, Long>? {
    val fixedEnd = offset + 10
    if (fixedEnd > end) return null
    val left = readUInt16LE(reader, offset + 1)
    val top = readUInt16LE(reader, offset + 3)
    val width = readUInt16LE(reader, offset + 5)
    val height = readUInt16LE(reader, offset + 7)
    val packed = reader.readUInt8(offset + 9)
    val localColorTableFlag = (packed shr 7) and 0x01
    val interlaceFlag = (packed shr 6) and 0x01
    val localColorTableSize = packed and 0x07

    var pos = fixedEnd
    val children = mutableListOf<BoxNode>()
    if (localColorTableFlag == 1) {
        val tableBytes = 3L * (1L shl (localColorTableSize + 1))
        if (pos + tableBytes > end) return null
        children.add(BoxNode("LocalColorTable", pos, 0, tableBytes, fields = listOf(BoxField("size", tableBytes.toString(), pos, tableBytes))))
        pos += tableBytes
    }

    if (pos + 1 > end) return null
    pos += 1 // LZW minimum code size

    val (_, nextPos) = readSubBlocks(reader, pos, end) ?: return null

    return BoxNode(
        type = "ImageDescriptor", offset = offset, headerSize = 10, size = nextPos - offset,
        fields = listOf(
            BoxField("left", left.toString(), offset + 1, 2),
            BoxField("top", top.toString(), offset + 3, 2),
            BoxField("width", width.toString(), offset + 5, 2),
            BoxField("height", height.toString(), offset + 7, 2),
            BoxField("local_color_table_flag", localColorTableFlag.toString(), offset + 9, 1),
            BoxField("interlace_flag", interlaceFlag.toString(), offset + 9, 1),
            BoxField("local_color_table_size", localColorTableSize.toString(), offset + 9, 1),
        ),
        children = children,
        summary = "${width}x${height} at ($left,$top)",
    ) to nextPos
}

private fun readSubBlocks(reader: ByteReader, pos: Long, end: Long): Pair<List<ByteArray>, Long>? {
    val blocks = mutableListOf<ByteArray>()
    var p = pos
    while (true) {
        if (p >= end) return null
        val size = reader.readUInt8(p)
        p += 1
        if (size == 0) break
        if (p + size > end) return null
        blocks.add(reader.readBytes(p, size))
        p += size
    }
    return blocks to p
}

private fun readUInt16LE(reader: ByteReader, offset: Long): Int {
    val b = reader.readBytes(offset, 2)
    return (b[0].toInt() and 0xFF) or ((b[1].toInt() and 0xFF) shl 8)
}
