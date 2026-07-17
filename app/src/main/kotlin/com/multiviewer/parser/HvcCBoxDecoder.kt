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
        }
        if (arraysWalked < numArrays) {
            w.add("Declared $numArrays NAL arrays but only found $arraysWalked")
        }

        val fields = listOf(
            BoxField("configuration_version", configVersion.toString(), payloadStart, 1),
            BoxField("general_profile_idc", generalProfileIdc.toString(), payloadStart + 1, 1),
            BoxField("general_level_idc", generalLevelIdc.toString(), payloadStart + 12, 1),
            BoxField("length_size", lengthSize.toString(), payloadStart + 21, 1),
            BoxField("num_arrays", numArrays.toString(), payloadStart + 22, 1),
            BoxField("num_vps", numVps.toString(), payloadStart + FIXED_HEADER_SIZE, 0),
            BoxField("num_sps", numSps.toString(), payloadStart + FIXED_HEADER_SIZE, 0),
            BoxField("num_pps", numPps.toString(), payloadStart + FIXED_HEADER_SIZE, 0),
        )
        return BoxNode(
            type = type, offset = offset, headerSize = headerSize, size = size,
            fields = fields, warnings = w,
            summary = "profile=$generalProfileIdc, level=$generalLevelIdc, $numSps SPS, $numPps PPS",
        )
    }
}
