package com.multiviewer.parser.integrity

import com.multiviewer.parser.ByteReader

private const val MPEG_SYNC_SCAN_LIMIT = 65_536L
private val MPEG1_BITRATES = arrayOf(
    intArrayOf(0, 32, 64, 96, 128, 160, 192, 224, 256, 288, 320, 352, 384, 416, 448),
    intArrayOf(0, 32, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384),
    intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320),
)
private val MPEG2_BITRATES = arrayOf(
    intArrayOf(0, 32, 48, 56, 64, 80, 96, 112, 128, 144, 160, 176, 192, 224, 256),
    intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160),
    intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160),
)
private val MPEG_SAMPLE_RATES = intArrayOf(44100, 48000, 32000)

internal fun checkMp3Structure(reader: ByteReader): List<IntegrityCheckItem> {
    val items = mutableListOf<IntegrityCheckItem>()
    fun add(id: String, title: String, status: CheckStatus, detail: String, offset: Long? = null, length: Long? = null) {
        items += IntegrityCheckItem("mp3.$id", title, status, detail, offset, length)
    }
    var start = 0L
    if (reader.length >= 3 && String(reader.readBytes(0, 3), Charsets.US_ASCII) == "ID3") {
        if (reader.length < 10) {
            add("id3.header", "ID3v2 header", CheckStatus.FAIL, "ID3v2 tag header is truncated", 0, reader.length)
            return items
        }
        val sizeBytes = reader.readBytes(6, 4)
        if (sizeBytes.any { it.toInt() and 0x80 != 0 }) {
            add("id3.size", "ID3v2 size", CheckStatus.FAIL, "ID3v2 size is not syncsafe", 6, 4)
            return items
        }
        val size = sizeBytes.fold(0L) { acc, b -> (acc shl 7) or (b.toLong() and 0x7f) }
        start = 10 + size
        if (start > reader.length) {
            add("id3.bounds", "ID3v2 bounds", CheckStatus.FAIL, "ID3v2 tag extends past end of file", 6, 4)
            return items
        }
    }
    var audioEnd = reader.length
    if (audioEnd >= 128 && String(reader.readBytes(audioEnd - 128, 3), Charsets.US_ASCII) == "TAG") audioEnd -= 128
    if (audioEnd >= 32 && String(reader.readBytes(audioEnd - 32, 8), Charsets.US_ASCII) == "APETAGEX") {
        val tagSize = readU32Le(reader, audioEnd - 20)
        if (tagSize in 32..audioEnd) audioEnd -= tagSize
        else add("ape.bounds", "APE tag bounds", CheckStatus.FAIL, "APE tag size $tagSize is outside the file", audioEnd - 20, 4)
    }
    val first = findMpegFrame(reader, start, minOf(start + MPEG_SYNC_SCAN_LIMIT, audioEnd))
    if (first == null) {
        val free = findFreeFormatFrame(reader, start, minOf(start + MPEG_SYNC_SCAN_LIMIT, audioEnd))
        if (free != null) add("freeFormat", "Free-format MPEG audio", CheckStatus.SKIP,
            "MPEG free-format frame bitrate cannot be derived without locating frame boundaries", free, 4)
        else add("frame.header", "MPEG audio frame", CheckStatus.FAIL, "No valid MPEG audio frame found", start, audioEnd - start)
        return items
    }
    val firstFrame = first ?: return items
    if (firstFrame > start) add("frame.leading", "Leading bytes", CheckStatus.WARN,
        "Skipped ${firstFrame - start} byte(s) before the first valid MPEG frame", start, firstFrame - start)

    var position = firstFrame
    var frameCount = 0L
    var frameBytes = 0L
    var baseline: MpegFrame? = null
    var xing: VbrInfo? = null
    var vbri: VbrInfo? = null
    while (position < audioEnd) {
        checkAudioScanActive()
        val current = parseMpegFrame(reader, position, audioEnd)
        if (current == null) {
            val next = findMpegFrame(reader, position + 1, audioEnd)
            if (next == null) {
                add("frame.gap", "MPEG frame continuity", CheckStatus.FAIL,
                    "Invalid data or truncated frame at byte $position", position, audioEnd - position)
                break
            }
            add("frame.gap", "MPEG frame continuity", CheckStatus.FAIL,
                "${next - position} byte(s) between MPEG frames", position, next - position)
            position = next
            continue
        }
        if (current.freeFormat) {
            add("freeFormat", "Free-format MPEG audio", CheckStatus.SKIP,
                "Free-format frame length is not derivable from a bitrate field", position, 4)
            break
        }
        if (current.length <= 0 || position + current.length > audioEnd) {
            add("frame.truncated", "MPEG frame extent", CheckStatus.FAIL,
                "Frame declares ${current.length} bytes but only ${audioEnd - position} remain", position, audioEnd - position)
            break
        }
        val reference = baseline
        if (reference != null && (current.version != reference.version || current.layer != reference.layer || current.sampleRate != reference.sampleRate)) {
            add("frame.continuity", "MPEG stream continuity", CheckStatus.FAIL,
                "MPEG version/layer/sample-rate changed from ${reference.version}/${reference.layer}/${reference.sampleRate} Hz to ${current.version}/${current.layer}/${current.sampleRate} Hz",
                position, 4)
        }
        if (baseline == null) {
            baseline = current
            val sideInfo = if (current.version == 1) { if (current.channelMode == 3) 17 else 32 } else { if (current.channelMode == 3) 9 else 17 }
            val xingOffset = position + 4 + sideInfo
            if (xingOffset + 12 <= position + current.length) {
                val marker = reader.readFourCC(xingOffset)
                if (marker == "Xing" || marker == "Info") {
                    val flags = readU32Be(reader, xingOffset + 4)
                    var p = xingOffset + 8
                    val count = if (flags and 1L != 0L) readU32Be(reader, p).also { p += 4 } else null
                    val bytes = if (flags and 2L != 0L) readU32Be(reader, p) else null
                    xing = VbrInfo(count, bytes, xingOffset)
                }
            }
            val vbriOffset = position + 4 + 32
            if (vbriOffset + 18 <= position + current.length && reader.readFourCC(vbriOffset) == "VBRI") {
                vbri = VbrInfo(readU32Be(reader, vbriOffset + 14), readU32Be(reader, vbriOffset + 10), vbriOffset)
            }
        }
        frameCount++
        frameBytes += current.length
        position += current.length
    }
    if (frameCount > 0) add("frames", "MPEG frames", CheckStatus.PASS, "$frameCount complete MPEG frame(s), $frameBytes bytes", firstFrame, frameBytes)
    xing?.let { info -> compareVbr("xing", "Xing/Info", info, frameCount, frameBytes, ::add) }
    vbri?.let { info -> compareVbr("vbri", "VBRI", info, frameCount, frameBytes, ::add) }
    return items
}

internal data class MpegFrame(
    val version: Int,
    val layer: Int,
    val sampleRate: Int,
    val channelMode: Int,
    val length: Int,
    val freeFormat: Boolean,
)

private data class VbrInfo(val frames: Long?, val bytes: Long?, val offset: Long)

internal fun parseMpegFrame(reader: ByteReader, position: Long, end: Long): MpegFrame? {
    if (end - position < 4) return null
    val b0 = reader.readUInt8(position)
    val b1 = reader.readUInt8(position + 1)
    val b2 = reader.readUInt8(position + 2)
    val b3 = reader.readUInt8(position + 3)
    if (b0 != 0xff || b1 and 0xe0 != 0xe0) return null
    val versionBits = b1 shr 3 and 3
    val layerBits = b1 shr 1 and 3
    val bitrateIndex = b2 shr 4 and 0xf
    val rateIndex = b2 shr 2 and 3
    if (versionBits == 1 || layerBits == 0 || bitrateIndex == 15 || rateIndex == 3) return null
    val version = when (versionBits) { 3 -> 1; 2 -> 2; else -> 25 }
    val layer = 4 - layerBits
    val sampleRate = MPEG_SAMPLE_RATES[rateIndex] / when (versionBits) { 3 -> 1; 2 -> 2; else -> 4 }
    val channelMode = b3 shr 6 and 3
    if (bitrateIndex == 0) return MpegFrame(version, layer, sampleRate, channelMode, 0, true)
    val table = if (version == 1) MPEG1_BITRATES[layer - 1] else MPEG2_BITRATES[layer - 1]
    val bitrate = table[bitrateIndex] * 1000
    val padding = b2 shr 1 and 1
    val length = when (layer) {
        1 -> (12 * bitrate / sampleRate + padding) * 4
        3 -> (if (version == 1) 144 else 72) * bitrate / sampleRate + padding
        else -> 144 * bitrate / sampleRate + padding
    }
    return MpegFrame(version, layer, sampleRate, channelMode, length, false)
}

private fun findMpegFrame(reader: ByteReader, start: Long, end: Long): Long? {
    var position = start
    while (position <= end - 4) {
        checkAudioScanActive()
        val frame = parseMpegFrame(reader, position, end)
        if (frame != null && !frame.freeFormat) return position
        position++
    }
    return null
}

private fun findFreeFormatFrame(reader: ByteReader, start: Long, end: Long): Long? {
    var position = start
    while (position <= end - 4) {
        checkAudioScanActive()
        val frame = parseMpegFrame(reader, position, end)
        if (frame?.freeFormat == true) return position
        position++
    }
    return null
}

private fun compareVbr(
    id: String,
    label: String,
    info: VbrInfo,
    observedFrames: Long,
    observedBytes: Long,
    add: (String, String, CheckStatus, String, Long?, Long?) -> Unit,
) {
    val mismatch = info.frames?.let { it != observedFrames } == true || info.bytes?.let { it != observedBytes } == true
    add("$id.count", "$label counts", if (mismatch) CheckStatus.WARN else CheckStatus.PASS,
        "$label declares ${info.frames ?: "unknown"} frame(s) and ${info.bytes ?: "unknown"} byte(s); observed $observedFrames frame(s), $observedBytes bytes",
        info.offset, 4)
}

private fun readU32Be(reader: ByteReader, offset: Long): Long = reader.readUInt32(offset)

private fun readU32Le(reader: ByteReader, offset: Long): Long {
    val b = reader.readBytes(offset, 4)
    return (b[0].toLong() and 0xff) or ((b[1].toLong() and 0xff) shl 8) or
        ((b[2].toLong() and 0xff) shl 16) or ((b[3].toLong() and 0xff) shl 24)
}
