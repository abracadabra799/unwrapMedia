package com.multiviewer.parser

val PNG_COLOR_TYPE_NAMES = mapOf(
    0 to "Grayscale",
    2 to "Truecolor",
    3 to "Indexed",
    4 to "Grayscale+Alpha",
    6 to "Truecolor+Alpha",
)

fun parsePngChunks(reader: ByteReader, start: Long, end: Long): List<BoxNode> {
    val result = mutableListOf<BoxNode>()
    var pos = start
    while (pos < end) {
        if (pos + 8 > end) {
            result.add(BoxNode("?", pos, 0, end - pos, warnings = listOf("Trailing ${end - pos} byte(s): too short for a chunk header")))
            break
        }
        val length = reader.readUInt32(pos)
        val type = reader.readFourCC(pos + 4)
        val dataStart = pos + 8
        val chunkTotalSize = 8L + length + 4L
        if (pos + chunkTotalSize > end) {
            result.add(BoxNode(type, pos, 8, end - pos, warnings = listOf("Chunk declares length $length but only ${end - pos - 8} byte(s) remain")))
            break
        }
        result.add(decodePngChunk(reader, type, pos, dataStart, length, chunkTotalSize))
        pos += chunkTotalSize
    }
    return result
}

private fun decodePngChunk(reader: ByteReader, type: String, offset: Long, dataStart: Long, length: Long, totalSize: Long): BoxNode =
    when (type) {
        "IHDR" -> decodeIhdr(reader, offset, dataStart, totalSize)
        "pHYs" -> decodePhys(reader, offset, dataStart, totalSize)
        "tEXt" -> decodeText(reader, offset, dataStart, length, totalSize)
        "eXIf" -> decodeExifChunk(reader, offset, dataStart, dataStart + length, totalSize)
        "gAMA" -> decodeGama(reader, offset, dataStart, totalSize)
        "cHRM" -> decodeChrm(reader, offset, dataStart, totalSize)
        "sRGB" -> decodeSrgb(reader, offset, dataStart, totalSize)
        "tIME" -> decodeTime(reader, offset, dataStart, totalSize)
        "iCCP" -> decodeIccp(reader, offset, dataStart, length, totalSize)
        "zTXt" -> decodeZtxt(reader, offset, dataStart, length, totalSize)
        "iTXt" -> decodeItxt(reader, offset, dataStart, length, totalSize)
        else -> BoxNode(type = type, offset = offset, headerSize = 8, size = totalSize)
    }

private fun decodeIhdr(reader: ByteReader, offset: Long, dataStart: Long, totalSize: Long): BoxNode {
    if (totalSize < 25) { // 8 (length+type) + 13 (IHDR body) + 4 (crc)
        return BoxNode(type = "IHDR", offset = offset, headerSize = 8, size = totalSize, warnings = listOf("IHDR chunk too short to contain all fields"))
    }
    val width = reader.readUInt32(dataStart)
    val height = reader.readUInt32(dataStart + 4)
    val bitDepth = reader.readUInt8(dataStart + 8)
    val colorType = reader.readUInt8(dataStart + 9)
    val compressionMethod = reader.readUInt8(dataStart + 10)
    val filterMethod = reader.readUInt8(dataStart + 11)
    val interlaceMethod = reader.readUInt8(dataStart + 12)
    val colorTypeName = PNG_COLOR_TYPE_NAMES[colorType] ?: "Unknown"
    return BoxNode(
        type = "IHDR", offset = offset, headerSize = 8, size = totalSize,
        fields = listOf(
            BoxField("width", width.toString(), dataStart, 4),
            BoxField("height", height.toString(), dataStart + 4, 4),
            BoxField("bit_depth", bitDepth.toString(), dataStart + 8, 1),
            BoxField("color_type", colorType.toString(), dataStart + 9, 1),
            BoxField("compression_method", compressionMethod.toString(), dataStart + 10, 1),
            BoxField("filter_method", filterMethod.toString(), dataStart + 11, 1),
            BoxField("interlace_method", interlaceMethod.toString(), dataStart + 12, 1),
        ),
        summary = "${width}x${height}, $colorTypeName, ${bitDepth}-bit",
    )
}

private fun decodePhys(reader: ByteReader, offset: Long, dataStart: Long, totalSize: Long): BoxNode {
    if (totalSize < 21) { // 8 (length+type) + 9 (pHYs body) + 4 (crc)
        return BoxNode(type = "pHYs", offset = offset, headerSize = 8, size = totalSize, warnings = listOf("pHYs chunk too short to contain all fields"))
    }
    val ppuX = reader.readUInt32(dataStart)
    val ppuY = reader.readUInt32(dataStart + 4)
    val unitSpecifier = reader.readUInt8(dataStart + 8)
    val unitLabel = if (unitSpecifier == 1) "meter" else "unknown"
    return BoxNode(
        type = "pHYs", offset = offset, headerSize = 8, size = totalSize,
        fields = listOf(
            BoxField("pixels_per_unit_x", ppuX.toString(), dataStart, 4),
            BoxField("pixels_per_unit_y", ppuY.toString(), dataStart + 4, 4),
            BoxField("unit_specifier", unitLabel, dataStart + 8, 1),
        ),
        summary = "${ppuX}x${ppuY} px/$unitLabel",
    )
}

private fun decodeText(reader: ByteReader, offset: Long, dataStart: Long, length: Long, totalSize: Long): BoxNode {
    val bytes = reader.readBytes(dataStart, length.toInt())
    val nullIndex = bytes.indexOf(0)
    if (nullIndex < 0) {
        return BoxNode(type = "tEXt", offset = offset, headerSize = 8, size = totalSize, warnings = listOf("Missing keyword/text separator"))
    }
    val keyword = String(bytes, 0, nullIndex, Charsets.ISO_8859_1)
    val text = String(bytes, nullIndex + 1, bytes.size - nullIndex - 1, Charsets.ISO_8859_1)
    return BoxNode(
        type = "tEXt", offset = offset, headerSize = 8, size = totalSize,
        fields = listOf(
            BoxField("keyword", keyword, dataStart, nullIndex.toLong()),
            BoxField("text", text, dataStart + nullIndex + 1, (bytes.size - nullIndex - 1).toLong()),
        ),
        summary = "$keyword: $text",
    )
}

private fun decodeExifChunk(reader: ByteReader, offset: Long, dataStart: Long, dataEnd: Long, totalSize: Long): BoxNode {
    val children = decodeTiff(reader, dataStart, dataEnd)
    return BoxNode(type = "eXIf", offset = offset, headerSize = 8, size = totalSize, children = children, summary = "Exif metadata")
}

private fun decodeGama(reader: ByteReader, offset: Long, dataStart: Long, totalSize: Long): BoxNode {
    if (totalSize < 16) { // 8 (length+type) + 4 (gAMA body) + 4 (crc)
        return BoxNode(type = "gAMA", offset = offset, headerSize = 8, size = totalSize, warnings = listOf("gAMA chunk too short to contain the gamma value"))
    }
    val gamma = reader.readUInt32(dataStart) / 100000.0
    return BoxNode(
        type = "gAMA", offset = offset, headerSize = 8, size = totalSize,
        fields = listOf(BoxField("gamma", "%.5f".format(gamma), dataStart, 4)),
        summary = "gamma=%.5f".format(gamma),
    )
}

private fun decodeChrm(reader: ByteReader, offset: Long, dataStart: Long, totalSize: Long): BoxNode {
    if (totalSize < 44) { // 8 + 32 (cHRM body) + 4 (crc)
        return BoxNode(type = "cHRM", offset = offset, headerSize = 8, size = totalSize, warnings = listOf("cHRM chunk too short to contain all chromaticity values"))
    }
    fun point(pos: Long) = reader.readUInt32(pos) / 100000.0
    val whiteX = point(dataStart)
    val whiteY = point(dataStart + 4)
    val redX = point(dataStart + 8)
    val redY = point(dataStart + 12)
    val greenX = point(dataStart + 16)
    val greenY = point(dataStart + 20)
    val blueX = point(dataStart + 24)
    val blueY = point(dataStart + 28)
    return BoxNode(
        type = "cHRM", offset = offset, headerSize = 8, size = totalSize,
        fields = listOf(
            BoxField("white_point", "x=%.4f, y=%.4f".format(whiteX, whiteY), dataStart, 8),
            BoxField("red", "x=%.4f, y=%.4f".format(redX, redY), dataStart + 8, 8),
            BoxField("green", "x=%.4f, y=%.4f".format(greenX, greenY), dataStart + 16, 8),
            BoxField("blue", "x=%.4f, y=%.4f".format(blueX, blueY), dataStart + 24, 8),
        ),
        summary = "white=(%.4f, %.4f)".format(whiteX, whiteY),
    )
}

private val PNG_RENDERING_INTENT_NAMES = mapOf(
    0 to "Perceptual",
    1 to "Media-Relative Colorimetric",
    2 to "Saturation",
    3 to "ICC-Absolute Colorimetric",
)

private fun decodeSrgb(reader: ByteReader, offset: Long, dataStart: Long, totalSize: Long): BoxNode {
    if (totalSize < 13) { // 8 + 1 (sRGB body) + 4 (crc)
        return BoxNode(type = "sRGB", offset = offset, headerSize = 8, size = totalSize, warnings = listOf("sRGB chunk too short to contain the rendering intent"))
    }
    val intentCode = reader.readUInt8(dataStart)
    val intent = PNG_RENDERING_INTENT_NAMES[intentCode] ?: "Unknown ($intentCode)"
    return BoxNode(
        type = "sRGB", offset = offset, headerSize = 8, size = totalSize,
        fields = listOf(BoxField("rendering_intent", intent, dataStart, 1)),
        summary = intent,
    )
}

private fun decodeTime(reader: ByteReader, offset: Long, dataStart: Long, totalSize: Long): BoxNode {
    if (totalSize < 19) { // 8 + 7 (tIME body) + 4 (crc)
        return BoxNode(type = "tIME", offset = offset, headerSize = 8, size = totalSize, warnings = listOf("tIME chunk too short to contain all fields"))
    }
    val year = reader.readUInt16(dataStart)
    val month = reader.readUInt8(dataStart + 2)
    val day = reader.readUInt8(dataStart + 3)
    val hour = reader.readUInt8(dataStart + 4)
    val minute = reader.readUInt8(dataStart + 5)
    val second = reader.readUInt8(dataStart + 6)
    val formatted = "%04d-%02d-%02d %02d:%02d:%02d UTC".format(year, month, day, hour, minute, second)
    return BoxNode(
        type = "tIME", offset = offset, headerSize = 8, size = totalSize,
        fields = listOf(BoxField("last_modified", formatted, dataStart, 7)),
        summary = formatted,
    )
}

private const val ICCP_MAX_DECOMPRESSED_BYTES = 64 * 1024 * 1024 // 64 MB safety cap against a decompression bomb

// Shared zlib inflate for iCCP/zTXt/iTXt. Returns null (never throws, never hangs)
// on malformed input or if the output would exceed [maxOutputBytes].
private fun inflateZlib(compressed: ByteArray, maxOutputBytes: Int): ByteArray? {
    val inflater = java.util.zip.Inflater()
    inflater.setInput(compressed)
    val out = java.io.ByteArrayOutputStream(minOf(compressed.size * 4, maxOutputBytes))
    val buf = ByteArray(8192)
    return try {
        while (!inflater.finished()) {
            val n = inflater.inflate(buf)
            if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
            out.write(buf, 0, n)
            if (out.size() > maxOutputBytes) return null
        }
        out.toByteArray()
    } catch (e: java.util.zip.DataFormatException) {
        null
    } finally {
        inflater.end()
    }
}

private fun decodeIccp(reader: ByteReader, offset: Long, dataStart: Long, length: Long, totalSize: Long): BoxNode {
    val dataEnd = dataStart + length
    val nameBytes = reader.readBytes(dataStart, minOf(length, 80L).toInt())
    val nullIndex = nameBytes.indexOf(0)
    if (nullIndex < 0) {
        return BoxNode(type = "iCCP", offset = offset, headerSize = 8, size = totalSize, warnings = listOf("Missing profile name terminator"))
    }
    val profileName = String(nameBytes, 0, nullIndex, Charsets.ISO_8859_1)
    val nameField = BoxField("profile_name", profileName, dataStart, nullIndex.toLong())
    val compressionMethodPos = dataStart + nullIndex + 1
    if (compressionMethodPos >= dataEnd) {
        return BoxNode(type = "iCCP", offset = offset, headerSize = 8, size = totalSize, fields = listOf(nameField), warnings = listOf("Missing compression method byte"))
    }
    val compressionMethod = reader.readUInt8(compressionMethodPos)
    if (compressionMethod != 0) {
        return BoxNode(
            type = "iCCP", offset = offset, headerSize = 8, size = totalSize,
            fields = listOf(nameField),
            warnings = listOf("Unknown iCCP compression method $compressionMethod"),
            summary = profileName,
        )
    }
    val compressedStart = compressionMethodPos + 1
    val compressed = reader.readBytes(compressedStart, (dataEnd - compressedStart).toInt())
    val decompressed = inflateZlib(compressed, ICCP_MAX_DECOMPRESSED_BYTES)
    if (decompressed == null) {
        return BoxNode(
            type = "iCCP", offset = offset, headerSize = 8, size = totalSize,
            fields = listOf(nameField),
            warnings = listOf("Failed to decompress ICC profile data"),
            summary = profileName,
        )
    }
    val fields = mutableListOf(nameField)
    if (decompressed.size >= 128) {
        fields.addAll(decodeIccProfileHeader(decompressed, dataStart))
    }
    return BoxNode(
        type = "iCCP", offset = offset, headerSize = 8, size = totalSize,
        fields = fields,
        summary = "$profileName (${decompressed.size} bytes decompressed)",
    )
}

private fun decodeZtxt(reader: ByteReader, offset: Long, dataStart: Long, length: Long, totalSize: Long): BoxNode {
    val headBytes = reader.readBytes(dataStart, length.toInt())
    val nullIndex = headBytes.indexOf(0)
    if (nullIndex < 0) {
        return BoxNode(type = "zTXt", offset = offset, headerSize = 8, size = totalSize, warnings = listOf("Missing keyword terminator"))
    }
    val keyword = String(headBytes, 0, nullIndex, Charsets.ISO_8859_1)
    val keywordField = BoxField("keyword", keyword, dataStart, nullIndex.toLong())
    val compressionMethodPos = nullIndex + 1
    if (compressionMethodPos >= headBytes.size) {
        return BoxNode(type = "zTXt", offset = offset, headerSize = 8, size = totalSize, fields = listOf(keywordField), warnings = listOf("Missing compression method byte"))
    }
    val compressionMethod = headBytes[compressionMethodPos].toInt() and 0xFF
    if (compressionMethod != 0) {
        return BoxNode(type = "zTXt", offset = offset, headerSize = 8, size = totalSize, fields = listOf(keywordField), warnings = listOf("Unknown zTXt compression method $compressionMethod"), summary = keyword)
    }
    val compressed = headBytes.copyOfRange(compressionMethodPos + 1, headBytes.size)
    val decompressed = inflateZlib(compressed, ICCP_MAX_DECOMPRESSED_BYTES)
        ?: return BoxNode(type = "zTXt", offset = offset, headerSize = 8, size = totalSize, fields = listOf(keywordField), warnings = listOf("Failed to decompress zTXt text"), summary = keyword)
    val text = String(decompressed, Charsets.ISO_8859_1)
    return BoxNode(
        type = "zTXt", offset = offset, headerSize = 8, size = totalSize,
        fields = listOf(keywordField, BoxField("text", text, dataStart + compressionMethodPos + 1, compressed.size.toLong())),
        summary = "$keyword: $text",
    )
}

// Kotlin's stdlib ByteArray.indexOf(element) has no start-index overload; iTXt needs one
// to find the language-tag and translated-keyword terminators after the first NUL.
private fun ByteArray.indexOf(element: Byte, startIndex: Int): Int {
    for (i in startIndex until size) {
        if (this[i] == element) return i
    }
    return -1
}

private fun decodeItxt(reader: ByteReader, offset: Long, dataStart: Long, length: Long, totalSize: Long): BoxNode {
    val bytes = reader.readBytes(dataStart, length.toInt())

    val keywordEnd = bytes.indexOf(0)
    if (keywordEnd < 0) {
        return BoxNode(type = "iTXt", offset = offset, headerSize = 8, size = totalSize, warnings = listOf("Missing keyword terminator"))
    }
    val keyword = String(bytes, 0, keywordEnd, Charsets.ISO_8859_1)

    val flagsStart = keywordEnd + 1
    if (flagsStart + 2 > bytes.size) {
        return BoxNode(type = "iTXt", offset = offset, headerSize = 8, size = totalSize, warnings = listOf("Missing compression flag/method"))
    }
    val compressionFlag = bytes[flagsStart].toInt() and 0xFF
    val compressionMethod = bytes[flagsStart + 1].toInt() and 0xFF

    val langStart = flagsStart + 2
    val langEnd = bytes.indexOf(0, langStart)
    if (langEnd < 0) {
        return BoxNode(type = "iTXt", offset = offset, headerSize = 8, size = totalSize, warnings = listOf("Missing language tag terminator"))
    }
    val languageTag = String(bytes, langStart, langEnd - langStart, Charsets.US_ASCII)

    val translatedStart = langEnd + 1
    val translatedEnd = bytes.indexOf(0, translatedStart)
    if (translatedEnd < 0) {
        return BoxNode(type = "iTXt", offset = offset, headerSize = 8, size = totalSize, warnings = listOf("Missing translated keyword terminator"))
    }
    val translatedKeyword = String(bytes, translatedStart, translatedEnd - translatedStart, Charsets.UTF_8)

    val baseFields = listOf(
        BoxField("keyword", keyword, dataStart, keywordEnd.toLong()),
        BoxField("language_tag", languageTag, dataStart + langStart, (langEnd - langStart).toLong()),
        BoxField("translated_keyword", translatedKeyword, dataStart + translatedStart, (translatedEnd - translatedStart).toLong()),
    )

    val textStart = translatedEnd + 1
    val rawTextBytes = bytes.copyOfRange(textStart, bytes.size)
    if (compressionFlag == 1 && compressionMethod != 0) {
        return BoxNode(type = "iTXt", offset = offset, headerSize = 8, size = totalSize, fields = baseFields, warnings = listOf("Unknown iTXt compression method $compressionMethod"), summary = keyword)
    }
    val textBytes = if (compressionFlag == 1) {
        inflateZlib(rawTextBytes, ICCP_MAX_DECOMPRESSED_BYTES)
            ?: return BoxNode(type = "iTXt", offset = offset, headerSize = 8, size = totalSize, fields = baseFields, warnings = listOf("Failed to decompress iTXt text"), summary = keyword)
    } else {
        rawTextBytes
    }
    val text = String(textBytes, Charsets.UTF_8)
    return BoxNode(
        type = "iTXt", offset = offset, headerSize = 8, size = totalSize,
        fields = baseFields + BoxField("text", text, dataStart + textStart, rawTextBytes.size.toLong()),
        summary = "$keyword: $text",
    )
}
