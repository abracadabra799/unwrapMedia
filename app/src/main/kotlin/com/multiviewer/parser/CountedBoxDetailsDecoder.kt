package com.multiviewer.parser

/** Counted entry containers and lazy sample-to-group mappings. */
object CountedBoxDetailsDecoder : BoxDecoder {
    override fun decode(
        reader: ByteReader, type: String, offset: Long, headerSize: Int,
        size: Long, warnings: List<String>,
    ): BoxNode {
        val fields = mutableListOf<BoxField>()
        val w = warnings.toMutableList()
        fun result(children: List<BoxNode> = emptyList(), table: TableData? = null) =
            BoxNode(type, offset, headerSize, size, children = children, fields = fields.toList(), warnings = w.toList(), table = table)
        if (offset < 0 || headerSize < 0 || size < headerSize || offset > reader.length || size > reader.length - offset || size - headerSize < 8) {
            w += "Truncated box header / 박스 헤더 잘림: $type"
            return result()
        }
        val start = offset + headerSize
        val end = offset + size
        val version = reader.readUInt8(start)
        fields += BoxField("version", version.toString(), start, 1)
        fields += BoxField("flags", "0x${(reader.readUInt32(start) and 0xffffff).toString(16).padStart(6, '0')}", start + 1, 3)
        if (version !in (if (type == "sbgp") 0..1 else 0..0)) {
            w += "Unsupported version / 미지원 버전: $type v$version"
            return result()
        }
        var position = start + 4
        if (type == "sbgp") {
            val needed = if (version == 1) 12 else 8
            if (end - position < needed) {
                w += "Truncated sample group header / 샘플 그룹 헤더 잘림"
                return result()
            }
            fields += BoxField("grouping_type", reader.readFourCC(position), position, 4)
            position += 4
            if (version == 1) {
                fields += BoxField("grouping_type_parameter", reader.readUInt32(position).toString(), position, 4)
                position += 4
            }
        }
        val count = reader.readUInt32(position)
        fields += BoxField("entry_count", count.toString(), position, 4)
        position += 4
        if (type == "sbgp") {
            val available = (end - position) / 8
            if (count > available) w += "Declared $count entries but only $available fit / 샘플 그룹 항목 잘림"
            if (count <= available && end - position != count * 8) w += "Extra sample group bytes / 샘플 그룹 잔여 바이트"
            return result(table = TableData(listOf("sample_count", "group_description_index"), listOf(4, 4), position, minOf(count, available)))
        }
        val children = parseBoxes(reader, position, end)
        if (children.size.toLong() != count) w += "Declared $count entries, found ${children.size} / 선언된 항목 수 불일치"
        return result(children).copy(summary = pluralize(children.size.toLong(), "entry", "entries"))
    }
}
