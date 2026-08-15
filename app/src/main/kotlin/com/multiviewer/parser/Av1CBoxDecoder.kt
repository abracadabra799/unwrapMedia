package com.multiviewer.parser

object Av1CBoxDecoder : BoxDecoder {
    private const val FIXED_HEADER_SIZE = 4

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
            w.add("Box too short for av1C fixed header")
            return BoxNode(type, offset, headerSize, size, warnings = w)
        }
        val byte0 = reader.readUInt8(payloadStart)
        val marker = (byte0 shr 7) and 0x01
        val version = byte0 and 0x7F
        val byte1 = reader.readUInt8(payloadStart + 1)
        val seqProfile = (byte1 shr 5) and 0x07
        val seqLevelIdx0 = byte1 and 0x1F
        val byte2 = reader.readUInt8(payloadStart + 2)
        val seqTier0 = (byte2 shr 7) and 0x01
        val highBitdepth = (byte2 shr 6) and 0x01
        val twelveBit = (byte2 shr 5) and 0x01
        val monochrome = (byte2 shr 4) and 0x01
        val chromaSubsamplingX = (byte2 shr 3) and 0x01
        val chromaSubsamplingY = (byte2 shr 2) and 0x01
        val chromaSamplePosition = byte2 and 0x03
        val byte3 = reader.readUInt8(payloadStart + 3)
        val initialPresentationDelayPresent = (byte3 shr 4) and 0x01

        val fields = listOf(
            BoxField("marker", marker.toString(), payloadStart, 1),
            BoxField("version", version.toString(), payloadStart, 1),
            BoxField("seq_profile", seqProfile.toString(), payloadStart + 1, 1),
            BoxField("seq_level_idx_0", seqLevelIdx0.toString(), payloadStart + 1, 1),
            BoxField("seq_tier_0", seqTier0.toString(), payloadStart + 2, 1),
            BoxField("high_bitdepth", highBitdepth.toString(), payloadStart + 2, 1),
            BoxField("twelve_bit", twelveBit.toString(), payloadStart + 2, 1),
            BoxField("monochrome", monochrome.toString(), payloadStart + 2, 1),
            BoxField("chroma_subsampling_x", chromaSubsamplingX.toString(), payloadStart + 2, 1),
            BoxField("chroma_subsampling_y", chromaSubsamplingY.toString(), payloadStart + 2, 1),
            BoxField("chroma_sample_position", chromaSamplePosition.toString(), payloadStart + 2, 1),
            BoxField("initial_presentation_delay_present", initialPresentationDelayPresent.toString(), payloadStart + 3, 1),
        )
        return BoxNode(
            type = type, offset = offset, headerSize = headerSize, size = size,
            fields = fields, warnings = w,
            summary = "profile=$seqProfile, level=$seqLevelIdx0, ${chromaSubsamplingX}:${chromaSubsamplingY} chroma",
        )
    }
}
