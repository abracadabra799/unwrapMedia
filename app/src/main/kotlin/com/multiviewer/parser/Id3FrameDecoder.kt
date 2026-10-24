package com.multiviewer.parser

import java.math.BigInteger

private val ID3_TEXT_NAMES = mapOf("TIT2" to "Title", "TPE1" to "Artist", "TALB" to "Album", "TYER" to "Year",
    "TDRC" to "Recording Time", "TCON" to "Genre", "TRCK" to "Track", "TCOM" to "Composer", "TENC" to "Encoded By", "TSSE" to "Encoder Settings")

internal fun decodeId3Frame(reader: ByteReader, frameId: String, offset: Long, payloadSize: Long, version: Int, tagUnsync: Boolean): BoxNode {
    val start = offset + 10
    val flags = reader.readUInt16(offset + 8)
    val fields = mutableListOf<BoxField>()
    val extras = mutableListOf(BoxField("declared_size", payloadSize.toString(), offset + 4, 4),
        BoxField("flags", "0x${flags.toString(16).padStart(4, '0')}", offset + 8, 2))
    val warnings = mutableListOf<String>()
    var state = ValueInterpretation.PARSED
    var summary: String? = null
    fun rawFallback(interpretation: ValueInterpretation) {
        val shown = minOf(payloadSize, 64L).toInt()
        val raw = reader.readBytes(start, shown).joinToString(" ") { "%02x".format(it.toInt() and 255) }
        fields += BoxField("raw_payload", raw, start, shown.toLong(), interpretation)
        if (payloadSize > shown) fields += BoxField("unshown_payload_bytes", (payloadSize - shown).toString(), start + shown, payloadSize - shown, ValueInterpretation.UNINTERPRETED)
    }
    try {
        if (payloadSize <= 0) throw Id3DecodeIssue("Empty ID3 frame / 빈 ID3 프레임")
        val allowed = if (version == 3) 0xe0e0 else 0x704f
        if (flags and allowed.inv() != 0) throw Id3DecodeIssue("Unknown ID3 frame flags / 미지원 ID3 프레임 플래그")
        val compressed = flags and (if (version == 3) 0x80 else 8) != 0
        val encrypted = flags and (if (version == 3) 0x40 else 4) != 0
        if (compressed || encrypted) throw Id3DecodeIssue("Compressed/encrypted frame is uninterpreted / 압축 또는 암호화 프레임 미해석", ValueInterpretation.UNINTERPRETED)
        val view = Id3Payload(reader, start, payloadSize, version == 4 && (tagUnsync || flags and 2 != 0))
        var pos = 0L
        if (flags and (if (version == 3) 0x20 else 0x40) != 0) {
            extras += view.field("group_identifier", view.byte(pos).toString(), pos, 1)
            pos++
        }
        if (version == 4 && flags and 1 != 0) {
            val length = view.syncsafe(pos)
            extras += view.field("data_length_indicator", length.toString(), pos, 4)
            pos += 4
            if (length != view.size - pos) throw Id3DecodeIssue("ID3 data length mismatch / ID3 데이터 길이 불일치")
        }
        fun encoding(): Int {
            val value = view.byte(pos)
            extras += view.field("text_encoding", value.toString(), pos, 1)
            pos++
            if (value !in 0..(if (version == 3) 1 else 3)) throw Id3DecodeIssue("Unsupported ID3 text encoding / 미지원 ID3 문자열 인코딩: $value")
            return value
        }
        fun terminated(name: String, encoding: Int): String {
            val end = view.terminator(pos, encoding)
            val text = view.text(pos, end - pos, encoding)
            fields += view.field(name, text, pos, end - pos)
            pos = end + if (encoding in 1..2) 2 else 1
            return text
        }
        fun remainingText(name: String, encoding: Int): String {
            val text = view.text(pos, view.size - pos, encoding)
            fields += view.field(name, text, pos, view.size - pos)
            pos = view.size
            return text
        }
        fun binary(name: String, interpreted: Boolean = false, max: Int = 64) {
            val length = view.size - pos
            val shown = minOf(length, max.toLong()).toInt()
            val value = view.read(pos, shown).joinToString(" ") { "%02x".format(it.toInt() and 255) }
            val interpretation = if (interpreted) ValueInterpretation.PARSED else ValueInterpretation.UNINTERPRETED
            fields += view.field(name, value, pos, shown.toLong(), interpretation)
            if (!interpreted && length > 0) state = ValueInterpretation.UNINTERPRETED
            if (shown < length) fields += view.field("unshown_payload_bytes", (length - shown).toString(), pos + shown, length - shown, ValueInterpretation.UNINTERPRETED)
            pos = view.size
        }
        fun counter() {
            val length = view.size - pos
            if (length < 4) throw Id3DecodeIssue("Truncated ID3 counter / ID3 카운터 잘림")
            if (length > 1024) throw Id3DecodeIssue("ID3 counter inspection limit / ID3 카운터 검사 한도", ValueInterpretation.UNINTERPRETED)
            fields += view.field("counter", BigInteger(1, view.read(pos, length.toInt())).toString(), pos, length)
            pos = view.size
        }
        when {
            frameId == "TXXX" -> { val enc = encoding(); terminated("description", enc); summary = remainingText("value", enc) }
            frameId.startsWith("T") -> { val enc = encoding(); summary = remainingText(ID3_TEXT_NAMES[frameId] ?: frameId, enc) }
            frameId == "WXXX" -> { val enc = encoding(); terminated("description", enc); summary = remainingText("url", 0) }
            frameId.startsWith("W") -> summary = remainingText("url", 0)
            frameId == "COMM" || frameId == "USLT" -> {
                val enc = encoding()
                view.need(pos, 3)
                fields += view.field("language", view.text(pos, 3, 0), pos, 3)
                pos += 3
                fields += BoxField("size", payloadSize.toString(), start, payloadSize)
                terminated("description", enc)
                summary = remainingText("text", enc)
            }
            frameId == "APIC" -> {
                val enc = encoding()
                val mime = terminated("mime_type", 0)
                fields += BoxField("size", payloadSize.toString(), start, payloadSize)
                fields += view.field("picture_type", view.byte(pos).toString(), pos, 1)
                pos++
                terminated("description", enc)
                fields += view.field("image_data_length", (view.size - pos).toString(), pos, view.size - pos)
                if (pos == view.size) throw Id3DecodeIssue("APIC has no image data / APIC 이미지 데이터 없음")
                if (mime == "-->") remainingText("image_url", 0)
                summary = "Embedded picture ($mime, $payloadSize bytes)"
            }
            frameId == "PRIV" || frameId == "UFID" -> {
                val owner = terminated("owner", 0)
                if (frameId == "UFID" && owner.isEmpty()) throw Id3DecodeIssue("UFID owner is empty / UFID 소유자 식별자 없음")
                if (frameId == "UFID" && view.size - pos > 64) throw Id3DecodeIssue("UFID exceeds 64 bytes / UFID 64바이트 초과")
                binary(if (frameId == "UFID") "identifier" else "private_data", frameId == "UFID")
            }
            frameId == "PCNT" -> counter()
            frameId == "POPM" -> {
                terminated("email", 0)
                fields += view.field("rating", view.byte(pos).toString(), pos, 1)
                pos++
                if (pos < view.size) counter()
            }
            frameId == "GEOB" -> {
                val enc = encoding(); terminated("mime_type", 0); terminated("filename", enc); terminated("description", enc); binary("object_data")
            }
            else -> { fields += BoxField("size", payloadSize.toString(), start, payloadSize); binary("raw_payload") }
        }
    } catch (e: Id3DecodeIssue) {
        state = e.state
        warnings += e.message.orEmpty()
        rawFallback(state)
    }
    return BoxNode(frameId, offset, 10, payloadSize + 10, fields = fields + extras, warnings = warnings,
        summary = summary, interpretation = state)
}
