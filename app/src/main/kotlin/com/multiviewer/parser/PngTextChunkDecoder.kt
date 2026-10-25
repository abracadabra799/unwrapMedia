package com.multiviewer.parser

import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

private const val PNG_ENCODED_METADATA_LIMIT = 1024 * 1024
private const val PNG_TEXT_LIMIT = 1024 * 1024
private const val PNG_PROFILE_LIMIT = 4 * 1024 * 1024
internal class PngTextBudget(var remaining: Int = 8 * 1024 * 1024)
private class PngTextLimit : RuntimeException()

private class PngTextReader(val c: PngDetailFields, val data: ByteArray, val budget: PngTextBudget) {
    fun charge(bytes: Int) {
        if (bytes > budget.remaining) throw PngTextLimit()
        budget.remaining -= bytes
    }
    fun decode(bytes: ByteArray, charset: Charset): String {
        if (bytes.any { it == 0.toByte() }) throw PngDetailIssue("NUL in PNG text / PNG 문자열에 NUL 포함")
        return try {
            charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: java.nio.charset.CharacterCodingException) {
            throw PngDetailIssue("Invalid PNG text encoding / PNG 문자열 인코딩 오류")
        }
    }
    fun terminated(name: String, charset: Charset, keyword: Boolean = false): String {
        val from = (c.pos - c.start).toInt()
        var to = from
        while (to < data.size && data[to] != 0.toByte()) to++
        if (to == data.size) throw PngDetailIssue("Missing PNG text separator / PNG 문자열 구분자 없음")
        val n = to - from
        if (keyword && n !in 1..79) throw PngDetailIssue("Invalid PNG keyword length / PNG 키워드 길이 오류")
        charge(n)
        val text = decode(data.copyOfRange(from, to), charset)
        if (keyword && (text.any { it.code !in 0x20..0x7e && it.code !in 0xa1..0xff } || text.startsWith(' ') || text.endsWith(' ') || "  " in text))
            throw PngDetailIssue("Invalid PNG keyword characters or spacing / PNG 키워드 문자 또는 공백 오류")
        c.field(name, text, c.pos, n.toLong())
        c.pos += n + 1
        return text
    }
    fun remainingBytes() = data.copyOfRange((c.pos - c.start).toInt(), data.size)
    fun inflate(profile: Boolean): PngInflateResult.Complete {
        return when (val result = inflatePngMetadata(remainingBytes(), minOf(if (profile) PNG_PROFILE_LIMIT else PNG_TEXT_LIMIT, budget.remaining))) {
            is PngInflateResult.Complete -> { charge(result.bytes.size); result }
            PngInflateResult.Limited -> throw PngTextLimit()
            PngInflateResult.Malformed -> throw PngDetailIssue("Incomplete or invalid PNG zlib stream / PNG zlib 스트림 잘림 또는 오류")
        }
    }
    fun textValue(compressed: Boolean, charset: Charset): String {
        val source = c.pos
        val bytes: ByteArray
        val sourceLength: Int
        if (compressed) {
            val result = inflate(false)
            bytes = result.bytes
            sourceLength = result.consumed
        } else {
            bytes = remainingBytes()
            if (bytes.size > PNG_TEXT_LIMIT) throw PngTextLimit()
            charge(bytes.size)
            sourceLength = bytes.size
        }
        val text = decode(bytes, charset)
        c.field("text", text, source, sourceLength.toLong())
        c.pos += sourceLength
        return text
    }
}

internal fun decodePngTextChunk(reader: ByteReader, type: String, offset: Long, length: Long, totalSize: Long, budget: PngTextBudget): BoxNode? {
    if (type !in setOf("tEXt", "zTXt", "iTXt", "iCCP")) return null
    var summary: String? = null
    val node = PngDetailFields(reader, type, offset, length, totalSize).finish { c ->
        try {
            // Test the UInt32 length before narrowing or allocating (including sparse multi-GB chunks).
            if (length > PNG_ENCODED_METADATA_LIMIT || budget.remaining == 0) throw PngTextLimit()
            val r = PngTextReader(c, reader.readBytes(c.start, length.toInt()), budget)
            val keyword = r.terminated(if (type == "iCCP") "profile_name" else "keyword", Charsets.ISO_8859_1, keyword = true)
            summary = keyword
            when (type) {
                "tEXt" -> summary = "$keyword: ${r.textValue(false, Charsets.ISO_8859_1).take(160)}"
                "zTXt", "iCCP" -> {
                    val method = c.uint("compression_method", 1, 0L..0L)
                    if (method != 0L) {
                        c.warnings += "Unsupported PNG compression method / 미지원 PNG 압축 방식"
                        return@finish
                    }
                    if (type == "zTXt") summary = "$keyword: ${r.textValue(true, Charsets.ISO_8859_1).take(160)}"
                    else {
                        val source = c.pos
                        val result = r.inflate(true)
                        val profile = result.bytes
                        val span = result.consumed.toLong()
                        c.field("decompressed_size", profile.size.toString(), source, span)
                        c.field("profile_source", "Decompressed from this byte range / 이 바이트 범위를 압축 해제한 값", source, span)
                        if (profile.size < 128) throw PngDetailIssue("Truncated ICC profile header / ICC 프로파일 헤더 잘림")
                        c.fields += decodeIccProfileHeader(profile, 0).map { it.copy(offset = source, length = span) }
                        if (profile.size < 132) throw PngDetailIssue("Missing ICC tag count / ICC 태그 개수 필드 잘림")
                        var declared = 0L
                        profile.take(4).forEach { declared = (declared shl 8) or (it.toLong() and 255) }
                        if (declared != profile.size.toLong() || !profile.copyOfRange(36,40).contentEquals("acsp".toByteArray(Charsets.US_ASCII)))
                            throw PngDetailIssue("Invalid ICC profile size or signature / ICC 프로파일 크기 또는 서명 오류")
                        if (profile.size > 128) c.field("profile_tag_data", "ICC tag data not interpreted / ICC 태그 데이터 미해석", source, span, ValueInterpretation.UNINTERPRETED)
                        c.pos += result.consumed
                        summary = "$keyword (${profile.size} bytes decompressed)"
                    }
                }
                "iTXt" -> {
                    val flag = c.uint("compression_flag", 1, 0L..1L)
                    // PNG3 requires decoders to ignore this byte for uncompressed iTXt.
                    val method = c.uint("compression_method", 1, if (flag == 0L) null else 0L..0L)
                    r.terminated("language_tag", Charsets.US_ASCII)
                    r.terminated("translated_keyword", Charsets.UTF_8)
                    if (flag !in 0L..1L || flag == 1L && method != 0L) {
                        c.warnings += "Unsupported PNG text compression / 미지원 PNG 문자열 압축"
                        return@finish
                    }
                    summary = "$keyword: ${r.textValue(flag == 1L, Charsets.UTF_8).take(160)}"
                }
            }
        } catch (_: PngTextLimit) {
            if (c.state == ValueInterpretation.PARSED) c.state = ValueInterpretation.UNINTERPRETED
            c.warnings += "PNG metadata inspection limit / PNG 메타데이터 검사 한도"
        }
    }
    return node.copy(summary = summary)
}
