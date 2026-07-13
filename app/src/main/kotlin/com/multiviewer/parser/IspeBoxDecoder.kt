package com.multiviewer.parser

object IspeBoxDecoder : BoxDecoder {
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
        if (payloadEnd - payloadStart < 12) {
            w.add("Box too short for ispe fields")
            return BoxNode(type, offset, headerSize, size, warnings = w)
        }
        val widthOffset = payloadStart + 4
        val width = reader.readUInt32(widthOffset)
        val heightOffset = payloadStart + 8
        val height = reader.readUInt32(heightOffset)
        val fields = listOf(
            BoxField("image_width", width.toString(), widthOffset, 4),
            BoxField("image_height", height.toString(), heightOffset, 4),
        )
        return BoxNode(type, offset, headerSize, size, fields = fields, warnings = w, summary = "${width}x${height}")
    }
}
