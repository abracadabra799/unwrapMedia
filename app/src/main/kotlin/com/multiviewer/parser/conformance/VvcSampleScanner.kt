package com.multiviewer.parser.conformance

import com.multiviewer.parser.BoxNode
import com.multiviewer.parser.ByteReader
import com.multiviewer.parser.VideoSample

data class VvcConfigObservation(
    val vvccOffset: Long,
    val version: Int,
    val lengthSizeMinusOne: Int? = null,
    val ptlPresent: Boolean = false,
    val maxPictureWidth: Int? = null,
    val maxPictureHeight: Int? = null,
    val numOfArrays: Int? = null,
    val issue: String? = null,
)

data class VvcNalObservation(
    val offset: Long,
    val size: Long,
    val forbiddenZeroBit: Boolean,
    val reservedZeroBit: Boolean,
    val layerId: Int,
    val nalUnitType: Int,
    val temporalIdPlus1: Int,
)

data class VvcSampleObservation(
    val sampleIndex: Int,
    val offset: Long,
    val size: Long,
    val nals: List<VvcNalObservation>,
    val issue: String? = null,
)

fun parseVvcConfig(reader: ByteReader, vvcC: BoxNode): VvcConfigObservation {
    val payloadStart = vvcC.offset + vvcC.headerSize
    val payloadEnd = vvcC.offset + vvcC.size
    val payloadLength = payloadEnd - payloadStart
    if (payloadStart < 0 || payloadEnd > reader.length || payloadLength < 5) {
        return VvcConfigObservation(vvcC.offset, 0, issue = "vvcC box is too short or outside file bounds")
    }

    val version = reader.readUInt8(payloadStart)
    val flags = (reader.readUInt8(payloadStart + 1) shl 16) or
        (reader.readUInt8(payloadStart + 2) shl 8) or
        reader.readUInt8(payloadStart + 3)

    if (version != 0) {
        return VvcConfigObservation(vvcC.offset, version, issue = "Unsupported vvcC version $version (expected 0)")
    }
    if (flags != 0) {
        return VvcConfigObservation(vvcC.offset, version, issue = "Unsupported vvcC flags $flags (expected 0)")
    }

    val recordStart = payloadStart + 4
    val b0 = reader.readUInt8(recordStart)
    val lengthSizeMinusOne = (b0 ushr 1) and 0x03
    val ptlPresent = (b0 and 0x01) != 0

    var cur = recordStart + 1
    var maxWidth: Int? = null
    var maxHeight: Int? = null
    if (ptlPresent) {
        if (payloadEnd - cur < 15) {
            return VvcConfigObservation(vvcC.offset, version, lengthSizeMinusOne, ptlPresent, issue = "Truncated VvcDecoderConfigurationRecord PTL record in vvcC")
        }
        cur += 3 // olsIdx (2B) + bitDepth (1B)
        val bConstraint = reader.readUInt8(cur)
        val numBytesConstraintInfo = bConstraint and 0x3F
        cur += 1 + 2 + numBytesConstraintInfo // bConstraint (1B) + profile (1B) + level (1B) + constraint bytes
        if (cur < payloadEnd) {
            val numSubProfiles = reader.readUInt8(cur)
            cur += 1 + numSubProfiles * 4
        }
        if (payloadEnd - cur >= 6) {
            maxWidth = reader.readUInt16(cur)
            maxHeight = reader.readUInt16(cur + 2)
            cur += 6 // width (2B) + height (2B) + avg_frame_rate (2B)
        }
    }

    var numOfArrays: Int? = null
    if (cur < payloadEnd) {
        numOfArrays = reader.readUInt8(cur)
    }

    return VvcConfigObservation(
        vvccOffset = vvcC.offset,
        version = version,
        lengthSizeMinusOne = lengthSizeMinusOne,
        ptlPresent = ptlPresent,
        maxPictureWidth = maxWidth,
        maxPictureHeight = maxHeight,
        numOfArrays = numOfArrays,
    )
}

fun scanVvcSample(reader: ByteReader, sample: VideoSample, nalLengthSize: Int): VvcSampleObservation {
    if (sample.offset < 0 || sample.offset + sample.size > reader.length) {
        return VvcSampleObservation(
            sampleIndex = sample.index,
            offset = sample.offset,
            size = sample.size,
            nals = emptyList(),
            issue = "Sample extent [${sample.offset}..${sample.offset + sample.size}) exceeds file bounds (length=${reader.length})",
        )
    }

    if (nalLengthSize !in 1..4) {
        return VvcSampleObservation(
            sampleIndex = sample.index,
            offset = sample.offset,
            size = sample.size,
            nals = emptyList(),
            issue = "Invalid NAL length size $nalLengthSize (must be 1..4)",
        )
    }

    val nals = mutableListOf<VvcNalObservation>()
    var pos = sample.offset
    val end = sample.offset + sample.size

    while (pos + nalLengthSize <= end) {
        val nalSize = when (nalLengthSize) {
            1 -> reader.readUInt8(pos).toLong()
            2 -> reader.readUInt16(pos).toLong()
            3 -> ((reader.readUInt8(pos) shl 16) or (reader.readUInt8(pos + 1) shl 8) or reader.readUInt8(pos + 2)).toLong()
            4 -> reader.readUInt32(pos)
            else -> 0L
        }

        if (nalSize <= 0) {
            return VvcSampleObservation(
                sampleIndex = sample.index,
                offset = sample.offset,
                size = sample.size,
                nals = nals,
                issue = "NAL unit size $nalSize <= 0 at offset $pos",
            )
        }

        val nalOffset = pos + nalLengthSize
        if (nalOffset + nalSize > end) {
            return VvcSampleObservation(
                sampleIndex = sample.index,
                offset = sample.offset,
                size = sample.size,
                nals = nals,
                issue = "NAL unit extent [${nalOffset}..${nalOffset + nalSize}) exceeds sample boundary $end",
            )
        }

        if (nalSize < 2) {
            return VvcSampleObservation(
                sampleIndex = sample.index,
                offset = sample.offset,
                size = sample.size,
                nals = nals,
                issue = "NAL unit size $nalSize is too short for 2-byte VVC header",
            )
        }

        val b0 = reader.readUInt8(nalOffset)
        val b1 = reader.readUInt8(nalOffset + 1)

        val forbiddenBit = (b0 and 0x80) != 0
        val reservedBit = (b0 and 0x40) != 0
        val layerId = b0 and 0x3F
        val nalType = (b1 ushr 3) and 0x1F
        val temporalIdPlus1 = b1 and 0x07

        nals.add(
            VvcNalObservation(
                offset = nalOffset,
                size = nalSize,
                forbiddenZeroBit = forbiddenBit,
                reservedZeroBit = reservedBit,
                layerId = layerId,
                nalUnitType = nalType,
                temporalIdPlus1 = temporalIdPlus1,
            )
        )

        pos = nalOffset + nalSize
    }

    if (pos != end) {
        return VvcSampleObservation(
            sampleIndex = sample.index,
            offset = sample.offset,
            size = sample.size,
            nals = nals,
            issue = "Dangling ${end - pos} byte(s) at end of sample",
        )
    }

    return VvcSampleObservation(
        sampleIndex = sample.index,
        offset = sample.offset,
        size = sample.size,
        nals = nals,
    )
}
