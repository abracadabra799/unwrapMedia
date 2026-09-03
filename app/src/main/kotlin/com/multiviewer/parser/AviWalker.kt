package com.multiviewer.parser

fun parseAviChunks(reader: ByteReader, start: Long, end: Long): List<BoxNode> {
    val result = mutableListOf<BoxNode>()
    if (end - start < 12) return result

    val riffSize = readUInt32LE(reader, start + 4)
    val formType = reader.readFourCC(start + 8)

    val rootChildren = mutableListOf<BoxNode>()
    var pos = start + 12
    val riffEnd = minOf(end, start + 8 + riffSize)

    while (pos + 8 <= riffEnd) {
        val chunkId = reader.readFourCC(pos)
        val chunkSize = readUInt32LE(reader, pos + 4)
        val chunkTotalSize = 8 + chunkSize
        val paddedSize = if (chunkSize % 2 == 1L) chunkTotalSize + 1 else chunkTotalSize

        if (pos + chunkTotalSize > riffEnd) {
            rootChildren.add(BoxNode(chunkId, pos, 8, riffEnd - pos, warnings = listOf("Chunk extends past RIFF size")))
            break
        }

        if (chunkId == "LIST") {
            if (chunkSize >= 4) {
                val listType = reader.readFourCC(pos + 8)
                val listChildren = parseAviListChildren(reader, listType, pos + 12, pos + chunkTotalSize)
                rootChildren.add(
                    BoxNode(
                        type = "LIST ($listType)",
                        offset = pos,
                        headerSize = 12,
                        size = chunkTotalSize,
                        children = listChildren,
                        summary = "LIST chunk of type $listType ($chunkSize bytes)",
                    )
                )
            } else {
                rootChildren.add(BoxNode("LIST", pos, 8, chunkTotalSize))
            }
        } else if (chunkId == "idx1") {
            val entryCount = chunkSize / 16
            rootChildren.add(
                BoxNode(
                    type = "idx1",
                    offset = pos,
                    headerSize = 8,
                    size = chunkTotalSize,
                    fields = listOf(
                        BoxField("entry_count", entryCount.toString(), pos + 8, 4),
                        BoxField("data_size", chunkSize.toString(), pos + 4, 4),
                    ),
                    summary = "AVI 1.0 Index ($entryCount entries)",
                )
            )
        } else {
            rootChildren.add(decodeAviChunk(reader, chunkId, pos, chunkSize, chunkTotalSize))
        }

        pos += paddedSize
    }

    result.add(
        BoxNode(
            type = "RIFF",
            offset = start,
            headerSize = 12,
            size = if (riffSize > 0) riffSize + 8 else end - start,
            fields = listOf(
                BoxField("file_size", (riffSize + 8).toString(), start + 4, 4),
                BoxField("form_type", formType, start + 8, 4),
            ),
            children = rootChildren,
        )
    )

    return result
}

private fun parseAviListChildren(reader: ByteReader, listType: String, start: Long, end: Long): List<BoxNode> {
    val children = mutableListOf<BoxNode>()
    if (listType == "movi") {
        children.add(
            BoxNode(
                type = "MovieData",
                offset = start,
                headerSize = 0,
                size = end - start,
                summary = "Interleaved audio/video sample chunks (${end - start} bytes)",
            )
        )
        return children
    }

    var pos = start
    while (pos + 8 <= end) {
        val chunkId = reader.readFourCC(pos)
        val chunkSize = readUInt32LE(reader, pos + 4)
        val chunkTotalSize = 8 + chunkSize
        val paddedSize = if (chunkSize % 2 == 1L) chunkTotalSize + 1 else chunkTotalSize

        if (pos + chunkTotalSize > end) {
            children.add(BoxNode(chunkId, pos, 8, end - pos, warnings = listOf("Chunk extends past list end")))
            break
        }

        if (chunkId == "LIST") {
            if (chunkSize >= 4) {
                val subListType = reader.readFourCC(pos + 8)
                val subChildren = parseAviListChildren(reader, subListType, pos + 12, pos + chunkTotalSize)
                children.add(
                    BoxNode(
                        type = "LIST ($subListType)",
                        offset = pos,
                        headerSize = 12,
                        size = chunkTotalSize,
                        children = subChildren,
                        summary = "LIST $subListType ($chunkSize bytes)",
                    )
                )
            } else {
                children.add(BoxNode("LIST", pos, 8, chunkTotalSize))
            }
        } else {
            children.add(decodeAviChunk(reader, chunkId, pos, chunkSize, chunkTotalSize))
        }

        pos += paddedSize
    }
    return children
}

private fun decodeAviChunk(reader: ByteReader, type: String, offset: Long, payloadSize: Long, totalSize: Long): BoxNode {
    val payloadStart = offset + 8
    val fields = mutableListOf<BoxField>()
    var summary: String? = null

    when (type) {
        "avih" -> {
            if (payloadSize >= 40) {
                val microSecPerFrame = readUInt32LE(reader, payloadStart)
                val maxBytesPerSec = readUInt32LE(reader, payloadStart + 4)
                val totalFrames = readUInt32LE(reader, payloadStart + 16)
                val streams = readUInt32LE(reader, payloadStart + 24)
                val width = readUInt32LE(reader, payloadStart + 32)
                val height = readUInt32LE(reader, payloadStart + 36)

                val fps = if (microSecPerFrame > 0) 1_000_000.0 / microSecPerFrame else 0.0
                fields.add(BoxField("micro_sec_per_frame", microSecPerFrame.toString(), payloadStart, 4))
                fields.add(BoxField("fps", String.format(java.util.Locale.US, "%.2f", fps), payloadStart, 4))
                fields.add(BoxField("max_bytes_per_sec", maxBytesPerSec.toString(), payloadStart + 4, 4))
                fields.add(BoxField("total_frames", totalFrames.toString(), payloadStart + 16, 4))
                fields.add(BoxField("streams", streams.toString(), payloadStart + 24, 4))
                fields.add(BoxField("width", width.toString(), payloadStart + 32, 4))
                fields.add(BoxField("height", height.toString(), payloadStart + 36, 4))

                summary = "${width}x$height, ${String.format(java.util.Locale.US, "%.2f", fps)} fps, $totalFrames frames"
            }
        }

        "strh" -> {
            if (payloadSize >= 48) {
                val fccType = reader.readFourCC(payloadStart)
                val fccHandler = reader.readFourCC(payloadStart + 4)
                val scale = readUInt32LE(reader, payloadStart + 20)
                val rate = readUInt32LE(reader, payloadStart + 24)
                val start = readUInt32LE(reader, payloadStart + 28)
                val length = readUInt32LE(reader, payloadStart + 32)

                val ratePerSec = if (scale > 0) rate.toDouble() / scale else 0.0
                fields.add(BoxField("stream_type", fccType, payloadStart, 4))
                fields.add(BoxField("handler_codec", fccHandler, payloadStart + 4, 4))
                fields.add(BoxField("scale", scale.toString(), payloadStart + 20, 4))
                fields.add(BoxField("rate", rate.toString(), payloadStart + 24, 4))
                fields.add(BoxField("rate_calculated", String.format(java.util.Locale.US, "%.2f", ratePerSec), payloadStart + 24, 4))
                fields.add(BoxField("start", start.toString(), payloadStart + 28, 4))
                fields.add(BoxField("length", length.toString(), payloadStart + 32, 4))

                val typeLabel = when (fccType) {
                    "vids" -> "Video"
                    "auds" -> "Audio"
                    "mids" -> "MIDI"
                    "txts" -> "Text"
                    else -> fccType
                }
                summary = "$typeLabel stream ($fccHandler), ${String.format(java.util.Locale.US, "%.2f", ratePerSec)} units/sec"
            }
        }

        "strf" -> {
            if (payloadSize >= 40) {
                val biSize = readUInt32LE(reader, payloadStart)
                if (biSize in 40L..1024L) {
                    val biWidth = readUInt32LE(reader, payloadStart + 4)
                    val biHeight = readUInt32LE(reader, payloadStart + 8)
                    val biBitCount = readUInt16LE(reader, payloadStart + 14)
                    val biCompression = reader.readFourCC(payloadStart + 16)
                    val biSizeImage = readUInt32LE(reader, payloadStart + 20)

                    fields.add(BoxField("header_size", biSize.toString(), payloadStart, 4))
                    fields.add(BoxField("width", biWidth.toString(), payloadStart + 4, 4))
                    fields.add(BoxField("height", biHeight.toString(), payloadStart + 8, 4))
                    fields.add(BoxField("bit_count", biBitCount.toString(), payloadStart + 14, 2))
                    fields.add(BoxField("compression", biCompression, payloadStart + 16, 4))
                    fields.add(BoxField("image_size", biSizeImage.toString(), payloadStart + 20, 4))

                    summary = "Bitmap Header: ${biWidth}x${biHeight}, $biCompression, ${biBitCount}-bit"
                } else if (payloadSize in 14..38) {
                    decodeWaveFormat(reader, payloadStart, payloadSize, fields)?.let { summary = it }
                }
            } else if (payloadSize >= 14) {
                decodeWaveFormat(reader, payloadStart, payloadSize, fields)?.let { summary = it }
            }
        }

        "dmlh" -> {
            if (payloadSize >= 4) {
                val totalFrames = readUInt32LE(reader, payloadStart)
                fields.add(BoxField("total_frames", totalFrames.toString(), payloadStart, 4))
                summary = "OpenDML Total Frames: $totalFrames"
            }
        }
    }

    return BoxNode(type = type, offset = offset, headerSize = 8, size = totalSize, fields = fields, summary = summary)
}

private fun decodeWaveFormat(reader: ByteReader, payloadStart: Long, payloadSize: Long, fields: MutableList<BoxField>): String? {
    if (payloadSize < 14) return null
    val formatTag = readUInt16LE(reader, payloadStart)
    val channels = readUInt16LE(reader, payloadStart + 2)
    val sampleRate = readUInt32LE(reader, payloadStart + 4)
    val avgBytesPerSec = readUInt32LE(reader, payloadStart + 8)
    val blockAlign = readUInt16LE(reader, payloadStart + 12)
    val bitsPerSample = if (payloadSize >= 16) readUInt16LE(reader, payloadStart + 14) else 0

    val formatName = when (formatTag) {
        0x0001 -> "PCM"
        0x0003 -> "IEEE Float"
        0x0055 -> "MP3"
        0x00FF -> "AAC"
        0x0161 -> "WMA v2"
        0x0162 -> "WMA Pro"
        else -> "0x%04X".format(formatTag)
    }

    fields.add(BoxField("audio_format", formatName, payloadStart, 2))
    fields.add(BoxField("channels", channels.toString(), payloadStart + 2, 2))
    fields.add(BoxField("sample_rate", sampleRate.toString(), payloadStart + 4, 4))
    fields.add(BoxField("avg_bytes_per_sec", avgBytesPerSec.toString(), payloadStart + 8, 4))
    fields.add(BoxField("block_align", blockAlign.toString(), payloadStart + 12, 2))
    if (payloadSize >= 16) {
        fields.add(BoxField("bits_per_sample", bitsPerSample.toString(), payloadStart + 14, 2))
    }
    return "$formatName, ${channels}ch, ${sampleRate}Hz"
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
