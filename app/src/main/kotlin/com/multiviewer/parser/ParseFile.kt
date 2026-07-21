package com.multiviewer.parser

import java.io.File

fun parseFile(path: File): BoxNode {
    registerAllDecoders()
    ByteReader.open(path).use { reader ->
        val isJpeg = reader.length >= 2 && reader.readUInt8(0) == 0xFF && reader.readUInt8(1) == 0xD8
        val isPng = !isJpeg && isPngMagic(reader)
        val isTiff = !isJpeg && !isPng && isTiffMagic(reader)
        val children = when {
            isJpeg -> parseJpegSegments(reader, 0, reader.length)
            isPng -> parsePngChunks(reader, 8, reader.length)
            isTiff -> decodeTiff(reader, 0, reader.length)
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

private fun isTiffMagic(reader: ByteReader): Boolean {
    if (reader.length < 4) return false
    val bytes = reader.readBytes(0, 4)
    val isLittleEndian = bytes[0] == 'I'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
        bytes[2] == 0x2A.toByte() && bytes[3] == 0x00.toByte()
    val isBigEndian = bytes[0] == 'M'.code.toByte() && bytes[1] == 'M'.code.toByte() &&
        bytes[2] == 0x00.toByte() && bytes[3] == 0x2A.toByte()
    return isLittleEndian || isBigEndian
}
