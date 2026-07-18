package com.multiviewer.parser

object IlocBoxDecoder : BoxDecoder {
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
            w.add("Box too short for a FullBox header and iloc size fields")
            return BoxNode(type, offset, headerSize, size, warnings = w)
        }
        val version = reader.readUInt8(payloadStart)
        val sizesByte1 = reader.readUInt8(payloadStart + 4)
        val offsetSize = (sizesByte1 shr 4) and 0xF
        val lengthSize = sizesByte1 and 0xF
        val sizesByte2 = reader.readUInt8(payloadStart + 5)
        val baseOffsetSize = (sizesByte2 shr 4) and 0xF
        val indexSize = sizesByte2 and 0xF

        if (offsetSize !in setOf(4, 8) || lengthSize !in setOf(4, 8) || baseOffsetSize !in setOf(0, 4, 8)) {
            w.add("Unsupported offset_size/length_size/base_offset_size combination ($offsetSize/$lengthSize/$baseOffsetSize)")
            return BoxNode(type, offset, headerSize, size, warnings = w)
        }

        var pos = payloadStart + 6
        val itemCountWidth = if (version < 2) 2 else 4
        if (pos + itemCountWidth > payloadEnd) {
            w.add("Box too short to contain item_count")
            return BoxNode(type, offset, headerSize, size, warnings = w)
        }
        val itemCount = if (itemCountWidth == 2) reader.readUInt16(pos).toLong() else reader.readUInt32(pos)
        pos += itemCountWidth

        val children = mutableListOf<BoxNode>()
        var itemsFound = 0L
        val itemIdWidth = if (version < 2) 2 else 4
        val constructionMethodWidth = if (version == 1 || version == 2) 2 else 0
        while (itemsFound < itemCount) {
            val fixedItemHeaderSize = itemIdWidth + constructionMethodWidth + 2 + baseOffsetSize + 2
            if (pos + fixedItemHeaderSize > payloadEnd) break

            val itemStart = pos
            val itemId = if (itemIdWidth == 2) reader.readUInt16(pos).toLong() else reader.readUInt32(pos)
            pos += itemIdWidth
            val constructionMethod = if (constructionMethodWidth > 0) {
                reader.readUInt16(pos) and 0xF
            } else {
                0
            }
            pos += constructionMethodWidth
            pos += 2 // data_reference_index, not surfaced
            val baseOffset = if (baseOffsetSize > 0) readUIntOfWidth(reader, pos, baseOffsetSize) else 0L
            pos += baseOffsetSize
            val extentCount = reader.readUInt16(pos)
            pos += 2

            val extentEntryWidth = indexSize + offsetSize + lengthSize
            val extentsNeeded = extentCount.toLong() * extentEntryWidth
            if (pos + extentsNeeded > payloadEnd) {
                w.add("Item $itemId's extents run past the end of the box")
                break
            }

            val extents = mutableListOf<BoxNode>()
            for (e in 0 until extentCount) {
                val extentStart = pos
                if (indexSize > 0) pos += indexSize
                val extentOffset = readUIntOfWidth(reader, pos, offsetSize)
                pos += offsetSize
                val extentLength = readUIntOfWidth(reader, pos, lengthSize)
                pos += lengthSize
                extents.add(
                    buildExtentNode(extentStart, pos - extentStart, constructionMethod, baseOffset, extentOffset, extentLength, itemId, w),
                )
            }

            children.add(
                BoxNode(
                    type = "item_$itemId",
                    offset = itemStart,
                    headerSize = fixedItemHeaderSize,
                    size = pos - itemStart,
                    children = extents,
                    fields = listOf(BoxField("construction_method", constructionMethod.toString(), itemStart + itemIdWidth, constructionMethodWidth.toLong())),
                ),
            )
            itemsFound++
        }
        if (itemsFound < itemCount) {
            w.add("Declared $itemCount items but only found $itemsFound")
        }

        return BoxNode(
            type = type, offset = offset, headerSize = headerSize, size = size,
            children = children, warnings = w,
            summary = pluralize(itemCount, "item", "items"),
        )
    }
}

private fun buildExtentNode(
    offset: Long,
    size: Long,
    constructionMethod: Int,
    baseOffset: Long,
    extentOffset: Long,
    extentLength: Long,
    itemId: Long,
    warnings: MutableList<String>,
): BoxNode {
    return when (constructionMethod) {
        0 -> {
            val absoluteOffset = baseOffset + extentOffset
            BoxNode(
                type = "extent", offset = offset, headerSize = 0, size = size,
                fields = listOf(
                    BoxField("offset", absoluteOffset.toString(), offset, size),
                    BoxField("length", extentLength.toString(), offset, size),
                ),
                summary = "offset=$absoluteOffset, length=$extentLength",
            )
        }
        1 -> {
            val idatRelativeOffset = baseOffset + extentOffset
            BoxNode(
                type = "extent", offset = offset, headerSize = 0, size = size,
                fields = listOf(
                    BoxField("idat_relative_offset", idatRelativeOffset.toString(), offset, size),
                    BoxField("length", extentLength.toString(), offset, size),
                ),
                summary = "idat_relative_offset=$idatRelativeOffset, length=$extentLength",
            )
        }
        else -> {
            warnings.add("Item $itemId: construction_method=$constructionMethod (item offset) is not supported")
            BoxNode(
                type = "extent", offset = offset, headerSize = 0, size = size,
                fields = listOf(
                    BoxField("extent_offset", extentOffset.toString(), offset, size),
                    BoxField("base_offset", baseOffset.toString(), offset, size),
                    BoxField("length", extentLength.toString(), offset, size),
                ),
                summary = "unresolved (construction_method=$constructionMethod)",
            )
        }
    }
}
