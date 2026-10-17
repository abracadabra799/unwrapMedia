package com.multiviewer.parser.conformance

import com.multiviewer.parser.ByteReader
import com.multiviewer.parser.VideoSample

data class AvcNalObservation(
    val sampleIndex: Int,
    val nalIndex: Int,
    val prefixOffset: Long,
    val headerOffset: Long?,
    val nalType: Int?,
    val forbiddenZeroBit: Int?,
    val refIdc: Int?,
    val issue: String?,
)

data class AvcSampleScan(val checkedNals: Int, val consumedBytes: Long, val coverageReason: String?)

/** Reads only length prefixes and 1-byte headers; never allocates a sample-sized buffer. */
fun scanAvcSample(
    reader: ByteReader,
    sample: VideoSample,
    lengthSize: Int,
    onNal: (AvcNalObservation) -> Unit,
): AvcSampleScan {
    if (lengthSize !in 1..4) return AvcSampleScan(0, 0, "invalid_length_size")
    if (sample.offset < 0 || sample.size < 0 || sample.offset > Long.MAX_VALUE - sample.size) {
        return AvcSampleScan(0, 0, "sample_extent_overflow")
    }
    val end = sample.offset + sample.size
    if (end > reader.length) return AvcSampleScan(0, 0, "sample_outside_file")
    var pos = sample.offset
    var nalIndex = 0
    var zeroLengthSeen = false
    fun issue(reason: String, prefix: Long, header: Long? = null): AvcSampleScan {
        onNal(AvcNalObservation(sample.index, nalIndex, prefix, header, null, null, null, reason))
        return AvcSampleScan(nalIndex, pos - sample.offset, reason)
    }
    while (pos < end) {
        val prefix = pos
        if (end - pos < lengthSize) return issue("truncated_length_prefix", prefix)
        var nalLength = 0L
        repeat(lengthSize) { index -> nalLength = (nalLength shl 8) or reader.readUInt8(pos + index).toLong() }
        pos += lengthSize
        if (nalLength == 0L) {
            zeroLengthSeen = true
            onNal(AvcNalObservation(sample.index, nalIndex++, prefix, null, null, null, null, "zero_length_nal"))
            continue
        }
        if (nalLength > end - pos) return issue("nal_exceeds_sample", prefix)
        if (nalLength < 1) return issue("truncated_nal_header", prefix, pos)
        val headerByte = reader.readUInt8(pos)
        val forbidden = (headerByte shr 7) and 1
        val refIdc = (headerByte shr 5) and 0x03
        val nalType = headerByte and 0x1F
        onNal(AvcNalObservation(
            sample.index, nalIndex++, prefix, pos, nalType,
            forbidden, refIdc, null,
        ))
        pos += nalLength
    }
    return AvcSampleScan(nalIndex, pos - sample.offset, if (zeroLengthSeen) "zero_length_nal" else null)
}
