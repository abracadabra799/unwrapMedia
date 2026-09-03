package com.multiviewer.parser

fun parseFlv(reader: ByteReader, start: Long, end: Long): List<BoxNode> {
    val result = mutableListOf<BoxNode>()
    if (end - start < 9) return result

    val sig = String(reader.readBytes(start, 3), Charsets.US_ASCII)
    val version = reader.readUInt8(start + 3)
    val flags = reader.readUInt8(start + 4)
    val dataOffset = reader.readUInt32(start + 5)

    val hasAudio = (flags and 0x04) != 0
    val hasVideo = (flags and 0x01) != 0

    val headerFields = listOf(
        BoxField("signature", sig, start, 3),
        BoxField("version", version.toString(), start + 3, 1),
        BoxField("has_audio", hasAudio.toString(), start + 4, 1),
        BoxField("has_video", hasVideo.toString(), start + 4, 1),
        BoxField("data_offset", dataOffset.toString(), start + 5, 4),
    )

    val streamsSummary = buildString {
        if (hasVideo) append("Video")
        if (hasAudio) {
            if (isNotEmpty()) append(" + ")
            append("Audio")
        }
    }.ifEmpty { "Empty" }

    result.add(
        BoxNode(
            type = "FLV Header",
            offset = start,
            headerSize = 9,
            size = dataOffset.coerceAtLeast(9L),
            fields = headerFields,
            summary = "FLV v$version ($streamsSummary)",
        )
    )

    var pos = start + dataOffset
    var tagCount = 0
    val maxIndividualTags = 30 // Parse first 30 tags individually for rich inspection

    while (pos + 15 <= end) {
        // Skip previous tag size (4 bytes)
        val prevTagSize = reader.readUInt32(pos)
        val tagStart = pos + 4
        if (tagStart + 11 > end) break

        val tagType = reader.readUInt8(tagStart)
        val dataSize = (reader.readUInt8(tagStart + 1) shl 16) or
            (reader.readUInt8(tagStart + 2) shl 8) or
            reader.readUInt8(tagStart + 3)
        val tsLower = (reader.readUInt8(tagStart + 4) shl 16) or
            (reader.readUInt8(tagStart + 5) shl 8) or
            reader.readUInt8(tagStart + 6)
        val tsUpper = reader.readUInt8(tagStart + 7)
        val timestampMs = (tsUpper.toLong() shl 24) or tsLower.toLong()
        val streamId = (reader.readUInt8(tagStart + 8) shl 16) or
            (reader.readUInt8(tagStart + 9) shl 8) or
            reader.readUInt8(tagStart + 10)

        val totalTagSize = 4L + 11L + dataSize
        if (pos + totalTagSize > end) {
            result.add(BoxNode("Tag", pos, 15, end - pos, warnings = listOf("Tag extends past end of file")))
            break
        }

        tagCount++
        if (tagCount <= maxIndividualTags || tagType == 18) {
            val node = decodeFlvTag(reader, tagType, tagStart, dataSize.toLong(), timestampMs, prevTagSize)
            result.add(node)
        } else if (tagCount == maxIndividualTags + 1) {
            val remainingBytes = end - pos
            result.add(
                BoxNode(
                    type = "MediaData",
                    offset = pos,
                    headerSize = 0,
                    size = remainingBytes,
                    summary = "Remaining interleaved FLV video and audio tags ($remainingBytes bytes)",
                )
            )
            break
        }

        pos += totalTagSize
    }

    return result
}

private fun decodeFlvTag(
    reader: ByteReader,
    tagType: Int,
    tagHeaderOffset: Long,
    dataSize: Long,
    timestampMs: Long,
    prevTagSize: Long,
): BoxNode {
    val payloadStart = tagHeaderOffset + 11
    val fields = mutableListOf<BoxField>()
    fields.add(BoxField("prev_tag_size", prevTagSize.toString(), tagHeaderOffset - 4, 4))
    fields.add(BoxField("tag_type", tagType.toString(), tagHeaderOffset, 1))
    fields.add(BoxField("data_size", dataSize.toString(), tagHeaderOffset + 1, 3))
    fields.add(BoxField("timestamp_ms", timestampMs.toString(), tagHeaderOffset + 4, 4))

    val typeName: String
    var summary: String? = null

    when (tagType) {
        18 -> { // Script data (metadata)
            typeName = "ScriptTag (onMetaData)"
            parseFlvAmfMetadata(reader, payloadStart, dataSize, fields)?.let { summary = it }
        }
        9 -> { // Video
            typeName = "VideoTag"
            if (dataSize >= 1) {
                val b0 = reader.readUInt8(payloadStart)
                val frameType = (b0 shr 4) and 0x0F
                val codecId = b0 and 0x0F

                val frameTypeName = when (frameType) {
                    1 -> "Keyframe"
                    2 -> "Inter frame"
                    3 -> "Disposable inter"
                    4 -> "Generated keyframe"
                    5 -> "Video info"
                    else -> "Type $frameType"
                }
                val codecName = when (codecId) {
                    7 -> "AVC/H.264"
                    2 -> "Sorenson Spark"
                    3 -> "Screen video"
                    4 -> "On2 VP6"
                    5 -> "On2 VP6 with alpha"
                    else -> "Codec $codecId"
                }

                fields.add(BoxField("frame_type", frameTypeName, payloadStart, 1))
                fields.add(BoxField("codec", codecName, payloadStart, 1))

                if (codecId == 7 && dataSize >= 5) {
                    val avcPacketType = reader.readUInt8(payloadStart + 1)
                    val compositionTime = (reader.readUInt8(payloadStart + 2) shl 16) or
                        (reader.readUInt8(payloadStart + 3) shl 8) or
                        reader.readUInt8(payloadStart + 4)
                    val avcTypeName = when (avcPacketType) {
                        0 -> "AVC Sequence Header (SPS/PPS)"
                        1 -> "AVC NALU"
                        2 -> "AVC End of Sequence"
                        else -> "AVC $avcPacketType"
                    }
                    fields.add(BoxField("avc_packet_type", avcTypeName, payloadStart + 1, 1))
                    fields.add(BoxField("composition_time", compositionTime.toString(), payloadStart + 2, 3))
                    summary = "$frameTypeName ($codecName) - $avcTypeName, PTS: ${timestampMs}ms"
                } else {
                    summary = "$frameTypeName ($codecName), PTS: ${timestampMs}ms"
                }
            }
        }
        8 -> { // Audio
            typeName = "AudioTag"
            if (dataSize >= 1) {
                val b0 = reader.readUInt8(payloadStart)
                val soundFormat = (b0 shr 4) and 0x0F
                val soundRate = (b0 shr 2) and 0x03
                val soundSize = (b0 shr 1) and 0x01
                val soundType = b0 and 0x01

                val formatName = when (soundFormat) {
                    10 -> "AAC"
                    2 -> "MP3"
                    0 -> "Linear PCM (platform endian)"
                    3 -> "Linear PCM (little endian)"
                    else -> "Format $soundFormat"
                }
                val rateName = when (soundRate) {
                    0 -> "5.5 kHz"
                    1 -> "11 kHz"
                    2 -> "22 kHz"
                    3 -> "44.1 kHz"
                    else -> "$soundRate"
                }
                val channelsName = if (soundType == 1) "Stereo" else "Mono"
                val bitsName = if (soundSize == 1) "16-bit" else "8-bit"

                fields.add(BoxField("format", formatName, payloadStart, 1))
                fields.add(BoxField("sample_rate", rateName, payloadStart, 1))
                fields.add(BoxField("bits_per_sample", bitsName, payloadStart, 1))
                fields.add(BoxField("channels", channelsName, payloadStart, 1))

                if (soundFormat == 10 && dataSize >= 2) {
                    val aacPacketType = reader.readUInt8(payloadStart + 1)
                    val aacTypeName = if (aacPacketType == 0) "AAC Sequence Header (AudioSpecificConfig)" else "AAC Raw"
                    fields.add(BoxField("aac_packet_type", aacTypeName, payloadStart + 1, 1))
                    summary = "$formatName ($aacTypeName), $rateName, $channelsName, PTS: ${timestampMs}ms"
                } else {
                    summary = "$formatName, $rateName, $channelsName, PTS: ${timestampMs}ms"
                }
            }
        }
        else -> {
            typeName = "Tag$tagType"
            summary = "Unknown tag type $tagType ($dataSize bytes)"
        }
    }

    return BoxNode(
        type = typeName,
        offset = tagHeaderOffset - 4,
        headerSize = 15,
        size = 15 + dataSize,
        fields = fields,
        summary = summary,
    )
}

private fun parseFlvAmfMetadata(
    reader: ByteReader,
    start: Long,
    length: Long,
    fields: MutableList<BoxField>,
): String? {
    if (length < 3) return null
    var pos = start
    val end = start + length

    // AMF0 Type 2: Short String
    if (reader.readUInt8(pos) == 2) {
        val strLen = reader.readUInt16(pos + 1)
        pos += 3
        if (pos + strLen > end) return null
        val name = String(reader.readBytes(pos, strLen), Charsets.UTF_8)
        pos += strLen
        fields.add(BoxField("amf_event", name, start, (pos - start).toLong()))
    }

    if (pos >= end) return null
    val valueType = reader.readUInt8(pos)
    pos++

    var durationSec: Double? = null
    var width: Double? = null
    var height: Double? = null
    var videoCodec: String? = null
    var audioCodec: String? = null

    // AMF0 Type 8: ECMA array (4 bytes count, then key-value pairs)
    // or AMF0 Type 3: Object (key-value pairs ending in 00 00 09)
    if (valueType == 8) {
        pos += 4 // Skip array length
    } else if (valueType != 3) {
        return null
    }

    while (pos + 2 <= end) {
        val keyLen = reader.readUInt16(pos)
        pos += 2
        if (keyLen == 0 && pos < end && reader.readUInt8(pos) == 9) {
            // End of Object marker (00 00 09)
            break
        }
        if (pos + keyLen > end) break
        val key = String(reader.readBytes(pos, keyLen), Charsets.UTF_8)
        pos += keyLen
        if (pos >= end) break

        val valType = reader.readUInt8(pos)
        pos++

        when (valType) {
            0 -> { // Number (IEEE 754 Double, 8 bytes)
                if (pos + 8 > end) break
                val rawLong = reader.readUInt64(pos)
                val doubleVal = Double.fromBits(rawLong)
                fields.add(BoxField(key, String.format(java.util.Locale.US, "%.2f", doubleVal), pos - keyLen - 3, keyLen + 11L))
                pos += 8

                when (key) {
                    "duration" -> durationSec = doubleVal
                    "width" -> width = doubleVal
                    "height" -> height = doubleVal
                    "videocodecid" -> videoCodec = if (doubleVal == 7.0) "AVC/H.264" else "Codec ${doubleVal.toInt()}"
                    "audiocodecid" -> audioCodec = if (doubleVal == 10.0) "AAC" else if (doubleVal == 2.0) "MP3" else "Codec ${doubleVal.toInt()}"
                }
            }
            1 -> { // Boolean (1 byte)
                if (pos + 1 > end) break
                val boolVal = reader.readUInt8(pos) != 0
                fields.add(BoxField(key, boolVal.toString(), pos - keyLen - 3, keyLen + 4L))
                pos += 1
            }
            2 -> { // String (2 bytes len + string)
                if (pos + 2 > end) break
                val sLen = reader.readUInt16(pos)
                pos += 2
                if (pos + sLen > end) break
                val sVal = String(reader.readBytes(pos, sLen), Charsets.UTF_8)
                fields.add(BoxField(key, sVal, pos - keyLen - 5, (keyLen + 5 + sLen).toLong()))
                pos += sLen
            }
            else -> {
                // Skip unhandled AMF type and stop parsing remainder of this array
                break
            }
        }
    }

    val parts = mutableListOf<String>()
    if (width != null && height != null) {
        parts.add("${width.toInt()}x${height.toInt()}")
    }
    if (durationSec != null) {
        parts.add(String.format(java.util.Locale.US, "%.2fs", durationSec))
    }
    if (videoCodec != null) parts.add(videoCodec)
    if (audioCodec != null) parts.add(audioCodec)

    return if (parts.isNotEmpty()) parts.joinToString(", ") else "FLV metadata properties"
}
