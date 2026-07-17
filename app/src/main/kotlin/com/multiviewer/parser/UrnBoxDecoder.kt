package com.multiviewer.parser

object UrnBoxDecoder : BoxDecoder {
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
        val stringsOffset = payloadStart + 4
        val stringBytes = reader.readBytes(stringsOffset, (payloadEnd - stringsOffset).toInt())
        val nullIndex = stringBytes.indexOf(0)
        val nameEnd = if (nullIndex >= 0) nullIndex else stringBytes.size
        val name = String(stringBytes, 0, nameEnd, Charsets.UTF_8)
        val locationStart = if (nullIndex >= 0) nullIndex + 1 else stringBytes.size
        val locationBytes = stringBytes.copyOfRange(locationStart, stringBytes.size)
        val location = String(locationBytes, Charsets.UTF_8).trimEnd(Char(0))
        val fields = listOf(
            BoxField("name", name, stringsOffset, nameEnd.toLong()),
            BoxField("location", location, stringsOffset + locationStart, locationBytes.size.toLong()),
        )
        return BoxNode(type, offset, headerSize, size, fields = fields, warnings = w, summary = "$name: $location")
    }
}
