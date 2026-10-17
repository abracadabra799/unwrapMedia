package com.multiviewer.parser

object VpcCBoxDecoder : BoxDecoder {
    private const val FULL_BOX_HEADER_SIZE = 4
    private const val VPCC_FIXED_RECORD_SIZE = 8

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
        if (payloadEnd - payloadStart < FULL_BOX_HEADER_SIZE + VPCC_FIXED_RECORD_SIZE) {
            w.add("Box too short for vpcC fixed header")
            return BoxNode(type, offset, headerSize, size, warnings = w)
        }
        val version = reader.readUInt8(payloadStart)
        val flags = (reader.readUInt8(payloadStart + 1) shl 16) or
            (reader.readUInt8(payloadStart + 2) shl 8) or
            reader.readUInt8(payloadStart + 3)

        val recordStart = payloadStart + FULL_BOX_HEADER_SIZE
        val profile = reader.readUInt8(recordStart)
        val level = reader.readUInt8(recordStart + 1)
        val byte2 = reader.readUInt8(recordStart + 2)
        val bitDepth = (byte2 shr 4) and 0x0F
        val chromaSubsampling = (byte2 shr 1) and 0x07
        val videoFullRangeFlag = byte2 and 0x01
        val colourPrimaries = reader.readUInt8(recordStart + 3)
        val transferCharacteristics = reader.readUInt8(recordStart + 4)
        val matrixCoefficients = reader.readUInt8(recordStart + 5)
        val codecInitDataSize = (reader.readUInt8(recordStart + 6) shl 8) or reader.readUInt8(recordStart + 7)

        val fields = listOf(
            BoxField("version", version.toString(), payloadStart, 1),
            BoxField("flags", flags.toString(), payloadStart + 1, 3),
            BoxField("profile", profile.toString(), recordStart, 1),
            BoxField("level", level.toString(), recordStart + 1, 1),
            BoxField("bit_depth", bitDepth.toString(), recordStart + 2, 1),
            BoxField("chroma_subsampling", chromaSubsampling.toString(), recordStart + 2, 1),
            BoxField("video_full_range_flag", videoFullRangeFlag.toString(), recordStart + 2, 1),
            BoxField("colour_primaries", colourPrimaries.toString(), recordStart + 3, 1),
            BoxField("transfer_characteristics", transferCharacteristics.toString(), recordStart + 4, 1),
            BoxField("matrix_coefficients", matrixCoefficients.toString(), recordStart + 5, 1),
            BoxField("codec_initialization_data_size", codecInitDataSize.toString(), recordStart + 6, 2),
        )
        return BoxNode(
            type = type, offset = offset, headerSize = headerSize, size = size,
            fields = fields, warnings = w,
            summary = "profile=$profile, level=$level, ${bitDepth}-bit, chroma=$chromaSubsampling",
        )
    }
}
