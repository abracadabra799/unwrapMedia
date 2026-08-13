package com.multiviewer.parser

import java.io.ByteArrayOutputStream
import java.io.File

private const val HVCC_FIXED_HEADER_SIZE = 23
private const val HEVC_NAL_TYPE_VPS = 32
private const val HEVC_NAL_TYPE_SPS = 33
private const val HEVC_NAL_TYPE_PPS = 34
private val ANNEX_B_START_CODE = byteArrayOf(0, 0, 0, 1)

private data class HvcCInfo(val parameterSetsAnnexB: ByteArray, val lengthSize: Int)

// Extracts a HEIC's embedded "thmb" thumbnail item as a standalone Annex-B HEVC elementary stream
// (VPS/SPS/PPS parameter sets from the item's associated hvcC property, then the item's own
// picture data converted from length-prefixed NAL units to Annex-B) -- decodable by ffmpeg's raw
// "-f hevc" demuxer directly, without needing to reconstruct a full MP4 container around it.
// ImageAnalyzer's own thumbnail extraction only handles JPEG-coded thumbnail items (a plain
// Image.makeFromEncoded on the raw bytes); modern HEIC "thmb" items are commonly HEVC-coded
// instead, which is what this covers. Returns null if the file has no thumbnail item, it isn't
// HEVC-coded, or a required property (hvcC, ispe) is missing.
fun extractHevcThumbnailAnnexB(file: File, root: BoxNode): ByteArray? {
    val meta = findFirst(root) { it.type == "meta" } ?: return null
    val iinf = findFirst(meta) { it.type == "iinf" }
    val iref = findFirst(meta) { it.type == "iref" }
    val pitm = findFirst(meta) { it.type == "pitm" }
    val primaryId = pitm?.fields?.find { it.name == "primary_item_ID" }?.value?.toLongOrNull() ?: return null

    val thumbId = iref?.children
        ?.filter { it.type == "thmb" }
        ?.firstNotNullOfOrNull { ref ->
            val fromId = ref.fields.find { it.name == "from_item_ID" }?.value?.toLongOrNull()
            val toIds = ref.fields.filter { it.name.startsWith("to_item_ID") }.mapNotNull { it.value.toLongOrNull() }
            if (fromId != null && toIds.contains(primaryId)) fromId else null
        } ?: return null

    val itemType = iinf?.children
        ?.find { it.type == "infe" && it.fields.find { f -> f.name == "item_ID" }?.value?.toLongOrNull() == thumbId }
        ?.fields?.find { it.name == "item_type" }?.value
    if (itemType?.lowercase() != "hvc1") return null

    return extractHevcItemAnnexB(file, root, thumbId)
}

// Generalized from extractHevcThumbnailAnnexB (originally hardcoded to the "thmb" iref reference)
// so any item ID -- e.g. one of a grid-tiled image's individual tile items -- can be extracted the
// same way. Callers resolve their own item ID first (extractHevcThumbnailAnnexB does so via
// "thmb"; tile extraction does so via "dimg", see HeicTileGrid.kt).
fun extractHevcItemAnnexB(file: File, root: BoxNode, itemId: Long): ByteArray? {
    val meta = findFirst(root) { it.type == "meta" } ?: return null
    val iloc = findFirst(meta) { it.type == "iloc" } ?: return null
    val hvcC = findItemProperty(meta, itemId, "hvcC") ?: return null
    val idatBase = findFirst(root) { it.type == "idat" }?.let { it.offset + it.headerSize } ?: 0L

    return try {
        ByteReader.open(file).use { reader ->
            val hvcCInfo = readHvcCInfo(reader, hvcC) ?: return@use null
            val itemBytes = extractItemBytes(reader, iloc, itemId, idatBase) ?: return@use null
            val pictureAnnexB = convertLengthPrefixedToAnnexB(itemBytes, hvcCInfo.lengthSize)
            if (pictureAnnexB.isEmpty()) null else hvcCInfo.parameterSetsAnnexB + pictureAnnexB
        }
    } catch (e: Exception) {
        null
    }
}

// Same item/property-association lookup as MediaSummaryBuilder's findPrimaryItemProperty, but for
// an arbitrary item ID rather than only the file's primary item.
internal fun findItemProperty(meta: BoxNode, itemId: Long, propertyType: String): BoxNode? {
    val ipma = findFirst(meta) { it.type == "ipma" } ?: return null
    val itemEntry = ipma.children.find { it.type == "item_$itemId" } ?: return null
    val propertyIndices = itemEntry.fields
        .filter { it.name == "property_index" }
        .mapNotNull { it.value.toIntOrNull() }
    val ipco = findFirst(meta) { it.type == "ipco" } ?: return null
    for (index in propertyIndices) {
        val property = ipco.children.getOrNull(index - 1) ?: continue
        if (property.type == propertyType) return property
    }
    return null
}

internal fun extractItemBytes(reader: ByteReader, iloc: BoxNode, itemId: Long, idatBase: Long): ByteArray? {
    val itemNode = iloc.children.find { it.type == "item_$itemId" } ?: return null
    val method = itemNode.fields.find { it.name == "construction_method" }?.value?.toIntOrNull() ?: 0
    val out = ByteArrayOutputStream()
    for (extent in itemNode.children) {
        val offsetVal = extent.fields.find { it.name == "offset" || it.name == "idat_relative_offset" }?.value?.toLongOrNull() ?: continue
        val length = extent.fields.find { it.name == "length" }?.value?.toLongOrNull() ?: continue
        if (length <= 0) continue
        val absOffset = if (method == 1) idatBase + offsetVal else offsetVal
        out.write(reader.readBytes(absOffset, length.toInt()))
    }
    val bytes = out.toByteArray()
    return bytes.takeIf { it.isNotEmpty() }
}

// Reads hvcC's fixed header (for the NAL length-prefix size used by the item's own picture data)
// and its VPS/SPS/PPS arrays, converted to one Annex-B buffer (each NAL prefixed with a
// 00 00 00 01 start code, VPS then SPS then PPS) -- exactly what a decoder needs before the
// picture data itself. Mirrors HvcCBoxDecoder's own walk of the same box, which only keeps counts
// for display rather than the raw NAL bytes this needs.
private fun readHvcCInfo(reader: ByteReader, hvcC: BoxNode): HvcCInfo? {
    val payloadStart = hvcC.offset + hvcC.headerSize
    val payloadEnd = hvcC.offset + hvcC.size
    if (payloadEnd - payloadStart < HVCC_FIXED_HEADER_SIZE) return null
    val lengthSize = (reader.readUInt8(payloadStart + 21) and 0x03) + 1
    val numArrays = reader.readUInt8(payloadStart + 22)

    val vps = mutableListOf<ByteArray>()
    val sps = mutableListOf<ByteArray>()
    val pps = mutableListOf<ByteArray>()

    var pos = payloadStart + HVCC_FIXED_HEADER_SIZE
    var arraysWalked = 0
    while (arraysWalked < numArrays && pos + 3 <= payloadEnd) {
        val nalType = reader.readUInt8(pos) and 0x3F
        val numNalus = reader.readUInt16(pos + 1)
        pos += 3
        var nalusWalked = 0
        while (nalusWalked < numNalus && pos + 2 <= payloadEnd) {
            val nalLength = reader.readUInt16(pos)
            pos += 2
            if (pos + nalLength > payloadEnd) break
            val nalBytes = reader.readBytes(pos, nalLength)
            when (nalType) {
                HEVC_NAL_TYPE_VPS -> vps.add(nalBytes)
                HEVC_NAL_TYPE_SPS -> sps.add(nalBytes)
                HEVC_NAL_TYPE_PPS -> pps.add(nalBytes)
            }
            pos += nalLength
            nalusWalked++
        }
        arraysWalked++
    }
    if (sps.isEmpty() || pps.isEmpty()) return null

    val out = ByteArrayOutputStream()
    for (nal in vps) { out.write(ANNEX_B_START_CODE); out.write(nal) }
    for (nal in sps) { out.write(ANNEX_B_START_CODE); out.write(nal) }
    for (nal in pps) { out.write(ANNEX_B_START_CODE); out.write(nal) }
    return HvcCInfo(out.toByteArray(), lengthSize)
}

// Converts length-prefixed HEVC NAL units (the format HEIF item data is stored in, per hvcC's
// length_size field) into Annex-B (each NAL prefixed with a 00 00 00 01 start code).
private fun convertLengthPrefixedToAnnexB(data: ByteArray, lengthSize: Int): ByteArray {
    val out = ByteArrayOutputStream()
    var pos = 0
    while (pos + lengthSize <= data.size) {
        var nalLength = 0
        for (i in 0 until lengthSize) {
            nalLength = (nalLength shl 8) or (data[pos + i].toInt() and 0xFF)
        }
        pos += lengthSize
        if (nalLength < 0 || pos + nalLength > data.size) break
        out.write(ANNEX_B_START_CODE)
        out.write(data, pos, nalLength)
        pos += nalLength
    }
    return out.toByteArray()
}
