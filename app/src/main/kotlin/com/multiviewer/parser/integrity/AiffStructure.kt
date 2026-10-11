package com.multiviewer.parser.integrity

import com.multiviewer.parser.ByteReader

internal fun checkAiffStructure(reader: ByteReader): List<IntegrityCheckItem> {
    val items = mutableListOf<IntegrityCheckItem>()
    fun add(id: String, title: String, status: CheckStatus, detail: String, offset: Long? = null, length: Long? = null) {
        items += IntegrityCheckItem("aiff.$id", title, status, detail, offset, length)
    }
    val fileLength = reader.length
    if (fileLength < 12 || reader.readFourCC(0) != "FORM") {
        return listOf(IntegrityCheckItem("aiff.signature", "AIFF FORM header", CheckStatus.FAIL,
            "File does not contain a complete FORM header", 0, minOf(fileLength, 12)))
    }
    val formType = reader.readFourCC(8)
    if (formType != "AIFF" && formType != "AIFC") {
        return listOf(IntegrityCheckItem("aiff.formType", "AIFF form type", CheckStatus.FAIL,
            "Unsupported FORM type '$formType'", 8, 4))
    }
    val declaredSize = reader.readUInt32(4) + 8
    if (declaredSize < 12) {
        add("form.size", "FORM extent", CheckStatus.FAIL, "Declared FORM size is smaller than its header", 4, 4)
        return items
    }
    val limit = minOf(declaredSize, fileLength)
    add("form.size", "FORM extent", if (declaredSize > fileLength) CheckStatus.FAIL else CheckStatus.PASS,
        if (declaredSize > fileLength) "Declared FORM extent $declaredSize bytes exceeds file size $fileLength" else "FORM extent is within the file", 4, 4)
    if (declaredSize < fileLength) add("form.trailing", "Trailing bytes", CheckStatus.WARN,
        "${fileLength - declaredSize} byte(s) follow the declared FORM extent", declaredSize, fileLength - declaredSize)

    var position = 12L
    var commCount = 0
    var ssndCount = 0
    var audio: AiffComm? = null
    var ssndAudioBytes: Long? = null
    while (position < limit) {
        checkAudioScanActive()
        val remaining = limit - position
        if (remaining < 8) {
            add("chunk.header", "Chunk header", CheckStatus.FAIL, "$remaining trailing byte(s) cannot contain a chunk header", position, remaining)
            break
        }
        val id = reader.readFourCC(position)
        val payloadSize = reader.readUInt32(position + 4)
        val payloadStart = position + 8
        val payloadEnd = payloadStart + payloadSize
        if (payloadEnd < payloadStart || payloadEnd > limit) {
            add("chunk.bounds", "Chunk bounds ($id)", CheckStatus.FAIL,
                "Chunk payload ending at $payloadEnd exceeds FORM extent $limit", position, 8)
            break
        }
        if (id == "COMM") {
            commCount++
            // AIFC's fixed COMM fields occupy 22 bytes and are followed by the
            // compression-name length byte before any name bytes.
            val minimum = if (formType == "AIFC") 23L else 18L
            if (payloadSize < minimum) {
                add("comm.size", "COMM chunk size", CheckStatus.FAIL,
                    "COMM chunk is $payloadSize bytes; at least $minimum are required", position, 8 + payloadSize)
            } else {
                val channels = reader.readUInt16(position + 8)
                val frames = reader.readUInt32(position + 10)
                val sampleSize = reader.readUInt16(position + 14)
                val compression = if (formType == "AIFC") reader.readFourCC(position + 26) else "NONE"
                audio = AiffComm(channels, frames, sampleSize, compression)
                add("comm.present", "Common chunk", CheckStatus.PASS, "$formType COMM chunk is present", position, 8 + payloadSize)
                if (channels == 0 || sampleSize == 0) {
                    add("comm.fields", "Audio parameters", CheckStatus.FAIL, "Channel count and sample size must be non-zero", position + 8, 8)
                }
                if (formType == "AIFC") {
                    val nameLength = reader.readUInt8(position + 30)
                    if (23L + nameLength > payloadSize) {
                        add("comm.name", "Compression name", CheckStatus.FAIL,
                            "Compression name extends past COMM chunk", position + 30, payloadSize - 22)
                    }
                }
            }
        } else if (id == "SSND") {
            ssndCount++
            if (payloadSize < 8) {
                add("ssnd.size", "SSND chunk size", CheckStatus.FAIL, "SSND chunk is shorter than its 8-byte offset/block-size header", position, 8 + payloadSize)
            } else {
                val dataOffset = reader.readUInt32(position + 8)
                if (dataOffset > payloadSize - 8) {
                    add("ssnd.offset", "SSND data offset", CheckStatus.FAIL,
                        "SSND offset $dataOffset exceeds its ${payloadSize - 8}-byte sample area", position + 8, 4)
                } else {
                    ssndAudioBytes = payloadSize - 8 - dataOffset
                    add("ssnd.bounds", "Sound data chunk", CheckStatus.PASS,
                        "$ssndAudioBytes audio byte(s) are within the FORM extent", position, 8 + payloadSize)
                }
            }
        }
        val paddedEnd = payloadEnd + (payloadSize and 1L)
        if (paddedEnd < payloadEnd || paddedEnd > limit) {
            add("chunk.padding", "Chunk padding ($id)", CheckStatus.FAIL, "Odd-sized chunk is missing its required pad byte", payloadEnd, 1)
            break
        }
        position = paddedEnd
    }
    if (commCount == 0) add("comm.missing", "Common chunk", CheckStatus.FAIL, "Required COMM chunk is missing")
    if (commCount > 1) add("comm.duplicate", "Duplicate COMM chunks", CheckStatus.WARN, "$commCount COMM chunks are present")
    if (ssndCount == 0) add("ssnd.missing", "Sound data chunk", CheckStatus.FAIL, "Required SSND chunk is missing")
    if (ssndCount > 1) add("ssnd.duplicate", "Duplicate SSND chunks", CheckStatus.WARN, "$ssndCount SSND chunks are present")
    val format = audio
    val audioBytes = ssndAudioBytes
    if (format != null && audioBytes != null) {
        if (formType == "AIFF" || format.compression == "NONE" || format.compression == "sowt") {
            val bytesPerFrame = format.channels.toLong() * ((format.sampleSize + 7) / 8)
            val expectedBytes = format.frames * bytesPerFrame
            if (expectedBytes != audioBytes) {
                add("comm.frames", "Sample-frame count", CheckStatus.WARN,
                    "COMM declares ${format.frames} frame(s) ($expectedBytes bytes), SSND contains $audioBytes audio bytes")
            } else add("comm.frames", "Sample-frame count", CheckStatus.PASS, "COMM frame count matches uncompressed SSND data")
        } else add("comm.frames", "Sample-frame count", CheckStatus.SKIP,
            "Sample-frame byte count cannot be derived for AIFC compression ${format.compression}")
    }
    return items.ifEmpty { listOf(IntegrityCheckItem("aiff.structure", "AIFF structure", CheckStatus.PASS, "No structural issue found")) }
}

private data class AiffComm(val channels: Int, val frames: Long, val sampleSize: Int, val compression: String)
