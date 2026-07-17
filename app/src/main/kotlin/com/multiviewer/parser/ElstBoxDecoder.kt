package com.multiviewer.parser

object ElstBoxDecoder : BoxDecoder {
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
        if (payloadEnd - payloadStart < 8) {
            w.add("Box too short to contain a FullBox header and entry count")
            return BoxNode(type, offset, headerSize, size, warnings = w)
        }
        val version = reader.readUInt8(payloadStart)
        val timeFieldWidth = if (version == 1) 8 else 4
        val entryWidth = (timeFieldWidth * 2 + 4).toLong()
        val declaredCount = reader.readUInt32(payloadStart + 4)
        val entriesStart = payloadStart + 8
        val available = payloadEnd - entriesStart
        val fitCount = if (entryWidth == 0L) 0L else available / entryWidth
        val actualCount = minOf(declaredCount, fitCount)
        if (actualCount < declaredCount) {
            w.add("Declared $declaredCount entries but only enough space for $fitCount")
        }

        val fields = mutableListOf<BoxField>()
        var pos = entriesStart
        for (i in 0L until actualCount) {
            val durationOffset = pos
            val duration = readUIntOfWidth(reader, pos, timeFieldWidth)
            pos += timeFieldWidth
            val mediaTimeOffset = pos
            val mediaTime = if (timeFieldWidth == 8) {
                reader.readUInt64(pos)
            } else {
                reader.readUInt32(pos).toInt().toLong()
            }
            pos += timeFieldWidth
            val rateIntegerOffset = pos
            val rateInteger = reader.readUInt16(pos).toShort().toInt()
            pos += 2
            val rateFraction = reader.readUInt16(pos).toShort().toInt()
            pos += 2
            val mediaRate = rateInteger + rateFraction / 65536.0

            fields.add(BoxField("segment_duration", duration.toString(), durationOffset, timeFieldWidth.toLong()))
            fields.add(BoxField("media_time", mediaTime.toString(), mediaTimeOffset, timeFieldWidth.toLong()))
            fields.add(BoxField("media_rate", mediaRate.toString(), rateIntegerOffset, 4))
        }

        return BoxNode(
            type = type, offset = offset, headerSize = headerSize, size = size,
            fields = fields, warnings = w,
            summary = pluralize(declaredCount, "edit", "edits"),
        )
    }
}
