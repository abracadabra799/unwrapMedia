package com.multiviewer.parser

object HdlrBoxDecoder : BoxDecoder {
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
        val needed = 4 + 4 + 4 + 12
        if (payloadEnd - payloadStart < needed) {
            w.add("Box too short for hdlr fixed fields")
            return BoxNode(type, offset, headerSize, size, warnings = w)
        }
        val handlerTypeOffset = payloadStart + 8
        val handlerType = reader.readFourCC(handlerTypeOffset)
        val nameOffset = payloadStart + needed
        val nameBytes = reader.readBytes(nameOffset, (payloadEnd - nameOffset).toInt())
        val name = String(nameBytes, Charsets.UTF_8).trimEnd('\u0000')
        val fields = listOf(
            BoxField("handler_type", handlerType, handlerTypeOffset, 4),
            BoxField("name", name, nameOffset, nameBytes.size.toLong()),
        )
        return BoxNode(type, offset, headerSize, size, fields = fields, warnings = w, summary = "$handlerType: $name")
    }
}
