package com.multiviewer.parser

object AudioSampleEntryDecoder : BoxDecoder {
    private const val FIXED_HEADER_SIZE = 28
    private fun looksLikeBox(reader: ByteReader, start: Long, end: Long): Boolean {
        if (end - start < 8) return false
        val size = reader.readUInt32(start)
        val type = reader.readFourCC(start + 4)
        return size in 8..(end - start) && type.all { it in ' '..'~' }
    }
    override fun decode(reader: ByteReader, type: String, offset: Long, headerSize: Int, size: Long, warnings: List<String>): BoxNode {
        val w = warnings.toMutableList()
        if (!validQuickTimeRange(reader, offset, headerSize, size, 28))
            return BoxNode(type, offset, headerSize, size, warnings = w + "Truncated audio sample entry / 오디오 샘플 설명 잘림", interpretation = ValueInterpretation.UNPARSABLE)
        val start = offset + headerSize
        val end = offset + size
        val version = reader.readUInt16(start + 8)
        val reference = BoxField("data_reference_index", reader.readUInt16(start + 6).toString(), start + 6, 2)
        if (version > 2 || version == 2 && end - start < 64) {
            return BoxNode(type, offset, headerSize, size,
                fields = listOf(reference, BoxField("qt_sound_description_version", version.toString(), start + 8, 2)),
                warnings = w + "Unsupported/truncated sound description / 미지원 또는 잘린 음성 샘플 설명", interpretation = ValueInterpretation.UNPARSABLE)
        }
        val quickTime = version == 2 || version == 1 && (start + 44 == end || looksLikeBox(reader, start + 44, end))
        var childrenStart = start + FIXED_HEADER_SIZE + if (quickTime && version == 1) 16 else if (quickTime && version == 2) 36 else 0
        var channelOffset = start + 16
        var channelWidth = 2L
        var channels = reader.readUInt16(channelOffset).toLong()
        var sizeOffset = start + 18
        var sizeWidth = 2L
        var sampleSize = reader.readUInt16(sizeOffset).toLong()
        var rateOffset = start + 24
        var rateWidth = 4L
        var rate = reader.readUInt32(rateOffset) / 65536.0
        if (version == 2) {
            rateOffset = start + 32; rateWidth = 8
            rate = Double.fromBits(reader.readUInt64(rateOffset))
            channelOffset = start + 40; channelWidth = 4
            channels = reader.readUInt32(channelOffset)
            val bits = reader.readUInt32(start + 48)
            if (bits != 0L) { sizeOffset = start + 48; sizeWidth = 4; sampleSize = bits }
        }
        val fields = mutableListOf(reference)
        if (quickTime) fields += BoxField("qt_sound_description_version", version.toString(), start + 8, 2)
        fields += listOf(BoxField("channelcount", channels.toString(), channelOffset, channelWidth),
            BoxField("samplesize", sampleSize.toString(), sizeOffset, sizeWidth), BoxField("samplerate", rate.toString(), rateOffset, rateWidth))
        var state = if (w.isEmpty()) ValueInterpretation.PARSED else ValueInterpretation.UNPARSABLE
        if (quickTime) {
            fields += listOf(BoxField("revision_level", reader.readUInt16(start + 10).toString(), start + 10, 2),
                BoxField("vendor", "0x${reader.readUInt32(start + 12).toString(16).padStart(8, '0')}", start + 12, 4),
                BoxField("compression_id", reader.readUInt16(start + 20).toShort().toString(), start + 20, 2),
                BoxField("packet_size", reader.readUInt16(start + 22).toString(), start + 22, 2))
        }
        if (quickTime && version == 1) {
            for ((i, name) in listOf("samples_per_packet", "bytes_per_packet", "bytes_per_frame", "bytes_per_sample").withIndex())
                fields += BoxField(name, reader.readUInt32(start + 28 + i * 4).toString(), start + 28 + i * 4, 4)
        } else if (version == 2) {
            val structureSize = reader.readUInt32(start + 28)
            fields += BoxField("size_of_struct_only", structureSize.toString(), start + 28, 4)
            fields += BoxField("reserved_constant", "0x${reader.readUInt32(start + 44).toString(16).padStart(8, '0')}", start + 44, 4)
            for ((i, name) in listOf("const_bits_per_channel", "format_specific_flags", "const_bytes_per_audio_packet", "const_lpcm_frames_per_audio_packet").withIndex())
                fields += BoxField(name, reader.readUInt32(start + 48 + i * 4).toString(), start + 48 + i * 4, 4)
            if (type == "lpcm") {
                val flags = reader.readUInt32(start + 52)
                for ((bit, name) in listOf("pcm_is_float", "pcm_is_big_endian", "pcm_is_signed_integer", "pcm_is_packed", "pcm_is_aligned_high", "pcm_is_non_interleaved", "pcm_is_non_mixable").withIndex())
                    fields += BoxField(name, (flags and (1L shl bit) != 0L).toString(), start + 52, 4)
            }
            if (structureSize < headerSize + 64 || structureSize > size) {
                w += "Invalid sound structure size / 음성 설명 구조 크기 오류"; state = ValueInterpretation.UNPARSABLE
                return BoxNode(type, offset, headerSize, size, fields = fields, warnings = w, interpretation = state)
            }
            val structureEnd = offset + structureSize
            if (structureEnd > childrenStart) {
                fields += BoxField("unparsed_sound_description_bytes", (structureEnd - childrenStart).toString(), childrenStart, structureEnd - childrenStart, ValueInterpretation.UNINTERPRETED)
                state = quickTimeState(state, ValueInterpretation.UNINTERPRETED)
            }
            childrenStart = structureEnd
        }
        val children = parseBoxes(reader, childrenStart, end)
        children.forEach { state = quickTimeState(state, detailInterpretation(it)) }
        return BoxNode(type, offset, headerSize, size, fields = fields, children = children, warnings = w,
            summary = "${channels}ch, ${"%.0f".format(rate)}Hz", interpretation = state)
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
