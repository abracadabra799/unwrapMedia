package com.multiviewer.parser

object ColrBoxDecoder : BoxDecoder {
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
            w.add("Box too short to contain colour_type")
            return BoxNode(type, offset, headerSize, size, warnings = w)
        }
        val colourType = reader.readFourCC(payloadStart)
        if (colourType != "nclx") {
            val remaining = payloadEnd - (payloadStart + 4)
            return BoxNode(
                type = type, offset = offset, headerSize = headerSize, size = size,
                fields = listOf(BoxField("colour_type", colourType, payloadStart, 4)),
                warnings = w,
                summary = "ICC profile ($remaining bytes)",
            )
        }
        if (payloadEnd - payloadStart < 11) {
            w.add("Box too short for nclx fields")
            return BoxNode(
                type = type, offset = offset, headerSize = headerSize, size = size,
                fields = listOf(BoxField("colour_type", colourType, payloadStart, 4)),
                warnings = w,
            )
        }
        val primariesOffset = payloadStart + 4
        val primaries = reader.readUInt16(primariesOffset)
        val transferOffset = payloadStart + 6
        val transfer = reader.readUInt16(transferOffset)
        val matrixOffset = payloadStart + 8
        val matrix = reader.readUInt16(matrixOffset)
        val fullRangeOffset = payloadStart + 10
        val fullRange = (reader.readUInt8(fullRangeOffset) and 0x80) != 0
        val fields = listOf(
            BoxField("colour_type", colourType, payloadStart, 4),
            BoxField("colour_primaries", primaries.toString(), primariesOffset, 2),
            BoxField("transfer_characteristics", transfer.toString(), transferOffset, 2),
            BoxField("matrix_coefficients", matrix.toString(), matrixOffset, 2),
            BoxField("full_range_flag", fullRange.toString(), fullRangeOffset, 1),
        )
        return BoxNode(
            type = type, offset = offset, headerSize = headerSize, size = size,
            fields = fields, warnings = w,
            summary = "nclx: $primaries/$transfer/$matrix",
        )
    }
}
