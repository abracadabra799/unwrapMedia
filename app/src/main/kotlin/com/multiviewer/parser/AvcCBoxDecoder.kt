package com.multiviewer.parser

object AvcCBoxDecoder : BoxDecoder {
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
        if (payloadEnd - payloadStart < 6) {
            w.add("Box too short for avcC fixed header")
            return BoxNode(type, offset, headerSize, size, warnings = w)
        }
        val configVersion = reader.readUInt8(payloadStart)
        val profileIndication = reader.readUInt8(payloadStart + 1)
        val profileCompatibility = reader.readUInt8(payloadStart + 2)
        val levelIndication = reader.readUInt8(payloadStart + 3)
        val lengthSize = (reader.readUInt8(payloadStart + 4) and 0x03) + 1
        val declaredSps = reader.readUInt8(payloadStart + 5) and 0x1F

        var pos = payloadStart + 6
        var numSps = 0
        while (numSps < declaredSps && pos + 2 <= payloadEnd) {
            val spsLength = reader.readUInt16(pos)
            if (pos + 2 + spsLength > payloadEnd) break
            pos += 2 + spsLength
            numSps++
        }
        if (numSps < declaredSps) {
            w.add("Declared $declaredSps SPS entries but only found $numSps")
        }

        var numPps = 0
        var declaredPps = 0
        val ppsCountOffset = pos
        if (pos < payloadEnd) {
            declaredPps = reader.readUInt8(pos)
            pos += 1
            while (numPps < declaredPps && pos + 2 <= payloadEnd) {
                val ppsLength = reader.readUInt16(pos)
                if (pos + 2 + ppsLength > payloadEnd) break
                pos += 2 + ppsLength
                numPps++
            }
            if (numPps < declaredPps) {
                w.add("Declared $declaredPps PPS entries but only found $numPps")
            }
        }

        val fields = listOf(
            BoxField("configuration_version", configVersion.toString(), payloadStart, 1),
            BoxField("avc_profile_indication", profileIndication.toString(), payloadStart + 1, 1),
            BoxField("profile_compatibility", profileCompatibility.toString(), payloadStart + 2, 1),
            BoxField("avc_level_indication", levelIndication.toString(), payloadStart + 3, 1),
            BoxField("length_size", lengthSize.toString(), payloadStart + 4, 1),
            BoxField("num_sps", numSps.toString(), payloadStart + 5, 1),
            BoxField("num_pps", numPps.toString(), ppsCountOffset, 1),
        )
        return BoxNode(
            type = type, offset = offset, headerSize = headerSize, size = size,
            fields = fields, warnings = w,
            summary = "profile=$profileIndication, level=$levelIndication, $numSps SPS, $numPps PPS",
        )
    }
}
