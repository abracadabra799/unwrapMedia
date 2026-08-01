package com.multiviewer.parser

private val AIFF_COMPRESSION_TYPE_NAMES = mapOf(
    "NONE" to "PCM (uncompressed)",
    "sowt" to "PCM (little-endian)",
    "fl32" to "32-bit float",
    "fl64" to "64-bit float",
    "ima4" to "IMA 4:1 ADPCM",
    "MAC3" to "MACE 3:1",
    "MAC6" to "MACE 6:1",
    "ulaw" to "µ-law",
    "ULAW" to "µ-law",
    "alaw" to "A-law",
    "ALAW" to "A-law",
    "Qclp" to "Qualcomm PureVoice",
    "QDMC" to "QDesign Music",
    "QDM2" to "QDesign Music 2",
)

fun parseAiffChunks(reader: ByteReader, start: Long, end: Long): List<BoxNode> {
    val result = mutableListOf<BoxNode>()
    if (end - start < 12) return result

    val formSize = reader.readUInt32(start + 4)
    val formType = reader.readFourCC(start + 8)
    result.add(
        BoxNode(
            type = "FORM", offset = start, headerSize = 8, size = 12,
            fields = listOf(
                BoxField("file_size", (formSize + 8).toString(), start + 4, 4),
                BoxField("form_type", if (formType == "AIFC") "AIFF-C" else formType, start + 8, 4),
            ),
        ),
    )

    var pos = start + 12
    while (pos + 8 <= end) {
        val type = reader.readFourCC(pos)
        val chunkSize = reader.readUInt32(pos + 4)
        val totalSize = 8 + chunkSize
        val paddedSize = if (chunkSize % 2 == 1L) totalSize + 1 else totalSize

        if (pos + totalSize > end) {
            result.add(BoxNode(type, pos, 8, end - pos, warnings = listOf("Chunk extends past end of file")))
            break
        }

        result.add(decodeAiffChunk(reader, type, pos, chunkSize, totalSize, formType))
        pos += paddedSize
    }
    return result
}

private fun decodeAiffChunk(reader: ByteReader, type: String, offset: Long, payloadSize: Long, totalSize: Long, formType: String): BoxNode {
    val payloadStart = offset + 8
    val fields = mutableListOf<BoxField>()
    var summary: String? = null

    when (type) {
        "COMM" -> {
            if (payloadSize >= 18) {
                val numChannels = reader.readUInt16(payloadStart)
                val numSampleFrames = reader.readUInt32(payloadStart + 2)
                val sampleSize = reader.readUInt16(payloadStart + 6)
                val sampleRate = readExtendedFloat80(reader, payloadStart + 8)
                val sampleRateRounded = Math.round(sampleRate)
                fields.add(BoxField("num_channels", numChannels.toString(), payloadStart, 2))
                fields.add(BoxField("num_sample_frames", numSampleFrames.toString(), payloadStart + 2, 4))
                fields.add(BoxField("sample_size", sampleSize.toString(), payloadStart + 6, 2))
                fields.add(BoxField("sample_rate", sampleRateRounded.toString(), payloadStart + 8, 10))
                var formatName = "PCM"
                if (formType == "AIFC" && payloadSize >= 18 + 4) {
                    val compressionType = reader.readFourCC(payloadStart + 18)
                    formatName = AIFF_COMPRESSION_TYPE_NAMES[compressionType] ?: "Unknown ($compressionType)"
                    fields.add(BoxField("compression_type", formatName, payloadStart + 18, 4))
                    if (payloadSize >= 18 + 4 + 1) {
                        val nameLength = reader.readUInt8(payloadStart + 22)
                        if (payloadSize >= 18 + 4 + 1 + nameLength) {
                            val compressionName = String(reader.readBytes(payloadStart + 23, nameLength), Charsets.US_ASCII)
                            fields.add(BoxField("compression_name", compressionName, payloadStart + 23, nameLength.toLong()))
                        }
                    }
                }
                summary = "$formatName, ${numChannels}ch, ${sampleRateRounded}Hz, ${sampleSize}-bit"
            }
        }
        "SSND" -> {
            if (payloadSize >= 8) {
                val ssndOffset = reader.readUInt32(payloadStart)
                val blockSize = reader.readUInt32(payloadStart + 4)
                fields.add(BoxField("offset", ssndOffset.toString(), payloadStart, 4))
                fields.add(BoxField("block_size", blockSize.toString(), payloadStart + 4, 4))
                summary = "Audio sample data (${payloadSize - 8} bytes)"
            }
        }
        else -> {
            summary = "$payloadSize byte(s)"
        }
    }

    return BoxNode(type = type, offset = offset, headerSize = 8, size = totalSize, fields = fields, summary = summary)
}

private fun readExtendedFloat80(reader: ByteReader, offset: Long): Double {
    val signExponent = reader.readUInt16(offset)
    val sign = if ((signExponent and 0x8000) != 0) -1.0 else 1.0
    val exponent = signExponent and 0x7FFF
    val mantissa = reader.readUInt64(offset + 2)
    if (exponent == 0 && mantissa == 0L) return 0.0
    return sign * mantissa.toULong().toDouble() * Math.pow(2.0, (exponent - 16383 - 63).toDouble())
}
