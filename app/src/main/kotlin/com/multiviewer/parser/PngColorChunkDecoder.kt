package com.multiviewer.parser

internal data class PngColorContext(val colorType: Int? = null, val bitDepth: Int? = null, val paletteEntries: Int? = null)

internal fun decodePngColorChunk(reader: ByteReader, type: String, offset: Long, length: Long, totalSize: Long, context: PngColorContext): BoxNode? {
    if (type !in setOf("tRNS", "bKGD", "sBIT", "hIST", "sPLT")) return null
    return PngDetailFields(reader, type, offset, length, totalSize).finish { c ->
        if (type == "sPLT") { decodeSuggestedPalette(c); return@finish }
        if (type == "hIST") {
            if (length !in 2L..512L || length % 2 != 0L) throw PngDetailIssue("Invalid histogram length / PNG 히스토그램 길이 오류")
            context.paletteEntries?.let { c.requireLength(it * 2L) }
            repeat((length / 2).toInt()) { c.uint("frequency[$it]", 2) }
            if (context.paletteEntries == null) {
                c.state = ValueInterpretation.UNINTERPRETED
                c.warnings += "Missing valid PNG palette context / 유효한 PNG 팔레트 정보 없음"
            }
            return@finish
        }
        val color = context.colorType
        if (color == null) {
            c.raw("raw_payload", length)
            c.warnings += "Missing valid PNG color context / 유효한 PNG 색상 타입 정보 없음"
            return@finish
        }
        when (type) {
            "tRNS" -> when (color) {
                0 -> { c.requireLength(2); c.uint("gray_sample", 2) }
                2 -> { c.requireLength(6); for (name in listOf("red_sample", "green_sample", "blue_sample")) c.uint(name, 2) }
                3 -> {
                    if (length !in 1L..256L || context.paletteEntries?.let { length > it } == true)
                        throw PngDetailIssue("Invalid palette transparency length / PNG 팔레트 투명도 길이 오류")
                    repeat(length.toInt()) { c.uint("alpha[$it]", 1) }
                }
                else -> throw PngDetailIssue("tRNS forbidden for alpha color types / 알파 색상 타입에 tRNS 사용 불가")
            }
            "bKGD" -> when (color) {
                0, 4 -> { c.requireLength(2); c.uint("gray_sample", 2) }
                2, 6 -> { c.requireLength(6); for (name in listOf("red_sample", "green_sample", "blue_sample")) c.uint(name, 2) }
                3 -> {
                    c.requireLength(1)
                    val index = c.uint("palette_index", 1)
                    if (context.paletteEntries?.let { index >= it } == true)
                        throw PngDetailIssue("Background palette index out of range / 배경색 팔레트 인덱스 범위 오류")
                }
            }
            "sBIT" -> {
                val names = when (color) {
                    0 -> listOf("gray_bits")
                    2, 3 -> listOf("red_bits", "green_bits", "blue_bits")
                    4 -> listOf("gray_bits", "alpha_bits")
                    else -> listOf("red_bits", "green_bits", "blue_bits", "alpha_bits")
                }
                c.requireLength(names.size.toLong())
                val maxBits = if (color == 3) 8 else context.bitDepth!!
                for (name in names) {
                    val bits = c.uint(name, 1)
                    if (bits !in 1L..maxBits.toLong()) throw PngDetailIssue("Invalid significant bit count / 유효 비트 수 오류")
                }
            }
        }
    }
}

private fun decodeSuggestedPalette(c: PngDetailFields) {
    val head = c.reader.readBytes(c.pos, minOf(c.length, 80).toInt())
    val nameLength = head.indexOf(0)
    if (nameLength !in 1..79) throw PngDetailIssue("Invalid suggested palette name / 권장 팔레트 이름 오류")
    val name = String(head, 0, nameLength, Charsets.ISO_8859_1)
    if (name.any { it.code !in 0x20..0x7e && it.code !in 0xa1..0xff } || name.startsWith(' ') || name.endsWith(' ') || "  " in name)
        throw PngDetailIssue("Invalid suggested palette name / 권장 팔레트 이름 오류")
    c.field("palette_name", name, c.pos, nameLength.toLong())
    c.pos += nameLength + 1
    val depth = c.uint("sample_depth", 1).toInt()
    if (depth != 8 && depth != 16) throw PngDetailIssue("Invalid suggested palette depth / 권장 팔레트 비트 깊이 오류")
    val width = depth / 8
    val entrySize = width * 4 + 2
    if ((c.end - c.pos) % entrySize != 0L) throw PngDetailIssue("Truncated suggested palette entry / 권장 팔레트 항목 잘림")
    val count = (c.end - c.pos) / entrySize
    repeat(minOf(count, 4096).toInt()) {
        val at = c.pos
        val fields = mutableListOf<BoxField>()
        for (name in listOf("red", "green", "blue", "alpha", "frequency")) {
            val bytes = if (name == "frequency") 2 else width
            val value = if (bytes == 2) c.reader.readUInt16(c.pos) else c.reader.readUInt8(c.pos)
            fields += BoxField(name, value.toString(), c.pos, bytes.toLong())
            c.pos += bytes
        }
        c.children += BoxNode("PaletteEntry", at, 0, entrySize.toLong(), fields = fields)
    }
    if (count > 4096) c.warnings += "Suggested palette inspection limit / 권장 팔레트 검사 한도"
}
