package com.multiviewer.parser

object VisualSampleEntryDecoder : BoxDecoder {
    private const val FIXED_HEADER_SIZE = 78

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
        if (payloadEnd - payloadStart < FIXED_HEADER_SIZE) {
            w.add("Box too short for VisualSampleEntry fixed header")
            return BoxNode(type, offset, headerSize, size, warnings = w)
        }
        val dataReferenceIndexOffset = payloadStart + 6
        val dataReferenceIndex = reader.readUInt16(dataReferenceIndexOffset)
        val widthOffset = payloadStart + 24
        val width = reader.readUInt16(widthOffset)
        val heightOffset = payloadStart + 26
        val height = reader.readUInt16(heightOffset)
        val fields = listOf(
            BoxField("data_reference_index", dataReferenceIndex.toString(), dataReferenceIndexOffset, 2),
            BoxField("width", width.toString(), widthOffset, 2),
            BoxField("height", height.toString(), heightOffset, 2),
        )
        val children = parseBoxes(reader, payloadStart + FIXED_HEADER_SIZE, payloadEnd)
        return BoxNode(
            type = type, offset = offset, headerSize = headerSize, size = size,
            fields = fields, children = children, warnings = w,
            summary = "${width}x${height}",
        )
    }
}
