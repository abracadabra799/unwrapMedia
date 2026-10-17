package com.multiviewer.parser.conformance

import com.multiviewer.parser.ByteReader
import com.multiviewer.parser.VideoSample

data class Av1ObuObservation(
    val sampleIndex: Int,
    val obuIndex: Int,
    val headerOffset: Long,
    val payloadOffset: Long,
    val obuType: Int,
    val forbiddenBit: Int,
    val reservedBit: Int,
    val extensionFlag: Boolean,
    val temporalId: Int?,
    val spatialId: Int?,
    val payloadSize: Long,
    val issue: String?,
    val extensionReservedBits: Int? = null,
)

data class Av1SampleScan(
    val checkedObus: Int,
    val consumedBytes: Long,
    val coverageReason: String?,
)

/**
 * Scans length-delimited AV1 OBUs from a sample index without allocating sample-sized buffers.
 * Reads only OBU headers and leb128 size fields.
 */
fun scanAv1Sample(
    reader: ByteReader,
    sample: VideoSample,
    onObu: (Av1ObuObservation) -> Unit,
): Av1SampleScan {
    if (sample.offset < 0 || sample.size < 0 || sample.offset > Long.MAX_VALUE - sample.size) {
        return Av1SampleScan(0, 0, "sample_extent_overflow")
    }
    val end = sample.offset + sample.size
    if (end > reader.length) return Av1SampleScan(0, 0, "sample_outside_file")

    var pos = sample.offset
    var obuIndex = 0

    fun issue(
        reason: String,
        header: Long,
        payload: Long = header,
        type: Int = 0,
        forbidden: Int = 0,
        reserved: Int = 0,
        ext: Boolean = false,
        tempId: Int? = null,
        spatId: Int? = null,
        size: Long = 0L,
        extReserved: Int? = null,
    ): Av1SampleScan {
        onObu(
            Av1ObuObservation(
                sampleIndex = sample.index,
                obuIndex = obuIndex,
                headerOffset = header,
                payloadOffset = payload,
                obuType = type,
                forbiddenBit = forbidden,
                reservedBit = reserved,
                extensionFlag = ext,
                temporalId = tempId,
                spatialId = spatId,
                payloadSize = size,
                issue = reason,
                extensionReservedBits = extReserved,
            )
        )
        return Av1SampleScan(obuIndex, pos - sample.offset, reason)
    }

    while (pos < end) {
        val headerOffset = pos
        if (end - pos < 1) return issue("truncated_obu_header", headerOffset)
        val b0 = reader.readUInt8(pos)
        val forbiddenBit = (b0 shr 7) and 1
        val obuType = (b0 shr 3) and 0x0F
        val extensionFlag = ((b0 shr 2) and 1) == 1
        val hasSizeField = ((b0 shr 1) and 1) == 1
        val reservedBit = b0 and 1

        val headerSize = if (extensionFlag) 2 else 1
        if (end - pos < headerSize) {
            return issue(
                "truncated_obu_header",
                headerOffset,
                type = obuType,
                forbidden = forbiddenBit,
                reserved = reservedBit,
                ext = extensionFlag,
            )
        }

        var temporalId: Int? = null
        var spatialId: Int? = null
        var extReservedBits: Int? = null
        if (extensionFlag) {
            val b1 = reader.readUInt8(pos + 1)
            temporalId = (b1 shr 5) and 0x07
            spatialId = (b1 shr 3) and 0x03
            extReservedBits = b1 and 0x07
        }

        var payloadStart = pos + headerSize
        var payloadSize = 0L

        if (hasSizeField) {
            var p = payloadStart
            var shift = 0
            var lebValid = false
            for (i in 0 until 8) {
                if (p >= end) {
                    return issue(
                        "truncated_size_field",
                        headerOffset,
                        p,
                        obuType,
                        forbiddenBit,
                        reservedBit,
                        extensionFlag,
                        temporalId,
                        spatialId,
                        extReserved = extReservedBits,
                    )
                }
                val b = reader.readUInt8(p++)
                payloadSize = payloadSize or ((b.toLong() and 0x7F) shl shift)
                shift += 7
                if ((b and 0x80) == 0) {
                    lebValid = true
                    break
                }
            }
            if (!lebValid) {
                return issue(
                    "malformed_leb128",
                    headerOffset,
                    p,
                    obuType,
                    forbiddenBit,
                    reservedBit,
                    extensionFlag,
                    temporalId,
                    spatialId,
                    extReserved = extReservedBits,
                )
            }
            payloadStart = p
        } else {
            payloadSize = end - payloadStart
        }

        if (payloadSize < 0 || payloadSize > end - payloadStart) {
            return issue(
                "obu_exceeds_sample",
                headerOffset,
                payloadStart,
                obuType,
                forbiddenBit,
                reservedBit,
                extensionFlag,
                temporalId,
                spatialId,
                payloadSize,
                extReserved = extReservedBits,
            )
        }

        onObu(
            Av1ObuObservation(
                sampleIndex = sample.index,
                obuIndex = obuIndex++,
                headerOffset = headerOffset,
                payloadOffset = payloadStart,
                obuType = obuType,
                forbiddenBit = forbiddenBit,
                reservedBit = reservedBit,
                extensionFlag = extensionFlag,
                temporalId = temporalId,
                spatialId = spatialId,
                payloadSize = payloadSize,
                issue = null,
                extensionReservedBits = extReservedBits,
            )
        )

        pos = payloadStart + payloadSize
    }

    return Av1SampleScan(obuIndex, pos - sample.offset, null)
}
