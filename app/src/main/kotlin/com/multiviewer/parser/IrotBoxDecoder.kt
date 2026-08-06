package com.multiviewer.parser

object IrotBoxDecoder : BoxDecoder {
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
            w.add("Box too short for irot angle byte")
            return BoxNode(type, offset, headerSize, size, warnings = w)
        }
        val angle = reader.readUInt8(payloadStart) and 0x03
        return BoxNode(
            type, offset, headerSize, size,
            fields = listOf(BoxField("angle", angle.toString(), payloadStart, 1)),
            warnings = w, summary = "${angle * 90}°",
        )
    }
}
