package com.multiviewer.parser

object InfeBoxDecoder : BoxDecoder {
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
        if (version < 2) {
            w.add("Unsupported infe version $version")
            return BoxNode(type, offset, headerSize, size, warnings = w)
        }
        val itemIdWidth = if (version == 2) 2 else 4
        val needed = 4 + itemIdWidth + 2 + 4
        if (payloadEnd - payloadStart < needed) {
            w.add("Box too short for infe version $version fields")
            return BoxNode(type, offset, headerSize, size, warnings = w)
        }
        val itemIdOffset = payloadStart + 4
        val itemId = if (itemIdWidth == 2) {
            reader.readUInt16(itemIdOffset).toLong()
        } else {
            reader.readUInt32(itemIdOffset)
        }
        val protectionIndexOffset = itemIdOffset + itemIdWidth
        val protectionIndex = reader.readUInt16(protectionIndexOffset)
        val itemTypeOffset = protectionIndexOffset + 2
        val itemType = reader.readFourCC(itemTypeOffset)
        val nameOffset = itemTypeOffset + 4
        val nameBytes = reader.readBytes(nameOffset, (payloadEnd - nameOffset).toInt())
        val itemName = String(nameBytes, Charsets.UTF_8).trimEnd(Char(0))
        val fields = listOf(
            BoxField("item_ID", itemId.toString(), itemIdOffset, itemIdWidth.toLong()),
            BoxField("item_protection_index", protectionIndex.toString(), protectionIndexOffset, 2),
            BoxField("item_type", itemType, itemTypeOffset, 4),
            BoxField("item_name", itemName, nameOffset, nameBytes.size.toLong()),
        )
        return BoxNode(
            type = type, offset = offset, headerSize = headerSize, size = size,
            fields = fields, warnings = w,
            summary = "$itemType: $itemName",
        )
    }
}
