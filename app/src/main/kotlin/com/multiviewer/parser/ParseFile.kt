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
        val isAvi = !isJpeg && !isPng && !isBmp && !isGif && !isTiff && !isWebp && !isWav && isAviMagic(reader)
        val isFlv = !isJpeg && !isPng && !isBmp && !isGif && !isTiff && !isWebp && !isWav && !isAvi && isFlvMagic(reader)
        val isAsf = !isJpeg && !isPng && !isBmp && !isGif && !isTiff && !isWebp && !isWav && !isAvi && !isFlv && isAsfMagic(reader)
        val isAac = !isJpeg && !isPng && !isBmp && !isGif && !isTiff && !isWebp && !isWav && !isAvi && !isFlv && !isAsf && isAacMagic(reader)
        val isMp3 = !isJpeg && !isPng && !isBmp && !isGif && !isTiff && !isWebp && !isWav && !isAvi && !isFlv && !isAsf && !isAac && isMp3Magic(reader)
        val isEbml = !isJpeg && !isPng && !isBmp && !isGif && !isTiff && !isWebp && !isWav && !isAvi && !isFlv && !isAsf && !isAac && !isMp3 && isEbmlMagic(reader)
        val isFlac = !isJpeg && !isPng && !isBmp && !isGif && !isTiff && !isWebp && !isWav && !isAvi && !isFlv && !isAsf && !isAac && !isMp3 && !isEbml && isFlacMagic(reader)
        val isOgg = !isJpeg && !isPng && !isBmp && !isGif && !isTiff && !isWebp && !isWav && !isAvi && !isFlv && !isAsf && !isAac && !isMp3 && !isEbml && !isFlac && isOggMagic(reader)
        val isAiff = !isJpeg && !isPng && !isBmp && !isGif && !isTiff && !isWebp && !isWav && !isAvi && !isFlv && !isAsf && !isAac && !isMp3 && !isEbml && !isFlac && !isOgg && isAiffMagic(reader)
        val children = when {
            isJpeg -> parseJpegSegments(reader, 0, reader.length)
            isPng -> parsePngChunks(reader, 8, reader.length)
            isBmp -> parseBmpHeaders(reader, 0, reader.length)
            isGif -> parseGifBlocks(reader, 6, reader.length)
            isTiff -> decodeTiff(reader, 0, reader.length)
            isWebp -> parseWebpChunks(reader, 0, reader.length)
            isWav -> parseWavChunks(reader, 0, reader.length)
            isAvi -> parseAviChunks(reader, 0, reader.length)
            isFlv -> parseFlv(reader, 0, reader.length)
            isAsf -> parseAsf(reader, 0, reader.length)
            isAac -> parseAac(reader, 0, reader.length)
            isMp3 -> parseMp3(reader, 0, reader.length)
            isEbml -> parseEbmlElements(reader, 0, reader.length)
            isFlac -> parseFlacBlocks(reader, 0, reader.length)
            isOgg -> parseOggPages(reader, 0, reader.length)
            isAiff -> parseAiffChunks(reader, 0, reader.length)
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

private fun isAviMagic(reader: ByteReader): Boolean {
    if (reader.length < 12) return false
    return reader.readFourCC(0) == "RIFF" && (reader.readFourCC(8) == "AVI " || reader.readFourCC(8) == "AVIX")
}

private fun isFlvMagic(reader: ByteReader): Boolean {
    if (reader.length < 9) return false
    return String(reader.readBytes(0, 3), Charsets.US_ASCII) == "FLV"
}

private fun isAsfMagic(reader: ByteReader): Boolean {
    if (reader.length < 24) return false
    val b = reader.readBytes(0, 16)
    return b[0] == 0x30.toByte() && b[1] == 0x26.toByte() && b[2] == 0xB2.toByte() && b[3] == 0x75.toByte() &&
        b[4] == 0x8E.toByte() && b[5] == 0x66.toByte() && b[6] == 0xCF.toByte() && b[7] == 0x11.toByte() &&
        b[8] == 0xA6.toByte() && b[9] == 0xD9.toByte() && b[10] == 0x00.toByte() && b[11] == 0xAA.toByte() &&
        b[12] == 0x00.toByte() && b[13] == 0x62.toByte() && b[14] == 0xCE.toByte() && b[15] == 0x6C.toByte()
}

private fun isAacMagic(reader: ByteReader): Boolean {
    return isAdtsMagic(reader)
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

private fun isFlacMagic(reader: ByteReader): Boolean {
    if (reader.length < 4) return false
    return reader.readFourCC(0) == "fLaC"
}

private fun isOggMagic(reader: ByteReader): Boolean {
    if (reader.length < 4) return false
    return reader.readFourCC(0) == "OggS"
}

private fun isAiffMagic(reader: ByteReader): Boolean {
    if (reader.length < 12) return false
    if (reader.readFourCC(0) != "FORM") return false
    val formType = reader.readFourCC(8)
    return formType == "AIFF" || formType == "AIFC"
}
