package com.multiviewer.parser.conformance.isobmff

import com.multiviewer.parser.ByteReader

/** One box header as found in the raw bytes. [problem] is non-null when the header is invalid. */
data class BmffBox(
    val type: String, val offset: Long, val headerSize: Int, val size: Long,
    val rawSize: Long,               // 0 = "to end", 1 = largesize
    val problem: String?,            // null, or why the header is invalid (too small / overruns parent)
    val children: List<BmffBox>,
    val parent: String?,             // parent type, null at top level
) {
    val end: Long get() = offset + size
    val payload: Long get() = offset + headerSize
}

/**
 * [trailingBytes]/[trailingOffset]: bytes after the last valid top-level box up to EOF (non-box data or < 8 bytes).
 * If the top level ends on a problem box (invalid header), the problem is reported on that box instead, so
 * trailingBytes = 0 and trailingOffset = null.
 */
data class BmffTree(
    val top: List<BmffBox>, val trailingBytes: Long, val trailingOffset: Long?,
    val leftovers: List<BmffLeftover> = emptyList(),
)

/**
 * Bytes inside container [container] (box at [containerOffset]) from the first byte that is not a box header
 * ([offset]) to the container's end. Not recorded inside `meta` (its payload may start with a version/flags word).
 */
data class BmffLeftover(val container: String, val containerOffset: Long, val offset: Long, val bytes: Long)

val BMFF_CONTAINERS: Set<String> = setOf(
    "moov", "trak", "mdia", "minf", "dinf", "stbl", "edts", "udta", "mvex", "moof", "traf", "mfra", "tref",
)

/** Bounded, viewer-independent ISO-BMFF box walker: every level is limited to its parent's payload range. */
object IsoBmffBoxTree {
    private const val MAX_DEPTH = 12

    fun walk(reader: ByteReader): BmffTree {
        val length = reader.length
        val leftovers = ArrayList<BmffLeftover>()
        val level = walkLevel(reader, 0L, length, null, -1L, 0, leftovers)
        val trailing = if (level.endedOnProblem) 0L else length - level.validEnd
        return BmffTree(level.boxes, trailing, if (trailing > 0) level.validEnd else null, leftovers)
    }

    private class Level(val boxes: List<BmffBox>, val validEnd: Long, val endedOnProblem: Boolean)

    private fun validType(t: ByteArray) = t.size == 4 && t.all { val v = it.toInt() and 0xFF; v in 0x20..0x7E || v == 0xA9 }

    /**
     * Walks [start, limit). A nested level (not top, not `meta`) that stops before [limit] without a problem box
     * — a non-printable child type or fewer than 8 bytes left — records the rest of the parent as a leftover.
     */
    private fun walkLevel(
        reader: ByteReader, start: Long, limit: Long, parent: String?, parentOffset: Long, depth: Int,
        leftovers: MutableList<BmffLeftover>,
    ): Level {
        val boxes = ArrayList<BmffBox>()
        var pos = start
        var validEnd = start
        var problemEnd = false
        while (pos + 8 <= limit) {
            val rawSize = reader.readUInt32(pos)
            val typeBytes = reader.readBytes(pos + 4, 4)
            if (!validType(typeBytes)) break
            val type = String(typeBytes, Charsets.ISO_8859_1)
            var header = 8
            var size = rawSize
            var problem: String? = null
            if (rawSize == 1L) {
                if (pos + 16 > limit) {
                    // The 64-bit largesize field does not fit in the parent's range: record a problem box.
                    boxes += BmffBox(type, pos, 8, limit - pos, rawSize, "largesize 헤더가 상위 범위를 넘음", emptyList(), parent)
                    problemEnd = true; break
                }
                header = 16
                size = reader.readUInt64(pos + 8)
            } else if (rawSize == 0L) {
                size = limit - pos
            }
            if (size < header) problem = "박스 크기($size)가 헤더 크기($header)보다 작음"
            else if (size < 0 || size > limit - pos) problem = "박스 크기($size)가 상위 범위를 넘음"
            if (problem != null) {
                boxes += BmffBox(type, pos, header, size, rawSize, problem, emptyList(), parent)
                problemEnd = true
                break
            }
            val end = pos + size
            val payload = pos + header
            val children = if (depth < MAX_DEPTH) {
                when {
                    type in BMFF_CONTAINERS -> walkLevel(reader, payload, end, type, pos, depth + 1, leftovers).boxes
                    type == "meta" -> {
                        val childStart = if (looksLikeBoxHeader(reader, payload, end)) payload else payload + 4
                        if (childStart <= end) walkLevel(reader, childStart, end, type, pos, depth + 1, leftovers).boxes else emptyList()
                    }
                    else -> emptyList()
                }
            } else emptyList()
            boxes += BmffBox(type, pos, header, size, rawSize, null, children, parent)
            pos = end
            validEnd = end
        }
        if (parent != null && parent != "meta" && !problemEnd && validEnd < limit)
            leftovers += BmffLeftover(parent, parentOffset, validEnd, limit - validEnd)
        return Level(boxes, validEnd, problemEnd)
    }

    private fun looksLikeBoxHeader(reader: ByteReader, at: Long, end: Long): Boolean {
        if (at + 8 > end) return false
        return reader.readUInt32(at) >= 8 && validType(reader.readBytes(at + 4, 4))
    }
}
