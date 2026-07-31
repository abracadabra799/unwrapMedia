package com.multiviewer.parser

private val FLAC_BLOCK_TYPE_NAMES = mapOf(
    0 to "STREAMINFO",
    1 to "PADDING",
    2 to "APPLICATION",
    3 to "SEEKTABLE",
    4 to "VORBIS_COMMENT",
    5 to "CUESHEET",
    6 to "PICTURE",
)

fun parseFlacBlocks(reader: ByteReader, start: Long, end: Long): List<BoxNode> {
    val result = mutableListOf<BoxNode>()
    if (end - start < 4) return result
    result.add(BoxNode("fLaC", start, 4, 4))

    var pos = start + 4
    var isLast = false
    while (!isLast && pos < end) {
        val remaining = end - pos
        if (remaining < 4) {
            result.add(BoxNode("?", pos, 0, remaining, warnings = listOf("Trailing $remaining byte(s): too short for a metadata block header")))
            pos = end
            break
        }
        val headerByte = reader.readUInt8(pos)
        isLast = (headerByte and 0x80) != 0
        val blockType = headerByte and 0x7F
        val blockLength = readUInt24BE(reader, pos + 1)
        val headerSize = 4
        var size = headerSize + blockLength
        val warnings = mutableListOf<String>()
        if (pos + size > end) {
            warnings.add("Declared size $size extends ${pos + size - end} byte(s) past the end of its parent")
            size = end - pos
        }

        val name = FLAC_BLOCK_TYPE_NAMES[blockType] ?: "Unknown ($blockType)"
        val payloadStart = pos + headerSize
        val payloadEnd = pos + size
        result.add(decodeFlacBlock(reader, name, pos, headerSize, size, payloadStart, payloadEnd, warnings))
        pos += size
    }

    if (pos < end) {
        result.add(BoxNode("FrameData", pos, 0, end - pos, summary = "${end - pos} byte(s)"))
    }

    return result
}

private fun decodeFlacBlock(
    reader: ByteReader,
    name: String,
    offset: Long,
    headerSize: Int,
    size: Long,
    payloadStart: Long,
    payloadEnd: Long,
    warnings: List<String>,
): BoxNode {
    val payloadLength = (payloadEnd - payloadStart).toInt()
    if (payloadLength <= 0) return BoxNode(name, offset, headerSize, size, warnings = warnings)
    return when (name) {
        "STREAMINFO" -> decodeStreamInfo(reader, name, offset, headerSize, size, payloadStart, payloadLength, warnings)
        "VORBIS_COMMENT" -> decodeVorbisComment(reader, name, offset, headerSize, size, payloadStart, payloadLength, warnings)
        "PICTURE" -> decodePicture(reader, name, offset, headerSize, size, payloadStart, payloadLength, warnings)
        "SEEKTABLE" -> BoxNode(name, offset, headerSize, size, summary = "${payloadLength / 18} seek point(s)", warnings = warnings)
        else -> BoxNode(name, offset, headerSize, size, summary = "$payloadLength byte(s)", warnings = warnings)
    }
}

private fun decodeStreamInfo(
    reader: ByteReader, name: String, offset: Long, headerSize: Int, size: Long,
    payloadStart: Long, payloadLength: Int, warnings: List<String>,
): BoxNode {
    if (payloadLength < 34) {
        return BoxNode(name, offset, headerSize, size, warnings = warnings + "STREAMINFO block is $payloadLength byte(s), expected 34")
    }
    val minBlocksize = reader.readUInt16(payloadStart)
    val maxBlocksize = reader.readUInt16(payloadStart + 2)
    val minFramesize = readUInt24BE(reader, payloadStart + 4)
    val maxFramesize = readUInt24BE(reader, payloadStart + 7)
    val packed = reader.readUInt64(payloadStart + 10)
    val sampleRate = (packed shr 44) and 0xFFFFF
    val channels = ((packed shr 41) and 0x7) + 1
    val bitsPerSample = ((packed shr 36) and 0x1F) + 1
    val totalSamples = packed and 0xFFFFFFFFFL
    val md5 = reader.readBytes(payloadStart + 18, 16).joinToString("") { "%02x".format(it) }

    val fields = listOf(
        BoxField("min_blocksize", minBlocksize.toString(), payloadStart, 2),
        BoxField("max_blocksize", maxBlocksize.toString(), payloadStart + 2, 2),
        BoxField("min_framesize", minFramesize.toString(), payloadStart + 4, 3),
        BoxField("max_framesize", maxFramesize.toString(), payloadStart + 7, 3),
        BoxField("sample_rate", sampleRate.toString(), payloadStart + 10, 8),
        BoxField("channels", channels.toString(), payloadStart + 10, 8),
        BoxField("bits_per_sample", bitsPerSample.toString(), payloadStart + 10, 8),
        BoxField("total_samples", totalSamples.toString(), payloadStart + 10, 8),
        BoxField("md5_signature", md5, payloadStart + 18, 16),
    )
    return BoxNode(
        name, offset, headerSize, size, fields = fields,
        summary = "${sampleRate}Hz, ${channels}ch, ${bitsPerSample}-bit, $totalSamples samples",
        warnings = warnings,
    )
}

private fun decodeVorbisComment(
    reader: ByteReader, name: String, offset: Long, headerSize: Int, size: Long,
    payloadStart: Long, payloadLength: Int, warnings: List<String>,
): BoxNode {
    val allWarnings = warnings.toMutableList()
    val payloadEnd = payloadStart + payloadLength
    val fields = mutableListOf<BoxField>()
    var pos = payloadStart

    if (pos + 4 > payloadEnd) {
        allWarnings.add("VORBIS_COMMENT block is $payloadLength byte(s), too short for its vendor length field")
        return BoxNode(name, offset, headerSize, size, warnings = allWarnings)
    }
    val vendorLength = readUInt32LE(reader, pos).toInt()
    pos += 4
    if (vendorLength < 0 || pos + vendorLength > payloadEnd) {
        allWarnings.add("Vendor string length $vendorLength extends past the end of this block")
        return BoxNode(name, offset, headerSize, size, warnings = allWarnings)
    }
    val vendor = String(reader.readBytes(pos, vendorLength), Charsets.UTF_8)
    fields.add(BoxField("vendor", vendor, pos, vendorLength.toLong()))
    pos += vendorLength

    if (pos + 4 > payloadEnd) {
        allWarnings.add("Truncated before comment count")
        return BoxNode(name, offset, headerSize, size, fields = fields, warnings = allWarnings)
    }
    val commentCount = readUInt32LE(reader, pos).toInt()
    pos += 4

    for (i in 0 until commentCount) {
        if (pos + 4 > payloadEnd) {
            allWarnings.add("Comment list truncated at entry $i of $commentCount")
            break
        }
        val commentLength = readUInt32LE(reader, pos).toInt()
        pos += 4
        if (commentLength < 0 || pos + commentLength > payloadEnd) {
            allWarnings.add("Comment $i length $commentLength extends past the end of this block")
            break
        }
        val comment = String(reader.readBytes(pos, commentLength), Charsets.UTF_8)
        val eq = comment.indexOf('=')
        if (eq >= 0) {
            fields.add(BoxField(comment.substring(0, eq), comment.substring(eq + 1), pos, commentLength.toLong()))
        } else {
            fields.add(BoxField("comment", comment, pos, commentLength.toLong()))
        }
        pos += commentLength
    }

    return BoxNode(name, offset, headerSize, size, fields = fields, summary = "$commentCount comment(s)", warnings = allWarnings)
}

private fun decodePicture(
    reader: ByteReader, name: String, offset: Long, headerSize: Int, size: Long,
    payloadStart: Long, payloadLength: Int, warnings: List<String>,
): BoxNode {
    val allWarnings = warnings.toMutableList()
    val payloadEnd = payloadStart + payloadLength
    var pos = payloadStart

    if (pos + 8 > payloadEnd) {
        allWarnings.add("PICTURE block is $payloadLength byte(s), too short for its fixed fields")
        return BoxNode(name, offset, headerSize, size, warnings = allWarnings)
    }
    val pictureType = reader.readUInt32(pos)
    val mimeLength = reader.readUInt32(pos + 4).toInt()
    pos += 8
    if (mimeLength < 0 || pos + mimeLength > payloadEnd) {
        allWarnings.add("MIME type length $mimeLength extends past the end of this block")
        return BoxNode(name, offset, headerSize, size, warnings = allWarnings)
    }
    val mime = String(reader.readBytes(pos, mimeLength), Charsets.US_ASCII)
    val mimeOffset = pos
    pos += mimeLength

    if (pos + 4 > payloadEnd) {
        allWarnings.add("Truncated before description length")
        return BoxNode(name, offset, headerSize, size, warnings = allWarnings)
    }
    val descriptionLength = reader.readUInt32(pos).toInt()
    pos += 4
    if (descriptionLength < 0 || pos + descriptionLength > payloadEnd) {
        allWarnings.add("Description length $descriptionLength extends past the end of this block")
        return BoxNode(name, offset, headerSize, size, warnings = allWarnings)
    }
    val description = String(reader.readBytes(pos, descriptionLength), Charsets.UTF_8)
    val descriptionOffset = pos
    pos += descriptionLength

    val baseFields = mutableListOf(
        BoxField("picture_type", pictureType.toString(), payloadStart, 4),
        BoxField("mime", mime, mimeOffset, mimeLength.toLong()),
        BoxField("description", description, descriptionOffset, descriptionLength.toLong()),
    )

    if (pos + 20 > payloadEnd) {
        allWarnings.add("Truncated before width/height/color fields")
        return BoxNode(name, offset, headerSize, size, fields = baseFields, warnings = allWarnings)
    }
    val width = reader.readUInt32(pos)
    val height = reader.readUInt32(pos + 4)
    val colorDepth = reader.readUInt32(pos + 8)
    baseFields.add(BoxField("width", width.toString(), pos, 4))
    baseFields.add(BoxField("height", height.toString(), pos + 4, 4))
    baseFields.add(BoxField("color_depth", colorDepth.toString(), pos + 8, 4))

    return BoxNode(name, offset, headerSize, size, fields = baseFields, summary = "$mime, ${width}x$height", warnings = allWarnings)
}

private fun readUInt24BE(reader: ByteReader, offset: Long): Long {
    val bytes = reader.readBytes(offset, 3)
    return ((bytes[0].toLong() and 0xFF) shl 16) or ((bytes[1].toLong() and 0xFF) shl 8) or (bytes[2].toLong() and 0xFF)
}

private fun readUInt32LE(reader: ByteReader, offset: Long): Long {
    val b = reader.readBytes(offset, 4)
    return (b[0].toLong() and 0xFF) or ((b[1].toLong() and 0xFF) shl 8) or
        ((b[2].toLong() and 0xFF) shl 16) or ((b[3].toLong() and 0xFF) shl 24)
}
