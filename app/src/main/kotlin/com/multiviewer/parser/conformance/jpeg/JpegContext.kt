package com.multiviewer.parser.conformance.jpeg

import com.multiviewer.parser.ByteReader
import com.multiviewer.parser.conformance.ConformanceFinding
import com.multiviewer.parser.conformance.ConformanceStatus

/** Shared, read-only state for the JPEG rule groups (primary image only). */
internal class JpegContext(val reader: ByteReader, val stream: JpegStream) {
    val length: Long = reader.length
    val segments: List<JpegSegment> get() = stream.segments
    val sofSegments: List<JpegSegment> = segments.filter { it.marker in JpegFrame.SOF_MARKERS }
    val frame: JpegFrame? by lazy { sofSegments.firstOrNull()?.let { JpegHeaders.frame(reader, it) } }

    /** DHP, EXP or a differential SOF: hierarchical mode, outside phase 1. */
    val hierarchical: Boolean = segments.any { it.marker == 0xDE || it.marker == 0xDF } ||
        sofSegments.any { JpegFrame.processOf(it.marker) == JpegProcess.DIFFERENTIAL }

    /** Number of lines: Y, or NL of the DNL that follows the first scan when Y is 0; null when unknown. */
    val lines: Int? by lazy {
        val f = frame ?: return@lazy null
        if (f.lines != 0) return@lazy f.lines
        val firstSos = segments.indexOfFirst { it.marker == 0xDA }
        segments.getOrNull(firstSos + 1)?.takeIf { it.marker == 0xDC }?.let { JpegHeaders.numberOfLines(reader, it) }
    }

    private val payloads = HashMap<JpegSegment, ByteArray>()
    fun payload(s: JpegSegment): ByteArray = payloads.getOrPut(s) { JpegHeaders.payload(reader, s) }

    fun startsWith(s: JpegSegment, prefix: ByteArray): Boolean {
        if (s.payloadSize < prefix.size) return false
        val p = payload(s)
        return prefix.indices.all { p[it] == prefix[it] }
    }

    fun finding(id: String, violations: List<String>, ranges: List<LongRange>, pass: List<String>): ConformanceFinding {
        val status = when {
            violations.isEmpty() -> ConformanceStatus.PASS
            id in JpegRules.WARN_RULES -> ConformanceStatus.WARN
            else -> ConformanceStatus.FAIL
        }
        return ConformanceFinding(JpegRules.rule(id), status, if (violations.isEmpty()) pass else capped(violations), ranges.take(MAX_LISTED))
    }

    fun skip(id: String, status: ConformanceStatus, reason: String): ConformanceFinding =
        ConformanceFinding(JpegRules.rule(id), status, listOf(reason), emptyList())

    companion object {
        const val MAX_LISTED = 50
        val EXIF_ID = byteArrayOf(0x45, 0x78, 0x69, 0x66, 0x00)
        val JFIF_ID = byteArrayOf(0x4A, 0x46, 0x49, 0x46, 0x00)
        val JFXX_ID = byteArrayOf(0x4A, 0x46, 0x58, 0x58, 0x00)
        val MPF_ID = byteArrayOf(0x4D, 0x50, 0x46, 0x00)

        fun capped(lines: List<String>): List<String> =
            if (lines.size <= MAX_LISTED) lines else lines.take(MAX_LISTED) + "… 외 ${lines.size - MAX_LISTED}건 / ${lines.size - MAX_LISTED} more"

        fun hex(offset: Long): String = "0x%X".format(offset)
        fun markerName(m: Int): String = when (m) {
            0xD8 -> "SOI"; 0xD9 -> "EOI"; 0xDA -> "SOS"; 0xDB -> "DQT"; 0xC4 -> "DHT"; 0xDD -> "DRI"; 0xDC -> "DNL"
            0xCC -> "DAC"; 0xFE -> "COM"; 0xDE -> "DHP"; 0xDF -> "EXP"; 0x01 -> "TEM"; 0xC8 -> "JPG"
            in 0xD0..0xD7 -> "RST${m - 0xD0}"
            in 0xE0..0xEF -> "APP${m - 0xE0}"
            in 0xF0..0xFD -> "JPG${m - 0xF0}"
            in JpegFrame.SOF_MARKERS -> "SOF${m - 0xC0}"
            else -> "RES(0x%02X)".format(m)
        }
    }
}
