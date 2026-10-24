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
        if (offset < 0 || headerSize < 0 || size < headerSize || offset > reader.length || size > reader.length - offset) {
            w.add("Invalid avcC byte range / avcC 바이트 범위 오류")
            return BoxNode(type, offset, headerSize, size, warnings = w)
        }
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
        } else {
            w.add("Box too short to contain a PPS count")
        }

        val fields = mutableListOf(
            BoxField("configuration_version", configVersion.toString(), payloadStart, 1),
            BoxField("avc_profile_indication", profileIndication.toString(), payloadStart + 1, 1),
            BoxField("profile_compatibility", profileCompatibility.toString(), payloadStart + 2, 1),
            BoxField("avc_level_indication", levelIndication.toString(), payloadStart + 3, 1),
            BoxField("length_size", lengthSize.toString(), payloadStart + 4, 1),
            BoxField("num_sps", numSps.toString(), payloadStart + 5, 1),
            BoxField("num_pps", numPps.toString(), ppsCountOffset, 1),
        )
        val extendedProfiles = setOf(100, 110, 122, 144, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135)
        if (numSps == declaredSps && numPps == declaredPps && pos < payloadEnd && profileIndication in extendedProfiles) {
            if (payloadEnd - pos < 4) {
                w.add("Truncated AVC configuration extension / AVC 설정 확장 잘림")
            } else {
                val luma = reader.readUInt8(pos + 1) and 7
                val chroma = reader.readUInt8(pos + 2) and 7
                val extCount = reader.readUInt8(pos + 3)
                val countOffset = pos + 3
                fields += listOf(
                    BoxField("chroma_format_idc", (reader.readUInt8(pos) and 3).toString(), pos, 1),
                    BoxField("bit_depth_luma_minus8", luma.toString(), pos + 1, 1),
                    BoxField("bit_depth_chroma_minus8", chroma.toString(), pos + 2, 1),
                    BoxField("bit_depth_luma", (8 + luma).toString(), pos + 1, 1),
                    BoxField("bit_depth_chroma", (8 + chroma).toString(), pos + 2, 1),
                    BoxField("num_sps_ext", extCount.toString(), countOffset, 1),
                )
                pos += 4
                var parsed = 0
                while (parsed < extCount && payloadEnd - pos >= 2) {
                    val length = reader.readUInt16(pos)
                    if (payloadEnd - pos - 2 < length) break
                    pos += 2 + length
                    parsed++
                }
                fields += BoxField("num_sps_ext_parsed", parsed.toString(), countOffset, 1)
                if (parsed < extCount) w.add("Truncated SPS extension array / SPS 확장 배열 잘림")
            }
        }
        return BoxNode(
            type = type, offset = offset, headerSize = headerSize, size = size,
            fields = fields, warnings = w,
            summary = "profile=$profileIndication, level=$levelIndication, $numSps SPS, $numPps PPS",
        )
    }
}
