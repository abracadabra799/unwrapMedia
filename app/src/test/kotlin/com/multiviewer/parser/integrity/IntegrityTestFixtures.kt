package com.multiviewer.parser.integrity

import com.multiviewer.parser.parseFile
import java.io.ByteArrayOutputStream
import java.io.File

internal fun checkBytes(bytes: ByteArray, ext: String): ImageStructureReport {
    val f = File.createTempFile("integrity-", ".$ext")
    f.deleteOnExit()
    f.writeBytes(bytes)
    return ImageIntegrityChecker.check(f, parseFile(f))
}

internal fun ImageStructureReport.item(id: String): IntegrityCheckItem =
    items.firstOrNull { it.id == id } ?: error("No item '$id' in ${items.map { it.id }}")

internal fun be16(v: Int) = byteArrayOf((v shr 8).toByte(), v.toByte())
internal fun be32(v: Long) = byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())
internal fun le16(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte())
internal fun le24(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte())
internal fun le32(v: Long) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())

/** Minimal baseline JPEG (3x2, 1 component). Not pixel-decodable; structurally complete. */
internal fun jpegBytes(
    withEoi: Boolean = true,
    withDht: Boolean = true,
    sofMarker: Int = 0xC0,
    trailing: ByteArray = ByteArray(0),
): ByteArray {
    val out = ByteArrayOutputStream()
    fun seg(marker: Int, payload: ByteArray) {
        val l = payload.size + 2
        out.write(0xFF); out.write(marker); out.write(l shr 8); out.write(l and 0xFF); out.write(payload)
    }
    out.write(0xFF); out.write(0xD8)
    seg(0xDB, byteArrayOf(0) + ByteArray(64) { 1 })
    seg(sofMarker, byteArrayOf(8, 0, 2, 0, 3, 1, 1, 0x11, 0))
    if (withDht) seg(0xC4, byteArrayOf(0x00, 1) + ByteArray(15) + byteArrayOf(0))
    seg(0xDA, byteArrayOf(1, 1, 0, 0, 63, 0))
    out.write(byteArrayOf(0x12, 0x34, 0x56))
    if (withEoi) { out.write(0xFF); out.write(0xD9) }
    out.write(trailing)
    return out.toByteArray()
}

internal val PNG_SIG = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

internal fun pngChunk(type: String, data: ByteArray, corruptCrc: Boolean = false): ByteArray {
    val typeBytes = type.toByteArray(Charsets.US_ASCII)
    val crc = java.util.zip.CRC32().apply { update(typeBytes); update(data) }.value
    return be32(data.size.toLong()) + typeBytes + data + be32(if (corruptCrc) crc xor 1L else crc)
}

internal fun pngIdatPayload(): ByteArray {
    val out = ByteArrayOutputStream()
    java.util.zip.DeflaterOutputStream(out).use { it.write(byteArrayOf(0, 0x7F)) } // filter byte + one gray pixel
    return out.toByteArray()
}

/** 1x1 8-bit grayscale PNG with a tEXt chunk before IDAT. */
internal fun pngBytes(
    corruptIdatCrc: Boolean = false,
    corruptTextCrc: Boolean = false,
    trailing: ByteArray = ByteArray(0),
): ByteArray {
    val ihdr = be32(1) + be32(1) + byteArrayOf(8, 0, 0, 0, 0)
    return PNG_SIG + pngChunk("IHDR", ihdr) +
        pngChunk("tEXt", "k\u0000v".toByteArray(Charsets.ISO_8859_1), corruptTextCrc) +
        pngChunk("IDAT", pngIdatPayload(), corruptIdatCrc) + pngChunk("IEND", ByteArray(0)) + trailing
}

/** 2x1 GIF89a with a 2-colour global table and one frame. */
internal fun gifBytes(withTrailer: Boolean = true, truncateImageData: Boolean = false, trailing: ByteArray = ByteArray(0)): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    out.write("GIF89a".toByteArray(Charsets.US_ASCII))
    out.write(byteArrayOf(2, 0, 1, 0, 0x80.toByte(), 0, 0))
    out.write(byteArrayOf(0, 0, 0, -1, -1, -1))
    out.write(byteArrayOf(0x2C, 0, 0, 0, 0, 2, 0, 1, 0, 0))
    out.write(2)
    if (truncateImageData) { out.write(byteArrayOf(5, 1, 2)); return out.toByteArray() }
    out.write(byteArrayOf(2, 0x44, 0x01, 0))
    if (withTrailer) out.write(0x3B)
    out.write(trailing)
    return out.toByteArray()
}

/** Uncompressed 24-bit BMP (BITMAPINFOHEADER). */
internal fun bmpBytes(width: Int = 2, height: Int = 2, pixelBytes: Int? = null, bfSizeOverride: Long? = null): ByteArray {
    val stride = ((width * 24 + 31) / 32) * 4
    val pixels = ByteArray(pixelBytes ?: (stride * height))
    val size = 54L + pixels.size
    return "BM".toByteArray(Charsets.US_ASCII) + le32(bfSizeOverride ?: size) + le32(0) + le32(54) +
        le32(40) + le32(width.toLong()) + le32(height.toLong()) + le16(1) + le16(24) + le32(0) + le32(pixels.size.toLong()) +
        le32(2835) + le32(2835) + le32(0) + le32(0) + pixels
}

/** Lossless WebP with a 1x1 VP8L header (header-valid only), optionally under a VP8X canvas. */
internal fun webpBytes(riffSizeDelta: Long = 0, trailing: ByteArray = ByteArray(0), vp8xCanvas: Pair<Int, Int>? = null): ByteArray {
    fun chunk(type: String, data: ByteArray) =
        type.toByteArray(Charsets.US_ASCII) + le32(data.size.toLong()) + data + (if (data.size % 2 == 1) byteArrayOf(0) else ByteArray(0))
    val vp8l = byteArrayOf(0x2F, 0, 0, 0, 0, 0x07, 0x10, 0x00)
    val vp8x = vp8xCanvas?.let { (w, h) -> chunk("VP8X", byteArrayOf(0, 0, 0, 0) + le24(w - 1) + le24(h - 1)) } ?: ByteArray(0)
    val body = "WEBP".toByteArray(Charsets.US_ASCII) + vp8x + chunk("VP8L", vp8l)
    return "RIFF".toByteArray(Charsets.US_ASCII) + le32(body.size + riffSizeDelta) + body + trailing
}
