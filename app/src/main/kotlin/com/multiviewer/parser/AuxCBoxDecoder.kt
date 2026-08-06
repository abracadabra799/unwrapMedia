package com.multiviewer.parser

object AuxCBoxDecoder : BoxDecoder {
    override fun decode(
        reader: ByteReader,
        type: String,
        offset: Long,
        headerSize: Int,
        size: Long,
        warnings: List<String>,
    ): BoxNode {
        val w = warnings.toMutableList()
        val payloadStart = offset + headerSize + 4 // skip version(1)+flags(3)
        val payloadEnd = offset + size
        if (payloadEnd - payloadStart < 1) {
            w.add("Box too short for auxC aux_type")
            return BoxNode(type, offset, headerSize, size, warnings = w)
        }
        val bytes = reader.readBytes(payloadStart, (payloadEnd - payloadStart).toInt())
        val nullIndex = bytes.indexOf(0)
        val auxType = String(bytes, 0, if (nullIndex >= 0) nullIndex else bytes.size, Charsets.US_ASCII)
        return BoxNode(
            type, offset, headerSize, size,
            fields = listOf(BoxField("aux_type", auxType, payloadStart, (if (nullIndex >= 0) nullIndex else bytes.size).toLong())),
            warnings = w, summary = auxType,
        )
    }
}
