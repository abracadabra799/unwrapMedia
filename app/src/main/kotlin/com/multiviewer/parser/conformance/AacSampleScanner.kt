package com.multiviewer.parser.conformance

import com.multiviewer.parser.AudioSample
import com.multiviewer.parser.BoxNode
import com.multiviewer.parser.ByteReader

data class AacConfigObservation(
    val esdsOffset: Long,
    val aot: Int? = null,
    val samplingFrequencyIndex: Int? = null,
    val sampleRate: Int? = null,
    val channelConfig: Int? = null,
    val issue: String? = null,
)

data class AacSampleObservation(
    val sampleIndex: Int,
    val offset: Long,
    val size: Long,
    val hasAdtsSync: Boolean,
    val elementId: Int? = null,
    val elementInstanceTag: Int? = null,
    val icsReservedBit: Int? = null,
    val issue: String? = null,
)

private val AAC_SAMPLING_FREQUENCIES = intArrayOf(
    96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350,
)

private const val MAX_SAMPLE_PREFIX_READ = 512

/**
 * Parses the ISO-BMFF `esds` box to extract AudioSpecificConfig parameters.
 */
fun parseAacConfig(reader: ByteReader, esds: BoxNode): AacConfigObservation {
    val payloadStart = esds.offset + esds.headerSize
    val payloadEnd = esds.offset + esds.size
    if (payloadStart < 0 || payloadEnd > reader.length || payloadEnd - payloadStart < 12) {
        return AacConfigObservation(esds.offset, issue = "esds box is too short or outside file bounds")
    }

    // esds is a FullBox: version (1B), flags (3B)
    val version = reader.readUInt8(payloadStart)
    if (version != 0) {
        return AacConfigObservation(esds.offset, issue = "Unsupported esds version $version (expected 0)")
    }

    val buf = try {
        reader.readBytes(payloadStart + 4, (payloadEnd - payloadStart - 4).toInt())
    } catch (e: Exception) {
        return AacConfigObservation(esds.offset, issue = "I/O error reading esds: ${e.message}")
    }

    // Helper to read MPEG-4 descriptor TLV: returns Triple(tag, payloadStart, payloadLength)
    fun readDescriptor(offset: Int, limit: Int): Triple<Int, Int, Int>? {
        if (offset >= limit) return null
        val tag = buf[offset].toInt() and 0xFF
        var pos = offset + 1
        var len = 0
        var count = 0
        while (pos < limit && count < 4) {
            val b = buf[pos].toInt() and 0xFF
            pos++
            count++
            len = (len shl 7) or (b and 0x7F)
            if ((b and 0x80) == 0) break
        }
        if (pos + len > limit) return null
        return Triple(tag, pos, len)
    }

    // ES_Descriptor (tag 0x03)
    val esDesc = readDescriptor(0, buf.size)
    if (esDesc == null || esDesc.first != 0x03) {
        return AacConfigObservation(esds.offset, issue = "Invalid ES_Descriptor tag ${esDesc?.first ?: "null"} (expected 0x03)")
    }
    val (_, esPayloadStart, esPayloadLen) = esDesc
    val esLimit = esPayloadStart + esPayloadLen
    if (esPayloadLen < 3) {
        return AacConfigObservation(esds.offset, issue = "ES_Descriptor is too short ($esPayloadLen bytes)")
    }

    // ES_ID (2B), flags (1B)
    val esFlags = buf[esPayloadStart + 2].toInt() and 0xFF
    val streamDependenceFlag = (esFlags and 0x80) != 0
    val urlFlag = (esFlags and 0x40) != 0
    val ocrStreamFlag = (esFlags and 0x20) != 0

    var cur = esPayloadStart + 3
    if (streamDependenceFlag) cur += 2
    if (urlFlag) {
        if (cur >= esLimit) return AacConfigObservation(esds.offset, issue = "Truncated URL in ES_Descriptor")
        val urlLen = buf[cur].toInt() and 0xFF
        cur += 1 + urlLen
    }
    if (ocrStreamFlag) cur += 2
    if (cur >= esLimit) {
        return AacConfigObservation(esds.offset, issue = "ES_Descriptor missing DecoderConfigDescriptor")
    }

    // DecoderConfigDescriptor (tag 0x04)
    val dcd = readDescriptor(cur, esLimit)
    if (dcd == null || dcd.first != 0x04) {
        return AacConfigObservation(esds.offset, issue = "Invalid DecoderConfigDescriptor tag ${dcd?.first ?: "null"} (expected 0x04)")
    }
    val (_, dcdStart, dcdLen) = dcd
    val dcdLimit = dcdStart + dcdLen
    if (dcdLen < 13) {
        return AacConfigObservation(esds.offset, issue = "DecoderConfigDescriptor is too short ($dcdLen bytes)")
    }

    val streamTypeByte = buf[dcdStart + 1].toInt() and 0xFF
    val streamType = (streamTypeByte shr 2) and 0x3F
    if (streamType != 5) { // 5 = AudioStream
        return AacConfigObservation(esds.offset, issue = "Invalid streamType $streamType (expected 5 for AudioStream)")
    }

    // DecoderSpecificInfo (tag 0x05) begins at dcdStart + 13
    val dsi = readDescriptor(dcdStart + 13, dcdLimit)
    if (dsi == null || dsi.first != 0x05) {
        return AacConfigObservation(esds.offset, issue = "Invalid DecoderSpecificInfo tag ${dsi?.first ?: "null"} (expected 0x05)")
    }
    val (_, dsiStart, dsiLen) = dsi
    if (dsiLen < 2) {
        return AacConfigObservation(esds.offset, issue = "AudioSpecificConfig payload too short ($dsiLen bytes)")
    }

    val b0 = buf[dsiStart].toInt() and 0xFF
    val b1 = buf[dsiStart + 1].toInt() and 0xFF

    val aot = (b0 shr 3) and 0x1F
    val freqIdx = ((b0 and 0x07) shl 1) or ((b1 and 0x80) shr 7)
    val channelConfig = (b1 shr 3) and 0x0F

    val sampleRate = if (freqIdx in AAC_SAMPLING_FREQUENCIES.indices) {
        AAC_SAMPLING_FREQUENCIES[freqIdx]
    } else null

    val issue = when {
        aot !in 1..4 && aot != 5 && aot != 29 -> "Unsupported audioObjectType $aot"
        freqIdx in 13..14 -> "Reserved samplingFrequencyIndex $freqIdx"
        freqIdx > 15 -> "Invalid samplingFrequencyIndex $freqIdx"
        channelConfig !in 1..7 -> "Unsupported channelConfiguration $channelConfig"
        else -> null
    }

    return AacConfigObservation(
        esdsOffset = esds.offset,
        aot = aot,
        samplingFrequencyIndex = freqIdx,
        sampleRate = sampleRate,
        channelConfig = channelConfig,
        issue = issue,
    )
}

/**
 * Scans an individual raw AAC sample prefix (up to 512 bytes) for ADTS leakage and element structure.
 */
fun scanAacSample(reader: ByteReader, sample: AudioSample, channelConfig: Int): AacSampleObservation {
    if (sample.offset < 0 || sample.size <= 0) {
        return AacSampleObservation(sample.index, sample.offset, sample.size, hasAdtsSync = false, issue = "Invalid sample extent")
    }
    if (sample.offset > Long.MAX_VALUE - sample.size) {
        return AacSampleObservation(sample.index, sample.offset, sample.size, hasAdtsSync = false, issue = "Sample extent exceeds file bounds")
    }
    val sampleEnd = sample.offset + sample.size
    if (sampleEnd > reader.length) {
        return AacSampleObservation(sample.index, sample.offset, sample.size, hasAdtsSync = false, issue = "Sample extent exceeds file bounds")
    }
    if (sample.size < 2) {
        return AacSampleObservation(sample.index, sample.offset, sample.size, hasAdtsSync = false, issue = "Sample too short for AAC element header")
    }

    val prefixLen = minOf(sample.size, MAX_SAMPLE_PREFIX_READ.toLong()).toInt()
    val buf = try {
        reader.readBytes(sample.offset, prefixLen)
    } catch (e: Exception) {
        return AacSampleObservation(sample.index, sample.offset, sample.size, hasAdtsSync = false, issue = "I/O error reading sample: ${e.message}")
    }

    val b0 = buf[0].toInt() and 0xFF
    val b1 = buf[1].toInt() and 0xFF

    // Check 12-bit ADTS syncword (0xFFF)
    val hasAdtsSync = (b0 == 0xFF && (b1 and 0xF0) == 0xF0)
    if (hasAdtsSync) {
        return AacSampleObservation(
            sampleIndex = sample.index,
            offset = sample.offset,
            size = sample.size,
            hasAdtsSync = true,
            issue = "Raw AAC access unit begins with ADTS syncword 0xFFF",
        )
    }

    // Syntactic element ID (bits 7..5 of b0)
    val elementId = (b0 shr 5) and 0x07
    val elementInstanceTag = (b0 shr 1) and 0x0F

    val icsReservedBit = when (elementId) {
        1 -> { // ID_CPE (Channel Pair Element)
            val commonWindow = b0 and 0x01
            if (commonWindow == 1) (b1 shr 7) and 0x01 else null
        }
        0 -> { // ID_SCE: id(3) tag(4) global_gain(8), then ics_info's ics_reserved_bit at bit 15
            b1 and 0x01
        }
        else -> null
    }

    var issue: String? = null
    if (channelConfig == 2 && elementId != 1 && elementId != 0 && elementId != 6 && elementId != 5) {
        issue = "First element for stereo stream is elementId=$elementId (expected ID_CPE=1)"
    } else if (channelConfig == 1 && elementId != 0 && elementId != 6 && elementId != 5) {
        issue = "First element for mono stream is elementId=$elementId (expected ID_SCE=0)"
    } else if (icsReservedBit != null && icsReservedBit != 0) {
        issue = "Non-zero ics_reserved_bit=$icsReservedBit"
    }

    return AacSampleObservation(
        sampleIndex = sample.index,
        offset = sample.offset,
        size = sample.size,
        hasAdtsSync = false,
        elementId = elementId,
        elementInstanceTag = elementInstanceTag,
        icsReservedBit = icsReservedBit,
        issue = issue,
    )
}
