package com.multiviewer.parser.conformance.png

import com.multiviewer.parser.byteReaderOf
import com.multiviewer.parser.conformance.ConformanceReport
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream

/** Minimal PNG writer for rule tests: chunks get correct lengths and CRCs unless stated otherwise. */
internal object SyntheticPng {
    val SIGNATURE = PngChunkScanner.SIGNATURE

    fun be32(v: Long) = byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())
    fun be16(v: Int) = byteArrayOf((v shr 8).toByte(), v.toByte())

    fun chunk(type: String, data: ByteArray = ByteArray(0)): ByteArray {
        val t = type.toByteArray(Charsets.ISO_8859_1)
        val crc = CRC32().apply { update(t); update(data) }.value
        return be32(data.size.toLong()) + t + data + be32(crc)
    }

    fun ihdr(w: Long = 2, h: Long = 2, depth: Int = 8, colorType: Int = 2, compression: Int = 0, filter: Int = 0, interlace: Int = 0) =
        chunk("IHDR", be32(w) + be32(h) + byteArrayOf(depth.toByte(), colorType.toByte(), compression.toByte(), filter.toByte(), interlace.toByte()))

    /** zlib stream of [rows] filter-0 scanlines of [rowBytes] zero samples. */
    fun zlib(rows: Int = 2, rowBytes: Int = 6): ByteArray {
        val out = ByteArrayOutputStream()
        DeflaterOutputStream(out).use { z -> repeat(rows) { z.write(0); z.write(ByteArray(rowBytes)) } }
        return out.toByteArray()
    }

    fun idat(data: ByteArray = zlib()) = chunk("IDAT", data)
    fun iend() = chunk("IEND")

    fun png(vararg chunks: ByteArray): ByteArray = chunks.fold(SIGNATURE) { acc, c -> acc + c }

    /** IHDR (2x2 RGB 8-bit) + [middle] + IDAT + IEND. */
    fun rgb(vararg beforeIdat: ByteArray, after: List<ByteArray> = emptyList()): ByteArray =
        png(ihdr(), *beforeIdat, idat(), *after.toTypedArray(), iend())

    fun fcTL(seq: Long, w: Long = 2, h: Long = 2, x: Long = 0, y: Long = 0, dispose: Int = 0, blend: Int = 0) =
        chunk("fcTL", be32(seq) + be32(w) + be32(h) + be32(x) + be32(y) + be16(1) + be16(10) + byteArrayOf(dispose.toByte(), blend.toByte()))

    fun fdAT(seq: Long, data: ByteArray = zlib()) = chunk("fdAT", be32(seq) + data)
    fun acTL(frames: Long, plays: Long = 0) = chunk("acTL", be32(frames) + be32(plays))

    fun check(bytes: ByteArray): ConformanceReport = byteReaderOf(bytes).use { PngConformanceChecker.check(it) }
}
