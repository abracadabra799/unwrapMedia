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
        if (offset < 0 || headerSize < 0 || size < headerSize || offset > reader.length || size > reader.length - offset) {
            w.add("Invalid av1C byte range / av1C 바이트 범위 오류")
            return BoxNode(type, offset, headerSize, size, warnings = w)
        }
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

        val fields = mutableListOf(
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
        if (marker != 1 || version != 1) {
            w.add("Unsupported AV1 configuration marker/version / 미지원 AV1 설정 마커 또는 버전")
            return BoxNode(type, offset, headerSize, size, fields = fields, warnings = w, interpretation = ValueInterpretation.UNPARSABLE)
        }
        val depth = if (highBitdepth == 0) 8 else if (seqProfile == 2 && twelveBit == 1) 12 else 10
        fields += BoxField("bit_depth", depth.toString(), payloadStart + 2, 1)
        fields += BoxField("reserved_3bits", (byte3 ushr 5).toString(), payloadStart + 3, 1)
        if (initialPresentationDelayPresent == 1) {
            fields += BoxField("initial_presentation_delay_minus_one", (byte3 and 15).toString(), payloadStart + 3, 1)
            fields += BoxField("initial_presentation_delay_samples", (1 + (byte3 and 15)).toString(), payloadStart + 3, 1)
        } else fields += BoxField("reserved_4bits", (byte3 and 15).toString(), payloadStart + 3, 1)
        if (byte3 and 0xe0 != 0 || initialPresentationDelayPresent == 0 && byte3 and 15 != 0) {
            w.add("Nonzero AV1 configuration reserved bits / AV1 설정 예약 비트 오류")
        }
        val obus = decodeAv1ConfigObus(reader, payloadStart + 4, payloadEnd)
        fields += BoxField("config_obu_bytes", (payloadEnd - payloadStart - 4).toString(), payloadStart + 4, payloadEnd - payloadStart - 4)
        if (obus.next < payloadEnd) fields += BoxField("unparsed_config_obu_bytes", (payloadEnd - obus.next).toString(), obus.next, payloadEnd - obus.next, ValueInterpretation.UNINTERPRETED)
        w += obus.warnings
        return BoxNode(
            type = type, offset = offset, headerSize = headerSize, size = size,
            children = obus.nodes, fields = fields, warnings = w,
            interpretation = if (obus.malformed) ValueInterpretation.UNPARSABLE else ValueInterpretation.PARSED,
            summary = "profile=$seqProfile, level=$seqLevelIdx0, ${chromaSubsamplingX}:${chromaSubsamplingY} chroma",
        )
    }
}
