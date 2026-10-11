package com.multiviewer.parser.integrity

import com.multiviewer.parser.ByteReader

private const val OGG_MAX_ITEMS = 1000
private const val OGG_MAX_STREAMS = 4096
private const val OGG_UINT32_MASK = 0xffff_ffffL

internal fun checkOggStructure(reader: ByteReader): List<IntegrityCheckItem> {
    val items = mutableListOf<IntegrityCheckItem>()
    var findingsTruncated = false
    fun add(id: String, title: String, status: CheckStatus, detail: String, offset: Long? = null, length: Long? = null) {
        if (items.size < OGG_MAX_ITEMS) items += IntegrityCheckItem("ogg.$id", title, status, detail, offset, length)
        else findingsTruncated = true
    }
    val streams = HashMap<Long, OggStreamState>()
    var position = 0L
    var pageCount = 0L
    while (position < reader.length) {
        checkAudioScanActive()
        if (reader.length - position < 27) {
            add("page.bounds", "Ogg page header", CheckStatus.FAIL, "Fewer than 27 bytes remain for an Ogg page header", position, reader.length - position)
            break
        }
        if (reader.readFourCC(position) != "OggS") {
            add("page.capture", "Ogg capture pattern", CheckStatus.FAIL, "Missing OggS capture pattern", position, 4)
            break
        }
        val version = reader.readUInt8(position + 4)
        val flags = reader.readUInt8(position + 5)
        if (version != 0) add("page.version", "Ogg page version", CheckStatus.FAIL, "Unsupported Ogg page version $version", position + 4, 1)
        if (flags and 0xf8 != 0) add("page.flags", "Ogg page flags", CheckStatus.FAIL, "Reserved page flag bits are set", position + 5, 1)
        val serial = readU32Le(reader, position + 14)
        val sequence = readU32Le(reader, position + 18)
        val segmentCount = reader.readUInt8(position + 26)
        val segmentTableStart = position + 27
        val bodyStart = segmentTableStart + segmentCount
        if (bodyStart < segmentTableStart || bodyStart > reader.length) {
            add("page.bounds", "Ogg segment table", CheckStatus.FAIL, "Segment table extends past end of file", position + 26, 1)
            break
        }
        var bodyLength = 0L
        val lacing = IntArray(segmentCount)
        for (index in 0 until segmentCount) {
            val lace = reader.readUInt8(segmentTableStart + index)
            lacing[index] = lace
            bodyLength += lace
        }
        val pageEnd = bodyStart + bodyLength
        if (pageEnd < bodyStart || pageEnd > reader.length) {
            add("page.bounds", "Ogg page bounds", CheckStatus.FAIL, "Page body extends past end of file", position, reader.length - position)
            break
        }
        val pageLength = pageEnd - position
        val storedCrc = readU32Le(reader, position + 22)
        val computedCrc = oggCrc(reader, position, pageLength)
        if (storedCrc != computedCrc) add("page.crc", "Ogg page CRC", CheckStatus.FAIL,
            "Stored CRC 0x${storedCrc.toString(16)} does not match computed 0x${computedCrc.toString(16)}", position + 22, 4)

        var state = streams[serial]
        if (state == null) {
            if (streams.size >= OGG_MAX_STREAMS) {
                add("streams.limit", "Logical stream limit", CheckStatus.WARN,
                    "More than $OGG_MAX_STREAMS logical streams; remaining streams are not tracked", position)
                position = reader.length
                break
            }
            state = OggStreamState(sequence, false, false)
            streams[serial] = state
        }
        val isBos = flags and 0x02 != 0
        val isEos = flags and 0x04 != 0
        if (pageCount == 0L || state.pageCount == 0L) {
            if (sequence != 0L) add("page.sequence", "Ogg page sequence", CheckStatus.WARN,
                "First observed page for serial $serial has sequence $sequence, not zero", position + 18, 4)
            if (!isBos) add("stream.bos", "Beginning-of-stream flag", CheckStatus.WARN,
                "First observed page for serial $serial does not carry BOS", position + 5, 1)
        } else {
            val expected = (state.lastSequence + 1) and OGG_UINT32_MASK
            if (sequence != expected) add("page.sequence", "Ogg page sequence", CheckStatus.FAIL,
                "Serial $serial expected sequence $expected but found $sequence", position + 18, 4)
            if (isBos) add("stream.bos", "Beginning-of-stream flag", CheckStatus.FAIL,
                "BOS flag is repeated after the first page", position + 5, 1)
        }
        if (state.ended) add("stream.afterEos", "End-of-stream flag", CheckStatus.FAIL,
            "A page appears after EOS for serial $serial", position + 5, 1)
        state.lastSequence = sequence
        state.pageCount++
        if (isEos) state.ended = true
        inspectFirstPacket(reader, state, bodyStart, lacing, flags)
        pageCount++
        position = pageEnd
    }
    if (pageCount == 0L) add("page.missing", "Ogg pages", CheckStatus.FAIL, "No complete Ogg page found")
    for ((serial, state) in streams) {
        if (!state.ended) add("stream.eos", "End-of-stream flag", CheckStatus.WARN,
            "No EOS page was observed for serial $serial")
        if (state.codecFailure != null) add("codec.header", "Ogg codec header", CheckStatus.FAIL,
            "Invalid ${state.codecName} identification header: ${state.codecFailure}")
        else if (!state.codecChecked) add("codec", "Ogg codec header", CheckStatus.SKIP,
            "Codec identification packet is incomplete or unavailable for serial $serial")
        else if (!state.codecSupported) add("codec", "Ogg codec", CheckStatus.SKIP,
            "Codec-specific checks are unavailable for serial $serial")
    }
    if (pageCount > 0) add("pages", "Ogg pages", CheckStatus.PASS, "$pageCount complete Ogg page(s) checked")
    if (findingsTruncated) items += IntegrityCheckItem("ogg.findings.truncated", "Finding limit", CheckStatus.WARN,
        "More than $OGG_MAX_ITEMS findings occurred; additional findings were omitted")
    return items
}

private data class OggStreamState(var lastSequence: Long, var ended: Boolean, var codecChecked: Boolean) {
    var pageCount: Long = 0
    val firstPacket = ByteArray(64)
    var firstPacketSize = 0
    var firstPacketComplete = false
    var codecSupported = false
    var codecName = "unknown"
    var codecFailure: String? = null
}

private fun inspectFirstPacket(reader: ByteReader, state: OggStreamState, bodyStart: Long, lacing: IntArray, flags: Int) {
    if (state.codecChecked || flags and 0x01 != 0 && state.pageCount == 1L) return
    var offset = bodyStart
    for (lace in lacing) {
        val remaining = (state.firstPacket.size - state.firstPacketSize).coerceAtLeast(0)
        val copy = minOf(remaining, lace)
        if (copy > 0) reader.readBytes(offset, copy).copyInto(state.firstPacket, state.firstPacketSize)
        state.firstPacketSize += copy
        offset += lace
        if (lace < 255) {
            state.firstPacketComplete = true
            validateOggCodec(state)
            return
        }
        if (state.firstPacketSize == state.firstPacket.size) return
    }
}

private fun validateOggCodec(state: OggStreamState) {
    val packet = state.firstPacket
    val size = state.firstPacketSize
    when {
        size >= 19 && String(packet, 0, 8, Charsets.US_ASCII) == "OpusHead" -> {
            state.codecName = "Opus"
            val channels = packet[9].toInt() and 0xff
            if (channels == 0) state.codecFailure = "channel count is zero"
            else state.codecSupported = true
            state.codecChecked = true
        }
        size >= 30 && packet[0] == 1.toByte() && String(packet, 1, 6, Charsets.US_ASCII) == "vorbis" -> {
            state.codecName = "Vorbis"
            val channels = packet[11].toInt() and 0xff
            val version = readU32Le(packet, 7)
            if (channels == 0 || version != 0L) state.codecFailure = "version=$version, channels=$channels"
            else state.codecSupported = true
            state.codecChecked = true
        }
        else -> state.codecChecked = true // unknown codec: generic page checks still apply
    }
}

private fun oggCrc(reader: ByteReader, start: Long, length: Long): Long {
    var crc = 0L
    var position = 0L
    while (position < length) {
        checkAudioScanActive()
        val count = minOf(64 * 1024L, length - position).toInt()
        val bytes = reader.readBytes(start + position, count)
        for (index in bytes.indices) {
            val absolute = position + index
            val value = if (absolute in 22L..25L) 0 else bytes[index].toInt() and 0xff
            crc = crc xor (value.toLong() shl 24)
            repeat(8) { crc = if (crc and 0x8000_0000L != 0L) (crc shl 1) xor 0x04c1_1db7L else crc shl 1 }
            crc = crc and OGG_UINT32_MASK
        }
        position += count
    }
    return crc
}

private fun readU32Le(reader: ByteReader, offset: Long): Long {
    val b = reader.readBytes(offset, 4)
    return (b[0].toLong() and 0xff) or ((b[1].toLong() and 0xff) shl 8) or
        ((b[2].toLong() and 0xff) shl 16) or ((b[3].toLong() and 0xff) shl 24)
}

private fun readU32Le(bytes: ByteArray, offset: Int): Long =
    (bytes[offset].toLong() and 0xff) or ((bytes[offset + 1].toLong() and 0xff) shl 8) or
        ((bytes[offset + 2].toLong() and 0xff) shl 16) or ((bytes[offset + 3].toLong() and 0xff) shl 24)
