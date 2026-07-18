package com.multiviewer.parser

private val MARKER_NAMES: Map<Int, String> = buildMap {
    put(0x01, "TEM")
    for (m in 0xC0..0xC3) put(m, "SOF${m - 0xC0}")
    put(0xC4, "DHT")
    for (m in 0xC5..0xC7) put(m, "SOF${m - 0xC0}")
    for (m in 0xC9..0xCB) put(m, "SOF${m - 0xC0}")
    put(0xCC, "DAC")
    for (m in 0xCD..0xCF) put(m, "SOF${m - 0xC0}")
    for (m in 0xD0..0xD7) put(m, "RST${m - 0xD0}")
    put(0xD8, "SOI")
    put(0xD9, "EOI")
    put(0xDA, "SOS")
    put(0xDB, "DQT")
    put(0xDC, "DNL")
    put(0xDD, "DRI")
    put(0xDE, "DHP")
    put(0xDF, "EXP")
    for (m in 0xE0..0xEF) put(m, "APP${m - 0xE0}")
    put(0xFE, "COM")
}

private val SOF_MARKERS = setOf(0xC0, 0xC1, 0xC2, 0xC3, 0xC5, 0xC6, 0xC7, 0xC9, 0xCA, 0xCB, 0xCD, 0xCE, 0xCF)

private val NO_PAYLOAD_MARKERS = setOf(0x01, 0xD8, 0xD9) + (0xD0..0xD7).toSet()

private fun markerName(marker: Int): String =
    MARKER_NAMES[marker] ?: "0x${marker.toString(16).padStart(2, '0').uppercase()}"

fun parseJpegSegments(reader: ByteReader, start: Long, end: Long): List<BoxNode> {
    val result = mutableListOf<BoxNode>()
    var pos = start
    while (pos < end) {
        val remaining = end - pos
        if (remaining < 2) {
            result.add(BoxNode("?", pos, 0, remaining, warnings = listOf("Trailing $remaining byte(s): too short for a marker")))
            break
        }

        val markerPrefix = reader.readUInt8(pos)
        if (markerPrefix != 0xFF) {
            result.add(
                BoxNode(
                    "?", pos, 0, remaining,
                    warnings = listOf("Expected marker prefix 0xFF, found 0x${markerPrefix.toString(16).padStart(2, '0')}"),
                ),
            )
            break
        }
        val marker = reader.readUInt8(pos + 1)

        if (marker in NO_PAYLOAD_MARKERS) {
            result.add(BoxNode(markerName(marker), pos, 2, 2))
            pos += 2
            continue
        }

        if (remaining < 4) {
            result.add(
                BoxNode(markerName(marker), pos, 2, remaining, warnings = listOf("Trailing $remaining byte(s): too short for a segment length")),
            )
            break
        }
        val length = reader.readUInt16(pos + 2)
        if (length < 2) {
            result.add(
                BoxNode(markerName(marker), pos, 2, remaining, warnings = listOf("Declared length $length is smaller than the 2 length bytes themselves")),
            )
            break
        }
        val declaredSize = 2L + length
        if (pos + declaredSize > end) {
            result.add(
                BoxNode(markerName(marker), pos, 2, remaining, warnings = listOf("Declared length $length extends past the end of the file")),
            )
            break
        }

        var totalSize = declaredSize
        if (marker == 0xDA) {
            var scanPos = pos + declaredSize
            while (scanPos + 2 <= end) {
                if (reader.readUInt8(scanPos) == 0xFF) {
                    val next = reader.readUInt8(scanPos + 1)
                    if (next != 0x00 && next != 0xFF && next !in 0xD0..0xD7) break
                }
                scanPos += 1
            }
            if (scanPos + 2 > end) scanPos = end
            totalSize = scanPos - pos
        }

        result.add(decodeSegment(reader, marker, pos, declaredSize, totalSize))
        pos += totalSize
    }
    return result
}

private fun decodeSegment(reader: ByteReader, marker: Int, offset: Long, declaredSize: Long, totalSize: Long): BoxNode {
    val name = markerName(marker)
    if (marker in SOF_MARKERS) {
        return decodeSof(reader, name, offset, declaredSize, totalSize)
    }
    return BoxNode(type = name, offset = offset, headerSize = 4, size = totalSize)
}

private fun decodeSof(reader: ByteReader, name: String, offset: Long, declaredSize: Long, totalSize: Long): BoxNode {
    val payloadStart = offset + 4
    val payloadEnd = offset + declaredSize
    if (payloadEnd - payloadStart < 6) {
        return BoxNode(name, offset, 4, totalSize, warnings = listOf("Segment too short to contain SOF fields"))
    }
    val precision = reader.readUInt8(payloadStart)
    val height = reader.readUInt16(payloadStart + 1)
    val width = reader.readUInt16(payloadStart + 3)
    val numComponents = reader.readUInt8(payloadStart + 5)
    val fields = mutableListOf(
        BoxField("precision", precision.toString(), payloadStart, 1),
        BoxField("height", height.toString(), payloadStart + 1, 2),
        BoxField("width", width.toString(), payloadStart + 3, 2),
        BoxField("num_components", numComponents.toString(), payloadStart + 5, 1),
    )
    var pos = payloadStart + 6
    var componentCount = 0
    for (i in 0 until numComponents) {
        if (pos + 3 > payloadEnd) break
        val componentId = reader.readUInt8(pos)
        val samplingFactors = reader.readUInt8(pos + 1)
        val quantizationTable = reader.readUInt8(pos + 2)
        fields.add(BoxField("component_id", componentId.toString(), pos, 1))
        fields.add(BoxField("sampling_factors", "0x${samplingFactors.toString(16).padStart(2, '0')}", pos + 1, 1))
        fields.add(BoxField("quantization_table", quantizationTable.toString(), pos + 2, 1))
        componentCount += 1
        pos += 3
    }
    return BoxNode(
        type = name, offset = offset, headerSize = 4, size = totalSize,
        fields = fields,
        summary = "${width}x${height}, $componentCount component(s)",
    )
}
