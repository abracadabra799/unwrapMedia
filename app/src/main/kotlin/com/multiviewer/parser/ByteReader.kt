package com.multiviewer.parser

import java.io.File
import java.io.RandomAccessFile

// Structural parsing (box/IFD/marker walking) does many small reads that are usually near each
// other in the file (a box header, then its immediate children, physically adjacent) -- each used
// to be its own raf.seek()+read() syscall pair. That's cheap on macOS/Linux but measurably slower
// per call on Windows (reported: video files taking noticeably longer to open there specifically),
// where each syscall carries more overhead and real-time antivirus scanning commonly intercepts
// file I/O. A read-ahead window turns most of those into plain in-memory copies: a request that
// falls entirely inside the cached chunk is served from it directly, and only a cache miss
// triggers a fresh (larger) read from disk.
private const val CACHE_CHUNK_SIZE = 65536

class ByteReader private constructor(private val raf: RandomAccessFile) : AutoCloseable {
    val length: Long get() = raf.length()
    private val fileLength = raf.length()

    private var cacheStart = 0L
    private var cache = ByteArray(0)

    private fun read(offset: Long, len: Int): ByteArray {
        if (len > CACHE_CHUNK_SIZE) {
            // Larger than the cache window (e.g. an embedded thumbnail or table row) -- caching
            // wouldn't help, just read it directly in one call.
            return directRead(offset, len)
        }
        val cacheEnd = cacheStart + cache.size
        if (offset < cacheStart || offset + len > cacheEnd) {
            val fetchLen = minOf(CACHE_CHUNK_SIZE.toLong(), fileLength - offset).toInt().coerceAtLeast(0)
            cache = if (fetchLen > 0) {
                raf.seek(offset)
                ByteArray(fetchLen).also { raf.readFully(it) }
            } else {
                ByteArray(0)
            }
            cacheStart = offset
        }
        val relativeStart = (offset - cacheStart).toInt()
        if (relativeStart < 0 || relativeStart + len > cache.size) {
            // Still doesn't fit -- e.g. offset+len runs past EOF, where the fetch above came back
            // shorter than len. Fall through to a direct read so readFully's normal EOFException
            // still fires, same as before this cache existed.
            return directRead(offset, len)
        }
        return cache.copyOfRange(relativeStart, relativeStart + len)
    }

    private fun directRead(offset: Long, len: Int): ByteArray {
        val buf = ByteArray(len)
        raf.seek(offset)
        raf.readFully(buf)
        return buf
    }

    fun readUInt8(offset: Long): Int = read(offset, 1)[0].toInt() and 0xFF

    fun readUInt16(offset: Long): Int {
        val buf = read(offset, 2)
        return ((buf[0].toInt() and 0xFF) shl 8) or (buf[1].toInt() and 0xFF)
    }

    fun readUInt32(offset: Long): Long {
        val buf = read(offset, 4)
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

    fun readFourCC(offset: Long): String = String(read(offset, 4), Charsets.US_ASCII)

    fun readBytes(offset: Long, len: Int): ByteArray = read(offset, len)

    override fun close() = raf.close()

    companion object {
        fun open(file: File): ByteReader = ByteReader(RandomAccessFile(file, "r"))
    }
}
