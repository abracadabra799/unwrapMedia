package com.multiviewer.parser

import java.io.File

private const val SEI_NAL_READ_CAP = 256 * 1024
private const val METADATA_OBU_READ_CAP = 64 * 1024
private const val AV1_OBU_METADATA = 5
private const val H264_NAL_SEI = 6
private const val HEVC_NAL_PREFIX_SEI = 39
private const val HEVC_NAL_SUFFIX_SEI = 40

// Judged from the first video trak's sample entry only (same rule as buildVideoSampleTable), so UI
// code can gate per-frame sample reads without touching data.
fun detectVideoCodec(root: BoxNode): VideoCodec? =
    firstVideoSampleEntry(root)?.let { (_, entry) -> SAMPLE_ENTRY_CODECS.getValue(entry.type) }

fun readFrameMetadataUnits(file: File, codec: VideoCodec, nalLengthSize: Int?, sampleOffset: Long, sampleSize: Long): List<MetadataUnit> =
    try {
        ByteReader.open(file).use { readFrameMetadataUnits(it, codec, nalLengthSize, sampleOffset, sampleSize) }
    } catch (e: Exception) {
        emptyList()
    }

// Walks every NAL/OBU of one sample (the whole sample, so HEVC suffix SEI and NAL 62/63 after the
// slices are seen) but reads payload bytes only for SEI NALs and metadata OBUs.
fun readFrameMetadataUnits(reader: ByteReader, codec: VideoCodec, nalLengthSize: Int?, sampleOffset: Long, sampleSize: Long): List<MetadataUnit> {
    val end = minOf(sampleOffset + sampleSize, reader.length)
    return when (codec) {
        VideoCodec.AV1 -> readAv1MetadataUnits(reader, sampleOffset, end)
        VideoCodec.H264, VideoCodec.HEVC -> readNalMetadataUnits(reader, codec, nalLengthSize ?: return emptyList(), sampleOffset, end)
        VideoCodec.VP9, VideoCodec.APV -> emptyList()
    }
}

private fun readNalMetadataUnits(reader: ByteReader, codec: VideoCodec, lengthSize: Int, start: Long, end: Long): List<MetadataUnit> {
    val units = mutableListOf<MetadataUnit>()
    var pos = start
    while (pos + lengthSize <= end) {
        val nalLength = when (lengthSize) {
            1 -> reader.readUInt8(pos).toLong()
            2 -> reader.readUInt16(pos).toLong()
            4 -> reader.readUInt32(pos)
            else -> return units
        }
        val nalStart = pos + lengthSize
        if (nalLength <= 0 || nalStart + nalLength > end) {
            units += MetadataUnit(
                MetadataSource.SAMPLE_STRUCTURE, -1, "malformed NAL length", pos, end - pos, 0,
                DecodedMetadata.Malformed("NAL length $nalLength at offset $pos runs past the sample end"),
            )
            break
        }
        val headerByte = reader.readUInt8(nalStart)
        if (codec == VideoCodec.H264) {
            if (headerByte and 0x1F == H264_NAL_SEI) {
                units += seiUnits(reader, codec, MetadataSource.H264_SEI, nalStart, nalLength, headerSize = 1)
            }
        } else {
            when (val nalType = (headerByte shr 1) and 0x3F) {
                HEVC_NAL_PREFIX_SEI -> units += seiUnits(reader, codec, MetadataSource.HEVC_PREFIX_SEI, nalStart, nalLength, headerSize = 2)
                HEVC_NAL_SUFFIX_SEI -> units += seiUnits(reader, codec, MetadataSource.HEVC_SUFFIX_SEI, nalStart, nalLength, headerSize = 2)
                62, 63 -> units += MetadataUnit(
                    MetadataSource.HEVC_UNSPECIFIED_NAL, nalType,
                    if (nalType == 62) "Dolby Vision RPU (NAL 62, not parsed)" else "Dolby Vision EL (NAL 63, not parsed)",
                    nalStart, nalLength, nalLength.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), DecodedMetadata.PresenceOnly,
                )
            }
        }
        pos = nalStart + nalLength
    }
    return units
}

private fun seiUnits(reader: ByteReader, codec: VideoCodec, source: MetadataSource, nalStart: Long, nalLength: Long, headerSize: Int): List<MetadataUnit> {
    val nal = reader.readBytes(nalStart, minOf(nalLength, SEI_NAL_READ_CAP.toLong()).toInt())
    return splitSeiMessages(nal, headerSize).map { m ->
        MetadataUnit(source, m.payloadType, seiPayloadTypeName(m.payloadType), nalStart, nalLength, m.payloadSize, decodeSeiPayload(codec, m))
    }
}

private fun readAv1MetadataUnits(reader: ByteReader, start: Long, end: Long): List<MetadataUnit> {
    val units = mutableListOf<MetadataUnit>()
    var pos = start
    while (pos < end) {
        val header = parseObuHeader(reader, pos)
        var payloadStart = pos + header.headerSize
        val payloadSize: Long
        if (header.hasSizeField) {
            if (payloadStart >= end) break
            val (size, next) = readLeb128(reader, payloadStart)
            payloadSize = size
            payloadStart = next
        } else {
            payloadSize = end - payloadStart
        }
        if (payloadSize < 0 || payloadStart + payloadSize > end) {
            units += MetadataUnit(
                MetadataSource.SAMPLE_STRUCTURE, header.obuType, "malformed OBU size", pos, end - pos, 0,
                DecodedMetadata.Malformed("obu_size $payloadSize at offset $pos runs past the sample end"),
            )
            break
        }
        if (header.obuType == AV1_OBU_METADATA) {
            val bytes = reader.readBytes(payloadStart, minOf(payloadSize, METADATA_OBU_READ_CAP.toLong()).toInt())
            val (type, decoded) = decodeAv1MetadataPayload(bytes)
            units += MetadataUnit(
                MetadataSource.AV1_METADATA_OBU, type.toInt(), av1MetadataTypeName(type),
                pos, payloadStart + payloadSize - pos, payloadSize.toInt(), decoded,
            )
        }
        pos = payloadStart + payloadSize
    }
    return units
}
