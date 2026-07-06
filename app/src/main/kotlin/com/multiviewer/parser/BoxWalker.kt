package com.multiviewer.parser

fun parseBoxes(reader: ByteReader, rangeStart: Long, rangeEnd: Long): List<BoxNode> {
    val result = mutableListOf<BoxNode>()
    var pos = rangeStart
    while (pos < rangeEnd) {
        val size32 = reader.readUInt32(pos)
        val type = reader.readFourCC(pos + 4)
        var headerSize = 8
        var size: Long

        if (size32 == 1L) {
            size = reader.readUInt64(pos + 8)
            headerSize = 16
        } else if (size32 == 0L) {
            size = rangeEnd - pos
        } else {
            size = size32
        }

        val decoder = BoxRegistry.decoderFor(type)
        result.add(decoder.decode(reader, type, pos, headerSize, size, emptyList()))
        pos += size
    }
    return result
}
