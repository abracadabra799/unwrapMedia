package com.multiviewer.parser

object TkhdBoxDecoder : BoxDecoder {
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
        val fixedTail = 8 + 2 + 2 + 2 + 2 + 36 + 4 + 4
        val totalNeeded = 4 + timeFieldWidth * 2 + 4 + 4 + timeFieldWidth + fixedTail
        if (payloadEnd - payloadStart < totalNeeded) {
            w.add("Box too short for tkhd version $version fields")
            return BoxNode(type, offset, headerSize, size, warnings = w)
        }
        var pos = payloadStart + 4
        val creationTime = readUIntOfWidth(reader, pos, timeFieldWidth)
        pos += timeFieldWidth
        val modificationTime = readUIntOfWidth(reader, pos, timeFieldWidth)
        pos += timeFieldWidth
        val trackIdOffset = pos
        val trackId = reader.readUInt32(pos)
        pos += 4
        pos += 4 // reserved
        val durationOffset = pos
        val duration = readUIntOfWidth(reader, pos, timeFieldWidth)
        pos += timeFieldWidth
        pos += 8 // reserved[2]
        val layer = reader.readUInt16(pos)
        pos += 2
        val alternateGroup = reader.readUInt16(pos)
        pos += 2
        val volumeRaw = reader.readUInt16(pos)
        val volume = volumeRaw / 256.0
        pos += 2
        pos += 2 // reserved
        pos += 36 // matrix
        val widthOffset = pos
        val widthRaw = reader.readUInt32(pos)
        pos += 4
        val heightOffset = pos
        val heightRaw = reader.readUInt32(pos)
        val width = widthRaw / 65536.0
        val height = heightRaw / 65536.0
        
        val fields = listOf(
            BoxField("track_ID", trackId.toString(), trackIdOffset, 4),
            BoxField("creation_time", formatMp4Time(creationTime), payloadStart + 4, timeFieldWidth.toLong()),
            BoxField("modification_time", formatMp4Time(modificationTime), payloadStart + 4 + timeFieldWidth, timeFieldWidth.toLong()),
            BoxField("duration", duration.toString(), durationOffset, timeFieldWidth.toLong()),
            BoxField("layer", layer.toString(), pos - 42, 2),
            BoxField("alternate_group", alternateGroup.toString(), pos - 40, 2),
            BoxField("volume", volume.toString(), pos - 38, 2),
            BoxField("width", width.toString(), widthOffset, 4),
            BoxField("height", height.toString(), heightOffset, 4),
        )
        return BoxNode(
            type, offset, headerSize, size, fields = fields, warnings = w,
            summary = "track_ID=$trackId, ${"%.0f".format(width)}x${"%.0f".format(height)}",
        )
    }
}
