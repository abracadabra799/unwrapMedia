package com.multiviewer.parser

private class FragmentFieldTruncated : RuntimeException()

/** Fragment metadata only; this does not build a fragmented playback sample index. */
object FragmentBoxDetailsDecoder : BoxDecoder {
    override fun decode(reader: ByteReader, type: String, offset: Long, headerSize: Int,
                        size: Long, warnings: List<String>): BoxNode {
        val w = warnings.toMutableList()
        val fields = mutableListOf<BoxField>()
        fun node(table: TableData? = null) = BoxNode(type, offset, headerSize, size, fields = fields.toList(), warnings = w.toList(), table = table)
        if (offset < 0 || headerSize < 0 || size < headerSize || offset > reader.length || size > reader.length - offset || size - headerSize < 4) {
            w += "Truncated fragment header / 분할 박스 헤더 잘림"
            return node()
        }
        val start = offset + headerSize
        val end = offset + size
        val version = reader.readUInt8(start)
        val flags = (reader.readUInt32(start) and 0xffffff).toInt()
        fields += BoxField("version", version.toString(), start, 1)
        fields += BoxField("flags", "0x${flags.toString(16).padStart(6, '0')}", start + 1, 3)
        if (version !in (if (type == "trun" || type == "tfdt") 0..1 else 0..0)) {
            w += "Unsupported fragment version / 미지원 분할 박스 버전: $version"
            return node()
        }
        val allowedFlags = when (type) { "tfhd" -> 0x03003b; "trun" -> 0x000f05; else -> 0 }
        if (flags and allowedFlags.inv() != 0) {
            w += "Unsupported fragment flags / 미지원 분할 박스 플래그"
            return node()
        }
        var pos = start + 4
        fun number(name: String, width: Int = 4, signed: Boolean = false): Long {
            if (end - pos < width) throw FragmentFieldTruncated()
            val value = if (width == 8) reader.readUInt64(pos) else reader.readUInt32(pos)
            val text = when {
                width == 8 -> value.toULong().toString()
                signed -> value.toInt().toString()
                else -> value.toString()
            }
            fields += BoxField(name, text, pos, width.toLong())
            pos += width
            return value
        }
        try {
            when (type) {
                "mfhd" -> number("sequence_number")
                "trex" -> {
                    number("track_ID")
                    number("default_sample_description_index")
                    number("default_sample_duration")
                    number("default_sample_size")
                    number("default_sample_flags")
                }
                "tfhd" -> {
                    number("track_ID")
                    if (flags and 1 != 0) number("base_data_offset", 8)
                    if (flags and 2 != 0) number("sample_description_index")
                    if (flags and 8 != 0) number("default_sample_duration")
                    if (flags and 16 != 0) number("default_sample_size")
                    if (flags and 32 != 0) number("default_sample_flags")
                    fields += BoxField("duration_is_empty", (flags and 0x010000 != 0).toString(), start + 1, 3)
                    fields += BoxField("default_base_is_moof", (flags and 0x020000 != 0).toString(), start + 1, 3)
                }
                "tfdt" -> number("baseMediaDecodeTime", if (version == 1) 8 else 4)
                "trun" -> {
                    val count = number("sample_count")
                    if (flags and 1 != 0) number("data_offset", signed = true)
                    if (flags and 4 != 0) number("first_sample_flags")
                    if (flags and 4 != 0 && flags and 0x400 != 0) w += "Conflicting sample flags / 샘플 플래그 중복"
                    val columns = mutableListOf<String>()
                    if (flags and 0x100 != 0) columns += "sample_duration"
                    if (flags and 0x200 != 0) columns += "sample_size"
                    if (flags and 0x400 != 0) columns += "sample_flags"
                    if (flags and 0x800 != 0) columns += "sample_composition_time_offset"
                    if (columns.isNotEmpty()) {
                        val width = columns.size * 4
                        val actual = minOf(count, (end - pos) / width)
                        if (actual < count) w += "Truncated track run samples / 트랙 실행 샘플 잘림"
                        else if (end - pos != count * width) w += "Extra track run bytes / 트랙 실행 잔여 바이트"
                        val encoding = if (version == 1 && flags and 0x800 != 0) TableRowEncoding.SIGNED_LAST_32 else TableRowEncoding.UNSIGNED
                        return node(TableData(columns, List(columns.size) { 4 }, pos, actual, encoding))
                    }
                }
            }
            if (pos != end) w += "Extra fragment bytes / 분할 박스 잔여 바이트"
        } catch (_: FragmentFieldTruncated) {
            w += "Truncated fragment field / 분할 박스 필드 잘림"
        }
        return node()
    }
}
