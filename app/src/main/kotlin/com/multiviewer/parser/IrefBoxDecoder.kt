package com.multiviewer.parser

object IrefBoxDecoder : BoxDecoder {
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
            w.add("Box too short for a FullBox header")
            return BoxNode(type, offset, headerSize, size, warnings = w)
        }
        val version = reader.readUInt8(payloadStart)
        val idWidth = if (version == 0) 2 else 4
        
        var pos = payloadStart + 4
        val children = mutableListOf<BoxNode>()
        while (pos + 8 <= payloadEnd) {
            val entrySize = reader.readUInt32(pos)
            val entryType = reader.readFourCC(pos + 4)
            if (pos + entrySize > payloadEnd) {
                w.add("Reference entry $entryType extends past end of box")
                break
            }
            
            val entryPayloadStart = pos + 8
            val fromId = if (idWidth == 2) reader.readUInt16(entryPayloadStart).toLong() else reader.readUInt32(entryPayloadStart)
            val refCount = reader.readUInt16(entryPayloadStart + idWidth)
            val toIds = mutableListOf<Long>()
            var toIdPos = entryPayloadStart + idWidth + 2
            for (i in 0 until refCount) {
                if (toIdPos + idWidth > pos + entrySize) break
                val toId = if (idWidth == 2) reader.readUInt16(toIdPos).toLong() else reader.readUInt32(toIdPos)
                toIds.add(toId)
                toIdPos += idWidth
            }
            
            children.add(
                BoxNode(
                    type = entryType,
                    offset = pos,
                    headerSize = 8,
                    size = entrySize,
                    fields = listOf(
                        BoxField("from_item_ID", fromId.toString(), entryPayloadStart, idWidth.toLong()),
                        BoxField("reference_count", refCount.toString(), entryPayloadStart + idWidth, 2)
                    ) + toIds.mapIndexed { index, id ->
                        BoxField("to_item_ID[$index]", id.toString(), entryPayloadStart + idWidth + 2 + index * idWidth, idWidth.toLong())
                    },
                    summary = "$fromId -> ${toIds.joinToString(", ")}"
                )
            )
            pos += entrySize
        }
        
        return BoxNode(
            type = type, offset = offset, headerSize = headerSize, size = size,
            children = children, warnings = w
        )
    }
}
