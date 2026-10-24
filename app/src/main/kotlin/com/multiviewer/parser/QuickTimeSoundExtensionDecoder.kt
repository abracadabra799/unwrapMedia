package com.multiviewer.parser

object QuickTimeSoundExtensionDecoder : BoxDecoder {
    override fun decode(reader: ByteReader, type: String, offset: Long, headerSize: Int, size: Long, warnings: List<String>): BoxNode {
        val w = warnings.toMutableList()
        val fields = mutableListOf<BoxField>()
        val children = mutableListOf<BoxNode>()
        var state = if (w.isEmpty()) ValueInterpretation.PARSED else ValueInterpretation.UNPARSABLE
        fun node() = BoxNode(type, offset, headerSize, size, fields = fields, children = children, warnings = w, interpretation = state)
        val minimum = when (type) { "chan" -> 16L; "frma" -> 4L; else -> 2L }
        if (!validQuickTimeRange(reader, offset, headerSize, size, minimum)) {
            w += "Truncated sound extension / 음성 설명 확장 잘림"; state = ValueInterpretation.UNPARSABLE; return node()
        }
        val start = offset + headerSize
        val end = offset + size
        var pos = start
        when (type) {
            "frma" -> { fields += BoxField("data_format", reader.readFourCC(pos), pos, 4); pos += 4 }
            "enda" -> {
                val value = reader.readUInt16(pos)
                fields += BoxField("little_endian", value.toString(), pos, 2,
                    if (value in 0..1) ValueInterpretation.PARSED else ValueInterpretation.UNINTERPRETED)
                pos += 2
            }
            "chan" -> {
                val version = reader.readUInt8(pos)
                val flags = reader.readUInt32(pos) and 0xffffff
                val layout = reader.readUInt32(pos + 4)
                val bitmap = reader.readUInt32(pos + 8)
                val count = reader.readUInt32(pos + 12)
                fields += listOf(BoxField("version", version.toString(), pos, 1), BoxField("flags", flags.toString(), pos + 1, 3),
                    BoxField("channel_layout_tag", layout.toString(), pos + 4, 4), BoxField("channel_bitmap", "0x${bitmap.toString(16).padStart(8, '0')}", pos + 8, 4),
                    BoxField("number_channel_descriptions", count.toString(), pos + 12, 4),
                    BoxField("layout_tag_family", (layout ushr 16).toString(), pos + 4, 4),
                    BoxField("layout_tag_channel_count", (layout and 65535).toString(), pos + 4, 4))
                pos += 16
                if (version != 0 || flags != 0L) {
                    w += "Unsupported channel layout header / 미지원 채널 배치 헤더"; state = ValueInterpretation.UNPARSABLE; return node()
                }
                val fit = (end - pos) / 20
                val actual = minOf(count, fit, 256L)
                repeat(actual.toInt()) {
                    val description = pos
                    val values = mutableListOf(BoxField("channel_label", reader.readUInt32(pos).toString(), pos, 4),
                        BoxField("channel_flags", "0x${reader.readUInt32(pos + 4).toString(16).padStart(8, '0')}", pos + 4, 4))
                    repeat(3) { i -> values += BoxField("coordinate[$i]", Float.fromBits(reader.readUInt32(pos + 8 + i * 4).toInt()).toString(), pos + 8 + i * 4, 4) }
                    children += BoxNode("channel_description", description, 0, 20, fields = values)
                    pos += 20
                }
                if (count > fit) { w += "Truncated channel descriptions / 채널 설명 잘림"; state = ValueInterpretation.UNPARSABLE }
                else if (count > actual) { w += "Channel description inspection limit / 채널 설명 검사 한도"; state = quickTimeState(state, ValueInterpretation.UNINTERPRETED) }
            }
        }
        if (pos < end) {
            val remainingState = if (state == ValueInterpretation.UNPARSABLE) state else ValueInterpretation.UNINTERPRETED
            fields += BoxField("unparsed_extension_bytes", (end - pos).toString(), pos, end - pos, remainingState)
            state = quickTimeState(state, remainingState)
        }
        return node()
    }
}
