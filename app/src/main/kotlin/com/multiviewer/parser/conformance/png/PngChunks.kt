package com.multiviewer.parser.conformance.png

import com.multiviewer.parser.ByteReader
import com.multiviewer.parser.conformance.ConformanceFinding
import com.multiviewer.parser.conformance.ConformanceStatus
import com.multiviewer.parser.conformance.ImageRuleBook.Companion.hex

/** One chunk as laid out in the file; [complete] = header, data and CRC are all inside the file. */
internal data class PngChunk(val index: Int, val offset: Long, val length: Long, val type: String, val complete: Boolean) {
    val dataStart: Long get() = offset + 8
    val end: Long get() = offset + 12 + length
    val label: String get() = "$type @${hex(offset)}"
}

/**
 * Bounded chunk walk from offset 8 up to and including the first IEND. [problem] = why the walk stopped early (a chunk
 * cut off by EOF, a length above 2^31-1); [capped] = stopped at [PngChunkScanner.MAX_CHUNKS] (not a violation);
 * [end] = first byte after the last complete chunk.
 */
internal class PngScan(val chunks: List<PngChunk>, val end: Long, val iend: PngChunk?, val problem: String?, val problemAt: Long?, val capped: Boolean = false)

internal object PngChunkScanner {
    const val MAX_CHUNKS = 200_000
    const val MAX_LENGTH = 0x7FFF_FFFFL
    val SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    fun scan(reader: ByteReader): PngScan {
        val len = reader.length
        val chunks = ArrayList<PngChunk>()
        var pos = 8L
        while (pos < len) {
            if (chunks.size >= MAX_CHUNKS) return PngScan(chunks, pos, null, null, null, capped = true)
            if (len - pos < 12) return PngScan(chunks, pos, null,
                "${hex(pos)}의 ${len - pos}바이트는 청크(최소 12바이트)가 될 수 없음 / ${len - pos} byte(s) at ${hex(pos)} are too short for a chunk (12 bytes minimum)", pos)
            val length = reader.readUInt32(pos)
            val type = reader.readFourCC(pos + 4)
            if (length > MAX_LENGTH) return PngScan(chunks, pos, null,
                "'$type' @${hex(pos)} 길이 $length > 2^31-1 / length $length exceeds 2^31-1", pos)
            if (length > len - pos - 12) {
                chunks += PngChunk(chunks.size, pos, length, type, complete = false)
                return PngScan(chunks, pos, null,
                    "'$type' @${hex(pos)} 데이터 ${length}바이트+CRC가 파일 끝(${hex(len)})을 넘음 / declares $length data bytes + CRC past EOF ${hex(len)}", pos)
            }
            val chunk = PngChunk(chunks.size, pos, length, type, complete = true)
            chunks += chunk
            pos = chunk.end
            if (type == "IEND") return PngScan(chunks, pos, chunk, null, null)
        }
        return PngScan(chunks, pos, null, null, null)
    }
}

/** IHDR fields (only when the first chunk is a complete 13-byte IHDR). */
internal data class PngHeader(val width: Long, val height: Long, val bitDepth: Int, val colorType: Int, val compression: Int, val filter: Int, val interlace: Int) {
    /** Sample depth: 8 for indexed colour, the bit depth otherwise (11.3.2.4). */
    val sampleDepth: Int get() = if (colorType == 3) 8 else bitDepth

    companion object {
        /** Table 12. */
        val ALLOWED_DEPTHS = mapOf(0 to setOf(1, 2, 4, 8, 16), 2 to setOf(8, 16), 3 to setOf(1, 2, 4, 8), 4 to setOf(8, 16), 6 to setOf(8, 16))
    }
}

/** Shared, read-only state for the PNG rule groups. */
internal class PngContext(val reader: ByteReader, val scan: PngScan) {
    val length: Long = reader.length
    val chunks: List<PngChunk> get() = scan.chunks
    val complete: List<PngChunk> = scan.chunks.filter { it.complete }
    val book = PngRules.book

    fun of(type: String): List<PngChunk> = complete.filter { it.type == type }
    fun first(type: String): PngChunk? = complete.firstOrNull { it.type == type }

    val header: PngHeader? by lazy {
        val c = chunks.firstOrNull()?.takeIf { it.type == "IHDR" && it.complete && it.length == 13L } ?: return@lazy null
        val b = reader.readBytes(c.dataStart, 13)
        fun u32(i: Int) = ((b[i].toLong() and 0xFF) shl 24) or ((b[i + 1].toLong() and 0xFF) shl 16) or ((b[i + 2].toLong() and 0xFF) shl 8) or (b[i + 3].toLong() and 0xFF)
        PngHeader(u32(0), u32(4), b[8].toInt() and 0xFF, b[9].toInt() and 0xFF, b[10].toInt() and 0xFF, b[11].toInt() and 0xFF, b[12].toInt() and 0xFF)
    }

    /** IHDR whose colour type / bit depth combination is valid (needed by every colour-dependent rule). */
    val validHeader: PngHeader? by lazy { header?.takeIf { PngHeader.ALLOWED_DEPTHS[it.colorType]?.contains(it.bitDepth) == true } }

    /** Entries of the first complete PLTE whose length is a multiple of 3 (null when absent or malformed). */
    val paletteEntries: Int? by lazy { first("PLTE")?.takeIf { it.length % 3 == 0L && it.length in 3L..768L }?.let { (it.length / 3).toInt() } }

    val firstIdat: Int? by lazy { complete.firstOrNull { it.type == "IDAT" }?.index }
    val lastIdat: Int? by lazy { complete.lastOrNull { it.type == "IDAT" }?.index }

    /** Up to [max] bytes of the chunk's data (the caller decides what a short read means). */
    fun data(c: PngChunk, max: Int = PAYLOAD_CAP): ByteArray = reader.readBytes(c.dataStart, minOf(c.length, max.toLong()).toInt())

    fun finding(id: String, violations: List<String>, ranges: List<LongRange>, pass: List<String>): ConformanceFinding =
        book.finding(id, violations, ranges, pass)

    fun skip(id: String, status: ConformanceStatus, reason: String): ConformanceFinding = book.skip(id, status, reason)

    fun range(c: PngChunk): LongRange = c.offset until minOf(c.end, length)

    companion object {
        /** Largest chunk payload read into memory (text, sPLT); larger chunks are reported NOT_CHECKED where it matters. */
        const val PAYLOAD_CAP = 1 shl 20
    }
}
