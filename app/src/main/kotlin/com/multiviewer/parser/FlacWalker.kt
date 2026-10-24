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
        val decoded = decodeFlacBlock(reader, name, pos, headerSize, size, payloadStart, payloadEnd, warnings)
        result.add(decoded.copy(interpretation = if (decoded.warnings.isNotEmpty() && decoded.interpretation == ValueInterpretation.PARSED) ValueInterpretation.UNPARSABLE else decoded.interpretation, fields = decoded.fields + listOf(
            BoxField("block_type", blockType.toString(), pos, 1),
            BoxField("is_last_metadata_block", isLast.toString(), pos, 1),
            BoxField("block_length", blockLength.toString(), pos + 1, 3),
        )))
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
    if (payloadLength <= 0 && name !in setOf("APPLICATION", "STREAMINFO", "VORBIS_COMMENT", "PICTURE", "CUESHEET")) return BoxNode(name, offset, headerSize, size, warnings = warnings)
    return when (name) {
        "STREAMINFO" -> decodeStreamInfo(reader, name, offset, headerSize, size, payloadStart, payloadLength, warnings)
        "VORBIS_COMMENT" -> FlacVorbisCommentDecoder.decode(reader, name, offset, headerSize, size, warnings)
        "PICTURE" -> FlacPictureDecoder.decode(reader, name, offset, headerSize, size, warnings)
        "CUESHEET" -> FlacCueSheetDecoder.decode(reader, name, offset, headerSize, size, warnings)
        "SEEKTABLE", "APPLICATION" -> FlacExtraMetadataDecoder.decode(reader, name, offset, headerSize, size, warnings)
        else -> BoxNode(name, offset, headerSize, size, summary = "$payloadLength byte(s)", warnings = warnings,
            fields = if (name == "PADDING") emptyList() else listOf(BoxField("parsing_status", "Uninterpreted payload / 미해석 페이로드", payloadStart, payloadLength.toLong(), ValueInterpretation.UNINTERPRETED)))
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

private fun readUInt24BE(reader: ByteReader, offset: Long): Long {
    val bytes = reader.readBytes(offset, 3)
    return ((bytes[0].toLong() and 0xFF) shl 16) or ((bytes[1].toLong() and 0xFF) shl 8) or (bytes[2].toLong() and 0xFF)
}
