package com.multiviewer.parser

fun parseBoxes(reader: ByteReader, rangeStart: Long, rangeEnd: Long): List<BoxNode> {
    val result = mutableListOf<BoxNode>()
    var pos = rangeStart
    while (pos < rangeEnd) {
        val remaining = rangeEnd - pos
        if (remaining < 8) {
            result.add(
                BoxNode(
                    type = "?",
                    offset = pos,
                    headerSize = 0,
                    size = remaining,
                    warnings = listOf("Trailing $remaining byte(s): too short for a box header"),
                )
            )
            break
        }

        val size32 = reader.readUInt32(pos)
        val type = reader.readFourCC(pos + 4)
        var headerSize = 8
        var size: Long

        if (size32 == 1L) {
            if (remaining < 16) {
                result.add(
                    BoxNode(
                        type = type,
                        offset = pos,
                        headerSize = 8,
                        size = remaining,
                        warnings = listOf("Declared a 64-bit size but only $remaining byte(s) remain"),
                    )
                )
                break
            }
            size = reader.readUInt64(pos + 8)
            headerSize = 16
        } else if (size32 == 0L) {
            size = remaining
        } else {
            size = size32
        }

        val warnings = mutableListOf<String>()
        if (size < headerSize) {
            warnings.add("Declared size $size is smaller than header size $headerSize")
            size = remaining
        } else if (pos + size > rangeEnd) {
            warnings.add("Declared size $size extends ${pos + size - rangeEnd} byte(s) past the end of its parent")
            size = remaining
        }

        val decoder = BoxRegistry.decoderFor(type)
        result.add(decoder.decode(reader, type, pos, headerSize, size, warnings))
        pos += size
    }
    return result
}
