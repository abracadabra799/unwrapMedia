package com.multiviewer.parser.conformance

import com.multiviewer.parser.AudioSample
import com.multiviewer.parser.BoxNode
import com.multiviewer.parser.ByteReader

data class FlacConfigObservation(
    val dflaOffset: Long,
    val version: Int,
    val minBlockSize: Int? = null,
    val maxBlockSize: Int? = null,
    val sampleRate: Long? = null,
    val channels: Int? = null,
    val bitsPerSample: Int? = null,
    val issue: String? = null,
)

data class FlacSampleObservation(
    val sampleIndex: Int,
    val offset: Long,
    val size: Long,
    val syncValid: Boolean,
    val headerCrcValid: Boolean,
    val frameCrcValid: Boolean,
    val blockSize: Int? = null,
    val sampleRate: Long? = null,
    val channels: Int? = null,
    val issue: String? = null,
)

private const val MAX_HEADER_PREFIX_READ = 512

internal fun computeFlacCrc8(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size): Int {
    var crc = 0
    for (i in offset until (offset + length)) {
        crc = crc xor (bytes[i].toInt() and 0xFF)
        repeat(8) {
            crc = if (crc and 0x80 != 0) ((crc shl 1) xor 0x07) and 0xFF else (crc shl 1) and 0xFF
        }
    }
    return crc
}

internal fun computeFlacCrc16(reader: ByteReader, start: Long, length: Long): Int {
    var crc = 0
    var position = start
    val end = start + length
    while (position < end) {
        val size = minOf(65536L, end - position).toInt()
        val bytes = reader.readBytes(position, size)
        for (byte in bytes) {
            crc = crc xor ((byte.toInt() and 0xFF) shl 8)
            repeat(8) {
                crc = if (crc and 0x8000 != 0) ((crc shl 1) xor 0x8005) and 0xFFFF else (crc shl 1) and 0xFFFF
            }
        }
        position += size
    }
    return crc
}

/**
 * Parses the ISO-BMFF `dfLa` (FLACSpecificBox) to extract STREAMINFO parameters.
 */
fun parseFlacConfig(reader: ByteReader, dfLa: BoxNode): FlacConfigObservation {
    val payloadStart = dfLa.offset + dfLa.headerSize
    val payloadEnd = dfLa.offset + dfLa.size
    val payloadLength = payloadEnd - payloadStart
    if (payloadStart < 0 || payloadEnd > reader.length || payloadLength < 4) {
        return FlacConfigObservation(dfLa.offset, 0, issue = "dfLa box is too short or outside file bounds")
    }

    val version = reader.readUInt8(payloadStart)
    val flags = (reader.readUInt8(payloadStart + 1) shl 16) or
        (reader.readUInt8(payloadStart + 2) shl 8) or
        reader.readUInt8(payloadStart + 3)

    if (version != 0) {
        return FlacConfigObservation(dfLa.offset, version, issue = "Unsupported dfLa version $version (expected 0)")
    }
    if (flags != 0) {
        return FlacConfigObservation(dfLa.offset, version, issue = "Unsupported dfLa flags $flags (expected 0)")
    }

    if (payloadLength < 8) {
        return FlacConfigObservation(dfLa.offset, version, issue = "dfLa box payload too short for metadata block header")
    }

    val blockHdrOffset = payloadStart + 4
    val b0 = reader.readUInt8(blockHdrOffset)
    val blockType = b0 and 0x7F
    val blockLength = (reader.readUInt8(blockHdrOffset + 1) shl 16) or
        (reader.readUInt8(blockHdrOffset + 2) shl 8) or
        reader.readUInt8(blockHdrOffset + 3)

    if (blockType != 0) {
        return FlacConfigObservation(dfLa.offset, version, issue = "First metadata block is not STREAMINFO (type=$blockType)")
    }
    if (blockLength != 34) {
        return FlacConfigObservation(dfLa.offset, version, issue = "STREAMINFO block size is $blockLength (expected 34)")
    }
    if (payloadLength < 8 + blockLength) {
        return FlacConfigObservation(dfLa.offset, version, issue = "dfLa box payload shorter than declared STREAMINFO block")
    }

    val streaminfoOffset = blockHdrOffset + 4
    val minBlockSize = reader.readUInt16(streaminfoOffset)
    val maxBlockSize = reader.readUInt16(streaminfoOffset + 2)
    val qVal = reader.readUInt64(streaminfoOffset + 10)
    val sampleRate = (qVal ushr 44) and 0xFFFFF
    val channels = (((qVal ushr 41) and 0x07) + 1).toInt()
    val bitsPerSample = (((qVal ushr 36) and 0x1F) + 1).toInt()

    var issue: String? = null
    if (minBlockSize < 16) {
        issue = "min_block_size $minBlockSize < 16"
    } else if (maxBlockSize < minBlockSize) {
        issue = "max_block_size $maxBlockSize < min_block_size $minBlockSize"
    } else if (sampleRate == 0L || sampleRate > 655350L) {
        issue = "Invalid sample_rate $sampleRate"
    } else if (channels !in 1..8) {
        issue = "Invalid channels $channels"
    } else if (bitsPerSample !in 4..32) {
        issue = "Invalid bits_per_sample $bitsPerSample"
    }

    return FlacConfigObservation(
        dflaOffset = dfLa.offset,
        version = version,
        minBlockSize = minBlockSize,
        maxBlockSize = maxBlockSize,
        sampleRate = sampleRate,
        channels = channels,
        bitsPerSample = bitsPerSample,
        issue = issue,
    )
}

/**
 * Scans an individual FLAC audio sample to verify frame sync, header fields, CRC-8, and frame CRC-16.
 */
fun scanFlacSample(reader: ByteReader, sample: AudioSample, config: FlacConfigObservation): FlacSampleObservation {
    if (sample.offset < 0 || sample.size < 6 || sample.offset > Long.MAX_VALUE - sample.size || sample.offset + sample.size > reader.length) {
        return FlacSampleObservation(
            sampleIndex = sample.index,
            offset = sample.offset,
            size = sample.size,
            syncValid = false,
            headerCrcValid = false,
            frameCrcValid = false,
            issue = "Sample offset ${sample.offset} size ${sample.size} extends beyond file bounds (length ${reader.length})",
        )
    }

    val readLen = minOf(sample.size, MAX_HEADER_PREFIX_READ.toLong()).toInt()
    val buf = try {
        reader.readBytes(sample.offset, readLen)
    } catch (e: Exception) {
        return FlacSampleObservation(
            sampleIndex = sample.index,
            offset = sample.offset,
            size = sample.size,
            syncValid = false,
            headerCrcValid = false,
            frameCrcValid = false,
            issue = "I/O error reading sample: ${e.message}",
        )
    }

    val b0 = buf[0].toInt() and 0xFF
    val b1 = buf[1].toInt() and 0xFF
    if (b0 != 0xFF || (b1 and 0xFE) != 0xF8) {
        return FlacSampleObservation(
            sampleIndex = sample.index,
            offset = sample.offset,
            size = sample.size,
            syncValid = false,
            headerCrcValid = false,
            frameCrcValid = false,
            issue = "Invalid frame syncword 0x${b0.toString(16)}${b1.toString(16)} (expected 0xFFF8)",
        )
    }

    val b2 = buf[2].toInt() and 0xFF
    val blockSizeCode = (b2 shr 4) and 0x0F
    val sampleRateCode = b2 and 0x0F

    val b3 = buf[3].toInt() and 0xFF
    val channelAssignment = (b3 shr 4) and 0x0F
    val sampleSizeCode = (b3 shr 1) and 0x07
    val reservedBit = b3 and 0x01

    var issue: String? = null
    if (blockSizeCode == 0) {
        issue = "Forbidden block size code 0"
    } else if (sampleRateCode == 15) {
        issue = "Forbidden sample rate code 15"
    } else if (channelAssignment > 10) {
        issue = "Reserved channel assignment $channelAssignment"
    } else if (sampleSizeCode == 3 || sampleSizeCode == 7) {
        issue = "Reserved sample size code $sampleSizeCode"
    } else if (reservedBit != 0) {
        issue = "Non-zero reserved bit in frame header"
    }

    var pos = 4
    if (pos >= buf.size) {
        return FlacSampleObservation(
            sampleIndex = sample.index,
            offset = sample.offset,
            size = sample.size,
            syncValid = true,
            headerCrcValid = false,
            frameCrcValid = false,
            issue = "Truncated frame header",
        )
    }

    val first = buf[pos].toInt() and 0xFF
    val continuationCount = when {
        first and 0x80 == 0 -> 0
        first and 0xE0 == 0xC0 -> 1
        first and 0xF0 == 0xE0 -> 2
        first and 0xF8 == 0xF0 -> 3
        first and 0xFC == 0xF8 -> 4
        first and 0xFE == 0xFC -> 5
        first == 0xFE -> 6
        else -> -1
    }
    if (continuationCount == -1 || pos + 1 + continuationCount >= buf.size) {
        return FlacSampleObservation(
            sampleIndex = sample.index,
            offset = sample.offset,
            size = sample.size,
            syncValid = true,
            headerCrcValid = false,
            frameCrcValid = false,
            issue = "Malformed UTF-8 frame number in header",
        )
    }
    pos += 1 + continuationCount
    if (blockSizeCode == 6) pos++ else if (blockSizeCode == 7) pos += 2
    if (sampleRateCode == 12) pos++ else if (sampleRateCode == 13 || sampleRateCode == 14) pos += 2

    if (pos >= buf.size) {
        return FlacSampleObservation(
            sampleIndex = sample.index,
            offset = sample.offset,
            size = sample.size,
            syncValid = true,
            headerCrcValid = false,
            frameCrcValid = false,
            issue = "Truncated frame header before CRC-8",
        )
    }

    val storedCrc8 = buf[pos].toInt() and 0xFF
    val computedCrc8 = computeFlacCrc8(buf, 0, pos)
    val headerCrcValid = storedCrc8 == computedCrc8
    if (!headerCrcValid) {
        return FlacSampleObservation(
            sampleIndex = sample.index,
            offset = sample.offset,
            size = sample.size,
            syncValid = true,
            headerCrcValid = false,
            frameCrcValid = false,
            issue = "Frame header CRC-8 mismatch (stored 0x${storedCrc8.toString(16)}, computed 0x${computedCrc8.toString(16)})",
        )
    }

    val storedCrc16 = reader.readUInt16(sample.offset + sample.size - 2)
    val computedCrc16 = computeFlacCrc16(reader, sample.offset, sample.size - 2)
    val frameCrcValid = storedCrc16 == computedCrc16
    if (!frameCrcValid) {
        return FlacSampleObservation(
            sampleIndex = sample.index,
            offset = sample.offset,
            size = sample.size,
            syncValid = true,
            headerCrcValid = true,
            frameCrcValid = false,
            issue = "Frame CRC-16 mismatch (stored 0x${storedCrc16.toString(16)}, computed 0x${computedCrc16.toString(16)})",
        )
    }

    return FlacSampleObservation(
        sampleIndex = sample.index,
        offset = sample.offset,
        size = sample.size,
        syncValid = true,
        headerCrcValid = true,
        frameCrcValid = true,
        issue = issue,
    )
}
