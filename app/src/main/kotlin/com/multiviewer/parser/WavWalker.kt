package com.multiviewer.parser

private val WAVE_FORMAT_NAMES = mapOf(
    1 to "PCM",
    3 to "IEEE Float",
    6 to "A-law",
    7 to "mu-law",
    0xFFFE to "Extensible",
)

fun parseWavChunks(reader: ByteReader, start: Long, end: Long): List<BoxNode> {
    val result = mutableListOf<BoxNode>()
    if (end - start < 12) return result

    val riffSize = readUInt32LE(reader, start + 4)
    result.add(
        BoxNode(
            type = "RIFF",
            offset = start,
            headerSize = 8,
            size = 12,
            fields = listOf(
                BoxField("file_size", (riffSize + 8).toString(), start + 4, 4),
                BoxField("form_type", reader.readFourCC(start + 8), start + 8, 4),
            ),
        ),
    )

    var pos = start + 12
    while (pos + 8 <= end) {
        val type = reader.readFourCC(pos)
        val chunkSize = readUInt32LE(reader, pos + 4)
        val totalSize = 8 + chunkSize
        val paddedSize = if (chunkSize % 2 == 1L) totalSize + 1 else totalSize

        if (pos + totalSize > end) {
            result.add(BoxNode(type, pos, 8, end - pos, warnings = listOf("Chunk extends past end of file")))
            break
        }

        result.add(decodeWavChunk(reader, type, pos, chunkSize, totalSize))
        pos += paddedSize
    }
    return result
}

private fun decodeWavChunk(reader: ByteReader, type: String, offset: Long, payloadSize: Long, totalSize: Long): BoxNode {
    val payloadStart = offset + 8
    val fields = mutableListOf<BoxField>()
    var summary: String? = null

    when (type) {
        "fmt " -> {
            if (payloadSize >= 16) {
                val audioFormat = readUInt16LE(reader, payloadStart)
                val numChannels = readUInt16LE(reader, payloadStart + 2)
                val sampleRate = readUInt32LE(reader, payloadStart + 4)
                val byteRate = readUInt32LE(reader, payloadStart + 8)
                val blockAlign = readUInt16LE(reader, payloadStart + 12)
                val bitsPerSample = readUInt16LE(reader, payloadStart + 14)
                val formatName = WAVE_FORMAT_NAMES[audioFormat] ?: "Unknown (0x${audioFormat.toString(16)})"
                fields.add(BoxField("audio_format", formatName, payloadStart, 2))
                fields.add(BoxField("num_channels", numChannels.toString(), payloadStart + 2, 2))
                fields.add(BoxField("sample_rate", sampleRate.toString(), payloadStart + 4, 4))
                fields.add(BoxField("byte_rate", byteRate.toString(), payloadStart + 8, 4))
                fields.add(BoxField("block_align", blockAlign.toString(), payloadStart + 12, 2))
                fields.add(BoxField("bits_per_sample", bitsPerSample.toString(), payloadStart + 14, 2))
                summary = "$formatName, ${numChannels}ch, ${sampleRate}Hz, ${bitsPerSample}-bit"
            }
        }
        "data" -> {
            summary = "Audio sample data ($payloadSize bytes)"
        }
        "fact" -> {
            if (payloadSize >= 4) {
                val sampleCount = readUInt32LE(reader, payloadStart)
                fields.add(BoxField("sample_count", sampleCount.toString(), payloadStart, 4))
            }
        }
    }

    return BoxNode(type = type, offset = offset, headerSize = 8, size = totalSize, fields = fields, summary = summary)
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
