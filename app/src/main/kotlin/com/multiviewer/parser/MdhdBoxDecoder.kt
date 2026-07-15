package com.multiviewer.parser

object MdhdBoxDecoder : BoxDecoder {
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
        val version = reader.readUInt8(payloadStart)
        val timeFieldWidth = if (version == 1) 8 else 4
        val totalNeeded = 4 + timeFieldWidth * 2 + 4 + timeFieldWidth + 2
        if (payloadEnd - payloadStart < totalNeeded) {
            w.add("Box too short for mdhd version $version fields")
            return BoxNode(type, offset, headerSize, size, warnings = w)
        }
        var pos = payloadStart + 4
        pos += timeFieldWidth // creation_time (not surfaced)
        pos += timeFieldWidth // modification_time (not surfaced)
        val timescaleOffset = pos
        val timescale = reader.readUInt32(pos)
        pos += 4
        val durationOffset = pos
        val duration = readUIntOfWidth(reader, pos, timeFieldWidth)
        pos += timeFieldWidth
        val languageOffset = pos
        val language = unpackLanguage(reader.readUInt16(pos))
        val fields = listOf(
            BoxField("timescale", timescale.toString(), timescaleOffset, 4),
            BoxField("duration", duration.toString(), durationOffset, timeFieldWidth.toLong()),
            BoxField("language", language, languageOffset, 2),
        )
        val summary = if (timescale > 0) {
            "timescale=$timescale, duration=${"%.3f".format(duration.toDouble() / timescale.toDouble())}s, language=$language"
        } else {
            "timescale=$timescale, language=$language"
        }
        return BoxNode(type, offset, headerSize, size, fields = fields, warnings = w, summary = summary)
    }
}

internal fun unpackLanguage(packed: Int): String {
    val c1 = ((packed shr 10) and 0x1F) + 0x60
    val c2 = ((packed shr 5) and 0x1F) + 0x60
    val c3 = (packed and 0x1F) + 0x60
    return "${c1.toChar()}${c2.toChar()}${c3.toChar()}"
}
