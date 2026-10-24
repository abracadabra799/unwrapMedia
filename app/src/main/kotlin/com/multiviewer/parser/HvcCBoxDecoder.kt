package com.multiviewer.parser

object HvcCBoxDecoder : BoxDecoder {
    private const val FIXED_HEADER_SIZE = 23
    private const val NAL_TYPE_VPS = 32
    private const val NAL_TYPE_SPS = 33
    private const val NAL_TYPE_PPS = 34

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
            w.add("Invalid hvcC byte range / hvcC 바이트 범위 오류")
            return BoxNode(type, offset, headerSize, size, warnings = w)
        }
        val payloadStart = offset + headerSize
        val payloadEnd = offset + size
        if (payloadEnd - payloadStart < FIXED_HEADER_SIZE) {
            w.add("Box too short for hvcC fixed header")
            return BoxNode(type, offset, headerSize, size, warnings = w)
        }
        val configVersion = reader.readUInt8(payloadStart)
        val generalProfileIdc = reader.readUInt8(payloadStart + 1) and 0x1F
        val generalLevelIdc = reader.readUInt8(payloadStart + 12)
        val lengthSize = (reader.readUInt8(payloadStart + 21) and 0x03) + 1
        val numArrays = reader.readUInt8(payloadStart + 22)

        var pos = payloadStart + FIXED_HEADER_SIZE
        var numVps = 0
        var numSps = 0
        var numPps = 0
        var arraysWalked = 0
        while (arraysWalked < numArrays && pos + 3 <= payloadEnd) {
            val nalType = reader.readUInt8(pos) and 0x3F
            val numNalus = reader.readUInt16(pos + 1)
            pos += 3
            var nalusWalked = 0
            while (nalusWalked < numNalus && pos + 2 <= payloadEnd) {
                val nalLength = reader.readUInt16(pos)
                if (pos + 2 + nalLength > payloadEnd) break
                pos += 2 + nalLength
                nalusWalked++
            }
            when (nalType) {
                NAL_TYPE_VPS -> numVps += nalusWalked
                NAL_TYPE_SPS -> numSps += nalusWalked
                NAL_TYPE_PPS -> numPps += nalusWalked
            }
            arraysWalked++
            if (nalusWalked < numNalus) {
                w.add("Truncated hvcC NAL array / hvcC NAL 배열 잘림: $nalusWalked/$numNalus")
                break
            }
        }
        if (arraysWalked < numArrays) {
            w.add("Declared $numArrays NAL arrays but only found $arraysWalked")
        }

        val fields = mutableListOf(
            BoxField("configuration_version", configVersion.toString(), payloadStart, 1),
            BoxField("general_profile_idc", generalProfileIdc.toString(), payloadStart + 1, 1),
            BoxField("general_level_idc", generalLevelIdc.toString(), payloadStart + 12, 1),
            BoxField("length_size", lengthSize.toString(), payloadStart + 21, 1),
            BoxField("num_arrays", numArrays.toString(), payloadStart + 22, 1),
            BoxField("num_vps", numVps.toString(), payloadStart + FIXED_HEADER_SIZE, 0),
            BoxField("num_sps", numSps.toString(), payloadStart + FIXED_HEADER_SIZE, 0),
            BoxField("num_pps", numPps.toString(), payloadStart + FIXED_HEADER_SIZE, 0),
        )
        val profileByte = reader.readUInt8(payloadStart + 1)
        val temporalByte = reader.readUInt8(payloadStart + 21)
        val constraints = (0 until 6).fold(0L) { value, i -> (value shl 8) or reader.readUInt8(payloadStart + 6 + i).toLong() }
        fields += listOf(
            BoxField("general_profile_space", (profileByte ushr 6).toString(), payloadStart + 1, 1),
            BoxField("general_tier_flag", ((profileByte ushr 5) and 1).toString(), payloadStart + 1, 1),
            BoxField("general_profile_compatibility_flags", "0x${reader.readUInt32(payloadStart + 2).toString(16).padStart(8, '0')}", payloadStart + 2, 4),
            BoxField("general_constraint_indicator_flags", "0x${constraints.toString(16).padStart(12, '0')}", payloadStart + 6, 6),
            BoxField("min_spatial_segmentation_idc", (reader.readUInt16(payloadStart + 13) and 0xfff).toString(), payloadStart + 13, 2),
            BoxField("parallelism_type", (reader.readUInt8(payloadStart + 15) and 3).toString(), payloadStart + 15, 1),
            BoxField("chroma_format_idc", (reader.readUInt8(payloadStart + 16) and 3).toString(), payloadStart + 16, 1),
            BoxField("bit_depth_luma_minus8", (reader.readUInt8(payloadStart + 17) and 7).toString(), payloadStart + 17, 1),
            BoxField("bit_depth_chroma_minus8", (reader.readUInt8(payloadStart + 18) and 7).toString(), payloadStart + 18, 1),
            BoxField("bit_depth_luma", (8 + (reader.readUInt8(payloadStart + 17) and 7)).toString(), payloadStart + 17, 1),
            BoxField("bit_depth_chroma", (8 + (reader.readUInt8(payloadStart + 18) and 7)).toString(), payloadStart + 18, 1),
            BoxField("avg_frame_rate", reader.readUInt16(payloadStart + 19).toString(), payloadStart + 19, 2),
            BoxField("constant_frame_rate", (temporalByte ushr 6).toString(), payloadStart + 21, 1),
            BoxField("num_temporal_layers", ((temporalByte ushr 3) and 7).toString(), payloadStart + 21, 1),
            BoxField("temporal_id_nested", (temporalByte and 4 != 0).toString(), payloadStart + 21, 1),
        )
        return BoxNode(
            type = type, offset = offset, headerSize = headerSize, size = size,
            fields = fields, warnings = w,
            summary = "profile=$generalProfileIdc, level=$generalLevelIdc, $numSps SPS, $numPps PPS",
        )
    }
}
