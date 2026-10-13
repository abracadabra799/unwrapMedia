package com.multiviewer.parser

import java.io.File

/** An ISO 21496-1 APP2 payload found in one JPEG image, with its absolute file offset. */
data class JpegIsoApp2(val payload: ByteArray, val payloadOffset: Long)

/**
 * Walks the markers of the JPEG image starting at [imageOffset] (must be an SOI) up to its SOS and
 * returns the payload of the first APP2 whose identifier is `urn:iso:std:iso:ts:21496:-1\0`. On the
 * primary image that is the 4-byte version block; on the gain map image, the full metadata.
 */
fun readJpegIsoApp2(file: File, imageOffset: Long, imageLength: Long): JpegIsoApp2? = try {
    ByteReader.open(file).use { reader ->
        val end = minOf(reader.length, imageOffset + imageLength)
        if (imageOffset + 4 > end) return null
        val soi = reader.readBytes(imageOffset, 2)
        if (soi[0] != 0xFF.toByte() || soi[1] != 0xD8.toByte()) return null
        val urn = (ISO_GAINMAP_APP2_URN + "\u0000").toByteArray(Charsets.US_ASCII)
        var pos = imageOffset + 2
        while (pos + 4 <= end) {
            val head = reader.readBytes(pos, 4)
            if (head[0] != 0xFF.toByte()) return null
            val marker = head[1].toInt() and 0xFF
            if (marker == 0xDA || marker == 0xD9) return null // SOS / EOI: header segments are over
            val segLength = ((head[2].toInt() and 0xFF) shl 8) or (head[3].toInt() and 0xFF)
            if (segLength < 2 || pos + 2 + segLength > end) return null
            if (marker == 0xE2 && segLength - 2 >= urn.size) {
                val body = reader.readBytes(pos + 4, segLength - 2)
                if (body.copyOfRange(0, urn.size).contentEquals(urn)) {
                    return JpegIsoApp2(body.copyOfRange(urn.size, body.size), pos + 4 + urn.size)
                }
            }
            pos += 2 + segLength
        }
        null
    }
} catch (_: Exception) {
    null
}

/** A HEIF/AVIF `tmap` (tone-map derived image) item: 1 version byte + the ISO 21496-1 payload. */
data class HeifTmapItem(
    val itemId: Long,
    val version: Int?,
    val payload: ByteArray?,
    /** `iref dimg` inputs in order: the base image, then the gain map. */
    val inputItemIds: List<Long>,
)

fun readHeifTmapItem(file: File, root: BoxNode): HeifTmapItem? {
    val meta = root.children.firstOrNull { it.type == "meta" } ?: return null
    val iinf = findFirst(meta) { it.type == "iinf" } ?: return null
    val infe = iinf.children.firstOrNull { node ->
        node.type == "infe" && node.fields.find { it.name == "item_type" }?.value?.trim() == "tmap"
    } ?: return null
    val itemId = infe.fields.find { it.name == "item_ID" }?.value?.toLongOrNull() ?: return null
    val inputs = findFirst(meta) { it.type == "iref" }?.children
        ?.firstOrNull { it.type == "dimg" && it.fields.find { f -> f.name == "from_item_ID" }?.value?.toLongOrNull() == itemId }
        ?.fields?.filter { it.name.startsWith("to_item_ID") }?.mapNotNull { it.value.toLongOrNull() }
        .orEmpty()
    val data = findFirst(meta) { it.type == "iloc" }?.let { iloc ->
        val idat = findFirst(meta) { it.type == "idat" }
        try {
            ByteReader.open(file).use { reader -> extractItemBytes(reader, iloc, itemId, idat?.let { it.offset + it.headerSize } ?: 0L) }
        } catch (_: Exception) {
            null
        }
    }
    return HeifTmapItem(
        itemId = itemId,
        version = data?.firstOrNull()?.toInt()?.and(0xFF),
        payload = data?.takeIf { it.size > 1 }?.copyOfRange(1, data.size),
        inputItemIds = inputs,
    )
}
