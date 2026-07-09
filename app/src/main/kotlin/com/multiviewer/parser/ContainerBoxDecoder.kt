package com.multiviewer.parser

class ContainerBoxDecoder(
    private val childOffsetInPayload: Int = 0,
    private val summarize: Boolean = false,
) : BoxDecoder {
    override fun decode(
        reader: ByteReader,
        type: String,
        offset: Long,
        headerSize: Int,
        size: Long,
        warnings: List<String>,
    ): BoxNode {
        val payloadStart = offset + headerSize + childOffsetInPayload
        val payloadEnd = offset + size
        val children = parseBoxes(reader, payloadStart, payloadEnd)
        val summary = if (summarize) "${children.size} entries" else null
        return BoxNode(
            type = type,
            offset = offset,
            headerSize = headerSize,
            size = size,
            children = children,
            warnings = warnings,
            summary = summary,
        )
    }
}
