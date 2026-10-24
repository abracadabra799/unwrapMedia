package com.multiviewer.parser

/** Bounded property inspection; proprietary auxiliary bytes remain uninterpreted. */
object HeifPropertyDetailsDecoder : BoxDecoder {
    override fun decode(reader: ByteReader, type: String, offset: Long, headerSize: Int,
                        size: Long, warnings: List<String>): BoxNode {
        val w = warnings.toMutableList()
        val fields = mutableListOf<BoxField>()
        fun node(summary: String? = null) = BoxNode(type, offset, headerSize, size,
            fields = fields.toList(), warnings = w.toList(), summary = summary,
            interpretation = if (w.isNotEmpty()) ValueInterpretation.UNPARSABLE else ValueInterpretation.PARSED)
        if (offset < 0 || headerSize < 0 || size < headerSize || offset > reader.length ||
            size > reader.length - offset || size - headerSize < 4) {
            w += "Truncated property header / 속성 헤더 잘림"
            return node()
        }
        val start = offset + headerSize
        val end = offset + size
        val version = reader.readUInt8(start)
        val flags = reader.readUInt32(start) and 0xffffff
        fields += BoxField("version", version.toString(), start, 1)
        fields += BoxField("flags", "0x${flags.toString(16).padStart(6, '0')}", start + 1, 3)
        if (version != 0 || flags != 0L) {
            w += "Unsupported property version/flags / 미지원 속성 버전 또는 플래그"
            return node()
        }
        val pos = start + 4
        if (pos == end) {
            w += "Missing property data / 속성 데이터 없음"
            return node()
        }
        if (type == "pixi") {
            val count = reader.readUInt8(pos)
            fields += BoxField("num_channels", count.toString(), pos, 1)
            if (end - pos - 1 < count) {
                w += "Truncated channel depths / 채널 비트 깊이 잘림"
                return node()
            }
            val bits = (0 until count).map { reader.readUInt8(pos + 1 + it) }
            fields += BoxField("bits_per_channel", bits.joinToString(", "), pos + 1, count.toLong())
            if (count == 0) w += "No channels / 채널 없음"
            if (end - pos - 1 != count.toLong()) w += "Extra property bytes / 속성 잔여 바이트"
            return node(bits.joinToString(", ") { "${it}-bit" })
        }
        val bytes = reader.readBytes(pos, minOf(end - pos, 4096L).toInt())
        val terminator = bytes.indexOf(0)
        if (terminator < 0) {
            if (end - pos > bytes.size) {
                fields += BoxField("aux_type_preview", String(bytes, Charsets.US_ASCII), pos, bytes.size.toLong(), ValueInterpretation.UNPARSABLE)
                fields += BoxField("unparsed_payload_bytes", (end - pos - bytes.size).toString(), pos + bytes.size, end - pos - bytes.size, ValueInterpretation.UNINTERPRETED)
                w += "Auxiliary type exceeds inspection limit / 보조 타입 검사 한도 초과"
            } else {
                fields += BoxField("aux_type", String(bytes, Charsets.US_ASCII), pos, bytes.size.toLong(), ValueInterpretation.UNPARSABLE)
                w += "Unterminated auxiliary type / 보조 타입 문자열 종결자 없음"
            }
            return node()
        }
        val auxType = String(bytes, 0, terminator, Charsets.US_ASCII)
        fields += BoxField("aux_type", auxType, pos, terminator.toLong())
        val subtypeStart = pos + terminator + 1
        val length = end - subtypeStart
        if (length > 0) {
            val shown = minOf(length, 4096L).toInt()
            val hex = reader.readBytes(subtypeStart, shown).joinToString(" ") { "%02x".format(it.toInt() and 255) }
            fields += BoxField("aux_subtype", hex, subtypeStart, shown.toLong(), ValueInterpretation.UNINTERPRETED)
            fields += BoxField("aux_subtype_length", length.toString(), subtypeStart, length)
            if (length > shown) fields += BoxField("unshown_subtype_bytes", (length - shown).toString(), subtypeStart + shown, length - shown)
        }
        return node(auxType)
    }
}
