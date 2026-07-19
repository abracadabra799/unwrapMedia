package com.multiviewer.parser

object PitmBoxDecoder : BoxDecoder {
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
        val itemIdWidth = if (version == 0) 2 else 4
        val itemIdOffset = payloadStart + 4
        if (payloadEnd - itemIdOffset < itemIdWidth) {
            w.add("Box too short for primary_item_ID")
            return BoxNode(type, offset, headerSize, size, warnings = w)
        }
        val primaryItemId = if (itemIdWidth == 2) {
            reader.readUInt16(itemIdOffset).toLong()
        } else {
            reader.readUInt32(itemIdOffset)
        }
        return BoxNode(
            type = type, offset = offset, headerSize = headerSize, size = size,
            fields = listOf(BoxField("primary_item_ID", primaryItemId.toString(), itemIdOffset, itemIdWidth.toLong())),
            warnings = w,
            summary = "primary_item_ID=$primaryItemId",
        )
    }
}
