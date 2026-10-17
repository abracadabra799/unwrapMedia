package com.multiviewer.parser.conformance

import com.multiviewer.parser.ByteReader
import com.multiviewer.parser.VideoSample

data class HevcNalObservation(
    val sampleIndex: Int,
    val nalIndex: Int,
    val prefixOffset: Long,
    val headerOffset: Long?,
    val nalType: Int?,
    val forbiddenZeroBit: Int?,
    val temporalIdPlus1: Int?,
    val issue: String?,
)

data class HevcSampleScan(val checkedNals: Int, val consumedBytes: Long, val coverageReason: String?)

/** Reads only length prefixes and two-byte headers; never allocates a sample-sized buffer. */
fun scanHevcSample(
    reader: ByteReader,
    sample: VideoSample,
    lengthSize: Int,
    onNal: (HevcNalObservation) -> Unit,
): HevcSampleScan {
    if (lengthSize !in 1..4) return HevcSampleScan(0, 0, "invalid_length_size")
    if (sample.offset < 0 || sample.size < 0 || sample.offset > Long.MAX_VALUE - sample.size) {
        return HevcSampleScan(0, 0, "sample_extent_overflow")
    }
    val end = sample.offset + sample.size
    if (end > reader.length) return HevcSampleScan(0, 0, "sample_outside_file")
    var pos = sample.offset
    var nalIndex = 0
    fun issue(reason: String, prefix: Long, header: Long? = null): HevcSampleScan {
        onNal(HevcNalObservation(sample.index, nalIndex, prefix, header, null, null, null, reason))
        return HevcSampleScan(nalIndex, pos - sample.offset, reason)
    }
    while (pos < end) {
        val prefix = pos
        if (end - pos < lengthSize) return issue("truncated_length_prefix", prefix)
        var nalLength = 0L
        repeat(lengthSize) { index -> nalLength = (nalLength shl 8) or reader.readUInt8(pos + index).toLong() }
        pos += lengthSize
        if (nalLength == 0L) {
            onNal(HevcNalObservation(sample.index, nalIndex++, prefix, null, null, null, null, "zero_length_nal"))
            continue
        }
        if (nalLength > end - pos) return issue("nal_exceeds_sample", prefix)
        if (nalLength < 2) return issue("truncated_nal_header", prefix, pos)
        val first = reader.readUInt8(pos)
        val second = reader.readUInt8(pos + 1)
        onNal(HevcNalObservation(
            sample.index, nalIndex++, prefix, pos, (first shr 1) and 0x3F,
            (first shr 7) and 1, second and 0x07, null,
        ))
        pos += nalLength
    }
    return HevcSampleScan(nalIndex, pos - sample.offset, null)
}
