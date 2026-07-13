package com.multiviewer.parser

internal fun readUIntOfWidth(reader: ByteReader, offset: Long, width: Int): Long = when (width) {
    4 -> reader.readUInt32(offset)
    8 -> reader.readUInt64(offset)
    else -> error("Unsupported field width: $width")
}
