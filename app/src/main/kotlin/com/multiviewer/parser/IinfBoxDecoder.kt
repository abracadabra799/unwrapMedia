package com.multiviewer.parser

object IinfBoxDecoder : BoxDecoder {
    override fun decode(
        reader: ByteReader,
        type: String,
        offset: Long,
        headerSize: Int,
        size: Long,
        warnings: List<String>,
    ): BoxNode {
        val w = warnings.toMutableList()
        val payloadStart = offset + headerSize
        val payloadEnd = offset + size
        if (payloadEnd - payloadStart < 4) {
            w.add("Box too short to contain a FullBox header")
            return BoxNode(type, offset, headerSize, size, warnings = w)
        }
        val version = reader.readUInt8(payloadStart)
        val entryCountWidth = if (version == 0) 2 else 4
        if (payloadEnd - payloadStart < 4 + entryCountWidth) {
            w.add("Box too short to contain entry_count")
            return BoxNode(type, offset, headerSize, size, warnings = w)
        }
        val entryCount = if (entryCountWidth == 2) {
            reader.readUInt16(payloadStart + 4).toLong()
        } else {
            reader.readUInt32(payloadStart + 4)
        }
        val childrenStart = payloadStart + 4 + entryCountWidth
        val children = parseBoxes(reader, childrenStart, payloadEnd)
        return BoxNode(
            type = type, offset = offset, headerSize = headerSize, size = size,
            children = children, warnings = w,
            summary = pluralize(entryCount, "item", "items"),
        )
    }
}
