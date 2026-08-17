package com.multiviewer.parser

object DolbyVisionConfigDecoder : BoxDecoder {
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
        if (payloadEnd - payloadStart < 5) {
            w.add("Box too short for Dolby Vision configuration record")
            return BoxNode(type, offset, headerSize, size, warnings = w)
        }

        val major = reader.readUInt8(payloadStart)
        val minor = reader.readUInt8(payloadStart + 1)
        val bits16 = reader.readUInt16(payloadStart + 2)

        val profile = (bits16 shr 9) and 0x7F
        val level = (bits16 shr 3) and 0x3F
        val rpuPresent = (bits16 shr 2) and 0x1
        val elPresent = (bits16 shr 1) and 0x1
        val blPresent = bits16 and 0x1

        val byte4 = reader.readUInt8(payloadStart + 4)
        val compatId = (byte4 shr 4) and 0x0F

        val compatDesc = when (compatId) {
            0 -> "0 (None)"
            1 -> "1 (HDR10)"
            2 -> "2 (SDR)"
            4 -> "4 (HLG)"
            else -> "$compatId"
        }

        val fields = listOf(
            BoxField("dv_version_major", major.toString(), payloadStart, 1),
            BoxField("dv_version_minor", minor.toString(), payloadStart + 1, 1),
            BoxField("dv_profile", profile.toString(), payloadStart + 2, 2),
            BoxField("dv_level", level.toString(), payloadStart + 2, 2),
            BoxField("rpu_present_flag", rpuPresent.toString(), payloadStart + 2, 2),
            BoxField("el_present_flag", elPresent.toString(), payloadStart + 2, 2),
            BoxField("bl_present_flag", blPresent.toString(), payloadStart + 2, 2),
            BoxField("dv_bl_signal_compatibility_id", compatDesc, payloadStart + 4, 1),
        )

        return BoxNode(
            type = type,
            offset = offset,
            headerSize = headerSize,
            size = size,
            fields = fields,
            warnings = w,
            summary = "Dolby Vision Profile $profile.$level (compat=$compatDesc)",
        )
    }
}
