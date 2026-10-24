package com.multiviewer.parser

object InfeBoxDecoder : BoxDecoder {
    override fun decode(reader: ByteReader, type: String, offset: Long, headerSize: Int,
                        size: Long, warnings: List<String>): BoxNode {
        val w = warnings.toMutableList()
        val fields = mutableListOf<BoxField>()
        fun node(summary: String? = null) = BoxNode(type, offset, headerSize, size,
            fields = fields.toList(), warnings = w.toList(), summary = summary)
        if (offset < 0 || headerSize < 0 || size < headerSize || offset > reader.length ||
            size > reader.length - offset || size - headerSize < 4) {
            w += "Truncated infe header / infe 헤더 잘림"
            return node()
        }
        val start = offset + headerSize
        val end = offset + size
        val version = reader.readUInt8(start)
        if (version !in 2..3) {
            w += "Unsupported infe version / 미지원 infe 버전: $version"
            return node()
        }
        val idWidth = if (version == 2) 2 else 4
        if (end - start < 4 + idWidth + 6) {
            w += "Truncated infe fixed fields / infe 고정 필드 잘림"
            return node()
        }
        val flags = reader.readUInt32(start) and 0xffffff
        val idStart = start + 4
        val itemId = if (idWidth == 2) reader.readUInt16(idStart).toLong() else reader.readUInt32(idStart)
        val typeStart = idStart + idWidth + 2
        val itemType = reader.readFourCC(typeStart)
        fields += BoxField("item_ID", itemId.toString(), idStart, idWidth.toLong())
        fields += BoxField("item_protection_index", reader.readUInt16(typeStart - 2).toString(), typeStart - 2, 2)
        fields += BoxField("item_type", itemType, typeStart, 4)
        var pos = typeStart + 4
        var stopped = false
        fun string(name: String, optional: Boolean = false): String? {
            if (stopped || optional && pos == end) return null
            if (pos == end) {
                w += "Missing $name / 문자열 없음"
                stopped = true
                return null
            }
            val bytes = reader.readBytes(pos, minOf(end - pos, 65536L).toInt())
            val zero = bytes.indexOf(0)
            val length = if (zero >= 0) zero else bytes.size
            val value = String(bytes, 0, length, Charsets.UTF_8)
            val limited = zero < 0 && end - pos > bytes.size
            fields += BoxField(if (limited) "${name}_preview" else name, value, pos, length.toLong())
            if (zero < 0) {
                w += if (limited) "String inspection limit reached / 문자열 검사 한도 초과" else "Unterminated $name / 문자열 종결자 없음"
                if (limited) fields += BoxField("unparsed_payload_bytes", (end - pos - length).toString(), pos + length, end - pos - length)
                stopped = true
                return null
            }
            pos += length + 1
            return value
        }
        val name = string("item_name")
        var summary = name?.let { "$itemType: $it" }
        if (itemType == "mime") {
            val contentType = string("content_type")
            if (name != null && contentType != null) summary = "$itemType ($contentType): $name"
            string("content_encoding", optional = true)
        } else if (itemType == "uri ") {
            string("item_uri_type")
        }
        // Append added headers so existing field positions remain stable.
        fields += BoxField("version", version.toString(), start, 1)
        fields += BoxField("flags", "0x${flags.toString(16).padStart(6, '0')}", start + 1, 3)
        fields += BoxField("hidden_item", (flags and 1 != 0L).toString(), start + 1, 3)
        if (flags and 0xfffffe != 0L) w += "Unknown infe flags / 미지원 infe 플래그"
        if (!stopped && pos < end) {
            fields += BoxField("unparsed_payload_bytes", (end - pos).toString(), pos, end - pos)
        }
        return node(summary)
    }
}
