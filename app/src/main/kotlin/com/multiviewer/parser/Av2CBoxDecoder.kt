package com.multiviewer.parser

object Av2CBoxDecoder : BoxDecoder {
    override fun decode(
        reader: ByteReader,
        type: String,
        offset: Long,
        headerSize: Int,
        size: Long,
        warnings: List<String>,
    ): BoxNode {
        val resultWarnings = warnings.toMutableList()
        val payloadStart = offset + headerSize
        val declaredPayloadEnd = offset + size
        val payloadEnd = minOf(declaredPayloadEnd, reader.length)
        if (declaredPayloadEnd > reader.length) resultWarnings += "av2C box extends beyond end of file"
        if (payloadStart > payloadEnd || payloadEnd - payloadStart < 2) {
            resultWarnings += "Box too short for av2C fixed header"
            return BoxNode(type, offset, headerSize, size, warnings = resultWarnings)
        }

        val reserved = reader.readUInt8(payloadStart)
        val countMinusOne = reader.readUInt8(payloadStart + 1)
        if (reserved != 0) resultWarnings += "av2C reserved byte must be 0"
        val fields = listOf(
            BoxField("reserved", reserved.toString(), payloadStart, 1),
            BoxField("config_obus_count_minus1", countMinusOne.toString(), payloadStart + 1, 1),
        )
        val children = mutableListOf<BoxNode>()
        var hasSequenceHeader = false
        var cursor = payloadStart + 2
        val configObuCount = countMinusOne + 1
        for (index in 0 until configObuCount) {
            if (cursor >= payloadEnd) {
                resultWarnings += "config_obu[$index] is missing"
                break
            }
            val length = when (val parsedLength = readAv2Leb128(reader, cursor, payloadEnd)) {
                is Av2ParseResult.Error -> {
                    resultWarnings += "config_obu[$index] ${parsedLength.message}"
                    break
                }
                is Av2ParseResult.Value -> parsedLength
            }
            val obuStart = length.nextOffset
            val obuEnd = obuStart + length.value
            if (length.value <= 0) {
                resultWarnings += "config_obu[$index] has no OBU header"
                break
            }
            if (obuEnd < obuStart || obuEnd > payloadEnd) {
                resultWarnings += "config_obu[$index] extends beyond av2C payload"
                break
            }
            val header = when (val parsedHeader = parseAv2ObuHeader(reader, obuStart, obuEnd)) {
                is Av2ParseResult.Error -> {
                    resultWarnings += "config_obu[$index] ${parsedHeader.message}"
                    break
                }
                is Av2ParseResult.Value -> parsedHeader.value
            }
            if (header.obuType == 1) hasSequenceHeader = true
            if (header.obuType in setOf(2, 3, 4, 5, 6, 7, 10, 11, 12, 13, 14, 15, 17, 18, 19, 20, 21, 22, 25)) {
                resultWarnings += "config_obu[$index] ${av2ObuTypeName(header.obuType)} is forbidden in av2C"
            }
            val obuFields = buildList {
                add(BoxField("declared_size", length.value.toString(), cursor, length.nextOffset - cursor))
                add(BoxField("obu_type", header.obuType.toString(), obuStart, 1))
                add(BoxField("temporal_layer_id", header.temporalLayerId.toString(), obuStart, 1))
                if (header.extensionFlag) {
                    add(BoxField("embedded_layer_id", header.embeddedLayerId.toString(), obuStart + 1, 1))
                    add(BoxField("extended_layer_id", header.extendedLayerId.toString(), obuStart + 1, 1))
                }
            }
            children += BoxNode(
                type = av2ObuTypeName(header.obuType), offset = obuStart, headerSize = header.headerSize,
                size = length.value, fields = obuFields,
                summary = "${av2ObuTypeName(header.obuType)}, ${length.value} bytes",
            )
            cursor = obuEnd
        }
        if (cursor < payloadEnd && children.size == configObuCount) {
            resultWarnings += "av2C has bytes after declared config_obu records"
        }
        if (!hasSequenceHeader) resultWarnings += "av2C must contain an OBU_SEQUENCE_HEADER"
        return BoxNode(
            type = type, offset = offset, headerSize = headerSize, size = size,
            fields = fields, children = children, warnings = resultWarnings,
            summary = "$configObuCount configuration OBU(s)",
        )
    }
}
