package com.multiviewer.parser

internal fun decodeId3ExtendedHeader(reader: ByteReader, start: Long, end: Long, version: Int): BoxNode {
    if (end - start < 4) throw Id3DecodeIssue("Truncated ID3 extended header size / ID3 확장 헤더 크기 잘림")
    val source = Id3Payload(reader, start, end - start, false)
    val declared = if (version == 3) reader.readUInt32(start) else source.syncsafe(0)
    val total = declared + if (version == 3) 4 else 0
    if (total < (if (version == 3) 10 else 6) || total > end - start) throw Id3DecodeIssue("Invalid ID3 extended header extent / ID3 확장 헤더 범위 오류")
    val limit = start + total
    val fields = mutableListOf(BoxField("size", declared.toString(), start, 4))
    val warnings = mutableListOf<String>()
    var state = ValueInterpretation.PARSED
    var pos = start + 4
    fun need(length: Long) { if (length > limit - pos) throw Id3DecodeIssue("Truncated ID3 extended fields / ID3 확장 필드 잘림") }
    try {
        if (version == 3) {
            val flags = reader.readUInt16(pos)
            fields += BoxField("flags", "0x${flags.toString(16).padStart(4, '0')}", pos, 2)
            pos += 2
            fields += BoxField("padding_size", reader.readUInt32(pos).toString(), pos, 4)
            pos += 4
            if (flags and 0x7fff != 0) throw Id3DecodeIssue("Unknown extended flags / 미지원 확장 플래그", ValueInterpretation.UNINTERPRETED)
            if (flags and 0x8000 != 0) {
                need(4)
                fields += BoxField("crc32", reader.readUInt32(pos).toString(), pos, 4)
                pos += 4
            }
        } else {
            val flagBytes = reader.readUInt8(pos)
            fields += BoxField("flag_bytes", flagBytes.toString(), pos, 1)
            pos++
            val flags = reader.readUInt8(pos)
            fields += BoxField("flags", "0x${flags.toString(16).padStart(2, '0')}", pos, 1)
            pos++
            if (flagBytes != 1 || flags and 0x8f != 0) throw Id3DecodeIssue("Unknown extended flags / 미지원 확장 플래그", ValueInterpretation.UNINTERPRETED)
            for ((mask, length) in listOf(0x40 to 0, 0x20 to 5, 0x10 to 1)) {
                if (flags and mask == 0) continue
                need(1)
                val lengthPos = pos
                if (reader.readUInt8(pos++) != length) throw Id3DecodeIssue("Invalid extended flag data length / 확장 플래그 데이터 길이 오류")
                need(length.toLong())
                when (mask) {
                    0x40 -> fields += BoxField("update", "true", lengthPos, 1)
                    0x20 -> {
                        var crc = 0L
                        repeat(5) {
                            val b = reader.readUInt8(pos + it)
                            if (b and 128 != 0) throw Id3DecodeIssue("Invalid extended CRC encoding / 확장 CRC 인코딩 오류")
                            crc = (crc shl 7) or b.toLong()
                        }
                        if (crc > 0xffffffffL) throw Id3DecodeIssue("Extended CRC exceeds 32 bits / 확장 CRC 32비트 초과")
                        fields += BoxField("crc32", crc.toString(), pos, 5)
                    }
                    0x10 -> fields += BoxField("restrictions", reader.readUInt8(pos).toString(), pos, 1)
                }
                pos += length
            }
        }
        if (pos < limit) {
            fields += BoxField("unparsed_extended_bytes", (limit - pos).toString(), pos, limit - pos, ValueInterpretation.UNINTERPRETED)
            state = ValueInterpretation.UNINTERPRETED
        }
    } catch (e: Id3DecodeIssue) {
        warnings += e.message.orEmpty()
        state = e.state
    }
    return BoxNode("ID3ExtendedHeader", start, 4, total, fields = fields, warnings = warnings, interpretation = state)
}
