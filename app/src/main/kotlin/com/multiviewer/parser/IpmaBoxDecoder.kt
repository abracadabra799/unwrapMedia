package com.multiviewer.parser

object IpmaBoxDecoder : BoxDecoder {
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
        if (payloadEnd - payloadStart < 8) {
            w.add("Box too short for a FullBox header and entry_count")
            return BoxNode(type, offset, headerSize, size, warnings = w)
        }
        val version = reader.readUInt8(payloadStart)
        val flagsLastByte = reader.readUInt8(payloadStart + 3)
        val twoByteAssociations = (flagsLastByte and 0x1) != 0
        val entryCount = reader.readUInt32(payloadStart + 4)

        var pos = payloadStart + 8
        val itemIdWidth = if (version == 0) 2 else 4
        val associationWidth = if (twoByteAssociations) 2 else 1
        val children = mutableListOf<BoxNode>()
        var entriesFound = 0L
        while (entriesFound < entryCount) {
            if (pos + itemIdWidth + 1 > payloadEnd) break
            val itemStart = pos
            val itemId = if (itemIdWidth == 2) reader.readUInt16(pos).toLong() else reader.readUInt32(pos)
            pos += itemIdWidth
            val associationCount = reader.readUInt8(pos)
            pos += 1

            val associationsNeeded = associationCount.toLong() * associationWidth
            if (pos + associationsNeeded > payloadEnd) {
                w.add("Item $itemId's property associations run past the end of the box")
                break
            }

            val fields = mutableListOf<BoxField>()
            for (a in 0 until associationCount) {
                val assocOffset = pos
                val propertyIndex = if (twoByteAssociations) {
                    reader.readUInt16(pos) and 0x7FFF
                } else {
                    reader.readUInt8(pos) and 0x7F
                }
                pos += associationWidth
                fields.add(BoxField("property_index", propertyIndex.toString(), assocOffset, associationWidth.toLong()))
            }

            children.add(
                BoxNode(
                    type = "item_$itemId",
                    offset = itemStart,
                    headerSize = itemIdWidth + 1,
                    size = pos - itemStart,
                    fields = fields,
                    summary = "properties: ${fields.joinToString(", ") { it.value }}",
                ),
            )
            entriesFound++
        }
        if (entriesFound < entryCount) {
            w.add("Declared $entryCount entries but only found $entriesFound")
        }

        return BoxNode(
            type = type, offset = offset, headerSize = headerSize, size = size,
            children = children, warnings = w,
            summary = pluralize(entryCount, "entry", "entries"),
        )
    }
}
