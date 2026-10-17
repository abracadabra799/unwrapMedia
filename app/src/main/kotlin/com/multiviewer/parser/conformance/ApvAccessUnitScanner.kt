package com.multiviewer.parser.conformance

import com.multiviewer.parser.BitReader
import com.multiviewer.parser.ByteReader
import com.multiviewer.parser.VideoSample

data class ApvSampleObservation(
    val sampleIndex: Int,
    val offset: Long,
    val size: Long,
    val auLength: Long? = null,
    val signature: Int? = null,
    val primaryFrameOffset: Long? = null,
    val pbuType: Int? = null,
    val groupId: Int? = null,
    val pbuReservedZero: Int? = null,
    val profileIdc: Int? = null,
    val levelIdc: Int? = null,
    val bandIdc: Int? = null,
    val reservedZero5bits: Int? = null,
    val frameWidth: Int? = null,
    val frameHeight: Int? = null,
    val chromaFormatIdc: Int? = null,
    val bitDepth: Int? = null,
    val frameInfoReservedZero: Int? = null,
    val frameHeaderReservedZero: Int? = null,
    val issue: String? = null,
)

private const val MAX_AU_HEADER_READ = 4096

fun scanApvSample(reader: ByteReader, sample: VideoSample): ApvSampleObservation {
    if (sample.offset < 0 || sample.size <= 0) {
        return ApvSampleObservation(sample.index, sample.offset, sample.size, issue = "Invalid sample extent")
    }
    if (sample.offset > Long.MAX_VALUE - sample.size) {
        return ApvSampleObservation(sample.index, sample.offset, sample.size, issue = "Sample extent exceeds file bounds")
    }
    val sampleEnd = sample.offset + sample.size
    if (sampleEnd > reader.length) {
        return ApvSampleObservation(sample.index, sample.offset, sample.size, issue = "Sample extent exceeds file bounds")
    }
    if (sample.size < 8) {
        return ApvSampleObservation(sample.index, sample.offset, sample.size, issue = "Sample too short for APV access unit header")
    }

    val prefixLen = minOf(sample.size, MAX_AU_HEADER_READ.toLong()).toInt()
    val buf = try {
        reader.readBytes(sample.offset, prefixLen)
    } catch (e: Exception) {
        return ApvSampleObservation(sample.index, sample.offset, sample.size, issue = "I/O error reading sample: ${e.message}")
    }

    val auLength = ((buf[0].toLong() and 0xFF) shl 24) or
        ((buf[1].toLong() and 0xFF) shl 16) or
        ((buf[2].toLong() and 0xFF) shl 8) or
        (buf[3].toLong() and 0xFF)

    val signature = ((buf[4].toInt() and 0xFF) shl 24) or
        ((buf[5].toInt() and 0xFF) shl 16) or
        ((buf[6].toInt() and 0xFF) shl 8) or
        (buf[7].toInt() and 0xFF)

    if (signature != 0x61507631) {
        return ApvSampleObservation(
            sampleIndex = sample.index,
            offset = sample.offset,
            size = sample.size,
            auLength = auLength,
            signature = signature,
            issue = "Invalid access unit signature: 0x${signature.toUInt().toString(16)} (expected 'aPv1')",
        )
    }

    var pos = 8
    while (pos + 8 <= prefixLen) {
        val pbuSize = ((buf[pos].toLong() and 0xFF) shl 24) or
            ((buf[pos + 1].toLong() and 0xFF) shl 16) or
            ((buf[pos + 2].toLong() and 0xFF) shl 8) or
            (buf[pos + 3].toLong() and 0xFF)

        val pbuStart = pos + 4
        if (pbuSize < 4) {
            return ApvSampleObservation(
                sampleIndex = sample.index,
                offset = sample.offset,
                size = sample.size,
                auLength = auLength,
                signature = signature,
                issue = "PBU size $pbuSize is less than minimum header size 4",
            )
        }

        val pbuType = buf[pbuStart].toInt() and 0xFF
        val groupId = ((buf[pbuStart + 1].toInt() and 0xFF) shl 8) or (buf[pbuStart + 2].toInt() and 0xFF)
        val pbuReservedZero = buf[pbuStart + 3].toInt() and 0xFF

        val payloadStart = pbuStart + 4
        val payloadLength = pbuSize - 4

        if (pbuType == 1) { // PRIMARY_FRAME
            val frameOffset = sample.offset + payloadStart
            val available = minOf(prefixLen - payloadStart, payloadLength.toInt())
            if (available < 14) {
                return ApvSampleObservation(
                    sampleIndex = sample.index,
                    offset = sample.offset,
                    size = sample.size,
                    auLength = auLength,
                    signature = signature,
                    primaryFrameOffset = frameOffset,
                    pbuType = pbuType,
                    groupId = groupId,
                    pbuReservedZero = pbuReservedZero,
                    issue = "Primary frame payload too short for frame_info header",
                )
            }

            return try {
                val bitReader = BitReader(buf.copyOfRange(payloadStart, payloadStart + available))
                val profileIdc = bitReader.readBits(8)
                val levelIdc = bitReader.readBits(8)
                val bandIdc = bitReader.readBits(3)
                val reservedZero5bits = bitReader.readBits(5)
                val frameWidth = bitReader.readBits(24)
                val frameHeight = bitReader.readBits(24)
                val chromaFormatIdc = bitReader.readBits(4)
                val bitDepthMinus8 = bitReader.readBits(4)
                val captureTimeDistance = bitReader.readBits(8)
                val frameInfoReservedZero = bitReader.readBits(8)
                val frameHeaderReservedZero = bitReader.readBits(8)

                ApvSampleObservation(
                    sampleIndex = sample.index,
                    offset = sample.offset,
                    size = sample.size,
                    auLength = auLength,
                    signature = signature,
                    primaryFrameOffset = frameOffset,
                    pbuType = pbuType,
                    groupId = groupId,
                    pbuReservedZero = pbuReservedZero,
                    profileIdc = profileIdc,
                    levelIdc = levelIdc,
                    bandIdc = bandIdc,
                    reservedZero5bits = reservedZero5bits,
                    frameWidth = frameWidth,
                    frameHeight = frameHeight,
                    chromaFormatIdc = chromaFormatIdc,
                    bitDepth = bitDepthMinus8 + 8,
                    frameInfoReservedZero = frameInfoReservedZero,
                    frameHeaderReservedZero = frameHeaderReservedZero,
                )
            } catch (e: Exception) {
                ApvSampleObservation(
                    sampleIndex = sample.index,
                    offset = sample.offset,
                    size = sample.size,
                    auLength = auLength,
                    signature = signature,
                    primaryFrameOffset = frameOffset,
                    pbuType = pbuType,
                    groupId = groupId,
                    pbuReservedZero = pbuReservedZero,
                    issue = "Failed to parse primary frame header: ${e.message}",
                )
            }
        }

        pos = (payloadStart + payloadLength).toInt()
        if (pos > sample.size) {
            return ApvSampleObservation(
                sampleIndex = sample.index,
                offset = sample.offset,
                size = sample.size,
                auLength = auLength,
                signature = signature,
                issue = "PBU extent exceeds sample size",
            )
        }
    }

    return ApvSampleObservation(
        sampleIndex = sample.index,
        offset = sample.offset,
        size = sample.size,
        auLength = auLength,
        signature = signature,
        issue = "No primary frame found in access unit",
    )
}
