package com.multiviewer.parser

object DopsBoxDecoder : BoxDecoder {
    private const val DOPS_FIXED_RECORD_SIZE = 11

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
        val payloadLength = payloadEnd - payloadStart
        if (payloadLength < DOPS_FIXED_RECORD_SIZE) {
            w.add("Box too short for dOps record: $payloadLength byte(s), expected at least $DOPS_FIXED_RECORD_SIZE")
            return BoxNode(type, offset, headerSize, size, warnings = w)
        }

        val version = reader.readUInt8(payloadStart)
        val channelCount = reader.readUInt8(payloadStart + 1)
        val preSkip = reader.readUInt16(payloadStart + 2)
        val inputSampleRate = reader.readUInt32(payloadStart + 4)
        val rawGain = reader.readUInt16(payloadStart + 8)
        val outputGain = rawGain.toShort().toInt()
        val mappingFamily = reader.readUInt8(payloadStart + 10)

        val fields = mutableListOf(
            BoxField("version", version.toString(), payloadStart, 1L),
            BoxField("output_channel_count", channelCount.toString(), payloadStart + 1, 1L),
            BoxField("pre_skip", preSkip.toString(), payloadStart + 2, 2L),
            BoxField("input_sample_rate", inputSampleRate.toString(), payloadStart + 4, 4L),
            BoxField("output_gain", outputGain.toString(), payloadStart + 8, 2L),
            BoxField("channel_mapping_family", mappingFamily.toString(), payloadStart + 10, 1L),
        )

        if (mappingFamily != 0) {
            val mappingExtra = 2 + channelCount
            if (payloadLength < DOPS_FIXED_RECORD_SIZE + mappingExtra) {
                w.add("Box too short for dOps channel mapping table: $payloadLength byte(s), expected ${DOPS_FIXED_RECORD_SIZE + mappingExtra}")
            } else {
                val streamCount = reader.readUInt8(payloadStart + 11)
                val coupledCount = reader.readUInt8(payloadStart + 12)
                fields.add(BoxField("stream_count", streamCount.toString(), payloadStart + 11, 1L))
                fields.add(BoxField("coupled_count", coupledCount.toString(), payloadStart + 12, 1L))
                val mappingBytes = (0 until channelCount).map { i ->
                    reader.readUInt8(payloadStart + 13 + i).toString()
                }.joinToString(", ")
                fields.add(BoxField("channel_mapping", "[$mappingBytes]", payloadStart + 13, channelCount.toLong()))
            }
        }

        return BoxNode(
            type = type,
            offset = offset,
            headerSize = headerSize,
            size = size,
            fields = fields,
            warnings = w,
            summary = "$channelCount ch, ${inputSampleRate}Hz, family $mappingFamily",
        )
    }
}
