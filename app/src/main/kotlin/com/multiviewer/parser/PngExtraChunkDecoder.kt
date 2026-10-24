package com.multiviewer.parser

import java.math.BigDecimal

internal class PngDetailIssue(message: String) : RuntimeException(message)
internal class PngDetailFields(val reader: ByteReader, val type: String, val offset: Long, val length: Long, val totalSize: Long) {
    val start = offset + 8
    val end = start + length
    var pos = start
    val fields = mutableListOf<BoxField>()
    val children = mutableListOf<BoxNode>()
    val warnings = mutableListOf<String>()
    var state = ValueInterpretation.PARSED
    fun requireLength(expected: Long) {
        if (length != expected) throw PngDetailIssue("Invalid $type payload length: $length, expected $expected / $type 페이로드 길이 오류")
    }
    fun requireBytes(n: Long) {
        if (n < 0 || n > end - pos) throw PngDetailIssue("Truncated $type field / $type 필드 잘림")
    }
    fun field(name: String, value: String, at: Long, size: Long, status: ValueInterpretation = ValueInterpretation.PARSED) {
        fields += BoxField(name, value, at, size, status)
        if (status == ValueInterpretation.UNPARSABLE || state == ValueInterpretation.PARSED) state = status
    }
    fun uint(name: String, width: Int, known: LongRange? = null): Long {
        requireBytes(width.toLong())
        var value = 0L
        reader.readBytes(pos, width).forEach { value = (value shl 8) or (it.toLong() and 255) }
        field(name, value.toString(), pos, width.toLong(), if (known == null || value in known) ValueInterpretation.PARSED else ValueInterpretation.UNINTERPRETED)
        pos += width
        return value
    }
    fun raw(name: String, n: Long, status: ValueInterpretation = ValueInterpretation.UNINTERPRETED) {
        requireBytes(n)
        val preview = reader.readBytes(pos, minOf(n, 32).toInt()).joinToString(" ") { "%02x".format(it.toInt() and 255) }
        field(name, "$preview${if (n > 32) " …" else ""} ($n bytes)", pos, n, status)
        pos += n
    }
    fun finish(block: (PngDetailFields) -> Unit): BoxNode {
        try { block(this) }
        catch (e: PngDetailIssue) { warnings += e.message.orEmpty(); state = ValueInterpretation.UNPARSABLE }
        if (pos < end) raw("unparsed_bytes", end - pos, if (state == ValueInterpretation.UNPARSABLE) state else ValueInterpretation.UNINTERPRETED)
        return BoxNode(type, offset, 8, totalSize, fields = fields, children = children, warnings = warnings, interpretation = state)
    }
}

internal fun decodePngExtraChunk(reader: ByteReader, type: String, offset: Long, length: Long, totalSize: Long): BoxNode? {
    if (type !in setOf("acTL", "fcTL", "fdAT", "cICP", "mDCV", "cLLI", "IEND")) return null
    return PngDetailFields(reader, type, offset, length, totalSize).finish { c ->
        when (type) {
            "acTL" -> {
                c.requireLength(8)
                c.uint("num_frames", 4)
                c.uint("num_plays", 4)
            }
            "fcTL" -> {
                c.requireLength(26)
                for (name in listOf("sequence_number", "width", "height", "x_offset", "y_offset")) c.uint(name, 4)
                c.uint("delay_num", 2)
                val denominatorOffset = c.pos
                val denominator = c.uint("delay_den", 2)
                c.field("effective_delay_den", (if (denominator == 0L) 100 else denominator).toString(), denominatorOffset, 2)
                c.uint("dispose_op", 1, 0L..2L)
                c.uint("blend_op", 1, 0L..1L)
            }
            "fdAT" -> {
                c.uint("sequence_number", 4)
                if (c.pos < c.end) c.raw("frame_data", c.end - c.pos)
            }
            "cICP" -> {
                c.requireLength(4)
                c.uint("color_primaries", 1)
                c.uint("transfer_characteristics", 1)
                c.uint("matrix_coefficients", 1, 0L..0L)
                c.uint("video_full_range_flag", 1, 0L..1L)
            }
            "mDCV" -> {
                c.requireLength(24)
                for (name in listOf("red_x", "red_y", "green_x", "green_y", "blue_x", "blue_y", "white_x", "white_y")) {
                    val at = c.pos
                    val raw = c.uint("${name}_raw", 2)
                    c.field(name, BigDecimal.valueOf(raw).divide(BigDecimal.valueOf(50_000)).stripTrailingZeros().toPlainString(), at, 2)
                }
                for (name in listOf("max_luminance", "min_luminance")) {
                    val at = c.pos
                    val raw = c.uint("${name}_raw", 4)
                    c.field("${name}_cd_m2", BigDecimal.valueOf(raw, 4).toPlainString(), at, 4)
                }
            }
            "cLLI" -> {
                c.requireLength(8)
                for (name in listOf("max_content_light_level", "max_frame_average_light_level")) {
                    val at = c.pos
                    val raw = c.uint("${name}_raw", 4)
                    c.field("${name}_cd_m2", if (raw == 0L) "Unknown / 알 수 없음" else BigDecimal.valueOf(raw, 4).toPlainString(), at, 4)
                }
            }
            "IEND" -> {
                c.requireLength(0)
                c.field("image_end", "true", offset + 4, 4)
            }
        }
    }
}
