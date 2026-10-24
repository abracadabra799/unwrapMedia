package com.multiviewer.parser

import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

private data class QuickTimeTextRecord(val start: Long, val declared: Int, val language: Int, val textStart: Long, val textLength: Int)

object QuickTimeLegacyTextDecoder : BoxDecoder {
    override fun decode(reader: ByteReader, type: String, offset: Long, headerSize: Int, size: Long, warnings: List<String>): BoxNode {
        val w = warnings.toMutableList()
        val fields = mutableListOf<BoxField>()
        val children = mutableListOf<BoxNode>()
        var state = if (w.isEmpty()) ValueInterpretation.PARSED else ValueInterpretation.UNPARSABLE
        fun node(summary: String? = null) = BoxNode(type, offset, headerSize, size, fields = fields, children = children, warnings = w, summary = summary, interpretation = state)
        if (!validQuickTimeRange(reader, offset, headerSize, size, 4)) {
            w += "Truncated legacy text record / legacy 텍스트 레코드 잘림"; state = ValueInterpretation.UNPARSABLE; return node()
        }
        val start = offset + headerSize
        val end = offset + size
        var limitReached = false
        fun scan(inclusive: Boolean): List<QuickTimeTextRecord>? {
            val records = mutableListOf<QuickTimeTextRecord>()
            var pos = start
            var bytes = 0L
            while (pos < end) {
                if (end - pos < 4) return null
                if (records.size >= 256 || bytes >= 65_536) { limitReached = true; return null }
                val declared = reader.readUInt16(pos)
                val length = declared - if (inclusive) 4 else 0
                if (length < 0 || length.toLong() > end - pos - 4) return null
                if (bytes + length > 65_536) { limitReached = true; return null }
                records += QuickTimeTextRecord(pos, declared, reader.readUInt16(pos + 2), pos + 4, length)
                bytes += length
                pos += 4 + length
            }
            return records
        }
        val inclusive = scan(true)
        val exclusive = scan(false)
        if ((inclusive == null) == (exclusive == null)) {
            state = if (limitReached || inclusive != null) ValueInterpretation.UNINTERPRETED else ValueInterpretation.UNPARSABLE
            w += if (limitReached) "Legacy text inspection limit / legacy 텍스트 검사 한도"
                else if (inclusive != null) "Ambiguous legacy text length / legacy 텍스트 길이 형식 모호"
                else "Invalid legacy text lengths / legacy 텍스트 길이 오류"
            val raw = quickTimeRawValue(reader, start, end - start, state)
            fields += BoxField("raw_payload", raw.value, start, end - start, state)
            return node()
        }
        val records = inclusive ?: exclusive!!
        fields += BoxField("length_encoding", if (inclusive != null) "header_inclusive" else "text_bytes_only", start, 2)
        for (record in records) {
            val rf = mutableListOf(BoxField("text_length", record.declared.toString(), record.start, 2),
                BoxField("language_code", record.language.toString(), record.start + 2, 2))
            val unicode = record.language >= 0x400 && record.language != 0x7fff
            val language = if (unicode) {
                val groups = listOf((record.language ushr 10) and 31, (record.language ushr 5) and 31, record.language and 31)
                if (groups.all { it in 1..26 }) groups.map { ('a'.code - 1 + it).toChar() }.joinToString("") else null
            } else listOf("en", "fr", "de", "it", "nl", "sv", "es", "da", "pt", "no").getOrNull(record.language)
            language?.let { rf += BoxField("language", it, record.start + 2, 2) }
            var encoding = "Macintosh"
            val value = if (unicode) {
                val bom = record.textLength >= 2 && reader.readUInt16(record.textStart) == 0xfeff
                encoding = if (bom) "UTF-16BE" else "UTF-8"
                readQuickTimeValue(reader, if (bom) 2 else 1, record.textStart, record.textLength.toLong())
            } else if (record.language in 0..9) {
                encoding = "MacRoman"
                try {
                    val text = Charset.forName("x-MacRoman").newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(reader.readBytes(record.textStart, record.textLength))).toString()
                    QuickTimeValue(text)
                } catch (_: java.nio.charset.CharacterCodingException) {
                    quickTimeRawValue(reader, record.textStart, record.textLength.toLong(), ValueInterpretation.UNPARSABLE, "Invalid Macintosh text / Macintosh 문자열 오류")
                } catch (_: java.nio.charset.UnsupportedCharsetException) {
                    quickTimeRawValue(reader, record.textStart, record.textLength.toLong(), ValueInterpretation.UNINTERPRETED, "Unavailable Macintosh encoding / Macintosh 인코딩 미지원")
                }
            } else quickTimeRawValue(reader, record.textStart, record.textLength.toLong(), ValueInterpretation.UNINTERPRETED, "Unsupported Macintosh text encoding / 미지원 Macintosh 문자열 인코딩")
            rf += BoxField("encoding", encoding, record.start + 2, 2)
            rf += BoxField("value", value.value, record.textStart, record.textLength.toLong(), value.state)
            children += BoxNode("text_record", record.start, 4, record.textLength + 4L, fields = rf,
                warnings = value.warning?.let { listOf(it) } ?: emptyList(), summary = value.value, interpretation = value.state)
            state = quickTimeState(state, value.state)
        }
        children.firstOrNull()?.fields?.firstOrNull { it.name == "value" }?.let { fields.add(0, it) }
        return node(fields.firstOrNull { it.name == "value" }?.value)
    }
}
