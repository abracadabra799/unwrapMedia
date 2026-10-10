package com.multiviewer.parser.integrity

import com.multiviewer.parser.ByteReader
import java.util.zip.CRC32

internal fun ByteArray.u8(i: Int): Int = this[i].toInt() and 0xFF
internal fun ByteArray.u16le(i: Int): Int = u8(i) or (u8(i + 1) shl 8)
internal fun ByteArray.u24le(i: Int): Int = u8(i) or (u8(i + 1) shl 8) or (u8(i + 2) shl 16)
internal fun ByteArray.u32le(i: Int): Long = u16le(i).toLong() or (u16le(i + 2).toLong() shl 16)
internal fun ByteArray.u16be(i: Int): Int = (u8(i) shl 8) or u8(i + 1)
internal fun ByteArray.u32be(i: Int): Long = (u16be(i).toLong() shl 16) or u16be(i + 2).toLong()

/** Streams [length] bytes starting at [offset] through CRC32 in 64 KiB blocks (never loads the whole file). */
internal fun crc32Of(reader: ByteReader, offset: Long, length: Long): Long {
    val crc = CRC32()
    var pos = offset
    val end = offset + length
    while (pos < end) {
        val n = minOf(65536L, end - pos).toInt()
        crc.update(reader.readBytes(pos, n))
        pos += n
    }
    return crc.value
}

internal fun hex32(v: Long): String = "0x" + v.toString(16).padStart(8, '0')
