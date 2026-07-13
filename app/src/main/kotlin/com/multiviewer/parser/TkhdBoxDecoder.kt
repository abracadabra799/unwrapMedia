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
        pos += timeFieldWidth // creation_time (not surfaced)
        pos += timeFieldWidth // modification_time (not surfaced)
        val trackIdOffset = pos
        val trackId = reader.readUInt32(pos)
        pos += 4
        pos += 4 // reserved
        val durationOffset = pos
        val duration = readUIntOfWidth(reader, pos, timeFieldWidth)
        pos += timeFieldWidth
        pos += 8 + 2 + 2 + 2 + 2 + 36 // reserved[2], layer, alternate_group, volume, reserved, matrix
        val widthOffset = pos
        val widthRaw = reader.readUInt32(pos)
        pos += 4
        val heightOffset = pos
        val heightRaw = reader.readUInt32(pos)
        val width = widthRaw / 65536.0
        val height = heightRaw / 65536.0
        val fields = listOf(
            BoxField("track_ID", trackId.toString(), trackIdOffset, 4),
            BoxField("duration", duration.toString(), durationOffset, timeFieldWidth.toLong()),
            BoxField("width", width.toString(), widthOffset, 4),
            BoxField("height", height.toString(), heightOffset, 4),
        )
        return BoxNode(
            type, offset, headerSize, size, fields = fields, warnings = w,
            summary = "track_ID=$trackId, ${"%.0f".format(width)}x${"%.0f".format(height)}",
        )
    }
}
