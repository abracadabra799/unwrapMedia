package com.multiviewer.parser

private const val OGG_HEADER_FIXED_SIZE = 27

fun parseOggPages(reader: ByteReader, start: Long, end: Long): List<BoxNode> {
    val result = mutableListOf<BoxNode>()
    var pos = start
    var pendingPageCount = 0
    var pendingByteCount = 0L
    var pendingStart = start
    val pendingWarnings = mutableListOf<String>()
    var finalGranulePosition: Long? = null
    var finalGranulePositionOffset = start

    fun flushPending() {
        if (pendingPageCount > 0) {
            val fields = mutableListOf<BoxField>()
            finalGranulePosition?.let { fields.add(BoxField("final_granule_position", it.toString(), finalGranulePositionOffset, 8)) }
            result.add(
                BoxNode(
                    "OggPages", pendingStart, 0, pendingByteCount, fields = fields,
                    summary = "$pendingPageCount page(s), $pendingByteCount byte(s)",
                    warnings = pendingWarnings.toList(),
                ),
            )
            pendingPageCount = 0
            pendingByteCount = 0L
            pendingWarnings.clear()
        }
    }

    while (pos < end) {
        val remaining = end - pos
        if (remaining < 4) {
            flushPending()
            result.add(BoxNode("?", pos, 0, remaining, warnings = listOf("Trailing $remaining byte(s): too short for a page header")))
            break
        }
        if (reader.readFourCC(pos) != "OggS") {
            flushPending()
            result.add(BoxNode("?", pos, 0, remaining, warnings = listOf("Expected 'OggS' capture pattern at this offset -- stopping page scan")))
            break
        }
        if (remaining < OGG_HEADER_FIXED_SIZE) {
            flushPending()
            result.add(BoxNode("?", pos, 0, remaining, warnings = listOf("Trailing $remaining byte(s): too short for a page header")))
            break
        }

        val headerType = reader.readUInt8(pos + 5)
        val granulePosition = readInt64LE(reader, pos + 6)
        val segmentCount = reader.readUInt8(pos + 26)

        if (remaining < OGG_HEADER_FIXED_SIZE + segmentCount) {
            flushPending()
            result.add(BoxNode("?", pos, 0, remaining, warnings = listOf("Trailing $remaining byte(s): too short for a page's segment table")))
            break
        }

        var declaredPayloadLength = 0
        for (i in 0 until segmentCount) {
            declaredPayloadLength += reader.readUInt8(pos + OGG_HEADER_FIXED_SIZE + i)
        }
        val headerSize = OGG_HEADER_FIXED_SIZE + segmentCount
        var pageSize = (headerSize + declaredPayloadLength).toLong()
        val warnings = mutableListOf<String>()
        if (pos + pageSize > end) {
            warnings.add("Declared size $pageSize extends ${pos + pageSize - end} byte(s) past the end of its parent")
            pageSize = end - pos
        }
        val payloadStart = pos + headerSize
        val payloadLength = (pos + pageSize - payloadStart).toInt()

        if ((headerType and 0x04) != 0) {
            finalGranulePosition = granulePosition
            finalGranulePositionOffset = pos + 6
        }

        val node = decodeOggPage(reader, pos, headerSize, pageSize, payloadStart, payloadLength, warnings)
        if (node != null) {
            flushPending()
            result.add(node)
        } else {
            if (pendingPageCount == 0) pendingStart = pos
            pendingPageCount++
            pendingByteCount += pageSize
            pendingWarnings.addAll(warnings)
        }

        pos += pageSize
    }

    flushPending()
    return result
}

private fun decodeOggPage(
    reader: ByteReader,
    offset: Long,
    headerSize: Int,
    size: Long,
    payloadStart: Long,
    payloadLength: Int,
    warnings: List<String>,
): BoxNode? {
    if (payloadLength <= 0) return null
    val first = reader.readUInt8(payloadStart)
    return when {
        payloadLength >= 7 && first == 0x01 && readAscii(reader, payloadStart + 1, 6) == "vorbis" ->
            decodeVorbisIdentificationHeader(reader, offset, headerSize, size, payloadStart, payloadLength, warnings)
        payloadLength >= 7 && first == 0x03 && readAscii(reader, payloadStart + 1, 6) == "vorbis" ->
            decodeOggVendorComment(reader, "OggVorbisComment", offset, headerSize, size, payloadStart + 7, payloadLength - 7, warnings)
        payloadLength >= 7 && first == 0x05 && readAscii(reader, payloadStart + 1, 6) == "vorbis" ->
            BoxNode("OggVorbisSetupHeader", offset, headerSize, size, summary = "$payloadLength byte(s)", warnings = warnings)
        payloadLength >= 8 && readAscii(reader, payloadStart, 8) == "OpusHead" ->
            decodeOpusIdentificationHeader(reader, offset, headerSize, size, payloadStart, payloadLength, warnings)
        payloadLength >= 8 && readAscii(reader, payloadStart, 8) == "OpusTags" ->
            decodeOggVendorComment(reader, "OggOpusTags", offset, headerSize, size, payloadStart + 8, payloadLength - 8, warnings)
        else -> null
    }
}

private fun decodeVorbisIdentificationHeader(
    reader: ByteReader, offset: Long, headerSize: Int, size: Long,
    payloadStart: Long, payloadLength: Int, warnings: List<String>,
): BoxNode {
    if (payloadLength < 30) {
        return BoxNode("OggVorbisIdentificationHeader", offset, headerSize, size, warnings = warnings + "Vorbis identification header is $payloadLength byte(s), expected 30")
    }
    val version = readUInt32LE(reader, payloadStart + 7)
    val channels = reader.readUInt8(payloadStart + 11)
    val sampleRate = readUInt32LE(reader, payloadStart + 12)
    val bitrateMaximum = readInt32LE(reader, payloadStart + 16)
    val bitrateNominal = readInt32LE(reader, payloadStart + 20)
    val bitrateMinimum = readInt32LE(reader, payloadStart + 24)
    val blocksizeByte = reader.readUInt8(payloadStart + 28)
    val blocksize0 = 1L shl (blocksizeByte and 0x0F)
    val blocksize1 = 1L shl ((blocksizeByte shr 4) and 0x0F)

    val fields = listOf(
        BoxField("version", version.toString(), payloadStart + 7, 4),
        BoxField("channels", channels.toString(), payloadStart + 11, 1),
        BoxField("sample_rate", sampleRate.toString(), payloadStart + 12, 4),
        BoxField("bitrate_maximum", bitrateMaximum.toString(), payloadStart + 16, 4),
        BoxField("bitrate_nominal", bitrateNominal.toString(), payloadStart + 20, 4),
        BoxField("bitrate_minimum", bitrateMinimum.toString(), payloadStart + 24, 4),
        BoxField("blocksize_0", blocksize0.toString(), payloadStart + 28, 1),
        BoxField("blocksize_1", blocksize1.toString(), payloadStart + 28, 1),
    )
    return BoxNode(
        "OggVorbisIdentificationHeader", offset, headerSize, size, fields = fields,
        summary = "${sampleRate}Hz, ${channels}ch",
        warnings = warnings,
    )
}

private fun decodeOpusIdentificationHeader(
    reader: ByteReader, offset: Long, headerSize: Int, size: Long,
    payloadStart: Long, payloadLength: Int, warnings: List<String>,
): BoxNode {
    if (payloadLength < 19) {
        return BoxNode("OggOpusIdentificationHeader", offset, headerSize, size, warnings = warnings + "Opus identification header is $payloadLength byte(s), expected 19")
    }
    val version = reader.readUInt8(payloadStart + 8)
    val channelCount = reader.readUInt8(payloadStart + 9)
    val preSkip = readUInt16LE(reader, payloadStart + 10)
    val inputSampleRate = readUInt32LE(reader, payloadStart + 12)
    val outputGain = readInt16LE(reader, payloadStart + 16)
    val channelMappingFamily = reader.readUInt8(payloadStart + 18)

    val fields = listOf(
        BoxField("version", version.toString(), payloadStart + 8, 1),
        BoxField("channel_count", channelCount.toString(), payloadStart + 9, 1),
        BoxField("pre_skip", preSkip.toString(), payloadStart + 10, 2),
        BoxField("input_sample_rate", inputSampleRate.toString(), payloadStart + 12, 4),
        BoxField("output_gain", outputGain.toString(), payloadStart + 16, 2),
        BoxField("channel_mapping_family", channelMappingFamily.toString(), payloadStart + 18, 1),
    )
    return BoxNode(
        "OggOpusIdentificationHeader", offset, headerSize, size, fields = fields,
        summary = "48000Hz (fixed), ${channelCount}ch",
        warnings = warnings,
    )
}

private fun decodeOggVendorComment(
    reader: ByteReader, name: String, offset: Long, headerSize: Int, size: Long,
    payloadStart: Long, payloadLength: Int, warnings: List<String>,
): BoxNode {
    val allWarnings = warnings.toMutableList()
    val payloadEnd = payloadStart + payloadLength
    val fields = mutableListOf<BoxField>()
    var pos = payloadStart

    if (pos + 4 > payloadEnd) {
        allWarnings.add("$name block is $payloadLength byte(s), too short for its vendor length field")
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

private fun readAscii(reader: ByteReader, offset: Long, length: Int): String =
    String(reader.readBytes(offset, length), Charsets.US_ASCII)

private fun readUInt16LE(reader: ByteReader, offset: Long): Int {
    val b = reader.readBytes(offset, 2)
    return (b[0].toInt() and 0xFF) or ((b[1].toInt() and 0xFF) shl 8)
}

private fun readInt16LE(reader: ByteReader, offset: Long): Int = readUInt16LE(reader, offset).toShort().toInt()

private fun readUInt32LE(reader: ByteReader, offset: Long): Long {
    val b = reader.readBytes(offset, 4)
    return (b[0].toLong() and 0xFF) or ((b[1].toLong() and 0xFF) shl 8) or
        ((b[2].toLong() and 0xFF) shl 16) or ((b[3].toLong() and 0xFF) shl 24)
}

private fun readInt32LE(reader: ByteReader, offset: Long): Long = readUInt32LE(reader, offset).toInt().toLong()

private fun readInt64LE(reader: ByteReader, offset: Long): Long {
    val b = reader.readBytes(offset, 8)
    var result = 0L
    for (i in 0 until 8) {
        result = result or ((b[i].toLong() and 0xFF) shl (8 * i))
    }
    return result
}
