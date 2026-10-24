package com.multiviewer.parser.conformance.isobmff

import com.multiviewer.parser.ByteReader

/**
 * Buffered reader over one counted sample table (`count` entries of `entryBits` bits starting at `start`).
 *
 * Walking several tables in lock-step (stsc, stco/co64, stsz/stz2 in `bmff.sample.location`) alternates reads
 * between distant offsets and defeats [ByteReader]'s single read-ahead window, costing one disk read per entry.
 * Each cursor keeps its own window of up to [bufEntries] entries, bulk-read with one [ByteReader.readBytes] and
 * refilled from the requested entry when an access falls outside it, so reads stay sequential per table.
 */
internal class TableCursor(
    private val reader: ByteReader,
    private val start: Long,
    val count: Long,
    private val entryBits: Int,
    bufEntries: Int = DEFAULT_BUF_ENTRIES,
) {
    init {
        require(entryBits == 4 || entryBits > 0 && entryBits % 8 == 0) { "entryBits $entryBits" }
        require(bufEntries >= 1)
    }

    /** Table bytes (4-bit tables round the last half byte up). */
    private val tableBytes: Long = (count * entryBits + 7) / 8
    /** Window size in bytes: at least one entry (and at least one byte for 4-bit tables). */
    private val windowBytes: Int = ((bufEntries.toLong() * entryBits + 7) / 8).coerceIn(maxOf(1L, entryBits / 8L), MAX_WINDOW).toInt()

    private var winStart = 0L          // byte offset relative to [start]
    private var buf = ByteArray(0)

    private fun ensure(rel: Long, len: Int): Int {
        if (rel < winStart || rel + len > winStart + buf.size) {
            val n = minOf(windowBytes.toLong(), tableBytes - rel).toInt()
            buf = reader.readBytes(start + rel, maxOf(n, len))
            winStart = rel
        }
        return (rel - winStart).toInt()
    }

    private fun be(at: Int, bytes: Int): Long {
        var v = 0L
        for (k in 0 until bytes) v = (v shl 8) or (buf[at + k].toLong() and 0xFF)
        return v
    }

    /** 32-bit field [field] of entry [i] (multi-field tables such as stsc, stts, ctts). */
    fun u32(i: Long, field: Int = 0): Long {
        val rel = i * (entryBits / 8) + field * 4L
        return be(ensure(rel, 4), 4)
    }

    /** Entry [i] of a single-value table: 4/8/16/32 bits unsigned, or 64 bits (co64; ≥ 2^63 reads negative). */
    fun value(i: Long): Long = when (entryBits) {
        4 -> { val b = be(ensure(i / 2, 1), 1); if (i % 2 == 0L) b shr 4 else b and 0xF }
        else -> { val n = entryBits / 8; be(ensure(i * n, n), n) }
    }

    companion object {
        const val DEFAULT_BUF_ENTRIES = 4096
        /** Keeps each bulk read within ByteReader's 64 KB cache path. */
        private const val MAX_WINDOW = 65_536L
    }
}
