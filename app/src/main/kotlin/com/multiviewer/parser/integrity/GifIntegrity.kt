package com.multiviewer.parser.integrity

import com.multiviewer.parser.ByteReader
import com.multiviewer.parser.integrity.CheckStatus.FAIL
import com.multiviewer.parser.integrity.CheckStatus.PASS
import com.multiviewer.parser.integrity.CheckStatus.WARN

object GifIntegrity {
    fun check(reader: ByteReader): FormatCheckResult {
        val len = reader.length
        val items = mutableListOf<IntegrityCheckItem>()
        if (len < 13) {
            return FormatCheckResult(listOf(IntegrityCheckItem("gif.lsd", "Logical screen descriptor", FAIL,
                "The file is too short ($len bytes) for a GIF header and screen descriptor", 0, len)))
        }
        val lsd = reader.readBytes(6, 7)
        val width = lsd.u16le(0)
        val height = lsd.u16le(2)
        val flags = lsd.u8(4)
        val gct = if (flags and 0x80 != 0) 3L * (1 shl ((flags and 7) + 1)) else 0L
        var pos = 13 + gct
        if (pos > len) {
            items += IntegrityCheckItem("gif.lsd", "Logical screen descriptor", FAIL,
                "The global color table needs $gct byte(s) but the file ends at $len", 13, len - 13)
            return FormatCheckResult(items, width, height)
        }
        items += IntegrityCheckItem("gif.lsd", "Logical screen descriptor", PASS,
            "${width}x$height, global color table $gct byte(s)", 6, 7 + gct)

        var frames = 0
        var trailerAt: Long? = null
        var problem: IntegrityCheckItem? = null
        loop@ while (pos < len) {
            when (val introducer = reader.readUInt8(pos)) {
                0x21 -> {
                    val next = if (pos + 2 <= len) skipSubBlocks(reader, pos + 2, len) else null
                    if (next == null) { problem = chainItem("extension block", pos, len); break@loop }
                    pos = next
                }
                0x2C -> {
                    if (pos + 11 > len) { problem = chainItem("image descriptor", pos, len); break@loop }
                    val imageFlags = reader.readUInt8(pos + 9)
                    val lct = if (imageFlags and 0x80 != 0) 3L * (1 shl ((imageFlags and 7) + 1)) else 0L
                    val dataStart = pos + 10 + lct + 1 // + LZW minimum code size byte
                    val next = if (dataStart <= len) skipSubBlocks(reader, dataStart, len) else null
                    if (next == null) { problem = chainItem("image data of frame ${frames + 1}", pos, len); break@loop }
                    frames++
                    pos = next
                }
                0x3B -> { trailerAt = pos; pos += 1; break@loop }
                else -> {
                    problem = IntegrityCheckItem("gif.blocks", "Block structure", FAIL,
                        "Unexpected byte 0x%02x at offset %d where a block introducer was expected".format(introducer, pos), pos, 1)
                    break@loop
                }
            }
        }

        items += if (frames > 0) {
            IntegrityCheckItem("gif.frames", "Image frames", PASS, "$frames frame(s)")
        } else {
            IntegrityCheckItem("gif.frames", "Image frames", FAIL, "No complete image frame found")
        }
        items += problem ?: IntegrityCheckItem("gif.blocks", "Block structure", PASS, "All blocks and sub-block chains terminate")
        val trailer = trailerAt
        if (trailer == null) {
            items += IntegrityCheckItem("gif.trailer", "Trailer (0x3B)", FAIL,
                "No trailer byte: the file ends at offset $len before the GIF is terminated (truncated)")
        } else {
            items += IntegrityCheckItem("gif.trailer", "Trailer (0x3B)", PASS, "Trailer at offset $trailer", trailer, 1)
            items += if (pos == len) {
                IntegrityCheckItem("gif.trailing", "Data after trailer", PASS, "No data after the trailer")
            } else {
                IntegrityCheckItem("gif.trailing", "Data after trailer", WARN, "${len - pos} byte(s) after the trailer", pos, len - pos)
            }
        }
        return FormatCheckResult(items, width, height)
    }

    /** Offset just past the zero-length terminator, or null if the chain runs past [end]. */
    private fun skipSubBlocks(reader: ByteReader, start: Long, end: Long): Long? {
        var p = start
        while (p < end) {
            val size = reader.readUInt8(p)
            if (size == 0) return p + 1
            p += 1 + size
        }
        return null
    }

    private fun chainItem(what: String, pos: Long, len: Long) = IntegrityCheckItem(
        "gif.blocks", "Block structure", FAIL,
        "The $what starting at offset $pos runs past the end of the file ($len bytes, truncated)", pos, len - pos,
    )
}
