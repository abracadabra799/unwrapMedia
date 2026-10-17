package com.multiviewer.parser.conformance

import com.multiviewer.parser.AudioSample
import com.multiviewer.parser.BoxNode
import com.multiviewer.parser.ByteReader

data class OpusConfigObservation(
    val dopsOffset: Long,
    val version: Int? = null,
    val channelCount: Int? = null,
    val preSkip: Int? = null,
    val sampleRate: Long? = null,
    val gain: Int? = null,
    val mappingFamily: Int? = null,
    val streamCount: Int? = null,
    val coupledCount: Int? = null,
    val issue: String? = null,
)

data class OpusSampleObservation(
    val sampleIndex: Int,
    val offset: Long,
    val size: Long,
    val tocConfig: Int? = null,
    val isStereo: Boolean? = null,
    val frameCountCode: Int? = null,
    val frameCount: Int? = null,
    val issue: String? = null,
)

private const val MAX_SAMPLE_PREFIX_READ = 512

internal fun opusFrameDurationMs(config: Int): Double = when (config) {
    0, 4, 8, 12, 14 -> 10.0
    1, 5, 9, 13, 15 -> 20.0
    2, 6, 10 -> 40.0
    3, 7, 11 -> 60.0
    16, 20, 24, 28 -> 2.5
    17, 21, 25, 29 -> 5.0
    18, 22, 26, 30 -> 10.0
    19, 23, 27, 31 -> 20.0
    else -> 20.0
}

/**
 * Parses the ISO-BMFF `dOps` (OpusSpecificBox) to extract Opus encapsulation parameters.
 */
fun parseOpusConfig(reader: ByteReader, dOps: BoxNode): OpusConfigObservation {
    val payloadStart = dOps.offset + dOps.headerSize
    val payloadEnd = dOps.offset + dOps.size
    val payloadLength = payloadEnd - payloadStart
    if (payloadStart < 0 || payloadEnd > reader.length || payloadLength < 11) {
        return OpusConfigObservation(dOps.offset, issue = "dOps box is too short or outside file bounds")
    }

    val version = reader.readUInt8(payloadStart)
    val channelCount = reader.readUInt8(payloadStart + 1)
    val preSkip = reader.readUInt16(payloadStart + 2)
    val sampleRate = reader.readUInt32(payloadStart + 4)
    val rawGain = reader.readUInt16(payloadStart + 8)
    val gain = rawGain.toShort().toInt()
    val mappingFamily = reader.readUInt8(payloadStart + 10)

    var issue: String? = null
    var streamCount: Int? = null
    var coupledCount: Int? = null

    if (version != 0) {
        issue = "Unsupported dOps version $version (expected 0)"
    } else if (channelCount == 0) {
        issue = "Invalid OutputChannelCount 0 (must be >= 1)"
    } else if (sampleRate == 0L) {
        issue = "Invalid InputSampleRate 0"
    } else if (mappingFamily !in setOf(0, 1, 255)) {
        issue = "Unsupported channel mapping family $mappingFamily"
    } else if (mappingFamily == 0 && channelCount > 2) {
        issue = "Family 0 allows at most 2 channels, found $channelCount"
    } else if (mappingFamily == 1) {
        val mappingExtra = 2 + channelCount
        if (payloadLength < 11 + mappingExtra) {
            issue = "Truncated channel mapping table in dOps"
        } else {
            streamCount = reader.readUInt8(payloadStart + 11)
            coupledCount = reader.readUInt8(payloadStart + 12)
            if (streamCount == 0) {
                issue = "StreamCount must be > 0"
            } else if (coupledCount > streamCount) {
                issue = "CoupledCount ($coupledCount) cannot exceed StreamCount ($streamCount)"
            } else if (streamCount + coupledCount < channelCount) {
                issue = "StreamCount + CoupledCount ($streamCount + $coupledCount) cannot be less than OutputChannelCount ($channelCount)"
            }
        }
    }

    return OpusConfigObservation(
        dopsOffset = dOps.offset,
        version = version,
        channelCount = channelCount,
        preSkip = preSkip,
        sampleRate = sampleRate,
        gain = gain,
        mappingFamily = mappingFamily,
        streamCount = streamCount,
        coupledCount = coupledCount,
        issue = issue,
    )
}

/**
 * Scans an individual Opus audio sample prefix up to 512 bytes to verify TOC and framing syntax.
 */
fun scanOpusSample(reader: ByteReader, sample: AudioSample, channelCount: Int): OpusSampleObservation {
    if (sample.offset < 0 || sample.size <= 0 || sample.offset > Long.MAX_VALUE - sample.size || sample.offset + sample.size > reader.length) {
        return OpusSampleObservation(
            sampleIndex = sample.index,
            offset = sample.offset,
            size = sample.size,
            issue = "Sample offset ${sample.offset} size ${sample.size} extends beyond file bounds (length ${reader.length})",
        )
    }

    val readLen = minOf(sample.size, MAX_SAMPLE_PREFIX_READ.toLong()).toInt()
    val buf = try {
        reader.readBytes(sample.offset, readLen)
    } catch (e: Exception) {
        return OpusSampleObservation(
            sampleIndex = sample.index,
            offset = sample.offset,
            size = sample.size,
            issue = "I/O error reading sample: ${e.message}",
        )
    }

    if (buf.isEmpty()) {
        return OpusSampleObservation(
            sampleIndex = sample.index,
            offset = sample.offset,
            size = sample.size,
            issue = "Empty sample",
        )
    }

    val b0 = buf[0].toInt() and 0xFF
    val config = (b0 shr 3) and 0x1F
    val isStereo = ((b0 shr 2) and 0x01) == 1
    val frameCountCode = b0 and 0x03

    var issue: String? = null
    if (channelCount == 1 && isStereo) {
        issue = "TOC has stereo flag (s=1) but track is mono (channels=1)"
    }

    var frameCount: Int? = null
    when (frameCountCode) {
        0 -> frameCount = 1
        1, 2 -> frameCount = 2
        3 -> {
            if (buf.size < 2) {
                issue = issue ?: "Code 3 packet missing frame count byte"
            } else {
                val b1 = buf[1].toInt() and 0xFF
                val m = b1 and 0x3F
                frameCount = m
                if (m == 0) {
                    issue = issue ?: "Code 3 packet has forbidden frame count M=0"
                } else {
                    val durationMs = opusFrameDurationMs(config)
                    val totalDuration = m * durationMs
                    if (totalDuration > 120.0) {
                        issue = issue ?: "Total packet duration ${totalDuration}ms exceeds maximum allowed 120ms (M=$m, frame=${durationMs}ms)"
                    }
                }
            }
        }
    }

    return OpusSampleObservation(
        sampleIndex = sample.index,
        offset = sample.offset,
        size = sample.size,
        tocConfig = config,
        isStereo = isStereo,
        frameCountCode = frameCountCode,
        frameCount = frameCount,
        issue = issue,
    )
}
