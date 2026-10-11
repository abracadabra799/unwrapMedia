package com.multiviewer.parser.integrity

import com.multiviewer.parser.ByteReader

private const val FLAC_SCAN_BUFFER = 64 * 1024
private const val MAX_FLAC_FRAME_FINDINGS = 1000

internal fun checkFlacStructure(reader: ByteReader): List<IntegrityCheckItem> {
    val items = mutableListOf<IntegrityCheckItem>()
    var retainedFramePasses = 0
    var framePassesTruncated = false
    fun add(id: String, title: String, status: CheckStatus, detail: String, offset: Long? = null, length: Long? = null) {
        if (id == "frame.crc" && status == CheckStatus.PASS && retainedFramePasses >= MAX_FLAC_FRAME_FINDINGS) {
            if (!framePassesTruncated) {
                framePassesTruncated = true
                items += IntegrityCheckItem("flac.frame.findingsTruncated", "Frame finding limit", CheckStatus.WARN,
                    "More than $MAX_FLAC_FRAME_FINDINGS successful frame CRC results; additional PASS rows were omitted")
            }
            return
        }
        if (id == "frame.crc" && status == CheckStatus.PASS) retainedFramePasses++
        items += IntegrityCheckItem("flac.$id", title, status, detail, offset, length)
    }
    if (reader.length < 4 || reader.readFourCC(0) != "fLaC") {
        return listOf(IntegrityCheckItem("flac.signature", "FLAC signature", CheckStatus.FAIL, "Missing fLaC marker", 0, minOf(4, reader.length)))
    }
    var position = 4L
    var lastBlock = false
    var blockCount = 0
    var streamInfoCount = 0
    var firstBlockType: Int? = null
    while (!lastBlock && position < reader.length) {
        checkAudioScanActive()
        if (reader.length - position < 4) {
            add("metadata.header", "Metadata block header", CheckStatus.FAIL, "Trailing bytes are too short for a metadata header", position, reader.length - position)
            return items
        }
        val header = reader.readUInt8(position)
        lastBlock = (header and 0x80) != 0
        val type = header and 0x7f
        val length = (reader.readUInt8(position + 1).toLong() shl 16) or
            (reader.readUInt8(position + 2).toLong() shl 8) or reader.readUInt8(position + 3).toLong()
        val end = position + 4 + length
        if (end < position || end > reader.length) {
            add("metadata.bounds", "Metadata block bounds", CheckStatus.FAIL,
                "Declared metadata block of $length bytes exceeds file size", position, 4)
            return items
        }
        if (blockCount == 0) firstBlockType = type
        if (type == 0) {
            streamInfoCount++
            if (length != 34L) add("streaminfo.size", "STREAMINFO size", CheckStatus.FAIL,
                "STREAMINFO is $length bytes; exactly 34 are required", position, 4)
            else add("streaminfo.size", "STREAMINFO size", CheckStatus.PASS, "STREAMINFO has the required 34-byte payload", position, 38)
        } else if (type in 7..126) {
            add("metadata.reserved", "Reserved metadata type", CheckStatus.WARN,
                "Metadata block type $type is reserved and cannot be structurally interpreted", position, 4 + length)
        } else if (type == 127) {
            add("metadata.reserved", "Forbidden metadata type", CheckStatus.FAIL,
                "Metadata block type 127 is forbidden", position, 4 + length)
        }
        blockCount++
        position = end
    }
    if (!lastBlock) {
        add("metadata.last", "Last metadata block", CheckStatus.FAIL, "Metadata chain ended without a last-block flag", position)
        return items
    }
    if (firstBlockType != 0) add("streaminfo.first", "STREAMINFO placement", CheckStatus.FAIL,
        "STREAMINFO must be the first metadata block", 4, 4)
    if (streamInfoCount == 0) add("streaminfo.missing", "STREAMINFO block", CheckStatus.FAIL, "Required STREAMINFO block is missing")
    if (streamInfoCount > 1) add("streaminfo.unique", "STREAMINFO uniqueness", CheckStatus.FAIL,
        "$streamInfoCount STREAMINFO blocks are present")
    if (firstBlockType == 0 && streamInfoCount == 1) add("streaminfo.first", "STREAMINFO placement", CheckStatus.PASS,
        "STREAMINFO is the first and only stream-info block", 4, 4)

    val framesStart = position
    val audioEnd = if (reader.length >= 128 && String(reader.readBytes(reader.length - 128, 3), Charsets.US_ASCII) == "TAG") reader.length - 128 else reader.length
    if (framesStart >= audioEnd) {
        add("frame.missing", "FLAC frames", CheckStatus.WARN, "No audio frame data follows the metadata blocks", framesStart)
        return items
    }

    var frameStart = framesStart
    var frameCount = 0
    while (frameStart < audioEnd) {
        checkAudioScanActive()
        val header = parseFlacFrameHeader(reader, frameStart, audioEnd)
        if (header == null) {
            val next = findNextFlacHeader(reader, frameStart + 1, audioEnd)
            if (next == null) {
                add(if (frameCount == 0) "frame.header" else "frame.truncated", "FLAC frame header",
                    CheckStatus.FAIL, "No valid frame header found at byte $frameStart", frameStart, audioEnd - frameStart)
                break
            }
            add("frame.resync", "Frame synchronization", CheckStatus.WARN,
                "Skipped ${next - frameStart} byte(s) while searching for the next valid frame header", frameStart, next - frameStart)
            frameStart = next
            continue
        }
        if (!header.crcValid) {
            add("frame.headerCrc", "Frame header CRC", CheckStatus.FAIL, "FLAC frame header CRC-8 mismatch", frameStart, header.headerLength)
        }
        val next = findNextFlacFrame(reader, frameStart, frameStart + header.headerLength, audioEnd)
        val frameEnd = next?.boundaryEnd ?: audioEnd
        if (next != null && next.hasNonZeroGap) {
            add("frame.boundary", "Frame boundary", CheckStatus.WARN,
                "Non-zero bytes occur between the selected CRC boundary and the next valid header; frame CRC cannot be attributed unambiguously",
                frameStart, next.frameStart - frameStart)
            frameCount++
            frameStart = next.frameStart
            continue
        }
        if (next != null && next.boundaryEnd < next.frameStart) {
            add("frame.resync", "Frame synchronization", CheckStatus.WARN,
                "Skipped ${next.frameStart - next.boundaryEnd} byte(s) after the selected CRC boundary", next.boundaryEnd, next.frameStart - next.boundaryEnd)
        }
        if (frameEnd - frameStart < header.headerLength + 3) {
            add("frame.truncated", "FLAC frame extent", CheckStatus.FAIL, "Frame is too short to contain header, subframe data, and CRC-16", frameStart, frameEnd - frameStart)
            break
        }
        val storedCrcOffset = frameEnd - 2
        val storedCrc = (reader.readUInt8(storedCrcOffset) shl 8) or reader.readUInt8(storedCrcOffset + 1)
        val computedCrc = crc16(reader, frameStart, storedCrcOffset - frameStart)
        if (computedCrc != storedCrc) add("frame.crc", "Frame CRC", CheckStatus.FAIL,
            "FLAC frame CRC-16 mismatch (stored 0x${storedCrc.toString(16)}, computed 0x${computedCrc.toString(16)})",
            storedCrcOffset, 2)
        else add("frame.crc", "Frame CRC", CheckStatus.PASS, "Frame CRC-16 matches", storedCrcOffset, 2)
        frameCount++
        if (next == null) break
        frameStart = next.frameStart
    }
    if (frameCount == 0 && items.none { it.id == "flac.frame.header" || it.id == "flac.frame.truncated" }) {
        add("frame.missing", "FLAC frames", CheckStatus.WARN, "No valid audio frame was found", framesStart)
    }
    return items
}

private data class FlacFrameHeader(val headerLength: Long, val crcValid: Boolean)

private fun parseFlacFrameHeader(reader: ByteReader, offset: Long, end: Long): FlacFrameHeader? {
    if (offset < 0 || end - offset < 6) return null
    val b0 = reader.readUInt8(offset)
    val b1 = reader.readUInt8(offset + 1)
    val b2 = reader.readUInt8(offset + 2)
    val b3 = reader.readUInt8(offset + 3)
    if (b0 != 0xff || (b1 and 0xfe) != 0xf8 || (b2 ushr 4) == 0 || (b3 and 1) != 0 ||
        ((b3 ushr 4) > 10) || (b2 and 0x0f) == 15) return null
    var p = offset + 4
    val first = reader.readUInt8(p)
    val continuationCount = when {
        first and 0x80 == 0 -> 0
        first and 0xe0 == 0xc0 -> 1
        first and 0xf0 == 0xe0 -> 2
        first and 0xf8 == 0xf0 -> 3
        first and 0xfc == 0xf8 -> 4
        first and 0xfe == 0xfc -> 5
        first == 0xfe -> 6
        else -> return null
    }
    if (p + continuationCount >= end) return null
    repeat(continuationCount) {
        p++
        if (reader.readUInt8(p) and 0xc0 != 0x80) return null
    }
    p++
    val blockSizeCode = b2 ushr 4
    if (blockSizeCode == 6) p++ else if (blockSizeCode == 7) p += 2
    val sampleRateCode = b2 and 0x0f
    if (sampleRateCode == 12) p++ else if (sampleRateCode == 13 || sampleRateCode == 14) p += 2
    if (p >= end) return null
    val headerLength = p - offset + 1
    if (p + 1 > end) return null
    val expected = reader.readUInt8(p)
    val actual = crc8(reader, offset, headerLength - 1)
    return FlacFrameHeader(headerLength, expected == actual)
}

private data class FlacNextFrame(val frameStart: Long, val boundaryEnd: Long, val hasNonZeroGap: Boolean)

private fun findNextFlacHeader(reader: ByteReader, start: Long, end: Long): Long? {
    var position = start
    while (position <= end - 6) {
        checkAudioScanActive()
        if (parseFlacFrameHeader(reader, position, end)?.crcValid == true) return position
        position++
    }
    return null
}

private fun findNextFlacFrame(reader: ByteReader, frameStart: Long, searchStart: Long, end: Long): FlacNextFrame? {
    var position = frameStart
    var crc = 0
    var boundaryEnd: Long? = null
    var hasNonZeroGap = false
    val minBoundary = searchStart + 2
    while (position < end) {
        checkAudioScanActive()
        val previousCrc = crc
        val currentByte = reader.readUInt8(position)
        crc = updateCrc16(crc, currentByte)
        position++
        if (position >= minBoundary && crc == 0 && !(previousCrc == 0 && currentByte == 0)) {
            boundaryEnd = position
            hasNonZeroGap = false
        } else if (boundaryEnd != null && position > boundaryEnd && currentByte != 0) {
            hasNonZeroGap = true
        }
        if (position >= searchStart && position <= end - 6 && boundaryEnd != null) {
            val header = parseFlacFrameHeader(reader, position, end)
            if (header?.crcValid == true) return FlacNextFrame(position, boundaryEnd, hasNonZeroGap)
        }
    }
    return null
}

private fun updateCrc16(initial: Int, value: Int): Int {
    var crc = initial xor ((value and 0xff) shl 8)
    repeat(8) { crc = if (crc and 0x8000 != 0) ((crc shl 1) xor 0x8005) and 0xffff else (crc shl 1) and 0xffff }
    return crc
}

private fun crc8(reader: ByteReader, start: Long, length: Long): Int {
    var crc = 0
    var position = start
    val end = start + length
    while (position < end) {
        checkAudioScanActive()
        val size = minOf(FLAC_SCAN_BUFFER.toLong(), end - position).toInt()
        val bytes = reader.readBytes(position, size)
        for (byte in bytes) {
            crc = crc xor (byte.toInt() and 0xff)
            repeat(8) { crc = if (crc and 0x80 != 0) ((crc shl 1) xor 0x07) and 0xff else (crc shl 1) and 0xff }
        }
        position += size
    }
    return crc
}

private fun crc16(reader: ByteReader, start: Long, length: Long): Int {
    var crc = 0
    var position = start
    val end = start + length
    while (position < end) {
        checkAudioScanActive()
        val size = minOf(FLAC_SCAN_BUFFER.toLong(), end - position).toInt()
        val bytes = reader.readBytes(position, size)
        for (byte in bytes) {
            crc = crc xor ((byte.toInt() and 0xff) shl 8)
            repeat(8) { crc = if (crc and 0x8000 != 0) ((crc shl 1) xor 0x8005) and 0xffff else (crc shl 1) and 0xffff }
        }
        position += size
    }
    return crc
}
