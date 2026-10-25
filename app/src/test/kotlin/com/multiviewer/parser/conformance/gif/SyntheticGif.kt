package com.multiviewer.parser.conformance.gif

import com.multiviewer.parser.byteReaderOf
import com.multiviewer.parser.conformance.ConformanceReport

/** Minimal GIF writer for rule tests: a 4x4 screen, 2-colour Global Color Table, LZW min code size 2. */
internal object SyntheticGif {
    fun le16(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte())

    fun header(version: String = "89a", w: Int = 4, h: Int = 4, packed: Int = 0x80, background: Int = 0): ByteArray =
        "GIF$version".toByteArray() + le16(w) + le16(h) + byteArrayOf(packed.toByte(), background.toByte(), 0) +
            (if (packed and 0x80 != 0) ByteArray(3 * (1 shl ((packed and 7) + 1))) else ByteArray(0))

    /** Clear (4) then EOI (5) in 3-bit codes: 0b101_100 = 0x2C. */
    val LZW_CLEAR_EOI = byteArrayOf(0x2C, 0x00)

    fun image(x: Int = 0, y: Int = 0, w: Int = 4, h: Int = 4, packed: Int = 0, lzw: Int = 2, data: ByteArray = LZW_CLEAR_EOI): ByteArray =
        byteArrayOf(0x2C) + le16(x) + le16(y) + le16(w) + le16(h) + byteArrayOf(packed.toByte()) +
            (if (packed and 0x80 != 0) ByteArray(3 * (1 shl ((packed and 7) + 1))) else ByteArray(0)) +
            byteArrayOf(lzw.toByte()) + (if (data.isEmpty()) ByteArray(0) else byteArrayOf(data.size.toByte()) + data) + byteArrayOf(0)

    fun gce(packed: Int = 0, size: Int = 4): ByteArray = byteArrayOf(0x21, 0xF9.toByte(), size.toByte()) + byteArrayOf(packed.toByte()) + ByteArray(size - 1) + byteArrayOf(0)
    fun app(id: String = "NETSCAPE2.0", size: Int = 11): ByteArray =
        byteArrayOf(0x21, 0xFF.toByte(), size.toByte()) + id.toByteArray(Charsets.ISO_8859_1).copyOf(size) + byteArrayOf(3, 1, 0, 0, 0)
    fun comment(text: String = "hi"): ByteArray = byteArrayOf(0x21, 0xFE.toByte(), text.length.toByte()) + text.toByteArray() + byteArrayOf(0)
    fun plainText(size: Int = 12): ByteArray = byteArrayOf(0x21, 0x01, size.toByte()) + ByteArray(size) + byteArrayOf(1, 0x41, 0)
    val TRAILER = byteArrayOf(0x3B)

    fun gif(vararg parts: ByteArray): ByteArray = parts.fold(ByteArray(0)) { a, p -> a + p }

    fun check(bytes: ByteArray): ConformanceReport = byteReaderOf(bytes).use { GifConformanceChecker.check(it) }
}
