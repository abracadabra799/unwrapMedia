package com.multiviewer.parser

import java.io.File

fun parseFile(path: File): BoxNode {
    registerAllDecoders()
    ByteReader.open(path).use { reader ->
        val isJpeg = reader.length >= 2 && reader.readUInt8(0) == 0xFF && reader.readUInt8(1) == 0xD8
        val isPng = !isJpeg && isPngMagic(reader)
        val isBmp = !isJpeg && !isPng && isBmpMagic(reader)
        val isGif = !isJpeg && !isPng && !isBmp && isGifMagic(reader)
        val isTiff = !isJpeg && !isPng && !isBmp && !isGif && isTiffMagic(reader)
        val isWebp = !isJpeg && !isPng && !isBmp && !isGif && !isTiff && isWebpMagic(reader)
        val isWav = !isJpeg && !isPng && !isBmp && !isGif && !isTiff && !isWebp && isWavMagic(reader)
        val isMp3 = !isJpeg && !isPng && !isBmp && !isGif && !isTiff && !isWebp && !isWav && isMp3Magic(reader)
        val isEbml = !isJpeg && !isPng && !isBmp && !isGif && !isTiff && !isWebp && !isWav && !isMp3 && isEbmlMagic(reader)
        val children = when {
            isJpeg -> parseJpegSegments(reader, 0, reader.length)
            isPng -> parsePngChunks(reader, 8, reader.length)
            isBmp -> parseBmpHeaders(reader, 0, reader.length)
            isGif -> parseGifBlocks(reader, 6, reader.length)
            isTiff -> decodeTiff(reader, 0, reader.length)
            isWebp -> parseWebpChunks(reader, 0, reader.length)
            isWav -> parseWavChunks(reader, 0, reader.length)
            isMp3 -> parseMp3(reader, 0, reader.length)
            isEbml -> parseEbmlElements(reader, 0, reader.length)
            else -> parseBoxes(reader, 0, reader.length)
        }
        return BoxNode(type = "root", offset = 0, headerSize = 0, size = reader.length, children = children)
    }
}

private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

private fun isPngMagic(reader: ByteReader): Boolean {
    if (reader.length < 8) return false
    return reader.readBytes(0, 8).contentEquals(PNG_SIGNATURE)
}

private fun isBmpMagic(reader: ByteReader): Boolean {
    if (reader.length < 2) return false
    val bytes = reader.readBytes(0, 2)
    return bytes[0] == 'B'.code.toByte() && bytes[1] == 'M'.code.toByte()
}

private fun isGifMagic(reader: ByteReader): Boolean {
    if (reader.length < 6) return false
    val text = String(reader.readBytes(0, 6), Charsets.US_ASCII)
    return text == "GIF87a" || text == "GIF89a"
}

private fun isTiffMagic(reader: ByteReader): Boolean {
    if (reader.length < 4) return false
    val bytes = reader.readBytes(0, 4)
    val isLittleEndian = bytes[0] == 'I'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
        bytes[2] == 0x2A.toByte() && bytes[3] == 0x00.toByte()
    val isBigEndian = bytes[0] == 'M'.code.toByte() && bytes[1] == 'M'.code.toByte() &&
        bytes[2] == 0x00.toByte() && bytes[3] == 0x2A.toByte()
    return isLittleEndian || isBigEndian
}

private fun isWebpMagic(reader: ByteReader): Boolean {
    if (reader.length < 12) return false
    return reader.readFourCC(0) == "RIFF" && reader.readFourCC(8) == "WEBP"
}

private fun isWavMagic(reader: ByteReader): Boolean {
    if (reader.length < 12) return false
    return reader.readFourCC(0) == "RIFF" && reader.readFourCC(8) == "WAVE"
}

private fun isMp3Magic(reader: ByteReader): Boolean {
    if (reader.length >= 3 && String(reader.readBytes(0, 3), Charsets.US_ASCII) == "ID3") return true
    if (reader.length < 2) return false
    val b0 = reader.readUInt8(0)
    val b1 = reader.readUInt8(1)
    return b0 == 0xFF && (b1 and 0xE0) == 0xE0
}

private fun isEbmlMagic(reader: ByteReader): Boolean {
    if (reader.length < 4) return false
    return reader.readUInt32(0) == 0x1A45DFA3L
}
