package com.multiviewer.parser.conformance.webp

import com.multiviewer.parser.ByteReader
import com.multiviewer.parser.conformance.ConformanceFinding
import com.multiviewer.parser.conformance.ConformanceStatus
import com.multiviewer.parser.conformance.ImageRuleBook.Companion.hex

/** A RIFF chunk; [paddedEnd] includes the pad byte of an odd [size]; [complete] = payload inside the parent. */
internal data class RiffChunk(val fourcc: String, val offset: Long, val size: Long, val complete: Boolean) {
    val payload: Long get() = offset + 8
    val paddedEnd: Long get() = offset + 8 + size + (size and 1)
    val label: String get() = "'$fourcc' @${hex(offset)}"
}

/** One 'ANMF' chunk with its 16-byte header fields and its Frame Data sub-chunks. */
internal data class AnmfFrame(val chunk: RiffChunk, val x: Long, val y: Long, val width: Long, val height: Long, val flags: Int, val subchunks: List<RiffChunk>)

/** Problems of a chunk list: chunk past its parent, a non-zero or missing pad byte, bytes too short for a chunk header. */
internal class RiffWalk(val chunks: List<RiffChunk>, val problems: List<Pair<String, LongRange>>, val end: Long, val capped: Boolean = false)

internal object WebpChunkScanner {
    const val MAX_CHUNKS = 100_000

    fun walk(reader: ByteReader, start: Long, limit: Long): RiffWalk {
        val chunks = ArrayList<RiffChunk>()
        val problems = ArrayList<Pair<String, LongRange>>()
        var pos = start
        while (pos < limit && chunks.size < MAX_CHUNKS) {
            if (limit - pos < 8) { problems += "${hex(pos)}의 ${limit - pos}바이트는 청크 헤더(8바이트)가 될 수 없음 / ${limit - pos} byte(s) at ${hex(pos)} are too short for a chunk header" to (pos until limit); pos = limit; break }
            val fourcc = reader.readFourCC(pos)
            val size = reader.readBytes(pos + 4, 4).let { b -> (0..3).fold(0L) { acc, i -> acc or ((b[i].toLong() and 0xFF) shl (8 * i)) } }
            if (size > limit - pos - 8) {
                chunks += RiffChunk(fourcc, pos, size, complete = false)
                problems += "'$fourcc' @${hex(pos)} 크기 $size 이(가) 상위 범위(${hex(limit)})를 넘음 / size $size runs past the enclosing range end ${hex(limit)}" to (pos until limit)
                pos = limit; break
            }
            val c = RiffChunk(fourcc, pos, size, complete = true)
            chunks += c
            if (size % 2 == 1L) {
                val padAt = pos + 8 + size
                if (padAt >= limit) problems += "${c.label}: 홀수 크기 패딩 바이트 없음 / odd size without its padding byte" to (pos until limit)
                else if (reader.readUInt8(padAt) != 0) problems += "${c.label}: 패딩 바이트 0x%02X ≠ 0 / padding byte 0x%02X ≠ 0".format(reader.readUInt8(padAt), reader.readUInt8(padAt)) to (padAt..padAt)
            }
            pos = minOf(c.paddedEnd, limit)
        }
        return RiffWalk(chunks, problems, pos, capped = pos < limit && chunks.size >= MAX_CHUNKS)
    }
}

/** VP8X fields (payload ≥ 10). */
internal data class Vp8x(val flagsByte: Int, val reserved24: Int, val canvasWidth: Long, val canvasHeight: Long) {
    val icc get() = flagsByte and 0x20 != 0
    val alpha get() = flagsByte and 0x10 != 0
    val exif get() = flagsByte and 0x08 != 0
    val xmp get() = flagsByte and 0x04 != 0
    val animation get() = flagsByte and 0x02 != 0
}

/** Bitstream header facts of one 'VP8 ' or 'VP8L' chunk; null fields = not readable. */
internal data class Bitstream(val chunk: RiffChunk, val width: Int?, val height: Int?)

internal class WebpContext(val reader: ByteReader) {
    val length: Long = reader.length
    val book = WebpRules.book
    val headerOk: Boolean = length >= 12 && reader.readFourCC(0) == "RIFF" && reader.readFourCC(8) == "WEBP"
    val riffSize: Long? = if (length >= 8) reader.readBytes(4, 4).let { b -> (0..3).fold(0L) { acc, i -> acc or ((b[i].toLong() and 0xFF) shl (8 * i)) } } else null
    val riffEnd: Long = riffSize?.let { 8 + it } ?: length
    val top: RiffWalk = if (headerOk) WebpChunkScanner.walk(reader, 12, minOf(riffEnd, length)) else RiffWalk(emptyList(), emptyList(), 12)
    val chunks: List<RiffChunk> get() = top.chunks

    fun of(fourcc: String): List<RiffChunk> = chunks.filter { it.fourcc == fourcc && it.complete }

    val vp8x: Vp8x? by lazy {
        val c = chunks.firstOrNull()?.takeIf { it.fourcc == "VP8X" && it.complete && it.size >= 10 } ?: return@lazy null
        val b = reader.readBytes(c.payload, 10)
        fun u24(i: Int) = (b[i].toLong() and 0xFF) or ((b[i + 1].toLong() and 0xFF) shl 8) or ((b[i + 2].toLong() and 0xFF) shl 16)
        Vp8x(b[0].toInt() and 0xFF, u24(1).toInt(), u24(4) + 1, u24(7) + 1)
    }

    val frames: List<AnmfFrame> by lazy {
        of("ANMF").filter { it.size >= 16 }.map { c ->
            val b = reader.readBytes(c.payload, 16)
            fun u24(i: Int) = (b[i].toLong() and 0xFF) or ((b[i + 1].toLong() and 0xFF) shl 8) or ((b[i + 2].toLong() and 0xFF) shl 16)
            val sub = WebpChunkScanner.walk(reader, c.payload + 16, c.payload + c.size)
            AnmfFrame(c, u24(0) * 2, u24(3) * 2, u24(6) + 1, u24(9) + 1, b[15].toInt() and 0xFF, sub.chunks)
        }
    }

    /** Sub-chunk problems inside every ANMF Frame Data. */
    val frameProblems: List<Pair<String, LongRange>> by lazy {
        of("ANMF").filter { it.size >= 16 }.flatMap { WebpChunkScanner.walk(reader, it.payload + 16, it.payload + it.size).problems }
    }

    /** Every complete chunk, top-level and inside ANMF frames. */
    val allChunks: List<RiffChunk> by lazy { chunks.filter { it.complete } + frames.flatMap { f -> f.subchunks.filter { it.complete } } }

    fun bitstream(c: RiffChunk): Bitstream = when (c.fourcc) {
        "VP8 " -> if (c.size < 10) Bitstream(c, null, null) else reader.readBytes(c.payload, 10).let { b ->
            Bitstream(c, ((b[6].toInt() and 0xFF) or ((b[7].toInt() and 0xFF) shl 8)) and 0x3FFF, ((b[8].toInt() and 0xFF) or ((b[9].toInt() and 0xFF) shl 8)) and 0x3FFF)
        }
        else -> if (c.size < 5) Bitstream(c, null, null) else reader.readBytes(c.payload, 5).let { b ->
            val bits = (b[1].toLong() and 0xFF) or ((b[2].toLong() and 0xFF) shl 8) or ((b[3].toLong() and 0xFF) shl 16) or ((b[4].toLong() and 0xFF) shl 24)
            Bitstream(c, (bits and 0x3FFF).toInt() + 1, ((bits shr 14) and 0x3FFF).toInt() + 1)
        }
    }

    fun finding(id: String, violations: List<String>, ranges: List<LongRange>, pass: List<String>): ConformanceFinding = book.finding(id, violations, ranges, pass)
    fun skip(id: String, status: ConformanceStatus, reason: String): ConformanceFinding = book.skip(id, status, reason)
    fun range(c: RiffChunk): LongRange = c.offset until minOf(c.paddedEnd, length)

    companion object {
        val BITSTREAMS = setOf("VP8 ", "VP8L")
    }
}
