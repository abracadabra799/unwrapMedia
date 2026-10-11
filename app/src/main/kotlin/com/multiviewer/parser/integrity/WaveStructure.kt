package com.multiviewer.parser.integrity

import com.multiviewer.parser.ByteReader

internal fun checkWaveStructure(reader: ByteReader): List<IntegrityCheckItem> {
    val items = mutableListOf<IntegrityCheckItem>()
    fun add(id: String, title: String, status: CheckStatus, detail: String, offset: Long? = null, length: Long? = null) {
        items += IntegrityCheckItem("wave.$id", title, status, detail, offset, length)
    }
    val fileLength = reader.length
    if (fileLength < 12 || reader.readFourCC(0) != "RIFF" || reader.readFourCC(8) != "WAVE") {
        return listOf(IntegrityCheckItem("wave.signature", "RIFF/WAVE header", CheckStatus.FAIL,
            "File does not contain a complete RIFF/WAVE header", 0, minOf(fileLength, 12)))
    }
    val declaredSize = readU32Le(reader, 4) + 8
    if (declaredSize < 12) {
        add("riff.size", "RIFF extent", CheckStatus.FAIL, "Declared RIFF size is smaller than its header", 4, 4)
        return items
    }
    val limit = minOf(declaredSize, fileLength)
    add("riff.size", "RIFF extent", if (declaredSize > fileLength) CheckStatus.FAIL else CheckStatus.PASS,
        if (declaredSize > fileLength) "Declared RIFF extent $declaredSize bytes exceeds file size $fileLength" else "RIFF extent is within the file",
        4, 4)
    if (declaredSize < fileLength) add("riff.trailing", "Trailing bytes", CheckStatus.WARN,
        "${fileLength - declaredSize} byte(s) follow the declared RIFF extent", declaredSize, fileLength - declaredSize)

    var position = 12L
    var fmtCount = 0
    var dataCount = 0
    var fmt: WaveFormat? = null
    var dataBytes = 0L
    while (position < limit) {
        checkAudioScanActive()
        val remaining = limit - position
        if (remaining < 8) {
            add("chunk.header", "Chunk header", CheckStatus.FAIL, "$remaining trailing byte(s) cannot contain a chunk header", position, remaining)
            break
        }
        val id = reader.readFourCC(position)
        val payloadSize = readU32Le(reader, position + 4)
        val payloadStart = position + 8
        val payloadEnd = payloadStart + payloadSize
        if (payloadEnd < payloadStart || payloadEnd > limit) {
            add("chunk.bounds", "Chunk bounds ($id)", CheckStatus.FAIL,
                "Chunk payload ending at $payloadEnd exceeds RIFF extent $limit", position, 8)
            break
        }
        if (id == "fmt ") {
            fmtCount++
            if (payloadSize < 16) {
                add("fmt.size", "Format chunk size", CheckStatus.FAIL, "fmt chunk is $payloadSize bytes; at least 16 are required", position, 8 + payloadSize)
            } else {
                fmt = WaveFormat(
                    reader.readUInt16Le(payloadStart), reader.readUInt16Le(payloadStart + 2),
                    readU32Le(reader, payloadStart + 4), readU32Le(reader, payloadStart + 8),
                    reader.readUInt16Le(payloadStart + 12), reader.readUInt16Le(payloadStart + 14), payloadSize,
                )
                add("fmt.present", "Format chunk", CheckStatus.PASS, "fmt chunk is present", position, 8 + payloadSize)
                if (fmt!!.tag == 0xFFFE && payloadSize < 40) {
                    add("fmt.extensible", "Extensible format", CheckStatus.FAIL, "WAVE_FORMAT_EXTENSIBLE fmt chunk is shorter than 40 bytes", position, 8 + payloadSize)
                }
            }
        } else if (id == "data") {
            dataCount++
            dataBytes += payloadSize
            add("data.bounds", "Audio data chunk", CheckStatus.PASS, "$payloadSize audio byte(s) are within the RIFF extent", position, 8 + payloadSize)
        }
        val paddedEnd = payloadEnd + (payloadSize and 1L)
        if (paddedEnd < payloadEnd || paddedEnd > limit) {
            add("chunk.padding", "Chunk padding ($id)", CheckStatus.FAIL, "Odd-sized chunk is missing its required pad byte", payloadEnd, 1)
            break
        }
        position = paddedEnd
    }
    if (fmtCount == 0) add("fmt.missing", "Format chunk", CheckStatus.FAIL, "Required fmt chunk is missing")
    if (fmtCount > 1) add("fmt.duplicate", "Duplicate format chunks", CheckStatus.WARN, "$fmtCount fmt chunks are present")
    if (dataCount == 0) add("data.missing", "Audio data chunk", CheckStatus.FAIL, "Required data chunk is missing")
    if (dataCount > 1) add("data.duplicate", "Duplicate data chunks", CheckStatus.WARN, "$dataCount data chunks are present")
    fmt?.let { format ->
        if (format.channels == 0 || format.sampleRate == 0L || ((format.tag == 1 || format.tag == 3 || format.tag == 0xFFFE) && format.bitsPerSample == 0)) {
            add("fmt.fields", "Format parameters", CheckStatus.FAIL, "Channel count, sample rate, and PCM bit depth must be non-zero", 12, 8)
        } else if (format.tag == 1 || format.tag == 3) {
            val expectedAlign = format.channels.toLong() * ((format.bitsPerSample + 7) / 8)
            val expectedRate = format.sampleRate * expectedAlign
            if (format.blockAlign.toLong() != expectedAlign || format.byteRate != expectedRate) {
                add("fmt.alignment", "PCM alignment", CheckStatus.FAIL,
                    "Declared block alignment/byte rate ${format.blockAlign}/${format.byteRate} do not match expected $expectedAlign/$expectedRate")
            } else add("fmt.alignment", "PCM alignment", CheckStatus.PASS, "Block alignment and byte rate are consistent")
            if (format.blockAlign > 0 && dataBytes % format.blockAlign != 0L) {
                add("data.alignment", "Audio data alignment", CheckStatus.WARN,
                    "$dataBytes data byte(s) do not contain a whole number of ${format.blockAlign}-byte sample frame(s)")
            }
        } else {
            add("fmt.alignment", "Codec alignment", CheckStatus.SKIP, "Byte-rate consistency is not defined for this WAVE codec")
        }
    }
    return items.ifEmpty { listOf(IntegrityCheckItem("wave.structure", "WAVE structure", CheckStatus.PASS, "No structural issue found")) }
}

private data class WaveFormat(
    val tag: Int,
    val channels: Int,
    val sampleRate: Long,
    val byteRate: Long,
    val blockAlign: Int,
    val bitsPerSample: Int,
    val size: Long,
)

private fun ByteReader.readUInt16Le(offset: Long): Int {
    val b = readBytes(offset, 2)
    return (b[0].toInt() and 0xff) or ((b[1].toInt() and 0xff) shl 8)
}

private fun readU32Le(reader: ByteReader, offset: Long): Long {
    val b = reader.readBytes(offset, 4)
    return (b[0].toLong() and 0xff) or ((b[1].toLong() and 0xff) shl 8) or
        ((b[2].toLong() and 0xff) shl 16) or ((b[3].toLong() and 0xff) shl 24)
}
