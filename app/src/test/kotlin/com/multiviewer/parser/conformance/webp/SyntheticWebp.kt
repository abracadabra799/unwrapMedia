package com.multiviewer.parser.conformance.webp

import com.multiviewer.parser.byteReaderOf
import com.multiviewer.parser.conformance.ConformanceReport

/** Minimal WebP writer for rule tests (headers only; the bitstreams are not decodable). */
internal object SyntheticWebp {
    fun le16(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte())
    fun le24(v: Long) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte())
    fun le32(v: Long) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())

    fun chunk(fourcc: String, data: ByteArray, pad: Int = 0): ByteArray =
        fourcc.toByteArray(Charsets.ISO_8859_1) + le32(data.size.toLong()) + data + (if (data.size % 2 == 1) byteArrayOf(pad.toByte()) else ByteArray(0))

    fun riff(vararg chunks: ByteArray, sizeDelta: Long = 0): ByteArray {
        val body = "WEBP".toByteArray() + chunks.fold(ByteArray(0)) { a, c -> a + c }
        return "RIFF".toByteArray() + le32(body.size + sizeDelta) + body
    }

    /** 'VP8 ' key frame header for [w]x[h] + 2 filler bytes (first_part_size 2). */
    fun vp8(w: Int = 4, h: Int = 4, tag: Int = (2 shl 5) or (1 shl 4)): ByteArray =
        chunk("VP8 ", le24(tag.toLong()) + byteArrayOf(0x9D.toByte(), 0x01, 0x2A) + le16(w) + le16(h) + ByteArray(2))

    /** 'VP8L' header for [w]x[h] (version [version]). */
    fun vp8l(w: Int = 4, h: Int = 4, alpha: Int = 0, version: Int = 0): ByteArray {
        val bits = (w - 1).toLong() or ((h - 1).toLong() shl 14) or (alpha.toLong() shl 28) or (version.toLong() shl 29)
        return chunk("VP8L", byteArrayOf(0x2F) + le32(bits) + ByteArray(1))
    }

    fun vp8x(flags: Int, w: Long = 4, h: Long = 4, reserved: Long = 0) = chunk("VP8X", byteArrayOf(flags.toByte()) + le24(reserved) + le24(w - 1) + le24(h - 1))
    fun alph(header: Int = 0x01, data: ByteArray = ByteArray(4)) = chunk("ALPH", byteArrayOf(header.toByte()) + data)
    fun anim() = chunk("ANIM", le32(0) + le16(0))
    fun anmf(x: Long = 0, y: Long = 0, w: Long = 4, h: Long = 4, flags: Int = 0, vararg sub: ByteArray) =
        chunk("ANMF", le24(x / 2) + le24(y / 2) + le24(w - 1) + le24(h - 1) + le24(100) + byteArrayOf(flags.toByte()) + sub.fold(ByteArray(0)) { a, c -> a + c })

    const val I = 0x20; const val L = 0x10; const val E = 0x08; const val X = 0x04; const val A = 0x02

    fun check(bytes: ByteArray): ConformanceReport = byteReaderOf(bytes).use { WebpConformanceChecker.check(it) }
}
