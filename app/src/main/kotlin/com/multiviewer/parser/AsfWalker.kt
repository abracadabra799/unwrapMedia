package com.multiviewer.parser

import java.util.UUID

object AsfGuids {
    val HEADER = UUID.fromString("75b22630-668e-11cf-a6d9-00aa0062ce6c")
    val DATA = UUID.fromString("75b22636-668e-11cf-a6d9-00aa0062ce6c")
    val SIMPLE_INDEX = UUID.fromString("33000890-e5b1-11cf-89f4-00a0c90349cb")

    val FILE_PROPERTIES = UUID.fromString("8cabdca1-a947-11cf-8ee4-00c00c205365")
    val STREAM_PROPERTIES = UUID.fromString("b7dc0791-a9b7-11cf-8ee6-00c00c205365")
    val HEADER_EXTENSION = UUID.fromString("5fbf03b5-a92e-11cf-8ee3-00c00c205365")
    val CONTENT_DESCRIPTION = UUID.fromString("75b22633-668e-11cf-a6d9-00aa0062ce6c")
    val EXTENDED_CONTENT_DESCRIPTION = UUID.fromString("d2d0a440-e307-11d2-97f0-00a0c95ea850")

    val STREAM_TYPE_VIDEO = UUID.fromString("bc19cfc5-daf9-11d0-8bfe-00a0c90395f6")
    val STREAM_TYPE_AUDIO = UUID.fromString("f8699e40-5b4d-11cf-a8fd-00805f5c442b")
}

fun parseAsf(reader: ByteReader, start: Long, end: Long): List<BoxNode> {
    val result = mutableListOf<BoxNode>()
    var pos = start

    while (pos + 24 <= end) {
        val guid = readGuid(reader, pos)
        val size = readUInt64LE(reader, pos + 16)

        if (size < 24 || pos + size > end) {
            val remaining = end - pos
            result.add(
                BoxNode(
                    type = "Object ($guid)",
                    offset = pos,
                    headerSize = 24,
                    size = remaining,
                    warnings = listOf("Object size $size extends past file end or invalid"),
                )
            )
            break
        }

        when (guid) {
            AsfGuids.HEADER -> {
                val headerChildren = parseAsfHeaderChildren(reader, pos + 24 + 6, pos + size)
                result.add(
                    BoxNode(
                        type = "ASF Header Object",
                        offset = pos,
                        headerSize = 24,
                        size = size,
                        children = headerChildren,
                        summary = "ASF/WMV Header (${headerChildren.size} sub-objects)",
                    )
                )
            }
            AsfGuids.DATA -> {
                result.add(
                    BoxNode(
                        type = "ASF Data Object",
                        offset = pos,
                        headerSize = 24,
                        size = size,
                        summary = "Media packet stream ($size bytes)",
                    )
                )
            }
            AsfGuids.SIMPLE_INDEX -> {
                result.add(
                    BoxNode(
                        type = "ASF Simple Index Object",
                        offset = pos,
                        headerSize = 24,
                        size = size,
                        summary = "Video frame index ($size bytes)",
                    )
                )
            }
            else -> {
                result.add(
                    BoxNode(
                        type = "Object (${guid.toString().take(8)}...)",
                        offset = pos,
                        headerSize = 24,
                        size = size,
                        summary = "ASF object $guid ($size bytes)",
                    )
                )
            }
        }

        pos += size
    }

    return result
}

private fun parseAsfHeaderChildren(reader: ByteReader, start: Long, end: Long): List<BoxNode> {
    val children = mutableListOf<BoxNode>()
    var pos = start

    while (pos + 24 <= end) {
        val guid = readGuid(reader, pos)
        val size = readUInt64LE(reader, pos + 16)

        if (size < 24 || pos + size > end) {
            children.add(BoxNode("SubObject", pos, 24, end - pos, warnings = listOf("Subobject extends past header end")))
            break
        }

        val node = when (guid) {
            AsfGuids.FILE_PROPERTIES -> decodeFileProperties(reader, pos, size)
            AsfGuids.STREAM_PROPERTIES -> decodeStreamProperties(reader, pos, size)
            AsfGuids.CONTENT_DESCRIPTION -> decodeContentDescription(reader, pos, size)
            AsfGuids.HEADER_EXTENSION -> {
                BoxNode(
                    type = "Header Extension Object",
                    offset = pos,
                    headerSize = 24,
                    size = size,
                    summary = "Extended header properties ($size bytes)",
                )
            }
            else -> {
                BoxNode(
                    type = "HeaderObject (${guid.toString().take(8)}...)",
                    offset = pos,
                    headerSize = 24,
                    size = size,
                    summary = "Object $guid ($size bytes)",
                )
            }
        }
        children.add(node)
        pos += size
    }

    return children
}

private fun decodeFileProperties(reader: ByteReader, offset: Long, size: Long): BoxNode {
    val payloadStart = offset + 24
    val fields = mutableListOf<BoxField>()
    var summary: String? = null

    if (size >= 104) {
        val fileSize = readUInt64LE(reader, payloadStart + 16)
        val dataPacketsCount = readUInt64LE(reader, payloadStart + 32)
        val playDuration100ns = readUInt64LE(reader, payloadStart + 40)
        val sendDuration100ns = readUInt64LE(reader, payloadStart + 48)
        val preroll = readUInt64LE(reader, payloadStart + 56)
        val maxBitrate = readUInt32LE(reader, payloadStart + 76)

        val durationSec = playDuration100ns.toDouble() / 10_000_000.0

        fields.add(BoxField("file_size", fileSize.toString(), payloadStart + 16, 8))
        fields.add(BoxField("data_packets_count", dataPacketsCount.toString(), payloadStart + 32, 8))
        fields.add(BoxField("play_duration_sec", String.format(java.util.Locale.US, "%.3fs", durationSec), payloadStart + 40, 8))
        fields.add(BoxField("preroll_ms", preroll.toString(), payloadStart + 56, 8))
        fields.add(BoxField("max_bitrate_bps", maxBitrate.toString(), payloadStart + 76, 4))

        summary = "Duration: ${String.format(java.util.Locale.US, "%.2fs", durationSec)}, Max Bitrate: ${maxBitrate / 1000} kbps"
    }

    return BoxNode(
        type = "File Properties Object",
        offset = offset,
        headerSize = 24,
        size = size,
        fields = fields,
        summary = summary,
    )
}

private fun decodeStreamProperties(reader: ByteReader, offset: Long, size: Long): BoxNode {
    val payloadStart = offset + 24
    val fields = mutableListOf<BoxField>()
    var summary: String? = null

    if (size >= 78) {
        val streamTypeGuid = readGuid(reader, payloadStart)
        val flags = readUInt16LE(reader, payloadStart + 48)
        val streamNumber = flags and 0x007F

        fields.add(BoxField("stream_number", streamNumber.toString(), payloadStart + 48, 2))

        val typeDataStart = payloadStart + 54
        if (streamTypeGuid == AsfGuids.STREAM_TYPE_VIDEO) {
            fields.add(BoxField("stream_type", "Video", payloadStart, 16))
            if (payloadStart + 54 + 40 <= offset + size) {
                val encWidth = readUInt32LE(reader, typeDataStart)
                val encHeight = readUInt32LE(reader, typeDataStart + 4)
                fields.add(BoxField("encoded_width", encWidth.toString(), typeDataStart, 4))
                fields.add(BoxField("encoded_height", encHeight.toString(), typeDataStart + 4, 4))

                val bmpHeaderStart = typeDataStart + 11
                if (bmpHeaderStart + 40 <= offset + size) {
                    val width = readUInt32LE(reader, bmpHeaderStart + 4)
                    val height = readUInt32LE(reader, bmpHeaderStart + 8)
                    val compression = reader.readFourCC(bmpHeaderStart + 16)
                    val bitCount = readUInt16LE(reader, bmpHeaderStart + 14)

                    fields.add(BoxField("width", width.toString(), bmpHeaderStart + 4, 4))
                    fields.add(BoxField("height", height.toString(), bmpHeaderStart + 8, 4))
                    fields.add(BoxField("compression", compression, bmpHeaderStart + 16, 4))
                    fields.add(BoxField("bit_count", bitCount.toString(), bmpHeaderStart + 14, 2))

                    val codecName = when (compression) {
                        "WMV3" -> "Windows Media Video 9 (WMV3)"
                        "WVC1" -> "VC-1 Advanced Profile (WVC1)"
                        "WMV1" -> "Windows Media Video 7"
                        "WMV2" -> "Windows Media Video 8"
                        else -> compression
                    }
                    summary = "Video Stream #$streamNumber: ${width}x${height}, $codecName"
                } else {
                    summary = "Video Stream #$streamNumber: ${encWidth}x${encHeight}"
                }
            }
        } else if (streamTypeGuid == AsfGuids.STREAM_TYPE_AUDIO) {
            fields.add(BoxField("stream_type", "Audio", payloadStart, 16))
            if (typeDataStart + 16 <= offset + size) {
                val formatTag = readUInt16LE(reader, typeDataStart)
                val channels = readUInt16LE(reader, typeDataStart + 2)
                val sampleRate = readUInt32LE(reader, typeDataStart + 4)
                val avgBytesPerSec = readUInt32LE(reader, typeDataStart + 8)

                val formatName = when (formatTag) {
                    0x0161 -> "WMA v2"
                    0x0162 -> "WMA Pro"
                    0x0163 -> "WMA Lossless"
                    0x0001 -> "PCM"
                    0x0055 -> "MP3"
                    else -> "0x%04X".format(formatTag)
                }

                fields.add(BoxField("format", formatName, typeDataStart, 2))
                fields.add(BoxField("channels", channels.toString(), typeDataStart + 2, 2))
                fields.add(BoxField("sample_rate", sampleRate.toString(), typeDataStart + 4, 4))
                fields.add(BoxField("avg_bitrate_kbps", ((avgBytesPerSec * 8) / 1000).toString(), typeDataStart + 8, 4))

                summary = "Audio Stream #$streamNumber: $formatName, ${channels}ch, ${sampleRate}Hz"
            }
        } else {
            fields.add(BoxField("stream_type", streamTypeGuid.toString(), payloadStart, 16))
            summary = "Stream #$streamNumber ($streamTypeGuid)"
        }
    }

    return BoxNode(
        type = "Stream Properties Object",
        offset = offset,
        headerSize = 24,
        size = size,
        fields = fields,
        summary = summary,
    )
}

private fun decodeContentDescription(reader: ByteReader, offset: Long, size: Long): BoxNode {
    val payloadStart = offset + 24
    val fields = mutableListOf<BoxField>()
    var summary: String? = null

    if (size >= 34) {
        val titleLen = readUInt16LE(reader, payloadStart)
        val authorLen = readUInt16LE(reader, payloadStart + 2)
        val copyrightLen = readUInt16LE(reader, payloadStart + 4)
        val descLen = readUInt16LE(reader, payloadStart + 6)
        val ratingLen = readUInt16LE(reader, payloadStart + 8)

        var textPos = payloadStart + 10
        fun readUnicodeString(len: Int, name: String): String? {
            if (len <= 0 || textPos + len > offset + size) return null
            val bytes = reader.readBytes(textPos, len)
            textPos += len
            val str = String(bytes, Charsets.UTF_16LE).trimEnd('\u0000')
            if (str.isNotEmpty()) {
                fields.add(BoxField(name, str, textPos - len, len.toLong()))
            }
            return str
        }

        val title = readUnicodeString(titleLen, "title")
        val author = readUnicodeString(authorLen, "author")
        readUnicodeString(copyrightLen, "copyright")
        readUnicodeString(descLen, "description")
        readUnicodeString(ratingLen, "rating")

        summary = listOfNotNull(title, author).joinToString(" - ").ifEmpty { null }
    }

    return BoxNode(
        type = "Content Description Object",
        offset = offset,
        headerSize = 24,
        size = size,
        fields = fields,
        summary = summary,
    )
}

private fun readGuid(reader: ByteReader, offset: Long): UUID {
    val bytes = reader.readBytes(offset, 16)
    // Little-endian GUID to standard Java UUID
    val d1 = ((bytes[3].toLong() and 0xFF) shl 24) or
        ((bytes[2].toLong() and 0xFF) shl 16) or
        ((bytes[1].toLong() and 0xFF) shl 8) or
        (bytes[0].toLong() and 0xFF)
    val d2 = ((bytes[5].toInt() and 0xFF) shl 8) or (bytes[4].toInt() and 0xFF)
    val d3 = ((bytes[7].toInt() and 0xFF) shl 8) or (bytes[6].toInt() and 0xFF)

    val mostSigBits = (d1 shl 32) or ((d2.toLong() and 0xFFFFL) shl 16) or (d3.toLong() and 0xFFFFL)
    var leastSigBits = 0L
    for (i in 8..15) {
        leastSigBits = (leastSigBits shl 8) or (bytes[i].toLong() and 0xFF)
    }

    return UUID(mostSigBits, leastSigBits)
}

private fun readUInt16LE(reader: ByteReader, offset: Long): Int {
    val b = reader.readBytes(offset, 2)
    return (b[0].toInt() and 0xFF) or ((b[1].toInt() and 0xFF) shl 8)
}

private fun readUInt32LE(reader: ByteReader, offset: Long): Long {
    val b = reader.readBytes(offset, 4)
    return (b[0].toLong() and 0xFF) or ((b[1].toLong() and 0xFF) shl 8) or
        ((b[2].toLong() and 0xFF) shl 16) or ((b[3].toLong() and 0xFF) shl 24)
}

private fun readUInt64LE(reader: ByteReader, offset: Long): Long {
    val lo = readUInt32LE(reader, offset)
    val hi = readUInt32LE(reader, offset + 4)
    return (hi shl 32) or (lo and 0xFFFFFFFFL)
}
