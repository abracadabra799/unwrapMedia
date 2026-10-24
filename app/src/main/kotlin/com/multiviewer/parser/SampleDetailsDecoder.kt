package com.multiviewer.parser

/** Lazy sample dependencies and bounded sample group descriptions. */
object SampleDetailsDecoder : BoxDecoder {
    override fun decode(reader: ByteReader, type: String, offset: Long, headerSize: Int,
                        size: Long, warnings: List<String>): BoxNode {
        val fields = mutableListOf<BoxField>()
        val children = mutableListOf<BoxNode>()
        val w = warnings.toMutableList()
        fun node(table: TableData? = null) = BoxNode(type, offset, headerSize, size,
            fields = fields.toList(), children = children.toList(), warnings = w.toList(), table = table)
        if (offset < 0 || headerSize < 0 || size < headerSize || offset > reader.length ||
            size > reader.length - offset || size - headerSize < 4) {
            w += "Truncated box header / 박스 헤더 잘림"
            return node()
        }
        val start = offset + headerSize
        val end = offset + size
        val version = reader.readUInt8(start)
        fields += BoxField("version", version.toString(), start, 1)
        fields += BoxField("flags", "0x${(reader.readUInt32(start) and 0xffffff).toString(16).padStart(6, '0')}", start + 1, 3)
        if (type == "sdtp") {
            if (version != 0) {
                w += "Unsupported sdtp version / 미지원 sdtp 버전: $version"
                return node()
            }
            return node(TableData(listOf("is_leading", "sample_depends_on", "sample_is_depended_on", "sample_has_redundancy"),
                listOf(1), start + 4, end - start - 4, TableRowEncoding.SDTP_BITS))
        }
        if (version !in 0..2) {
            w += "Unsupported sgpd version / 미지원 sgpd 버전: $version"
            return node()
        }
        val required = when (version) { 0 -> 12; 1 -> 16; else -> 20 }
        if (end - start < required) {
            w += "Truncated sample group header / 샘플 그룹 헤더 잘림"
            return node()
        }
        val group = reader.readFourCC(start + 4)
        fields += BoxField("grouping_type", group, start + 4, 4)
        val isRoll = group == "roll" || group == "prol"
        var pos = start + 8
        var defaultLength = if (isRoll) 2L else 0L
        if (version >= 1) {
            defaultLength = reader.readUInt32(pos)
            fields += BoxField("default_length", defaultLength.toString(), pos, 4)
            pos += 4
        }
        if (version >= 2) {
            fields += BoxField("default_sample_description_index", reader.readUInt32(pos).toString(), pos, 4)
            pos += 4
        }
        val count = reader.readUInt32(pos)
        fields += BoxField("entry_count", count.toString(), pos, 4)
        pos += 4
        if (version == 0 && !isRoll) {
            fields += BoxField("parsing_status", "Unknown v0 group layout / 미지원 v0 그룹 구조", pos, 0)
            fields += BoxField("unparsed_payload_bytes", (end - pos).toString(), pos, end - pos)
            return node()
        }
        if (isRoll && defaultLength == 2L) {
            val actual = minOf(count, (end - pos) / 2)
            if (actual < count) w += "Truncated roll descriptions / 복구 거리 항목 잘림"
            else if (count * 2 != end - pos) w += "Extra group description bytes / 그룹 설명 잔여 바이트"
            return node(TableData(listOf("roll_distance"), listOf(2), pos, actual, TableRowEncoding.SIGNED_16))
        }
        var parsed = 0L
        while (parsed < count && parsed < 4096) {
            var length = defaultLength
            if (version >= 1 && defaultLength == 0L) {
                if (end - pos < 4) { w += "Truncated description length / 설명 길이 잘림"; break }
                length = reader.readUInt32(pos)
                pos += 4
            }
            if (length > end - pos) { w += "Truncated description / 그룹 설명 잘림"; break }
            val itemFields = mutableListOf(BoxField("description_length", length.toString(), pos, 0))
            if (isRoll && length == 2L) {
                itemFields += BoxField("roll_distance", reader.readUInt16(pos).toShort().toString(), pos, 2)
            } else {
                itemFields += BoxField("parsing_status", "Raw description / 원시 그룹 설명", pos, 0)
                itemFields += BoxField("payload_bytes", length.toString(), pos, length)
                if (isRoll) w += "Invalid roll description length / 복구 거리 길이 오류: $length"
            }
            children += BoxNode("$group[${parsed + 1}]", pos, 0, length, fields = itemFields)
            pos += length
            parsed++
        }
        if (parsed == count && pos != end) w += "Extra group description bytes / 그룹 설명 잔여 바이트"
        if (parsed < count && parsed == 4096L) {
            fields += BoxField("parsing_status", "Description display limited to 4096 / 그룹 설명 표시 4096개 제한", pos, 0)
            fields += BoxField("unparsed_payload_bytes", (end - pos).toString(), pos, end - pos)
        }
        return node()
    }
}
