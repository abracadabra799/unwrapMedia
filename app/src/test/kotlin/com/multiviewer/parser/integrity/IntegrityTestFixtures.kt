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
