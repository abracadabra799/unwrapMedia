package com.multiviewer.parser

object UrlBoxDecoder : BoxDecoder {
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
            w.add("Box too short to contain a FullBox header")
            return BoxNode(type, offset, headerSize, size, warnings = w)
        }
        val flags = reader.readUInt32(payloadStart) and 0xFFFFFFL
        val selfContained = (flags and 0x1L) == 1L
        val fields = mutableListOf<BoxField>()
        if (!selfContained && payloadEnd > payloadStart + 4) {
            val locationOffset = payloadStart + 4
            val locationBytes = reader.readBytes(locationOffset, (payloadEnd - locationOffset).toInt())
            val location = String(locationBytes, Charsets.UTF_8).trimEnd(Char(0))
            fields.add(BoxField("location", location, locationOffset, locationBytes.size.toLong()))
        }
        val summary = if (selfContained) "self-contained" else null
        return BoxNode(type, offset, headerSize, size, fields = fields, warnings = w, summary = summary)
    }
}
