package com.multiviewer.parser

object VvcCBoxDecoder : BoxDecoder {
    private const val FULL_BOX_HEADER_SIZE = 4

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
        if (payloadLength < FULL_BOX_HEADER_SIZE + 1) {
            w.add("Box too short for vvcC FullBox header")
            return BoxNode(type, offset, headerSize, size, warnings = w)
        }

        val version = reader.readUInt8(payloadStart)
        val flags = (reader.readUInt8(payloadStart + 1) shl 16) or
            (reader.readUInt8(payloadStart + 2) shl 8) or
            reader.readUInt8(payloadStart + 3)

        val fields = mutableListOf(
            BoxField("version", version.toString(), payloadStart, 1L),
            BoxField("flags", flags.toString(), payloadStart + 1, 3L),
        )

        val recordStart = payloadStart + FULL_BOX_HEADER_SIZE
        val b0 = reader.readUInt8(recordStart)
        val lengthSizeMinusOne = (b0 ushr 1) and 0x03
        val ptlPresent = (b0 and 0x01) != 0

        fields.add(BoxField("length_size_minus_one", "$lengthSizeMinusOne (${lengthSizeMinusOne + 1} bytes)", recordStart, 1L))
        fields.add(BoxField("ptl_present_flag", ptlPresent.toString(), recordStart, 1L))

        var cur = recordStart + 1
        if (ptlPresent) {
            if (payloadEnd - cur < 15) {
                w.add("Truncated VvcDecoderConfigurationRecord PTL record in vvcC")
                return BoxNode(type, offset, headerSize, size, fields = fields, warnings = w)
            }
            val val16 = reader.readUInt16(cur)
            val olsIdx = (val16 ushr 7) and 0x1FF
            val numSublayers = (val16 ushr 4) and 0x07
            val constantFrameRate = (val16 ushr 2) and 0x03
            val chromaFormatIdc = val16 and 0x03
            cur += 2

            val bBitDepth = reader.readUInt8(cur)
            val bitDepthMinus8 = (bBitDepth ushr 5) and 0x07
            cur += 1

            fields.add(BoxField("ols_idx", olsIdx.toString(), cur - 3, 2L))
            fields.add(BoxField("num_sublayers", numSublayers.toString(), cur - 3, 2L))
            fields.add(BoxField("constant_frame_rate", constantFrameRate.toString(), cur - 3, 2L))
            fields.add(BoxField("chroma_format_idc", chromaFormatIdc.toString(), cur - 3, 2L))
            fields.add(BoxField("bit_depth", "${bitDepthMinus8 + 8} bits", cur - 1, 1L))

            // VvcPTLRecord:
            val bConstraint = reader.readUInt8(cur)
            val numBytesConstraintInfo = bConstraint and 0x3F
            cur += 1

            val bProfile = reader.readUInt8(cur)
            val generalProfileIdc = (bProfile ushr 1) and 0x7F
            val generalTierFlag = bProfile and 0x01
            cur += 1

            val generalLevelIdc = reader.readUInt8(cur)
            cur += 1

            fields.add(BoxField("general_profile_idc", generalProfileIdc.toString(), cur - 2, 1L))
            fields.add(BoxField("general_tier_flag", generalTierFlag.toString(), cur - 2, 1L))
            fields.add(BoxField("general_level_idc", generalLevelIdc.toString(), cur - 1, 1L))

            cur += numBytesConstraintInfo
            if (cur < payloadEnd) {
                val numSubProfiles = reader.readUInt8(cur)
                cur += 1 + numSubProfiles * 4
            }

            if (payloadEnd - cur >= 6) {
                val maxPictureWidth = reader.readUInt16(cur)
                val maxPictureHeight = reader.readUInt16(cur + 2)
                val avgFrameRate = reader.readUInt16(cur + 4)
                cur += 6
                fields.add(BoxField("max_picture_width", maxPictureWidth.toString(), cur - 6, 2L))
                fields.add(BoxField("max_picture_height", maxPictureHeight.toString(), cur - 4, 2L))
                fields.add(BoxField("avg_frame_rate", avgFrameRate.toString(), cur - 2, 2L))
            }
        }

        if (cur < payloadEnd) {
            val numOfArrays = reader.readUInt8(cur)
            fields.add(BoxField("num_of_arrays", numOfArrays.toString(), cur, 1L))
        }

        return BoxNode(
            type = type,
            offset = offset,
            headerSize = headerSize,
            size = size,
            fields = fields,
            warnings = w,
        )
    }
}
