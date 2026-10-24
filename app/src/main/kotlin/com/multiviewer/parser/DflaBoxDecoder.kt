package com.multiviewer.parser

object DflaBoxDecoder : BoxDecoder {
    private const val FULL_BOX_HEADER_SIZE = 4
    private const val STREAMINFO_HEADER_SIZE = 4
    private const val STREAMINFO_PAYLOAD_SIZE = 34

    override fun decode(
        reader: ByteReader,
        type: String,
        offset: Long,
        headerSize: Int,
        size: Long,
        warnings: List<String>,
    ): BoxNode {
        val w = warnings.toMutableList()
        if (offset < 0 || headerSize < 0 || size < headerSize || offset > reader.length || size > reader.length - offset) {
            w.add("Invalid dfLa byte range / dfLa 바이트 범위 오류")
            return BoxNode(type, offset, headerSize, size, warnings = w)
        }
        val payloadStart = offset + headerSize
        val payloadEnd = offset + size
        val payloadLength = payloadEnd - payloadStart
        if (payloadLength < FULL_BOX_HEADER_SIZE + STREAMINFO_HEADER_SIZE) {
            w.add("Box too short for dfLa FullBox header")
            return BoxNode(type, offset, headerSize, size, warnings = w)
        }

        val version = reader.readUInt8(payloadStart)
        val flags = (reader.readUInt8(payloadStart + 1) shl 16) or
            (reader.readUInt8(payloadStart + 2) shl 8) or
            reader.readUInt8(payloadStart + 3)

        val blockHeaderOffset = payloadStart + FULL_BOX_HEADER_SIZE
        val b0 = reader.readUInt8(blockHeaderOffset)
        val isLast = (b0 and 0x80) != 0
        val blockType = b0 and 0x7F
        val blockLength = (reader.readUInt8(blockHeaderOffset + 1) shl 16) or
            (reader.readUInt8(blockHeaderOffset + 2) shl 8) or
            reader.readUInt8(blockHeaderOffset + 3)

        val fields = mutableListOf(
            BoxField("version", version.toString(), payloadStart, 1L),
            BoxField("flags", flags.toString(), payloadStart + 1, 3L),
            BoxField("block_type", "$blockType (${if (blockType == 0) "STREAMINFO" else "Unknown"})", blockHeaderOffset, 1L),
            BoxField("is_last_block", isLast.toString(), blockHeaderOffset, 1L),
            BoxField("block_length", blockLength.toString(), blockHeaderOffset + 1, 3L),
        )

        if (version != 0 || flags != 0) {
            w.add("Unsupported dfLa version/flags / 미지원 dfLa 버전 또는 플래그")
            return BoxNode(type, offset, headerSize, size, fields = fields, warnings = w, interpretation = ValueInterpretation.UNPARSABLE)
        }
        if (blockType != 0) {
            w.add("First metadata block in dfLa is not STREAMINFO (type=$blockType)")
            return BoxNode(type, offset, headerSize, size, fields = fields, warnings = w, interpretation = ValueInterpretation.UNPARSABLE)
        }

        if (blockLength != STREAMINFO_PAYLOAD_SIZE) {
            w.add("STREAMINFO payload is $blockLength byte(s), expected $STREAMINFO_PAYLOAD_SIZE")
        }

        val streaminfoOffset = blockHeaderOffset + STREAMINFO_HEADER_SIZE
        if (blockLength < STREAMINFO_PAYLOAD_SIZE || blockLength > payloadLength - FULL_BOX_HEADER_SIZE - STREAMINFO_HEADER_SIZE) {
            w.add("Truncated STREAMINFO payload in dfLa")
            return BoxNode(type, offset, headerSize, size, fields = fields, warnings = w, interpretation = ValueInterpretation.UNPARSABLE)
        }

        val minBlockSize = reader.readUInt16(streaminfoOffset)
        val maxBlockSize = reader.readUInt16(streaminfoOffset + 2)
        val minFrameSize = (reader.readUInt8(streaminfoOffset + 4) shl 16) or
            (reader.readUInt8(streaminfoOffset + 5) shl 8) or
            reader.readUInt8(streaminfoOffset + 6)
        val maxFrameSize = (reader.readUInt8(streaminfoOffset + 7) shl 16) or
            (reader.readUInt8(streaminfoOffset + 8) shl 8) or
            reader.readUInt8(streaminfoOffset + 9)

        val qVal = reader.readUInt64(streaminfoOffset + 10)
        val sampleRate = (qVal ushr 44) and 0xFFFFF
        val channels = (((qVal ushr 41) and 0x07) + 1).toInt()
        val bitsPerSample = (((qVal ushr 36) and 0x1F) + 1).toInt()
        val totalSamples = qVal and 0xFFFFFFFFF

        fields.add(BoxField("min_block_size", minBlockSize.toString(), streaminfoOffset, 2L))
        fields.add(BoxField("max_block_size", maxBlockSize.toString(), streaminfoOffset + 2, 2L))
        fields.add(BoxField("min_frame_size", minFrameSize.toString(), streaminfoOffset + 4, 3L))
        fields.add(BoxField("max_frame_size", maxFrameSize.toString(), streaminfoOffset + 7, 3L))
        fields.add(BoxField("sample_rate", sampleRate.toString(), streaminfoOffset + 10, 3L))
        fields.add(BoxField("channels", channels.toString(), streaminfoOffset + 12, 1L))
        fields.add(BoxField("bits_per_sample", bitsPerSample.toString(), streaminfoOffset + 13, 1L))
        fields.add(BoxField("total_samples", totalSamples.toString(), streaminfoOffset + 13, 5L))

        val md5 = reader.readBytes(streaminfoOffset + 18, 16).joinToString("") { "%02x".format(it.toInt() and 255) }
        fields.add(BoxField("md5_signature", md5, streaminfoOffset + 18, 16L))
        return BoxNode(
            type = type,
            offset = offset,
            headerSize = headerSize,
            size = size,
            fields = fields,
            warnings = w,
            interpretation = if (w.isNotEmpty()) ValueInterpretation.UNPARSABLE else ValueInterpretation.PARSED,
            summary = "${channels}ch, ${sampleRate}Hz, ${bitsPerSample}-bit",
        )
    }
}
