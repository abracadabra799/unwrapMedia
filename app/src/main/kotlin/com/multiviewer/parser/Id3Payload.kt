package com.multiviewer.parser

import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

internal class Id3DecodeIssue(message: String, val state: ValueInterpretation = ValueInterpretation.UNPARSABLE) : RuntimeException(message)

/** Logical de-stuffed payload with a mapping back to the exact physical file span. */
internal class Id3Payload(private val reader: ByteReader, private val base: Long, private val physicalSize: Long, unsynchronised: Boolean) {
    private var bytes: ByteArray? = null
    private var positions: IntArray? = null
    val size: Long
    var utf16Order: Charset? = null

    init {
        if (unsynchronised) {
            if (physicalSize > 1_048_576) throw Id3DecodeIssue("ID3 unsynchronisation inspection limit / ID3 동기화 제거 검사 한도", ValueInterpretation.UNINTERPRETED)
            val raw = reader.readBytes(base, physicalSize.toInt())
            val data = ByteArray(raw.size)
            val offsets = IntArray(raw.size)
            var p = 0
            var count = 0
            while (p < raw.size) {
                offsets[count] = p
                data[count++] = raw[p]
                if (raw[p].toInt() and 255 == 255 && p + 1 < raw.size && raw[p + 1] == 0.toByte()) p++
                p++
            }
            bytes = data.copyOf(count)
            positions = offsets.copyOf(count)
            size = count.toLong()
        } else size = physicalSize
    }

    fun source(pos: Long): Long {
        require(pos in 0..size)
        return if (pos == size) base + physicalSize else base + (positions?.get(pos.toInt())?.toLong() ?: pos)
    }
    fun field(name: String, value: String, pos: Long, length: Long, state: ValueInterpretation = ValueInterpretation.PARSED) =
        BoxField(name, value, source(pos), source(pos + length) - source(pos), state)

    fun need(pos: Long, length: Long) {
        if (pos < 0 || length < 0 || pos > size || length > size - pos) throw Id3DecodeIssue("Truncated ID3 payload / ID3 데이터 잘림")
    }
    fun byte(pos: Long): Int {
        need(pos, 1)
        return bytes?.get(pos.toInt())?.toInt()?.and(255) ?: reader.readUInt8(base + pos)
    }
    fun read(pos: Long, length: Int): ByteArray {
        need(pos, length.toLong())
        return bytes?.copyOfRange(pos.toInt(), pos.toInt() + length) ?: reader.readBytes(base + pos, length)
    }
    fun syncsafe(pos: Long): Long {
        need(pos, 4)
        var result = 0L
        repeat(4) {
            val b = byte(pos + it)
            if (b and 128 != 0) throw Id3DecodeIssue("Invalid ID3 syncsafe integer / ID3 syncsafe 정수 오류")
            result = (result shl 7) or b.toLong()
        }
        return result
    }
    fun terminator(pos: Long, encoding: Int): Long {
        val width = if (encoding in 1..2) 2 else 1
        var p = pos
        val limit = minOf(size, pos + 65_536)
        while (p + width <= limit) {
            if (byte(p) == 0 && (width == 1 || byte(p + 1) == 0)) return p
            p += width
        }
        if (limit < size) throw Id3DecodeIssue("ID3 string inspection limit / ID3 문자열 검사 한도", ValueInterpretation.UNINTERPRETED)
        throw Id3DecodeIssue("Unterminated ID3 string / ID3 문자열 종결자 없음")
    }
    fun text(pos: Long, length: Long, encoding: Int): String {
        need(pos, length)
        if (length > 65_536) throw Id3DecodeIssue("ID3 text inspection limit / ID3 텍스트 검사 한도", ValueInterpretation.UNINTERPRETED)
        val data = read(pos, length.toInt())
        var skip = 0
        val charset = when (encoding) {
            0 -> Charsets.ISO_8859_1
            1 -> {
                if (data.size >= 2 && data[0] == 0xff.toByte() && data[1] == 0xfe.toByte()) { utf16Order = Charsets.UTF_16LE; skip = 2 }
                else if (data.size >= 2 && data[0] == 0xfe.toByte() && data[1] == 0xff.toByte()) { utf16Order = Charsets.UTF_16BE; skip = 2 }
                if (data.isEmpty()) return ""
                utf16Order ?: throw Id3DecodeIssue("UTF16 byte order is unknown / UTF16 바이트 순서 미확인")
            }
            2 -> Charsets.UTF_16BE
            3 -> Charsets.UTF_8
            else -> throw Id3DecodeIssue("Unsupported ID3 text encoding / 미지원 ID3 문자열 인코딩: $encoding")
        }
        return try {
            charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(data, skip, data.size - skip)).toString().trimEnd('\u0000')
        } catch (_: java.nio.charset.CharacterCodingException) {
            throw Id3DecodeIssue("Invalid ID3 text bytes / ID3 문자열 바이트 오류")
        }
    }
}
