package com.multiviewer.parser

import java.io.File
import java.io.RandomAccessFile

class ByteReader private constructor(private val raf: RandomAccessFile) : AutoCloseable {
    val length: Long get() = raf.length()

    fun readUInt8(offset: Long): Int {
        raf.seek(offset)
        return raf.readUnsignedByte()
    }

    fun readUInt16(offset: Long): Int {
        val buf = ByteArray(2)
        raf.seek(offset)
        raf.readFully(buf)
        return ((buf[0].toInt() and 0xFF) shl 8) or (buf[1].toInt() and 0xFF)
    }

    fun readUInt32(offset: Long): Long {
        val buf = ByteArray(4)
        raf.seek(offset)
        raf.readFully(buf)
        return ((buf[0].toLong() and 0xFF) shl 24) or
            ((buf[1].toLong() and 0xFF) shl 16) or
            ((buf[2].toLong() and 0xFF) shl 8) or
            (buf[3].toLong() and 0xFF)
    }

    fun readUInt64(offset: Long): Long {
        val hi = readUInt32(offset)
        val lo = readUInt32(offset + 4)
        return (hi shl 32) or lo
    }

    fun readFourCC(offset: Long): String {
        val buf = ByteArray(4)
        raf.seek(offset)
        raf.readFully(buf)
        return String(buf, Charsets.US_ASCII)
    }

    fun readBytes(offset: Long, len: Int): ByteArray {
        val buf = ByteArray(len)
        raf.seek(offset)
        raf.readFully(buf)
        return buf
    }

    override fun close() = raf.close()

    companion object {
        fun open(file: File): ByteReader = ByteReader(RandomAccessFile(file, "r"))
    }
}
