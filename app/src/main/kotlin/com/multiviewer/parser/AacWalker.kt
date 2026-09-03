package com.multiviewer.parser

private val AAC_SAMPLING_FREQUENCIES = intArrayOf(
    96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350,
)

private val AAC_PROFILES = arrayOf("Main", "LC (Low Complexity)", "SSR", "LTP")

private val AAC_CHANNELS = arrayOf(
    "Custom / Defined in AOT",
    "1 channel (Mono)",
    "2 channels (Stereo)",
    "3 channels (Center, Front L/R)",
    "4 channels (Center, Front L/R, Rear Center)",
    "5 channels (Center, Front L/R, Surround L/R)",
    "6 channels (5.1 Surround)",
    "8 channels (7.1 Surround)",
)

fun parseAac(reader: ByteReader, start: Long, end: Long): List<BoxNode> {
    val result = mutableListOf<BoxNode>()
    var pos = start

    // Check for optional ID3v2 tag at the beginning
    if (end - pos >= 10 && String(reader.readBytes(pos, 3), Charsets.US_ASCII) == "ID3") {
        val id3Node = decodeId3v2(reader, pos, end)
        result.add(id3Node)
        pos = id3Node.offset + id3Node.size
    }

    // Scan for first valid ADTS syncword (0xFFF)
    val syncPos = findAdtsSync(reader, pos, minOf(pos + 65536L, end))
    if (syncPos != null) {
        pos = syncPos
    } else {
        if (pos < end) {
            result.add(BoxNode("AAC", pos, 0, end - pos, warnings = listOf("Could not find valid ADTS syncword")))
        }
        return result
    }

    var frameIndex = 0
    val maxIndividualFrames = 25
    var totalFramesCount = 0
    val audioDataStart = pos

    var firstSampleRate = 44100
    var firstChannels = "Stereo"
    var firstProfile = "LC"

    while (pos + 7 <= end) {
        val b0 = reader.readUInt8(pos)
        val b1 = reader.readUInt8(pos + 1)
        if (b0 != 0xFF || (b1 and 0xF0) != 0xF0) {
            // Re-sync if corrupted
            val nextSync = findAdtsSync(reader, pos + 1, minOf(pos + 4096L, end))
            if (nextSync != null) {
                pos = nextSync
                continue
            } else {
                break
            }
        }

        val id = (b1 shr 3) and 0x01
        val layer = (b1 shr 1) and 0x03
        val protectionAbsent = b1 and 0x01
        val headerSize = if (protectionAbsent == 1) 7 else 9

        val b2 = reader.readUInt8(pos + 2)
        val b3 = reader.readUInt8(pos + 3)
        val b4 = reader.readUInt8(pos + 4)
        val b5 = reader.readUInt8(pos + 5)
        val b6 = reader.readUInt8(pos + 6)

        val profileIdx = (b2 shr 6) and 0x03
        val sampleRateIdx = (b2 shr 2) and 0x0F
        val channelConfig = ((b2 and 0x01) shl 2) or ((b3 shr 6) and 0x03)
        val frameLength = ((b3 and 0x03) shl 11) or (b4 shl 3) or ((b5 shr 5) and 0x07)

        if (frameLength < headerSize || pos + frameLength > end) {
            break
        }

        val sampleRate = AAC_SAMPLING_FREQUENCIES.getOrElse(sampleRateIdx) { 44100 }
        val profileName = AAC_PROFILES.getOrElse(profileIdx) { "Profile $profileIdx" }
        val channelsName = AAC_CHANNELS.getOrElse(channelConfig) { "$channelConfig channels" }

        if (totalFramesCount == 0) {
            firstSampleRate = sampleRate
            firstChannels = channelsName
            firstProfile = profileName
        }

        totalFramesCount++
        if (frameIndex < maxIndividualFrames) {
            val fields = mutableListOf<BoxField>()
            fields.add(BoxField("mpeg_version", if (id == 0) "MPEG-4" else "MPEG-2", pos + 1, 1))
            fields.add(BoxField("profile", profileName, pos + 2, 1))
            fields.add(BoxField("sample_rate", "${sampleRate}Hz", pos + 2, 1))
            fields.add(BoxField("channels", channelsName, pos + 2, 2))
            fields.add(BoxField("frame_length", "$frameLength bytes", pos + 3, 3))
            fields.add(BoxField("crc_present", (protectionAbsent == 0).toString(), pos + 1, 1))

            result.add(
                BoxNode(
                    type = "ADTS Frame #$frameIndex",
                    offset = pos,
                    headerSize = headerSize,
                    size = frameLength.toLong(),
                    fields = fields,
                    summary = "AAC $profileName, ${sampleRate}Hz, $channelsName ($frameLength bytes)",
                )
            )
            frameIndex++
        }

        pos += frameLength
    }

    if (pos < end) {
        val remainingBytes = end - pos
        result.add(
            BoxNode(
                type = "AudioFrames",
                offset = pos,
                headerSize = 0,
                size = remainingBytes,
                summary = "Remaining ADTS audio frames (~$remainingBytes bytes)",
            )
        )
    }

    return result
}

internal fun isAdtsMagic(reader: ByteReader): Boolean {
    if (reader.length < 2) return false
    var pos = 0L
    if (reader.length >= 10 && String(reader.readBytes(0, 3), Charsets.US_ASCII) == "ID3") {
        val tagSize = readSyncsafeUInt32(reader, 6)
        pos = minOf(10L + tagSize, reader.length - 2)
    }
    if (pos + 2 > reader.length) return false
    val b0 = reader.readUInt8(pos)
    val b1 = reader.readUInt8(pos + 1)
    return b0 == 0xFF && (b1 and 0xF0) == 0xF0 && ((b1 shr 1) and 0x03) == 0
}

private fun findAdtsSync(reader: ByteReader, start: Long, limit: Long): Long? {
    var p = start
    while (p + 2 <= limit) {
        val b0 = reader.readUInt8(p)
        val b1 = reader.readUInt8(p + 1)
        if (b0 == 0xFF && (b1 and 0xF0) == 0xF0 && ((b1 shr 1) and 0x03) == 0) {
            return p
        }
        p++
    }
    return null
}
