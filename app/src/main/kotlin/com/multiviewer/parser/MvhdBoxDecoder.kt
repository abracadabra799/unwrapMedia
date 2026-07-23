package com.multiviewer.parser

object MvhdBoxDecoder : BoxDecoder {
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
        val needed = 4 + timeFieldWidth * 3 + 4
        if (payloadEnd - payloadStart < needed) {
            w.add("Box too short for mvhd version $version fields")
            return BoxNode(type, offset, headerSize, size, warnings = w)
        }
        var pos = payloadStart + 4
        val creationTime = readUIntOfWidth(reader, pos, timeFieldWidth)
        pos += timeFieldWidth
        val modificationTime = readUIntOfWidth(reader, pos, timeFieldWidth)
        pos += timeFieldWidth
        val timescaleOffset = pos
        val timescale = reader.readUInt32(pos)
        pos += 4
        val durationOffset = pos
        val duration = readUIntOfWidth(reader, pos, timeFieldWidth)
        pos += timeFieldWidth
        pos += 4 // rate
        pos += 2 // volume
        pos += 10 // reserved
        pos += 36 // matrix
        pos += 24 // predefined
        val nextTrackIdOffset = pos
        val nextTrackId = reader.readUInt32(pos)
        
        val fields = listOf(
            BoxField("version", version.toString(), payloadStart, 1),
            BoxField("creation_time", formatMp4Time(creationTime), payloadStart + 4, timeFieldWidth.toLong()),
            BoxField("modification_time", formatMp4Time(modificationTime), payloadStart + 4 + timeFieldWidth, timeFieldWidth.toLong()),
            BoxField("timescale", timescale.toString(), timescaleOffset, 4),
            BoxField("duration", duration.toString(), durationOffset, timeFieldWidth.toLong()),
            BoxField("next_track_ID", nextTrackId.toString(), nextTrackIdOffset, 4),
        )
        val summary = if (timescale > 0) {
            "timescale=$timescale, duration=${"%.3f".format(duration.toDouble() / timescale.toDouble())}s"
        } else {
            "timescale=$timescale"
        }
        return BoxNode(type, offset, headerSize, size, fields = fields, warnings = w, summary = summary)
    }
}
