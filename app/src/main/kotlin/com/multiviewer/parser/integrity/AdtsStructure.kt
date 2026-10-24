package com.multiviewer.parser.integrity

import com.multiviewer.parser.ByteReader
import com.multiviewer.parser.id3v2StoredEnd

private val ADTS_SAMPLE_RATES = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)

internal fun checkAdtsStructure(reader: ByteReader): List<IntegrityCheckItem> {
    val items = mutableListOf<IntegrityCheckItem>()
    fun add(id: String, title: String, status: CheckStatus, detail: String, offset: Long? = null, length: Long? = null) {
        items += IntegrityCheckItem("adts.$id", title, status, detail, offset, length)
    }
    var position = 0L
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
        position = 10 + sizeBytes.fold(0L) { acc, b -> (acc shl 7) or (b.toLong() and 0x7f) }
        if (position > reader.length) {
            add("id3.bounds", "ID3v2 bounds", CheckStatus.FAIL, "ID3v2 tag extends past end of file", 6, 4)
            return items
        }
        if (reader.readUInt8(3) == 4 && reader.readUInt8(5) and 16 != 0) {
            val storedEnd = id3v2StoredEnd(reader)
            if (storedEnd == null) {
                add("id3.footer", "ID3v2 footer", CheckStatus.FAIL, "ID3v2 footer is missing or mismatched", position, minOf(10L, reader.length - position))
                return items
            }
            position = storedEnd
        }
    }
    if (position == reader.length) {
        add("frame.missing", "ADTS frames", CheckStatus.FAIL, "No ADTS frame data follows the optional tag", position)
        return items
    }
    var frames = 0
    var crcNoted = false
    while (position < reader.length) {
        checkAudioScanActive()
        if (reader.length - position < 7) {
            add("frame.truncated", "ADTS frame header", CheckStatus.FAIL, "Fewer than 7 bytes remain for an ADTS header", position, reader.length - position)
            break
        }
        val header = parseAdtsHeader(reader, position)
        if (header == null) {
            val next = findAdtsHeader(reader, position + 1)
            if (next == null) {
                add(if (frames == 0) "frame.header" else "frame.gap", "ADTS frame synchronization", CheckStatus.FAIL,
                    "Invalid ADTS header or junk data at byte $position", position, reader.length - position)
                break
            }
            add("frame.gap", "ADTS frame continuity", CheckStatus.FAIL,
                "${next - position} byte(s) between ADTS frames", position, next - position)
            position = next
            continue
        }
        if (header.sampleRate == null) {
            add("sampleRate", "ADTS sample rate", CheckStatus.FAIL,
                "Reserved sampling-frequency index ${header.frequencyIndex}", position + 2, 1)
        }
        if (header.frameLength < header.headerLength) {
            add("frame.length", "ADTS frame length", CheckStatus.FAIL,
                "Frame length ${header.frameLength} is shorter than its ${header.headerLength}-byte header", position + 3, 3)
            position += 1
            continue
        }
        if (header.frameLength.toLong() > reader.length - position) {
            add("frame.truncated", "ADTS frame extent", CheckStatus.FAIL,
                "Frame declares ${header.frameLength} bytes but only ${reader.length - position} remain", position, reader.length - position)
            break
        }
        if (header.crcPresent && !crcNoted) {
            add("crc.unchecked", "ADTS payload CRC", CheckStatus.SKIP,
                "CRC protection is present, but AAC payload CRC is not recomputed by this structural check", position + 1, 1)
            crcNoted = true
        }
        frames++
        position += header.frameLength
    }
    if (frames > 0) add("frames", "ADTS frames", CheckStatus.PASS, "$frames complete ADTS frame(s)")
    if (frames == 0 && items.isEmpty()) add("frame.missing", "ADTS frames", CheckStatus.FAIL, "No ADTS frame was found")
    return items
}

private data class AdtsHeader(val headerLength: Int, val frameLength: Int, val frequencyIndex: Int, val sampleRate: Int?, val crcPresent: Boolean)

private fun parseAdtsHeader(reader: ByteReader, position: Long): AdtsHeader? {
    val b0 = reader.readUInt8(position)
    val b1 = reader.readUInt8(position + 1)
    if (b0 != 0xff || b1 and 0xf0 != 0xf0 || b1 and 0x06 != 0) return null
    val b2 = reader.readUInt8(position + 2)
    val b3 = reader.readUInt8(position + 3)
    val b4 = reader.readUInt8(position + 4)
    val b5 = reader.readUInt8(position + 5)
    val frequencyIndex = b2 shr 2 and 0x0f
    val frameLength = ((b3 and 3) shl 11) or (b4 shl 3) or (b5 shr 5)
    val crcPresent = b1 and 1 == 0
    return AdtsHeader(if (crcPresent) 9 else 7, frameLength, frequencyIndex,
        ADTS_SAMPLE_RATES.getOrNull(frequencyIndex), crcPresent)
}

private fun findAdtsHeader(reader: ByteReader, start: Long): Long? {
    var position = start
    while (position <= reader.length - 7) {
        checkAudioScanActive()
        if (parseAdtsHeader(reader, position) != null) return position
        position++
    }
    return null
}
