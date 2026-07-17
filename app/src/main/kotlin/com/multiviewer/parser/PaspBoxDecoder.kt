package com.multiviewer.parser

object PaspBoxDecoder : BoxDecoder {
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
            w.add("Box too short for pasp fields")
            return BoxNode(type, offset, headerSize, size, warnings = w)
        }
        val hSpacingOffset = payloadStart
        val hSpacing = reader.readUInt32(hSpacingOffset)
        val vSpacingOffset = payloadStart + 4
        val vSpacing = reader.readUInt32(vSpacingOffset)
        val fields = listOf(
            BoxField("hSpacing", hSpacing.toString(), hSpacingOffset, 4),
            BoxField("vSpacing", vSpacing.toString(), vSpacingOffset, 4),
        )
        return BoxNode(
            type = type, offset = offset, headerSize = headerSize, size = size,
            fields = fields, warnings = w,
            summary = "$hSpacing:$vSpacing",
        )
    }
}
