package com.multiviewer.parser

object AudioSampleEntryDecoder : BoxDecoder {
    private const val FIXED_HEADER_SIZE = 28
    private const val QT_V1_EXTRA = 16
    private const val QT_V2_EXTRA = 36

    private fun looksLikeBox(reader: ByteReader, start: Long, end: Long): Boolean {
        if (end - start < 8) return false
        val size = reader.readUInt32(start)
        val type = reader.readFourCC(start + 4)
        return size in 8..(end - start) && type.all { it in ' '..'~' }
    }

    override fun decode(
        reader: ByteReader,
        type: String,
        offset: Long,
        headerSize: Int,
        size: Long,
        warnings: List<String>,
    ): BoxNode {
        val w = warnings.toMutableList()
        val payloadStart = offset + headerSize
        val payloadEnd = offset + size
        if (payloadEnd - payloadStart < FIXED_HEADER_SIZE) {
            w.add("Box too short for AudioSampleEntry fixed header")
            return BoxNode(type, offset, headerSize, size, warnings = w)
        }
        val dataReferenceIndexOffset = payloadStart + 6
        val dataReferenceIndex = reader.readUInt16(dataReferenceIndexOffset)
        val versionOffset = payloadStart + 8
        val version = reader.readUInt16(versionOffset)
        // QuickTime SoundDescription V1 adds 16 bytes and V2 36 bytes before the child atoms. The ISO
        // AudioSampleEntryV1 also has version 1 but no extra bytes, so only take the QuickTime layout when a box
        // header actually starts there.
        val extra = when (version) { 1 -> QT_V1_EXTRA; 2 -> QT_V2_EXTRA; else -> 0 }
        val extensionEnd = payloadStart + FIXED_HEADER_SIZE + extra
        // ...or when the extension ends the entry exactly (e.g. a V1 'ima4' or V2 'lpcm' without child atoms).
        val quickTime = extra > 0 && (extensionEnd == payloadEnd || looksLikeBox(reader, extensionEnd, payloadEnd))
        var channelCountOffset = payloadStart + 16
        var channelCountLength = 2L
        var channelCount = reader.readUInt16(channelCountOffset).toLong()
        var sampleSizeOffset = payloadStart + 18
        var sampleSizeLength = 2L
        var sampleSize = reader.readUInt16(sampleSizeOffset).toLong()
        var sampleRateOffset = payloadStart + 24
        var sampleRateLength = 4L
        var sampleRate = reader.readUInt32(sampleRateOffset) / 65536.0
        if (quickTime && version == 2) {
            // V2: the 16.16 header fields are placeholders; the real rate is a float64 and the channel count a u32.
            sampleRateOffset = payloadStart + FIXED_HEADER_SIZE + 4
            sampleRateLength = 8L
            sampleRate = Double.fromBits(reader.readUInt64(sampleRateOffset))
            channelCountOffset = payloadStart + FIXED_HEADER_SIZE + 12
            channelCountLength = 4L
            channelCount = reader.readUInt32(channelCountOffset)
            // constBitsPerChannel is 0 for compressed formats (AAC); then the header's samplesize is all there is.
            val constBitsOffset = payloadStart + FIXED_HEADER_SIZE + 20
            val constBits = reader.readUInt32(constBitsOffset)
            if (constBits != 0L) {
                sampleSizeOffset = constBitsOffset
                sampleSizeLength = 4L
                sampleSize = constBits
            }
        }
        val fields = listOfNotNull(
            BoxField("data_reference_index", dataReferenceIndex.toString(), dataReferenceIndexOffset, 2),
            if (quickTime) BoxField("qt_sound_description_version", version.toString(), versionOffset, 2) else null,
            BoxField("channelcount", channelCount.toString(), channelCountOffset, channelCountLength),
            BoxField("samplesize", sampleSize.toString(), sampleSizeOffset, sampleSizeLength),
            BoxField("samplerate", sampleRate.toString(), sampleRateOffset, sampleRateLength),
        )
        val children = parseBoxes(reader, payloadStart + FIXED_HEADER_SIZE + (if (quickTime) extra else 0), payloadEnd)
        return BoxNode(
            type = type, offset = offset, headerSize = headerSize, size = size,
            fields = fields, children = children, warnings = w,
            summary = "${channelCount}ch, ${"%.0f".format(sampleRate)}Hz",
        )
    }
}

/**
 * QuickTime 'wave' (siDecompressionParam) atom inside a V1/V2 sound description: holds 'frma', a short format atom
 * named like the sample entry (e.g. a 12-byte 'mp4a'), the codec config ('esds') and a zero terminator. The format
 * atom is not a sample entry, so it is kept as a plain leaf rather than decoded (and warned about) as one.
 */
object QuickTimeWaveBoxDecoder : BoxDecoder {
    override fun decode(
        reader: ByteReader,
        type: String,
        offset: Long,
        headerSize: Int,
        size: Long,
        warnings: List<String>,
    ): BoxNode {
        val children = parseBoxes(reader, offset + headerSize, offset + size).map { child ->
            if (BoxRegistry.decoderFor(child.type) === AudioSampleEntryDecoder && child.size - child.headerSize < 28) {
                BoxNode(child.type, child.offset, child.headerSize, child.size, summary = "QuickTime format atom")
            } else child
        }
        return BoxNode(type, offset, headerSize, size, children = children, warnings = warnings)
    }
}
