package com.multiviewer.parser

private fun readUInt16LE(reader: ByteReader, offset: Long): Int {
    val b = reader.readBytes(offset, 2)
    return (b[0].toInt() and 0xFF) or ((b[1].toInt() and 0xFF) shl 8)
}

private fun readUInt32LE(reader: ByteReader, offset: Long): Long {
    val b = reader.readBytes(offset, 4)
    return (b[0].toLong() and 0xFF) or
        ((b[1].toLong() and 0xFF) shl 8) or
        ((b[2].toLong() and 0xFF) shl 16) or
        ((b[3].toLong() and 0xFF) shl 24)
}

private fun readInt32LE(reader: ByteReader, offset: Long): Int = readUInt32LE(reader, offset).toInt()

internal val BMP_COMPRESSION_NAMES = mapOf(
    0 to "None (BI_RGB)",
    1 to "RLE 8-bit (BI_RLE8)",
    2 to "RLE 4-bit (BI_RLE4)",
    3 to "Bit Fields (BI_BITFIELDS)",
    4 to "JPEG (BI_JPEG)",
    5 to "PNG (BI_PNG)",
    6 to "Alpha Bit Fields (BI_ALPHABITFIELDS)",
    11 to "CMYK (BI_CMYK)",
    12 to "CMYK RLE 8-bit (BI_CMYKRLE8)",
    13 to "CMYK RLE 4-bit (BI_CMYKRLE4)",
)

fun parseBmpHeaders(reader: ByteReader, start: Long, end: Long): List<BoxNode> {
    if (end - start < 14) {
        return listOf(BoxNode("?", start, 0, end - start, warnings = listOf("File too short for a BITMAPFILEHEADER")))
    }
    val result = mutableListOf<BoxNode>()
    result.add(decodeBitmapFileHeader(reader, start))

    val dibStart = start + 14
    if (end - dibStart < 4) return result
    val headerSize = readUInt32LE(reader, dibStart)
    result.add(
        when (headerSize) {
            40L -> decodeBitmapInfoHeader(reader, dibStart, end)
            108L -> decodeBitmapV4Header(reader, dibStart, end)
            124L -> decodeBitmapV5Header(reader, dibStart, end)
            else -> BoxNode(
                type = "DIBHEADER", offset = dibStart, headerSize = 0, size = minOf(headerSize, end - dibStart),
                fields = listOf(BoxField("header_size", headerSize.toString(), dibStart, 4)),
            )
        },
    )
    return result
}

private fun decodeBitmapFileHeader(reader: ByteReader, offset: Long): BoxNode {
    val fileSize = readUInt32LE(reader, offset + 2)
    val pixelDataOffset = readUInt32LE(reader, offset + 10)
    return BoxNode(
        type = "BITMAPFILEHEADER", offset = offset, headerSize = 0, size = 14,
        fields = listOf(
            BoxField("signature", "BM", offset, 2),
            BoxField("file_size", fileSize.toString(), offset + 2, 4),
            BoxField("pixel_data_offset", pixelDataOffset.toString(), offset + 10, 4),
        ),
    )
}

// Shared by BITMAPINFOHEADER, BITMAPV4HEADER, and BITMAPV5HEADER -- all
// three start with this exact 40-byte layout (verified against Microsoft's
// Win32 API docs; see the plan's byte-offset reference table).
private fun buildBitmapInfoHeaderFields(reader: ByteReader, offset: Long): List<BoxField> {
    val width = readInt32LE(reader, offset + 4)
    val height = readInt32LE(reader, offset + 8)
    val planes = readUInt16LE(reader, offset + 12)
    val bitCount = readUInt16LE(reader, offset + 14)
    val compression = readUInt32LE(reader, offset + 16)
    val compressionLabel = BMP_COMPRESSION_NAMES[compression.toInt()] ?: "Unknown ($compression)"
    val imageSize = readUInt32LE(reader, offset + 20)
    val xPelsPerMeter = readInt32LE(reader, offset + 24)
    val yPelsPerMeter = readInt32LE(reader, offset + 28)
    val colorsUsed = readUInt32LE(reader, offset + 32)
    val colorsImportant = readUInt32LE(reader, offset + 36)
    return listOf(
        BoxField("width", width.toString(), offset + 4, 4),
        BoxField("height", height.toString(), offset + 8, 4),
        BoxField("planes", planes.toString(), offset + 12, 2),
        BoxField("bit_count", bitCount.toString(), offset + 14, 2),
        BoxField("compression", compressionLabel, offset + 16, 4),
        BoxField("image_size", imageSize.toString(), offset + 20, 4),
        BoxField("x_pixels_per_meter", xPelsPerMeter.toString(), offset + 24, 4),
        BoxField("y_pixels_per_meter", yPelsPerMeter.toString(), offset + 28, 4),
        BoxField("colors_used", colorsUsed.toString(), offset + 32, 4),
        BoxField("colors_important", colorsImportant.toString(), offset + 36, 4),
    )
}

private fun decodeBitmapInfoHeader(reader: ByteReader, offset: Long, end: Long): BoxNode {
    if (end - offset < 40) {
        return BoxNode(type = "BITMAPINFOHEADER", offset = offset, headerSize = 0, size = end - offset, warnings = listOf("Truncated BITMAPINFOHEADER"))
    }
    val fields = buildBitmapInfoHeaderFields(reader, offset)
    val width = fields.first { it.name == "width" }.value
    val height = fields.first { it.name == "height" }.value
    val bitCount = fields.first { it.name == "bit_count" }.value
    return BoxNode(
        type = "BITMAPINFOHEADER", offset = offset, headerSize = 0, size = 40,
        fields = fields,
        summary = "${width}x${height}, ${bitCount}-bit",
    )
}

private val COLOR_SPACE_TYPE_NAMES = mapOf(
    0x00000000L to "Calibrated RGB (LCS_CALIBRATED_RGB)",
    0x73524742L to "sRGB (LCS_sRGB)",
    0x57696E20L to "Windows Color Space (LCS_WINDOWS_COLOR_SPACE)",
    0x4C494E4BL to "Linked Profile (PROFILE_LINKED)",
    0x4D424544L to "Embedded Profile (PROFILE_EMBEDDED)",
)

private fun colorSpaceTypeLabel(value: Long): String =
    COLOR_SPACE_TYPE_NAMES[value] ?: "Unknown (0x%08X)".format(value)

// FXPT2DOT30: a signed 2.30 fixed-point number (2's-complement sign, 1
// integer bit, 30 fractional bits), stored little-endian like every other
// BMP field -- NOT the same encoding as ICC's big-endian s15Fixed16Number
// (readS15Fixed16, JpegWalker.kt) this superficially resembles.
private fun readFxpt2Dot30(reader: ByteReader, offset: Long): Double {
    val raw = readInt32LE(reader, offset)
    return raw / 1073741824.0 // 2^30
}

// Unsigned 16.16 fixed-point: upper 16 bits are the integer part, lower 16
// bits are the fractional part (used for gamma_red/green/blue).
private fun formatGamma(raw: Long): String {
    val integerPart = raw shr 16
    val fractionalPart = (raw and 0xFFFF) / 65536.0
    return "%.4f".format(integerPart + fractionalPart)
}

private fun buildCalibratedRgbFields(reader: ByteReader, endpointsOffset: Long, gammaOffset: Long): List<BoxField> {
    val x1 = readFxpt2Dot30(reader, endpointsOffset)
    val y1 = readFxpt2Dot30(reader, endpointsOffset + 4)
    val z1 = readFxpt2Dot30(reader, endpointsOffset + 8)
    val x2 = readFxpt2Dot30(reader, endpointsOffset + 12)
    val y2 = readFxpt2Dot30(reader, endpointsOffset + 16)
    val z2 = readFxpt2Dot30(reader, endpointsOffset + 20)
    val x3 = readFxpt2Dot30(reader, endpointsOffset + 24)
    val y3 = readFxpt2Dot30(reader, endpointsOffset + 28)
    val z3 = readFxpt2Dot30(reader, endpointsOffset + 32)
    val gammaRed = readUInt32LE(reader, gammaOffset)
    val gammaGreen = readUInt32LE(reader, gammaOffset + 4)
    val gammaBlue = readUInt32LE(reader, gammaOffset + 8)
    return listOf(
        BoxField("endpoint_red_x", "%.6f".format(x1), endpointsOffset, 4),
        BoxField("endpoint_red_y", "%.6f".format(y1), endpointsOffset + 4, 4),
        BoxField("endpoint_red_z", "%.6f".format(z1), endpointsOffset + 8, 4),
        BoxField("endpoint_green_x", "%.6f".format(x2), endpointsOffset + 12, 4),
        BoxField("endpoint_green_y", "%.6f".format(y2), endpointsOffset + 16, 4),
        BoxField("endpoint_green_z", "%.6f".format(z2), endpointsOffset + 20, 4),
        BoxField("endpoint_blue_x", "%.6f".format(x3), endpointsOffset + 24, 4),
        BoxField("endpoint_blue_y", "%.6f".format(y3), endpointsOffset + 28, 4),
        BoxField("endpoint_blue_z", "%.6f".format(z3), endpointsOffset + 32, 4),
        BoxField("gamma_red", formatGamma(gammaRed), gammaOffset, 4),
        BoxField("gamma_green", formatGamma(gammaGreen), gammaOffset + 4, 4),
        BoxField("gamma_blue", formatGamma(gammaBlue), gammaOffset + 8, 4),
    )
}

// Shared by BITMAPV4HEADER and BITMAPV5HEADER -- both start with this exact
// 108-byte layout (verified against Microsoft's Win32 API docs).
private fun buildBitmapV4Fields(reader: ByteReader, offset: Long): List<BoxField> {
    val fields = buildBitmapInfoHeaderFields(reader, offset).toMutableList()
    val redMask = readUInt32LE(reader, offset + 40)
    val greenMask = readUInt32LE(reader, offset + 44)
    val blueMask = readUInt32LE(reader, offset + 48)
    val alphaMask = readUInt32LE(reader, offset + 52)
    val colorSpaceType = readUInt32LE(reader, offset + 56)
    fields.add(BoxField("red_mask", "0x%08X".format(redMask), offset + 40, 4))
    fields.add(BoxField("green_mask", "0x%08X".format(greenMask), offset + 44, 4))
    fields.add(BoxField("blue_mask", "0x%08X".format(blueMask), offset + 48, 4))
    fields.add(BoxField("alpha_mask", "0x%08X".format(alphaMask), offset + 52, 4))
    fields.add(BoxField("color_space_type", colorSpaceTypeLabel(colorSpaceType), offset + 56, 4))
    if (colorSpaceType == 0L) {
        fields.addAll(buildCalibratedRgbFields(reader, offset + 60, offset + 96))
    }
    return fields
}

private fun decodeBitmapV4Header(reader: ByteReader, offset: Long, end: Long): BoxNode {
    if (end - offset < 108) {
        return BoxNode(type = "BITMAPV4HEADER", offset = offset, headerSize = 0, size = end - offset, warnings = listOf("Truncated BITMAPV4HEADER"))
    }
    val fields = buildBitmapV4Fields(reader, offset)
    val width = fields.first { it.name == "width" }.value
    val height = fields.first { it.name == "height" }.value
    val bitCount = fields.first { it.name == "bit_count" }.value
    return BoxNode(
        type = "BITMAPV4HEADER", offset = offset, headerSize = 0, size = 108,
        fields = fields,
        summary = "${width}x${height}, ${bitCount}-bit",
    )
}

private val INTENT_NAMES = mapOf(
    1 to "Saturation",
    2 to "Relative Colorimetric",
    4 to "Perceptual",
    8 to "Absolute Colorimetric",
)

private const val PROFILE_EMBEDDED = 0x4D424544L
private const val PROFILE_LINKED = 0x4C494E4BL

private fun decodeBitmapV5Header(reader: ByteReader, offset: Long, end: Long): BoxNode {
    if (end - offset < 124) {
        return BoxNode(type = "BITMAPV5HEADER", offset = offset, headerSize = 0, size = end - offset, warnings = listOf("Truncated BITMAPV5HEADER"))
    }
    val fields = buildBitmapV4Fields(reader, offset).toMutableList()
    val colorSpaceType = readUInt32LE(reader, offset + 56)
    val intent = readUInt32LE(reader, offset + 108).toInt()
    val profileDataOffset = readUInt32LE(reader, offset + 112)
    val profileSize = readUInt32LE(reader, offset + 116)
    fields.add(BoxField("intent", INTENT_NAMES[intent] ?: "Unknown ($intent)", offset + 108, 4))
    fields.add(BoxField("profile_data_offset", profileDataOffset.toString(), offset + 112, 4))
    fields.add(BoxField("profile_size", profileSize.toString(), offset + 116, 4))

    val warnings = mutableListOf<String>()
    val profileStart = offset + profileDataOffset
    when (colorSpaceType) {
        PROFILE_EMBEDDED -> {
            if (profileSize >= 128 && profileStart + 128 <= end) {
                val headerBytes = reader.readBytes(profileStart, 128)
                fields.addAll(decodeIccProfileHeader(headerBytes, profileStart))
            } else {
                warnings.add("Embedded ICC profile too short or out of range to parse")
            }
        }
        PROFILE_LINKED -> {
            val maxLen = minOf(260L, end - profileStart).toInt()
            if (maxLen > 0) {
                val nameBytes = reader.readBytes(profileStart, maxLen)
                val nullIndex = nameBytes.indexOf(0)
                val pathLength = if (nullIndex >= 0) nullIndex else nameBytes.size
                val path = String(nameBytes, 0, pathLength, Charsets.ISO_8859_1)
                fields.add(BoxField("linked_profile_path", path, profileStart, pathLength.toLong()))
            } else {
                warnings.add("Linked ICC profile path is out of range")
            }
        }
    }

    val width = fields.first { it.name == "width" }.value
    val height = fields.first { it.name == "height" }.value
    val bitCount = fields.first { it.name == "bit_count" }.value
    return BoxNode(
        type = "BITMAPV5HEADER", offset = offset, headerSize = 0, size = 124,
        fields = fields, warnings = warnings,
        summary = "${width}x${height}, ${bitCount}-bit",
    )
}
