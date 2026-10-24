package com.multiviewer.parser

import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

internal class FlacMetadataIssue(message: String) : RuntimeException(message)
internal class FlacMetadataLimit : RuntimeException()
internal class FlacMetadataBudget(var nodes: Int = 0, var textBytes: Long = 0)

/** Bounds and display budgets shared by a metadata block and its nested records. */
internal class FlacMetadataCursor(val reader: ByteReader, val start: Long, val end: Long,
                                  val budget: FlacMetadataBudget = FlacMetadataBudget()) {
    var pos = start
    val fields = mutableListOf<BoxField>()
    val children = mutableListOf<BoxNode>()
    val warnings = mutableListOf<String>()
    var state = ValueInterpretation.PARSED
    val remaining get() = end - pos

    fun requireBytes(length: Long) {
        if (length < 0 || length > remaining) throw FlacMetadataIssue("Truncated FLAC metadata field / FLAC 메타데이터 필드 잘림")
    }
    fun expand() {
        if (budget.nodes >= 4096) throw FlacMetadataLimit()
        budget.nodes++
    }
    fun field(name: String, value: String, offset: Long, length: Long, interpretation: ValueInterpretation = ValueInterpretation.PARSED) {
        fields += BoxField(name, value, offset, length, interpretation)
        if (interpretation == ValueInterpretation.UNPARSABLE || state == ValueInterpretation.PARSED) state = interpretation
    }
    fun uint(name: String, width: Int, littleEndian: Boolean = false): Long {
        requireBytes(width.toLong())
        val bytes = reader.readBytes(pos, width)
        var value = 0L
        for (i in 0 until width) value = (value shl 8) or (bytes[if (littleEndian) width - 1 - i else i].toLong() and 255)
        field(name, if (width == 8) value.toULong().toString() else value.toString(), pos, width.toLong())
        pos += width
        return value
    }
    fun raw(name: String, length: Long, interpretation: ValueInterpretation = ValueInterpretation.UNINTERPRETED) {
        requireBytes(length)
        val bytes = reader.readBytes(pos, minOf(length, 32).toInt())
        val preview = bytes.joinToString(" ") { "%02x".format(it.toInt() and 255) }
        field(name, "$preview${if (length > bytes.size) " …" else ""} ($length bytes)", pos, length, interpretation)
        pos += length
    }
    fun text(name: String, length: Long, charset: Charset = Charsets.UTF_8, nulPadded: Boolean = false, printableAscii: Boolean = false): String? {
        requireBytes(length)
        if (length > 65_536 || budget.textBytes + length > 1_048_576) {
            raw(name, length)
            warnings += "FLAC text inspection limit / FLAC 문자열 검사 한도"
            return null
        }
        budget.textBytes += length
        val bytes = reader.readBytes(pos, length.toInt())
        val nul = if (nulPadded) bytes.indexOf(0) else -1
        val textLength = if (nul >= 0) nul else bytes.size
        val invalidPadding = nul >= 0 && bytes.drop(nul).any { it != 0.toByte() }
        val invalidAscii = printableAscii && bytes.take(textLength).any { (it.toInt() and 255) !in 0x20..0x7e }
        val decoded = try {
            if (invalidPadding || invalidAscii) null else charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, 0, textLength)).toString()
        } catch (_: java.nio.charset.CharacterCodingException) { null }
        if (decoded == null) {
            raw(name, length, ValueInterpretation.UNPARSABLE)
            warnings += "Invalid FLAC text encoding or padding / FLAC 문자열 인코딩 또는 패딩 오류"
            return null
        }
        field(name, decoded, pos, length)
        pos += length
        return decoded
    }
    fun reserved(name: String, length: Int) {
        requireBytes(length.toLong())
        val bytes = reader.readBytes(pos, length)
        if (bytes.all { it == 0.toByte() }) { field(name, "0", pos, length.toLong()); pos += length }
        else raw(name, length.toLong())
    }
    fun reservedBits(name: String, bits: Int, offset: Long) = field(name, bits.toString(), offset, 1,
        if (bits == 0) ValueInterpretation.PARSED else ValueInterpretation.UNINTERPRETED)

    fun decode(type: String, offset: Long, header: Int, size: Long, inherited: List<String> = emptyList(), body: (FlacMetadataCursor) -> Unit): BoxNode {
        warnings += inherited
        if (inherited.isNotEmpty()) state = ValueInterpretation.UNPARSABLE
        try { body(this) }
        catch (e: FlacMetadataIssue) { warnings += e.message.orEmpty(); state = ValueInterpretation.UNPARSABLE }
        catch (_: FlacMetadataLimit) {
            warnings += "FLAC metadata inspection limit / FLAC 메타데이터 검사 한도"
            if (state == ValueInterpretation.PARSED) state = ValueInterpretation.UNINTERPRETED
        }
        if (remaining > 0) raw("unparsed_bytes", remaining, if (state == ValueInterpretation.UNPARSABLE) state else ValueInterpretation.UNINTERPRETED)
        children.forEach {
            val childState = detailInterpretation(it)
            if (childState == ValueInterpretation.UNPARSABLE || state == ValueInterpretation.PARSED) state = childState
        }
        return BoxNode(type, offset, header, size, fields = fields, children = children, warnings = warnings, interpretation = state)
    }
}

internal fun decodeFlacMetadata(reader: ByteReader, type: String, offset: Long, header: Int, size: Long,
                                warnings: List<String>, body: (FlacMetadataCursor) -> Unit): BoxNode {
    if (offset < 0 || header < 0 || size < header || offset > reader.length || size > reader.length - offset)
        return BoxNode(type, offset, header, size, warnings = warnings + "Invalid FLAC metadata range / FLAC 메타데이터 범위 오류", interpretation = ValueInterpretation.UNPARSABLE)
    return FlacMetadataCursor(reader, offset + header, offset + size).decode(type, offset, header, size, warnings, body)
}
