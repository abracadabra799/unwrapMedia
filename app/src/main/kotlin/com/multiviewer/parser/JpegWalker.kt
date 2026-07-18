package com.multiviewer.parser

import kotlin.math.roundToInt

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
    return when {
        marker in SOF_MARKERS -> decodeSof(reader, name, offset, declaredSize, totalSize)
        marker == 0xE1 -> decodeApp1(reader, name, offset, declaredSize, totalSize)
        marker == 0xDB -> decodeDqt(reader, name, offset, declaredSize, totalSize)
        marker == 0xC4 -> decodeDht(reader, name, offset, declaredSize, totalSize)
        marker == 0xDA -> decodeSos(reader, name, offset, declaredSize, totalSize)
        marker == 0xFE -> decodeCom(reader, name, offset, declaredSize, totalSize)
        marker == 0xE0 -> decodeApp0(reader, name, offset, declaredSize, totalSize)
        else -> BoxNode(type = name, offset = offset, headerSize = 4, size = totalSize)
    }
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

private val EXIF_PREFIX = byteArrayOf(0x45, 0x78, 0x69, 0x66, 0x00, 0x00) // "Exif" + 2 NUL bytes
private val XMP_IDENTIFIER = "http://ns.adobe.com/xap/1.0/".toByteArray(Charsets.US_ASCII)

private fun decodeApp1(reader: ByteReader, name: String, offset: Long, declaredSize: Long, totalSize: Long): BoxNode {
    val payloadStart = offset + 4
    val payloadEnd = offset + declaredSize

    if (payloadEnd - payloadStart >= EXIF_PREFIX.size &&
        reader.readBytes(payloadStart, EXIF_PREFIX.size).contentEquals(EXIF_PREFIX)
    ) {
        val tiffStart = payloadStart + EXIF_PREFIX.size
        val children = decodeTiff(reader, tiffStart, payloadEnd)
        return BoxNode(type = name, offset = offset, headerSize = 4, size = totalSize, children = children, summary = "Exif metadata")
    }

    val xmpPrefixSize = XMP_IDENTIFIER.size + 1
    if (payloadEnd - payloadStart >= xmpPrefixSize &&
        reader.readBytes(payloadStart, XMP_IDENTIFIER.size).contentEquals(XMP_IDENTIFIER)
    ) {
        val textStart = payloadStart + xmpPrefixSize
        val textLength = payloadEnd - textStart
        val text = String(reader.readBytes(textStart, textLength.toInt()), Charsets.UTF_8).trimEnd(' ', Char(0))
        return BoxNode(
            type = name, offset = offset, headerSize = 4, size = totalSize,
            fields = listOf(BoxField("xmp", text, textStart, textLength)),
            summary = "XMP (${text.length} chars)",
        )
    }

    return BoxNode(type = name, offset = offset, headerSize = 4, size = totalSize)
}

private val ZIGZAG_TO_RASTER = intArrayOf(
    0, 1, 8, 16, 9, 2, 3, 10,
    17, 24, 32, 25, 18, 11, 4, 5,
    12, 19, 26, 33, 40, 48, 41, 34,
    27, 20, 13, 6, 7, 14, 21, 28,
    35, 42, 49, 56, 57, 50, 43, 36,
    29, 22, 15, 23, 30, 37, 44, 51,
    58, 59, 52, 45, 38, 31, 39, 46,
    53, 60, 61, 54, 47, 55, 62, 63,
)

private val BASELINE_LUMINANCE = intArrayOf(
    16, 11, 10, 16, 24, 40, 51, 61,
    12, 12, 14, 19, 26, 58, 60, 55,
    14, 13, 16, 24, 40, 57, 69, 56,
    14, 17, 22, 29, 51, 87, 80, 62,
    18, 22, 37, 56, 68, 109, 103, 77,
    24, 35, 55, 64, 81, 104, 113, 92,
    49, 64, 78, 87, 103, 121, 120, 101,
    72, 92, 95, 98, 112, 100, 103, 99,
)

private val BASELINE_CHROMINANCE = intArrayOf(
    17, 18, 24, 47, 99, 99, 99, 99,
    18, 21, 26, 66, 99, 99, 99, 99,
    24, 26, 56, 99, 99, 99, 99, 99,
    47, 66, 99, 99, 99, 99, 99, 99,
    99, 99, 99, 99, 99, 99, 99, 99,
    99, 99, 99, 99, 99, 99, 99, 99,
    99, 99, 99, 99, 99, 99, 99, 99,
    99, 99, 99, 99, 99, 99, 99, 99,
)

private fun estimateQuality(rasterTable: IntArray, baseline: IntArray): Int {
    var sumRatio = 0.0
    for (i in 0 until 64) {
        sumRatio += rasterTable[i].toDouble() / baseline[i]
    }
    val ratio = sumRatio / 64
    val scaleFactor = ratio * 100.0
    val quality = if (scaleFactor < 100.0) 100.0 - scaleFactor / 2.0 else 5000.0 / scaleFactor
    return quality.roundToInt().coerceIn(1, 100)
}

private fun decodeDqt(reader: ByteReader, name: String, offset: Long, declaredSize: Long, totalSize: Long): BoxNode {
    val payloadStart = offset + 4
    val payloadEnd = offset + declaredSize
    val children = mutableListOf<BoxNode>()
    val warnings = mutableListOf<String>()
    var pos = payloadStart
    while (pos < payloadEnd) {
        if (pos + 1 > payloadEnd) {
            warnings.add("Trailing byte(s) too short for a quantization table header")
            break
        }
        val pqTq = reader.readUInt8(pos)
        val precision = pqTq shr 4
        val destinationId = pqTq and 0x0F
        val valueSize = if (precision == 0) 1 else 2
        val tableBytes = 1 + 64 * valueSize
        if (pos + tableBytes > payloadEnd) {
            warnings.add("Quantization table at offset $pos needs $tableBytes byte(s) but only ${payloadEnd - pos} remain")
            break
        }
        val zigzag = IntArray(64)
        var valuePos = pos + 1
        for (k in 0 until 64) {
            zigzag[k] = if (valueSize == 1) reader.readUInt8(valuePos) else reader.readUInt16(valuePos)
            valuePos += valueSize
        }
        val raster = IntArray(64)
        for (k in 0 until 64) {
            raster[ZIGZAG_TO_RASTER[k]] = zigzag[k]
        }
        val baseline = if (destinationId == 0) BASELINE_LUMINANCE else BASELINE_CHROMINANCE
        val quality = estimateQuality(raster, baseline)
        children.add(
            BoxNode(
                type = "QuantizationTable",
                offset = pos,
                headerSize = 1,
                size = tableBytes.toLong(),
                fields = listOf(
                    BoxField("precision", precision.toString(), pos, 1),
                    BoxField("destination_id", destinationId.toString(), pos, 1),
                    BoxField("quality_estimate", "~$quality%", pos, tableBytes.toLong()),
                ),
                grid = GridData(8, 8, raster.map { it.toString() }),
                summary = "precision=$precision, destination_id=$destinationId, quality~$quality%",
            ),
        )
        pos += tableBytes
    }
    return BoxNode(
        type = name, offset = offset, headerSize = 4, size = totalSize,
        children = children, warnings = warnings,
        summary = "${children.size} quantization table(s)",
    )
}

private fun decodeDht(reader: ByteReader, name: String, offset: Long, declaredSize: Long, totalSize: Long): BoxNode {
    val payloadStart = offset + 4
    val payloadEnd = offset + declaredSize
    val children = mutableListOf<BoxNode>()
    val warnings = mutableListOf<String>()
    var pos = payloadStart
    while (pos < payloadEnd) {
        if (pos + 1 + 16 > payloadEnd) {
            warnings.add("Huffman table at offset $pos needs at least 17 byte(s) but only ${payloadEnd - pos} remain")
            break
        }
        val classDest = reader.readUInt8(pos)
        val tableClass = classDest shr 4
        val destinationId = classDest and 0x0F
        val bitCounts = IntArray(16)
        var totalCodes = 0
        for (i in 0 until 16) {
            bitCounts[i] = reader.readUInt8(pos + 1 + i)
            totalCodes += bitCounts[i]
        }
        val tableBytes = 1 + 16 + totalCodes
        if (pos + tableBytes > payloadEnd) {
            warnings.add("Huffman table at offset $pos declares $totalCodes code(s) but not enough symbol data remains")
            break
        }
        val className = if (tableClass == 0) "DC" else "AC"
        children.add(
            BoxNode(
                type = "HuffmanTable",
                offset = pos,
                headerSize = 1,
                size = tableBytes.toLong(),
                fields = listOf(
                    BoxField("class", className, pos, 1),
                    BoxField("destination_id", destinationId.toString(), pos, 1),
                    BoxField("bit_counts", bitCounts.joinToString(", "), pos + 1, 16),
                    BoxField("total_codes", totalCodes.toString(), pos + 1, 16),
                ),
                summary = "$className table $destinationId, $totalCodes code(s)",
            ),
        )
        pos += tableBytes
    }
    return BoxNode(
        type = name, offset = offset, headerSize = 4, size = totalSize,
        children = children, warnings = warnings,
        summary = "${children.size} Huffman table(s)",
    )
}

private fun decodeSos(reader: ByteReader, name: String, offset: Long, declaredSize: Long, totalSize: Long): BoxNode {
    val payloadStart = offset + 4
    val payloadEnd = offset + declaredSize
    if (payloadStart + 1 > payloadEnd) {
        return BoxNode(name, offset, 4, totalSize, warnings = listOf("Segment too short to contain num_components"))
    }
    val numComponents = reader.readUInt8(payloadStart)
    val fields = mutableListOf(BoxField("num_components", numComponents.toString(), payloadStart, 1))
    var pos = payloadStart + 1
    var componentCount = 0
    for (i in 0 until numComponents) {
        if (pos + 2 > payloadEnd) break
        val selector = reader.readUInt8(pos)
        val tables = reader.readUInt8(pos + 1)
        fields.add(BoxField("component_selector", selector.toString(), pos, 1))
        fields.add(BoxField("dc_table", (tables shr 4).toString(), pos + 1, 1))
        fields.add(BoxField("ac_table", (tables and 0x0F).toString(), pos + 1, 1))
        componentCount += 1
        pos += 2
    }
    if (pos + 3 > payloadEnd) {
        return BoxNode(
            type = name, offset = offset, headerSize = 4, size = totalSize,
            fields = fields,
            warnings = listOf("Segment too short to contain spectral selection / successive approximation fields"),
        )
    }
    val spectralStart = reader.readUInt8(pos)
    val spectralEnd = reader.readUInt8(pos + 1)
    val approx = reader.readUInt8(pos + 2)
    fields.add(BoxField("spectral_selection_start", spectralStart.toString(), pos, 1))
    fields.add(BoxField("spectral_selection_end", spectralEnd.toString(), pos + 1, 1))
    fields.add(BoxField("successive_approx_high", (approx shr 4).toString(), pos + 2, 1))
    fields.add(BoxField("successive_approx_low", (approx and 0x0F).toString(), pos + 2, 1))
    return BoxNode(
        type = name, offset = offset, headerSize = 4, size = totalSize,
        fields = fields,
        summary = "$componentCount component(s)",
    )
}

private fun decodeCom(reader: ByteReader, name: String, offset: Long, declaredSize: Long, totalSize: Long): BoxNode {
    val payloadStart = offset + 4
    val payloadEnd = offset + declaredSize
    val text = String(reader.readBytes(payloadStart, (payloadEnd - payloadStart).toInt()), Charsets.UTF_8)
    return BoxNode(
        type = name, offset = offset, headerSize = 4, size = totalSize,
        fields = listOf(BoxField("comment", text, payloadStart, payloadEnd - payloadStart)),
        summary = text,
    )
}

private val JFIF_PREFIX = byteArrayOf(0x4A, 0x46, 0x49, 0x46, 0x00) // "JFIF" + NUL

private fun decodeApp0(reader: ByteReader, name: String, offset: Long, declaredSize: Long, totalSize: Long): BoxNode {
    val payloadStart = offset + 4
    val payloadEnd = offset + declaredSize
    val jfifBodySize = 9 // version(2) + units(1) + x_density(2) + y_density(2) + x_thumbnail(1) + y_thumbnail(1)
    if (payloadEnd - payloadStart >= JFIF_PREFIX.size + jfifBodySize &&
        reader.readBytes(payloadStart, JFIF_PREFIX.size).contentEquals(JFIF_PREFIX)
    ) {
        val bodyStart = payloadStart + JFIF_PREFIX.size
        val majorVersion = reader.readUInt8(bodyStart)
        val minorVersion = reader.readUInt8(bodyStart + 1)
        val units = reader.readUInt8(bodyStart + 2)
        val xDensity = reader.readUInt16(bodyStart + 3)
        val yDensity = reader.readUInt16(bodyStart + 5)
        val xThumbnail = reader.readUInt8(bodyStart + 7)
        val yThumbnail = reader.readUInt8(bodyStart + 8)
        val unitsLabel = when (units) {
            0 -> "none"
            1 -> "pixels/inch"
            2 -> "pixels/cm"
            else -> units.toString()
        }
        return BoxNode(
            type = name, offset = offset, headerSize = 4, size = totalSize,
            fields = listOf(
                BoxField("version", "$majorVersion.$minorVersion", bodyStart, 2),
                BoxField("units", unitsLabel, bodyStart + 2, 1),
                BoxField("x_density", xDensity.toString(), bodyStart + 3, 2),
                BoxField("y_density", yDensity.toString(), bodyStart + 5, 2),
                BoxField("x_thumbnail", xThumbnail.toString(), bodyStart + 7, 1),
                BoxField("y_thumbnail", yThumbnail.toString(), bodyStart + 8, 1),
            ),
            summary = "JFIF $majorVersion.$minorVersion, ${xDensity}x${yDensity} $unitsLabel",
        )
    }
    return BoxNode(type = name, offset = offset, headerSize = 4, size = totalSize)
}
