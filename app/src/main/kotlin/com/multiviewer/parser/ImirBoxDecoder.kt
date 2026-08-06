package com.multiviewer.parser

object ImirBoxDecoder : BoxDecoder {
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
        if (offset + size - payloadStart < 1) {
            w.add("Box too short for imir axis byte")
            return BoxNode(type, offset, headerSize, size, warnings = w)
        }
        val axis = reader.readUInt8(payloadStart) and 0x01
        return BoxNode(
            type, offset, headerSize, size,
            fields = listOf(BoxField("axis", axis.toString(), payloadStart, 1)),
            warnings = w,
        )
    }
}
