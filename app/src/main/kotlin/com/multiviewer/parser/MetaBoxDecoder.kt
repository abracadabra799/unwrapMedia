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
        val enrichedChildren = enrichItemMetadata(reader, children)
        return BoxNode(
            type = type,
            offset = offset,
            headerSize = headerSize,
            size = size,
            children = enrichedChildren,
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

private fun enrichItemMetadata(reader: ByteReader, children: List<BoxNode>): List<BoxNode> {
    val iinfIndex = children.indexOfFirst { it.type == "iinf" }
    val ilocIndex = children.indexOfFirst { it.type == "iloc" }
    if (iinfIndex < 0 || ilocIndex < 0) return children

    val itemInfo = mutableMapOf<Long, Pair<String, String?>>()
    for (infe in children[iinfIndex].children) {
        val itemId = infe.fields.find { it.name == "item_ID" }?.value?.toLongOrNull() ?: continue
        val itemType = infe.fields.find { it.name == "item_type" }?.value ?: continue
        val contentType = infe.fields.find { it.name == "content_type" }?.value
        itemInfo[itemId] = itemType to contentType
    }

    val idatPayloadOffset = children.find { it.type == "idat" }?.let { it.offset + it.headerSize }

    val ilocNode = children[ilocIndex]
    val enrichedItems = ilocNode.children.map { itemNode ->
        val itemId = itemNode.type.removePrefix("item_").toLongOrNull() ?: return@map itemNode
        val (itemType, contentType) = itemInfo[itemId] ?: return@map itemNode
        enrichIlocItem(reader, itemNode, itemType, contentType, idatPayloadOffset)
    }
    val enrichedIloc = ilocNode.copy(children = enrichedItems)

    return children.toMutableList().also { it[ilocIndex] = enrichedIloc }
}

private fun enrichIlocItem(
    reader: ByteReader,
    itemNode: BoxNode,
    itemType: String,
    contentType: String?,
    idatPayloadOffset: Long?,
): BoxNode {
    val extentWarnings = mutableListOf<String>()
    val resolvedExtents = itemNode.children.map { extent ->
        val idatRelative = extent.fields.find { it.name == "idat_relative_offset" }?.value?.toLongOrNull()
        if (idatRelative == null) {
            extent
        } else if (idatPayloadOffset == null) {
            extentWarnings.add("Item ${itemNode.type}: idat box not found, cannot resolve idat-relative offset")
            extent
        } else {
            val absoluteOffset = idatPayloadOffset + idatRelative
            val length = extent.fields.find { it.name == "length" }?.value?.toLongOrNull() ?: 0L
            extent.copy(
                fields = listOf(
                    BoxField("offset", absoluteOffset.toString(), extent.offset, extent.size),
                    BoxField("length", length.toString(), extent.offset, extent.size),
                ),
                summary = "offset=$absoluteOffset, length=$length",
            )
        }
    }
    val enrichedItem = itemNode.copy(
        children = resolvedExtents,
        warnings = itemNode.warnings + extentWarnings,
    )

    val singleExtent = resolvedExtents.singleOrNull() ?: return enrichedItem
    val extentOffset = singleExtent.fields.find { it.name == "offset" }?.value?.toLongOrNull() ?: return enrichedItem
    val extentLength = singleExtent.fields.find { it.name == "length" }?.value?.toLongOrNull() ?: return enrichedItem

    return when {
        itemType == "Exif" -> {
            val exifChildren = decodeExif(reader, extentOffset, extentOffset + extentLength)
            enrichedItem.copy(children = enrichedItem.children + exifChildren, summary = "Exif metadata")
        }
        itemType == "mime" && contentType == "application/rdf+xml" -> {
            val bytes = reader.readBytes(extentOffset, extentLength.toInt())
            val text = String(bytes, Charsets.UTF_8).trimEnd(' ', Char(0))
            enrichedItem.copy(
                fields = enrichedItem.fields + BoxField("xmp", text, extentOffset, extentLength),
                summary = "XMP (${text.length} chars)",
            )
        }
        else -> enrichedItem
    }
}
