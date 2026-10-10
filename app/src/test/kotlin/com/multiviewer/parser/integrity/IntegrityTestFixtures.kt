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
