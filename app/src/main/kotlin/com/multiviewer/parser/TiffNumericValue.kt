package com.multiviewer.parser

/** Numeric TIFF types formerly shown as opaque bytes. Caller verifies the complete payload range. */
internal fun tiffNumericField(
    reader: ByteReader,
    name: String,
    type: Int,
    count: Long,
    offset: Long,
    littleEndian: Boolean,
    knownTag: Boolean,
): BoxField? {
    val width = when (type) { 6 -> 1; 11 -> 4; 12 -> 8; else -> return null }
    if (count == 0L) return BoxField(name, "빈 값 / Empty value", offset, 0, ValueInterpretation.UNPARSABLE)
    val shown = count.coerceAtMost(256).toInt()
    val bytes = reader.readBytes(offset, shown * width)
    val display = (0 until shown).joinToString(", ") { i ->
        if (type == 6) bytes[i].toString() else {
            var bits = 0L
            for (j in 0 until width) {
                val index = i * width + if (littleEndian) width - j - 1 else j
                bits = (bits shl 8) or (bytes[index].toLong() and 0xff)
            }
            if (type == 11) Float.fromBits(bits.toInt()).toString() else Double.fromBits(bits).toString()
        }
    } + if (count > shown) " … ($shown/$count 표시 / shown; 나머지 미해석 / remainder uninterpreted)" else ""
    return BoxField(name, display, offset, count * width,
        if (knownTag && count <= shown) ValueInterpretation.PARSED else ValueInterpretation.UNINTERPRETED)
}
