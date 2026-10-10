package com.multiviewer.parser.integrity

import com.multiviewer.parser.ByteReader
import com.multiviewer.parser.integrity.CheckStatus.FAIL
import com.multiviewer.parser.integrity.CheckStatus.PASS
import com.multiviewer.parser.integrity.CheckStatus.SKIP
import com.multiviewer.parser.integrity.CheckStatus.WARN

object WebpIntegrity {
    private data class Chunk(val type: String, val offset: Long, val size: Long)

    fun check(reader: ByteReader): FormatCheckResult {
        val len = reader.length
        val items = mutableListOf<IntegrityCheckItem>()
        val declaredEnd = reader.readBytes(4, 4).u32le(0) + 8
        items += when {
            declaredEnd == len -> IntegrityCheckItem("webp.riff", "RIFF size", PASS, "RIFF size matches the file size ($len)", 4, 4)
            declaredEnd < len -> IntegrityCheckItem("webp.riff", "RIFF size", WARN,
                "RIFF declares $declaredEnd byte(s); ${len - declaredEnd} extra byte(s) follow", declaredEnd, len - declaredEnd)
            else -> IntegrityCheckItem("webp.riff", "RIFF size", FAIL,
                "RIFF declares $declaredEnd byte(s) but the file has $len (truncated by ${declaredEnd - len})", 4, 4)
        }

        val limit = minOf(len, declaredEnd)
        val chunks = mutableListOf<Chunk>()
        var problem: IntegrityCheckItem? = null
        var pos = 12L
        while (pos + 8 <= limit) {
            val type = reader.readFourCC(pos)
            val size = reader.readBytes(pos + 4, 4).u32le(0)
            if (pos + 8 + size > limit) {
                problem = IntegrityCheckItem("webp.chunks", "Chunk layout", FAIL,
                    "Chunk '$type' at offset $pos declares $size byte(s) but only ${limit - pos - 8} remain (truncated)", pos, limit - pos)
                break
            }
            chunks += Chunk(type, pos, size)
            pos += 8 + size + (size and 1L)
        }
        if (problem == null && pos < limit) {
            problem = IntegrityCheckItem("webp.chunks", "Chunk layout", WARN,
                "${limit - pos} stray byte(s) at offset $pos are too short for a chunk header", pos, limit - pos)
        }
        items += problem ?: IntegrityCheckItem("webp.chunks", "Chunk layout", PASS, "${chunks.size} chunk(s), all within the RIFF payload")

        val images = chunks.filter { it.type == "VP8 " || it.type == "VP8L" }
        val frameCount = chunks.count { it.type == "ANMF" }
        items += when {
            frameCount > 0 -> IntegrityCheckItem("webp.image", "Image data", PASS, "Animated: $frameCount frame(s)")
            images.size == 1 -> IntegrityCheckItem("webp.image", "Image data", PASS,
                "${images[0].type.trim()} bitstream at offset ${images[0].offset}", images[0].offset, 8 + images[0].size)
            images.isEmpty() -> IntegrityCheckItem("webp.image", "Image data", FAIL, "No VP8/VP8L image data chunk")
            else -> IntegrityCheckItem("webp.image", "Image data", WARN,
                "${images.size} image data chunks; a still image has one", images[1].offset, 8 + images[1].size)
        }

        val bitstream = images.firstOrNull()?.let { bitstreamDimensions(reader, it.offset + 8, it.type, it.size) }
        val vp8x = chunks.firstOrNull()?.takeIf { it.type == "VP8X" && it.size >= 10 }
        val canvas = vp8x?.let { reader.readBytes(it.offset + 12, 6).let { b -> (b.u24le(0) + 1) to (b.u24le(3) + 1) } }
        items += when {
            vp8x == null -> IntegrityCheckItem("webp.canvas", "Canvas vs bitstream size", SKIP, "Simple format (no VP8X canvas)")
            frameCount > 0 -> IntegrityCheckItem("webp.canvas", "Canvas vs bitstream size", SKIP, "Animated: frames may be smaller than the canvas")
            canvas == null || bitstream == null -> IntegrityCheckItem("webp.canvas", "Canvas vs bitstream size", SKIP, "The bitstream size is not readable")
            canvas == bitstream -> IntegrityCheckItem("webp.canvas", "Canvas vs bitstream size", PASS,
                "Canvas ${canvas.first}x${canvas.second} matches the bitstream", vp8x.offset, 18)
            else -> IntegrityCheckItem("webp.canvas", "Canvas vs bitstream size", WARN,
                "Canvas ${canvas.first}x${canvas.second} differs from bitstream ${bitstream.first}x${bitstream.second}", vp8x.offset, 18)
        }
        val declared = canvas ?: bitstream
        return FormatCheckResult(items, declared?.first, declared?.second)
    }

    internal fun bitstreamDimensions(reader: ByteReader, dataOffset: Long, type: String, size: Long): Pair<Int, Int>? {
        val need = if (type == "VP8 ") 10 else 5
        if (size < need || dataOffset + need > reader.length) return null
        val b = reader.readBytes(dataOffset, need)
        return when (type) {
            "VP8 " -> if (b.u8(3) == 0x9D && b.u8(4) == 0x01 && b.u8(5) == 0x2A) (b.u16le(6) and 0x3FFF) to (b.u16le(8) and 0x3FFF) else null
            "VP8L" -> if (b.u8(0) != 0x2F) null else {
                val bits = b.u32le(1)
                ((bits and 0x3FFF) + 1).toInt() to (((bits shr 14) and 0x3FFF) + 1).toInt()
            }
            else -> null
        }
    }
}
