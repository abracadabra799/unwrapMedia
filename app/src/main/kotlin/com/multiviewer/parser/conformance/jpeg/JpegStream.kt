package com.multiviewer.parser.conformance.jpeg

import com.multiviewer.parser.ByteReader

/** A standalone marker ([length] 0) or a marker segment; [offset] is the 0xFF right before [marker] (after fill bytes). */
data class JpegSegment(val marker: Int, val offset: Long, val length: Int) {
    val standalone: Boolean get() = length == 0
    val payload: Long get() = offset + 4
    val payloadSize: Int get() = (length - 2).coerceAtLeast(0)
    val end: Long get() = if (standalone) offset + 2 else offset + 2 + length
    val range: LongRange get() = offset until end
}

/** Entropy-coded data after the SOS at [sosIndex]: every ECS of the scan and the RSTm markers between them. */
data class JpegEntropy(
    val sosIndex: Int,
    val start: Long,
    /** Offset of the marker that ends the scan data, or the scan limit when the data is cut off. */
    val end: Long,
    val restartCount: Long,
    /** Offset of the first RSTm whose m is not (number of earlier RSTs) mod 8. */
    val restartOutOfOrder: Long?,
    val firstRestart: Long?,
)

data class JpegStreamProblem(val offset: Long, val message: String)

/** Markers of one JPEG image from its first marker to its EOI (never past [end]); reads are bounded. */
data class JpegStream(
    val start: Long,
    val segments: List<JpegSegment>,
    val entropy: List<JpegEntropy>,
    val eoi: JpegSegment?,
    /** Exclusive end of the image: after EOI, or where the walk stopped. */
    val end: Long,
    /** Bytes that are not a marker where T.81 requires one (0xFF00 / non-0xFF). */
    val markerProblems: List<JpegStreamProblem>,
    /** Marker segments whose length parameter is < 2 or runs past the limit. */
    val lengthProblems: List<JpegStreamProblem>,
    /** The limit was reached before an EOI. */
    val truncated: Boolean,
    /** More than [JpegStreamScanner.MAX_SEGMENTS] segments: the walk stopped early. */
    val capped: Boolean,
) {
    fun index(segment: JpegSegment): Int = segments.indexOf(segment)
}

object JpegStreamScanner {
    const val MAX_SEGMENTS = 100_000
    private const val CHUNK = 64 * 1024
    private val STANDALONE = setOf(0x01, 0xD8, 0xD9) + (0xD0..0xD7)

    fun scan(reader: ByteReader, start: Long = 0, limit: Long = reader.length): JpegStream {
        val segments = ArrayList<JpegSegment>()
        val entropy = ArrayList<JpegEntropy>()
        val markerProblems = ArrayList<JpegStreamProblem>()
        val lengthProblems = ArrayList<JpegStreamProblem>()
        var pos = start
        var eoi: JpegSegment? = null
        var stoppedAtLimit = false
        var capped = false
        while (true) {
            if (segments.size >= MAX_SEGMENTS) { capped = true; break }
            if (pos + 2 > limit) { stoppedAtLimit = true; break }
            val first = reader.readUInt8(pos)
            if (first != 0xFF) {
                markerProblems += JpegStreamProblem(pos, "@0x%X: 0x%02X — 마커 자리에 0xFF가 아님 / not a marker".format(pos, first))
                break
            }
            var q = pos + 1
            while (q < limit && reader.readUInt8(q) == 0xFF) q++ // fill bytes (B.1.1.2)
            if (q >= limit) { stoppedAtLimit = true; break }
            val marker = reader.readUInt8(q)
            val at = q - 1
            if (marker == 0x00) {
                markerProblems += JpegStreamProblem(at, "@0x%X: 0xFF00 — 마커 자리에 채움 바이트 / stuffed byte where a marker is required".format(at))
                break
            }
            if (marker in STANDALONE) {
                val seg = JpegSegment(marker, at, 0)
                segments += seg
                pos = seg.end
                if (marker == 0xD9) { eoi = seg; break }
                continue
            }
            if (at + 4 > limit) {
                lengthProblems += JpegStreamProblem(at, "@0x%X: 0xFF%02X 길이 필드가 잘림 / length field cut off".format(at, marker))
                stoppedAtLimit = true
                break
            }
            val length = reader.readUInt16(at + 2)
            if (length < 2) {
                lengthProblems += JpegStreamProblem(at, "@0x%X: 0xFF%02X 길이 %d < 2 / length below 2".format(at, marker, length))
                break
            }
            if (at + 2 + length > limit) {
                lengthProblems += JpegStreamProblem(at, "@0x%X: 0xFF%02X 길이 %d — 파일 끝을 넘음 / runs past the end (%d)".format(at, marker, length, limit))
                stoppedAtLimit = true
                break
            }
            val seg = JpegSegment(marker, at, length)
            segments += seg
            pos = seg.end
            if (marker == 0xDA) {
                val e = scanEntropy(reader, segments.size - 1, pos, limit)
                entropy += e
                pos = e.end
            }
        }
        return JpegStream(start, segments, entropy, eoi, eoi?.end ?: minOf(pos, limit), markerProblems, lengthProblems,
            truncated = eoi == null && stoppedAtLimit, capped = capped)
    }

    /** Scans entropy-coded data in 64 KiB chunks: skips 0xFF00 and fill bytes, counts RSTm, stops at any other marker. */
    private fun scanEntropy(reader: ByteReader, sosIndex: Int, start: Long, limit: Long): JpegEntropy {
        var pos = start
        var count = 0L
        var outOfOrder: Long? = null
        var first: Long? = null
        while (pos < limit) {
            val n = minOf(CHUNK.toLong(), limit - pos).toInt()
            val buf = reader.readBytes(pos, n)
            var i = 0
            while (i < n) {
                if (buf[i] != 0xFF.toByte()) { i++; continue }
                if (i + 1 >= n) {
                    if (pos + i + 1 >= limit) return JpegEntropy(sosIndex, start, limit, count, outOfOrder, first)
                    break // re-read this 0xFF with the next chunk
                }
                val next = buf[i + 1].toInt() and 0xFF
                when {
                    next == 0x00 -> i += 2
                    next == 0xFF -> i += 1
                    next in 0xD0..0xD7 -> {
                        val at = pos + i
                        if (first == null) first = at
                        if (outOfOrder == null && next - 0xD0 != (count % 8).toInt()) outOfOrder = at
                        count++
                        i += 2
                    }
                    else -> return JpegEntropy(sosIndex, start, pos + i, count, outOfOrder, first)
                }
            }
            pos += i
        }
        return JpegEntropy(sosIndex, start, limit, count, outOfOrder, first)
    }
}

enum class JpegProcess { BASELINE, EXTENDED, PROGRESSIVE, LOSSLESS, DIFFERENTIAL }

data class FrameComponent(val id: Int, val h: Int, val v: Int, val tq: Int)

data class JpegFrame(
    val segment: JpegSegment,
    val precision: Int,
    val lines: Int,
    val samplesPerLine: Int,
    /** Nf as written. */
    val declaredCount: Int,
    /** Components actually present in the header (may be fewer than Nf when the segment is short). */
    val components: List<FrameComponent>,
) {
    val marker: Int get() = segment.marker
    val process: JpegProcess get() = processOf(marker)
    val huffman: Boolean get() = marker in 0xC0..0xC7
    val dct: Boolean get() = process != JpegProcess.LOSSLESS && marker !in setOf(0xC7, 0xCF)
    val hMax: Int get() = components.maxOfOrNull { it.h }?.coerceAtLeast(1) ?: 1
    val vMax: Int get() = components.maxOfOrNull { it.v }?.coerceAtLeast(1) ?: 1

    companion object {
        val SOF_MARKERS = setOf(0xC0, 0xC1, 0xC2, 0xC3, 0xC5, 0xC6, 0xC7, 0xC9, 0xCA, 0xCB, 0xCD, 0xCE, 0xCF)

        fun processOf(marker: Int): JpegProcess = when (marker) {
            0xC0 -> JpegProcess.BASELINE
            0xC1, 0xC9 -> JpegProcess.EXTENDED
            0xC2, 0xCA -> JpegProcess.PROGRESSIVE
            0xC3, 0xCB -> JpegProcess.LOSSLESS
            else -> JpegProcess.DIFFERENTIAL
        }
    }
}

data class ScanComponent(val selector: Int, val td: Int, val ta: Int)

data class JpegScanHeader(
    val segment: JpegSegment,
    val declaredCount: Int,
    val components: List<ScanComponent>,
    val ss: Int, val se: Int, val ah: Int, val al: Int,
    /** False when the segment is too short to hold Ss/Se/Ah/Al after the component list. */
    val complete: Boolean,
)

data class QuantTable(val segment: JpegSegment, val pq: Int, val tq: Int, val hasZero: Boolean)
data class HuffmanTable(val segment: JpegSegment, val tc: Int, val th: Int, val valueCount: Int)

/** Parses headers from segment payloads; every read stays inside the segment. */
object JpegHeaders {
    fun payload(reader: ByteReader, s: JpegSegment): ByteArray =
        if (s.payloadSize == 0) ByteArray(0) else reader.readBytes(s.payload, s.payloadSize)

    private fun ByteArray.u8(i: Int) = this[i].toInt() and 0xFF
    private fun ByteArray.u16(i: Int) = (u8(i) shl 8) or u8(i + 1)

    fun frame(reader: ByteReader, s: JpegSegment): JpegFrame? {
        val p = payload(reader, s)
        if (p.size < 6) return null
        val nf = p.u8(5)
        val n = minOf(nf, (p.size - 6) / 3)
        val comps = (0 until n).map { i -> FrameComponent(p.u8(6 + 3 * i), p.u8(7 + 3 * i) shr 4, p.u8(7 + 3 * i) and 15, p.u8(8 + 3 * i)) }
        return JpegFrame(s, p.u8(0), p.u16(1), p.u16(3), nf, comps)
    }

    fun scan(reader: ByteReader, s: JpegSegment): JpegScanHeader? {
        val p = payload(reader, s)
        if (p.isEmpty()) return null
        val ns = p.u8(0)
        val n = minOf(ns, (p.size - 1) / 2)
        val comps = (0 until n).map { i -> ScanComponent(p.u8(1 + 2 * i), p.u8(2 + 2 * i) shr 4, p.u8(2 + 2 * i) and 15) }
        val tail = 1 + 2 * ns
        val complete = n == ns && p.size >= tail + 3
        return if (complete) JpegScanHeader(s, ns, comps, p.u8(tail), p.u8(tail + 1), p.u8(tail + 2) shr 4, p.u8(tail + 2) and 15, true)
        else JpegScanHeader(s, ns, comps, 0, 0, 0, 0, false)
    }

    /** Tables of one DQT segment; [problems] gets one line per table that runs past the segment. */
    fun quantTables(reader: ByteReader, s: JpegSegment, problems: MutableList<String>): List<QuantTable> {
        val p = payload(reader, s)
        val out = ArrayList<QuantTable>()
        var i = 0
        while (i < p.size) {
            val pq = p.u8(i) shr 4
            val tq = p.u8(i) and 15
            val size = if (pq == 0) 64 else 128
            if (i + 1 + size > p.size) {
                problems += "DQT @0x%X: 테이블 %d이(가) 세그먼트 끝을 넘음 / table runs past the segment (Lq=%d)".format(s.offset, out.size, s.length)
                break
            }
            val zero = (0 until 64).any { k -> if (pq == 0) p.u8(i + 1 + k) == 0 else p.u16(i + 1 + 2 * k) == 0 }
            out += QuantTable(s, pq, tq, zero)
            i += 1 + size
        }
        return out
    }

    fun huffmanTables(reader: ByteReader, s: JpegSegment, problems: MutableList<String>): List<HuffmanTable> {
        val p = payload(reader, s)
        val out = ArrayList<HuffmanTable>()
        var i = 0
        while (i < p.size) {
            if (i + 17 > p.size) {
                problems += "DHT @0x%X: 테이블 %d의 BITS(16바이트)가 잘림 / BITS cut off (Lh=%d)".format(s.offset, out.size, s.length)
                break
            }
            val mt = (1..16).sumOf { p.u8(i + it) }
            if (i + 17 + mt > p.size) {
                problems += "DHT @0x%X: 테이블 %d의 값 %d개가 세그먼트 끝을 넘음 / %d values run past the segment (Lh=%d)".format(s.offset, out.size, mt, mt, s.length)
                break
            }
            out += HuffmanTable(s, p.u8(i) shr 4, p.u8(i) and 15, mt)
            i += 17 + mt
        }
        return out
    }

    /** Ri of a DRI segment, or null when Lr != 4. */
    fun restartInterval(reader: ByteReader, s: JpegSegment): Int? =
        if (s.length != 4) null else reader.readUInt16(s.payload)

    /** NL of a DNL segment, or null when Ld != 4. */
    fun numberOfLines(reader: ByteReader, s: JpegSegment): Int? =
        if (s.length != 4) null else reader.readUInt16(s.payload)
}
