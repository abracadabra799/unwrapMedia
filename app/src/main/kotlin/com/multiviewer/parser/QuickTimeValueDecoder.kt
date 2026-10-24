package com.multiviewer.parser

import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

internal fun validQuickTimeRange(reader: ByteReader, offset: Long, header: Int, size: Long, minimum: Long): Boolean =
    offset >= 0 && header >= 0 && size >= header && offset <= reader.length && size <= reader.length - offset && size - header >= minimum

internal fun quickTimeState(a: ValueInterpretation, b: ValueInterpretation): ValueInterpretation = when {
    a == ValueInterpretation.UNPARSABLE || b == ValueInterpretation.UNPARSABLE -> ValueInterpretation.UNPARSABLE
    a == ValueInterpretation.UNINTERPRETED || b == ValueInterpretation.UNINTERPRETED -> ValueInterpretation.UNINTERPRETED
    else -> ValueInterpretation.PARSED
}

internal data class QuickTimeValue(val value: String, val state: ValueInterpretation = ValueInterpretation.PARSED,
    val warning: String? = null, val components: List<BoxField> = emptyList())

internal fun quickTimeRawValue(reader: ByteReader, offset: Long, length: Long,
                               state: ValueInterpretation, warning: String? = null): QuickTimeValue {
    val shown = minOf(length, 32L).toInt()
    val hex = reader.readBytes(offset, shown).joinToString(" ") { "%02x".format(it.toInt() and 255) }
    return QuickTimeValue(if (length > shown) "$hex... ($length bytes)" else "$hex ($length bytes)", state, warning)
}

internal fun readQuickTimeValue(reader: ByteReader, code: Int, offset: Long, length: Long): QuickTimeValue {
    if (offset < 0 || length < 0 || offset > reader.length || length > reader.length - offset)
        return QuickTimeValue("Invalid source range / 원본 범위 오류", ValueInterpretation.UNPARSABLE, "QuickTime value outside file / QuickTime 값이 파일 범위 초과")
    fun invalid(reason: String) = quickTimeRawValue(reader, offset, length, ValueInterpretation.UNPARSABLE, reason)
    if (code in 1..5) {
        if (length > 65_536) return quickTimeRawValue(reader, offset, length, ValueInterpretation.UNINTERPRETED, "QuickTime text inspection limit / QuickTime 텍스트 검사 한도")
        val data = reader.readBytes(offset, length.toInt())
        return try {
            val charset = when (code) { 2, 5 -> Charsets.UTF_16BE; 3 -> Charset.forName("Shift_JIS"); else -> Charsets.UTF_8 }
            if (code in listOf(2, 5) && data.size >= 2 && data[0] == 0xff.toByte() && data[1] == 0xfe.toByte())
                return invalid("Unexpected UTF16 little endian data / UTF16 바이트 순서 오류")
            val text = charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(data)).toString().removePrefix("\uFEFF")
            QuickTimeValue(text)
        } catch (_: java.nio.charset.CharacterCodingException) {
            invalid("Malformed QuickTime text / QuickTime 문자열 인코딩 오류")
        } catch (_: java.nio.charset.UnsupportedCharsetException) {
            invalid("Unsupported QuickTime text encoding / 미지원 QuickTime 문자열 인코딩")
        }
    }
    val signedWidths = mapOf(65 to 1L, 66 to 2L, 67 to 4L, 74 to 8L)
    val unsignedWidths = mapOf(75 to 1L, 76 to 2L, 77 to 4L, 78 to 8L)
    if (code in signedWidths || code in unsignedWidths || code == 21 || code == 22) {
        val fixed = signedWidths[code] ?: unsignedWidths[code]
        if (fixed != null && length != fixed || fixed == null && length !in listOf(1L, 2L, 3L, 4L, 8L))
            return invalid("Invalid QuickTime integer width / QuickTime 정수 길이 오류")
        val data = reader.readBytes(offset, length.toInt())
        val signed = code == 21 || code in signedWidths
        return QuickTimeValue((if (signed) BigInteger(data) else BigInteger(1, data)).toString())
    }
    if (code == 23 || code == 24) {
        if (length != if (code == 23) 4L else 8L) return invalid("Invalid QuickTime float width / QuickTime 실수 길이 오류")
        return QuickTimeValue(if (code == 23) Float.fromBits(reader.readUInt32(offset).toInt()).toString() else Double.fromBits(reader.readUInt64(offset)).toString())
    }
    if (code in listOf(70, 71, 72, 79)) {
        val names = when (code) { 70 -> listOf("x", "y"); 71 -> listOf("width", "height"); 72 -> listOf("x", "y", "width", "height"); else -> (0..8).map { "matrix[${it / 3}][${it % 3}]" } }
        val width = if (code == 79) 8 else 4
        if (length != names.size * width.toLong()) return invalid("Invalid QuickTime geometry length / QuickTime 좌표·행렬 길이 오류")
        val components = names.mapIndexed { i, name ->
            val pos = offset + i * width
            val value = if (width == 4) Float.fromBits(reader.readUInt32(pos).toInt()).toString() else Double.fromBits(reader.readUInt64(pos)).toString()
            BoxField(name, value, pos, width.toLong())
        }
        return QuickTimeValue(components.joinToString(", ", "[", "]") { it.value }, components = components)
    }
    if (code in listOf(13, 14, 27)) {
        if (length == 0L) return invalid("Empty QuickTime image / QuickTime 이미지 데이터 없음")
        val kind = when (code) { 13 -> "JPEG"; 14 -> "PNG"; else -> "BMP" }
        return QuickTimeValue("$kind Image ($length bytes)", ValueInterpretation.UNINTERPRETED)
    }
    return quickTimeRawValue(reader, offset, length, ValueInterpretation.UNINTERPRETED)
}

object QuickTimeDataBoxDecoder : BoxDecoder {
    override fun decode(reader: ByteReader, type: String, offset: Long, headerSize: Int, size: Long, warnings: List<String>): BoxNode {
        val w = warnings.toMutableList()
        if (!validQuickTimeRange(reader, offset, headerSize, size, 8))
            return BoxNode(type, offset, headerSize, size, warnings = w + "Truncated QuickTime data header / QuickTime data 헤더 잘림", interpretation = ValueInterpretation.UNPARSABLE)
        val start = offset + headerSize
        val rawType = reader.readUInt32(start)
        val code = (rawType and 0xffffff).toInt()
        val typeSet = (rawType ushr 24).toInt()
        val locale = reader.readUInt32(start + 4)
        val dataStart = start + 8
        val length = size - headerSize - 8
        val value = if (typeSet == 0) readQuickTimeValue(reader, code, dataStart, length)
            else quickTimeRawValue(reader, dataStart, length, ValueInterpretation.UNINTERPRETED, "Unsupported QuickTime type set / 미지원 QuickTime 타입 집합")
        value.warning?.let { w += it }
        val fields = listOf(
            BoxField("type_code", "$code (0x%06x)".format(code), start, 4),
            BoxField("locale", locale.toString(), start + 4, 4),
            BoxField("value", value.value, dataStart, length, value.state),
            BoxField("type_set", typeSet.toString(), start, 1),
            BoxField("country_indicator", (locale ushr 16).toString(), start + 4, 2),
            BoxField("language_indicator", (locale and 65535).toString(), start + 6, 2),
        ) + value.components
        return BoxNode(type, offset, headerSize, size, fields = fields, warnings = w, summary = value.value,
            interpretation = quickTimeState(if (warnings.isEmpty()) ValueInterpretation.PARSED else ValueInterpretation.UNPARSABLE, value.state))
    }
}
