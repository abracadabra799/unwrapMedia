package com.multiviewer.parser

object AudioSampleEntryDecoder : BoxDecoder {
    private const val FIXED_HEADER_SIZE = 28

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
        val channelCountOffset = payloadStart + 16
        val channelCount = reader.readUInt16(channelCountOffset)
        val sampleSizeOffset = payloadStart + 18
        val sampleSize = reader.readUInt16(sampleSizeOffset)
        val sampleRateOffset = payloadStart + 24
        val sampleRateRaw = reader.readUInt32(sampleRateOffset)
        val sampleRate = sampleRateRaw / 65536.0
        val fields = listOf(
            BoxField("data_reference_index", dataReferenceIndex.toString(), dataReferenceIndexOffset, 2),
            BoxField("channelcount", channelCount.toString(), channelCountOffset, 2),
            BoxField("samplesize", sampleSize.toString(), sampleSizeOffset, 2),
            BoxField("samplerate", sampleRate.toString(), sampleRateOffset, 4),
        )
        val children = parseBoxes(reader, payloadStart + FIXED_HEADER_SIZE, payloadEnd)
        return BoxNode(
            type = type, offset = offset, headerSize = headerSize, size = size,
            fields = fields, children = children, warnings = w,
            summary = "${channelCount}ch, ${"%.0f".format(sampleRate)}Hz",
        )
    }
}
