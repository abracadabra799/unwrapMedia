package com.multiviewer.parser

internal data class Av1ConfigObus(val nodes: List<BoxNode>, val next: Long, val warnings: List<String>, val malformed: Boolean = false)

/** Inspect framing only; payload bytes stay in the source file. */
internal fun decodeAv1ConfigObus(reader: ByteReader, start: Long, end: Long): Av1ConfigObus {
    val nodes = mutableListOf<BoxNode>()
    val warnings = mutableListOf<String>()
    var pos = start
    while (pos < end && nodes.size < 256) {
        val begin = pos
        val fields = mutableListOf<BoxField>()
        val w = mutableListOf<String>()
        val header = reader.readUInt8(pos++)
        val type = (header ushr 3) and 15
        val extension = header and 4 != 0
        val hasSize = header and 2 != 0
        fields += BoxField("obu_type", type.toString(), begin, 1)
        fields += BoxField("obu_forbidden_bit", (header ushr 7).toString(), begin, 1)
        fields += BoxField("obu_extension_flag", extension.toString(), begin, 1)
        fields += BoxField("obu_has_size_field", hasSize.toString(), begin, 1)
        fields += BoxField("obu_reserved_1bit", (header and 1).toString(), begin, 1)
        if (header and 0x81 != 0) w += "Invalid OBU header bits / OBU 헤더 비트 오류"
        if (extension) {
            if (pos == end) w += "Truncated OBU extension / OBU 확장 잘림"
            else {
                val ext = reader.readUInt8(pos)
                fields += BoxField("temporal_id", (ext ushr 5).toString(), pos, 1)
                fields += BoxField("spatial_id", ((ext ushr 3) and 3).toString(), pos, 1)
                fields += BoxField("extension_header_reserved_3bits", (ext and 7).toString(), pos, 1)
                if (ext and 7 != 0) w += "Nonzero OBU reserved bits / OBU 예약 비트 오류"
                pos++
            }
        }
        if (!hasSize) w += "Config OBU has no size field / 설정 OBU 크기 필드 없음"
        var payloadSize = 0L
        val sizeStart = pos
        var terminated = false
        if (w.isEmpty()) {
            for (i in 0 until 8) {
                if (pos == end) break
                val b = reader.readUInt8(pos++)
                payloadSize = payloadSize or ((b.toLong() and 127) shl (i * 7))
                if (b and 128 == 0) { terminated = true; break }
            }
            if (!terminated) w += "Unterminated OBU size / OBU 크기 종결 오류"
            else fields += BoxField("obu_size", payloadSize.toString(), sizeStart, pos - sizeStart)
        }
        val payloadStart = pos
        if (w.isEmpty() && payloadSize > end - pos) w += "Truncated OBU payload / OBU 페이로드 잘림"
        if (w.isNotEmpty()) {
            nodes += BoxNode("OBU", begin, (payloadStart - begin).toInt(), end - begin,
                fields = fields, warnings = w, summary = "type=$type", interpretation = ValueInterpretation.UNPARSABLE)
            warnings += w
            return Av1ConfigObus(nodes, begin, warnings, malformed = true)
        }
        fields += BoxField("payload_bytes", payloadSize.toString(), pos, payloadSize)
        pos += payloadSize
        nodes += BoxNode("OBU", begin, (payloadStart - begin).toInt(), pos - begin,
            fields = fields, summary = "type=$type, $payloadSize bytes")
    }
    if (pos < end) warnings += "AV1 config OBU inspection limit / AV1 설정 OBU 검사 한도"
    return Av1ConfigObus(nodes, pos, warnings)
}
