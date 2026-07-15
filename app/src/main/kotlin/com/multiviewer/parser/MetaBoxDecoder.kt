package com.multiviewer.parser

object MetaBoxDecoder : BoxDecoder {
    override fun decode(
        reader: ByteReader,
        type: String,
        offset: Long,
        headerSize: Int,
        size: Long,
        warnings: List<String>,
    ): BoxNode {
        val payloadStart = offset + headerSize
        val payloadEnd = offset + size
        val childOffsetInPayload = if (isPlainBoxLayout(reader, payloadStart, payloadEnd)) 0 else 4
        val children = parseBoxes(reader, payloadStart + childOffsetInPayload, payloadEnd)
        return BoxNode(
            type = type,
            offset = offset,
            headerSize = headerSize,
            size = size,
            children = children,
            warnings = warnings,
        )
    }
}

private fun isPlainBoxLayout(reader: ByteReader, payloadStart: Long, payloadEnd: Long): Boolean {
    val fourCcOffset = payloadStart + 4
    if (fourCcOffset + 4 > payloadEnd) return false
    val bytes = reader.readBytes(fourCcOffset, 4)
    return bytes.all { (it.toInt() and 0xFF) in 0x20..0x7E }
}
