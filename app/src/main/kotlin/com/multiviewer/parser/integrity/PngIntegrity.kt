package com.multiviewer.parser.integrity

import com.multiviewer.parser.ByteReader
import com.multiviewer.parser.integrity.CheckStatus.FAIL
import com.multiviewer.parser.integrity.CheckStatus.PASS
import com.multiviewer.parser.integrity.CheckStatus.WARN

object PngIntegrity {
    private data class Chunk(val type: String, val offset: Long, val dataLength: Long)

    fun check(reader: ByteReader): FormatCheckResult {
        val len = reader.length
        val items = mutableListOf<IntegrityCheckItem>()
        val chunks = mutableListOf<Chunk>()
        val crcProblems = mutableListOf<IntegrityCheckItem>()
        var truncated: IntegrityCheckItem? = null
        var iend: Chunk? = null
        var verified = 0
        var pos = 8L
        while (pos < len) {
            if (len - pos < 12) {
                truncated = IntegrityCheckItem(
                    "png.truncated", "Chunk layout", FAIL,
                    "${len - pos} trailing byte(s) at offset $pos are too short for a chunk (truncated)", pos, len - pos,
                )
                break
            }
            val dataLength = reader.readUInt32(pos)
            val type = reader.readFourCC(pos + 4)
            if (dataLength > len - pos - 12) {
                truncated = IntegrityCheckItem(
                    "png.truncated", "Chunk layout", FAIL,
                    "Chunk '$type' at offset $pos declares $dataLength data byte(s) but the file ends at $len (truncated)",
                    pos, len - pos,
                )
                chunks += Chunk(type, pos, dataLength)
                break
            }
            val stored = reader.readUInt32(pos + 8 + dataLength)
            val computed = crc32Of(reader, pos + 4, 4 + dataLength)
            verified++
            if (stored != computed) {
                val critical = type.first().isUpperCase()
                crcProblems += IntegrityCheckItem(
                    "png.crc", "Chunk CRC ($type)", if (critical) FAIL else WARN,
                    "CRC mismatch in ${if (critical) "critical" else "ancillary"} chunk '$type': stored ${hex32(stored)}, computed ${hex32(computed)}",
                    pos, 12 + dataLength,
                )
            }
            val chunk = Chunk(type, pos, dataLength)
            chunks += chunk
            pos += 12 + dataLength
            if (type == "IEND") { iend = chunk; break }
        }

        val first = chunks.firstOrNull()
        items += if (first != null && first.type == "IHDR" && first.dataLength == 13L) {
            IntegrityCheckItem("png.ihdr", "Header chunk (IHDR)", PASS, "IHDR is the first chunk", first.offset, 25)
        } else {
            IntegrityCheckItem(
                "png.ihdr", "Header chunk (IHDR)", FAIL,
                if (first == null) "No chunks after the signature" else "First chunk is '${first.type}' (${first.dataLength} bytes); expected IHDR with 13 bytes",
                first?.offset, first?.let { 12 + it.dataLength },
            )
        }
        items += crcProblems.ifEmpty { listOf(IntegrityCheckItem("png.crc", "Chunk CRCs", PASS, "All $verified chunk CRC(s) match")) }
        truncated?.let { items += it }

        val idat = chunks.indices.filter { chunks[it].type == "IDAT" }
        items += when {
            idat.isEmpty() -> IntegrityCheckItem("png.idat", "Image data (IDAT)", FAIL, "No IDAT chunk")
            idat.last() - idat.first() + 1 != idat.size -> {
                val interrupter = chunks.subList(idat.first(), idat.last()).first { it.type != "IDAT" }
                IntegrityCheckItem(
                    "png.idat", "Image data (IDAT)", FAIL,
                    "IDAT chunks are not consecutive: '${interrupter.type}' at offset ${interrupter.offset} interrupts them",
                    interrupter.offset, 12 + interrupter.dataLength,
                )
            }
            else -> IntegrityCheckItem("png.idat", "Image data (IDAT)", PASS, "${idat.size} consecutive IDAT chunk(s)")
        }

        val end = iend
        if (end == null) {
            items += IntegrityCheckItem("png.iend", "End chunk (IEND)", FAIL, "No IEND chunk: the file ends at $len before the image is terminated")
        } else {
            items += IntegrityCheckItem("png.iend", "End chunk (IEND)", PASS, "IEND at offset ${end.offset}", end.offset, 12)
            val iendEnd = end.offset + 12 + end.dataLength
            items += if (iendEnd == len) {
                IntegrityCheckItem("png.trailing", "Data after IEND", PASS, "No data after IEND")
            } else {
                IntegrityCheckItem("png.trailing", "Data after IEND", WARN, "${len - iendEnd} byte(s) after IEND", iendEnd, len - iendEnd)
            }
        }

        val size = if (first?.type == "IHDR" && first.dataLength >= 8) {
            reader.readUInt32(16).toInt() to reader.readUInt32(20).toInt()
        } else null
        return FormatCheckResult(items, size?.first, size?.second)
    }
}
