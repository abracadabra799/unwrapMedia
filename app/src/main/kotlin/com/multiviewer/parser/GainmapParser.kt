package com.multiviewer.parser

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.multiviewer.ui.FfmpegImageSnapshotDecoder
import com.multiviewer.ui.FfmpegLocator
import org.jetbrains.skia.Image
import java.awt.EventQueue
import java.io.File
import kotlin.math.pow
import kotlin.math.roundToInt

enum class GainmapFormatType(val displayName: String) {
    ULTRA_HDR_JPEG("Ultra HDR (JPEG / GContainer)"),
    ISO_21496_1_JPEG("ISO 21496-1 (JPEG)"),
    ADOBE_GAINMAP_JPEG("Adobe HDR Gain Map (JPEG)"),
    APPLE_MPF_JPEG("Apple HDR Gain Map (JPEG / MPF)"),
    APPLE_HEIC("Apple HDR Gain Map (HEIC / HEIF)"),
    ISO_21496_1_HEIC("ISO 21496-1 (HEIC / HEIF)"),
    AVIF_GAINMAP("HDR Gain Map (AVIF)"),
    GENERIC_GAINMAP("HDR Gain Map"),
}

data class GainmapParsedParameters(
    val version: String? = null,
    val gainMapMin: Double? = null,
    val gainMapMax: Double? = null,
    val gamma: Double? = null,
    val offsetSdr: Double? = null,
    val offsetHdr: Double? = null,
    val hdrCapacityMin: Double? = null,
    val hdrCapacityMax: Double? = null,
    val baseRenditionIsHdr: Boolean? = null,
    val alternateColorSpace: String? = null,
    val stops: Double? = null,
    val linearMaxBoost: Double? = null,
    val linearMinBoost: Double? = null,
)

data class GainmapInfo(
    val hasGainmap: Boolean,
    val hasGainmapImage: Boolean = false,
    val formatType: GainmapFormatType,
    val rawXmp: String?,
    val secondaryXmp: String? = null,
    val primaryXmp: String? = null,
    val parameters: GainmapParsedParameters? = null,
    val imageWidth: Int? = null,
    val imageHeight: Int? = null,
    val imageFormat: String? = null,
    val byteOffset: Long? = null,
    val byteLength: Long? = null,
    val itemId: Long? = null,
    val summaryDescription: String = "",
)

data class MpfImageEntry(
    val typeFlags: Long,
    val size: Long,
    val offset: Long,
    val isPrimary: Boolean,
)

object GainmapParser {

    fun findGainmapInfo(file: File, root: BoxNode): GainmapInfo? {
        val extension = file.extension.lowercase()
        return when (extension) {
            "heic", "heif" -> findGainmapInHeic(file, root)
            "avif" -> findGainmapInAvif(file, root) ?: findGainmapInHeic(file, root)
            "jpg", "jpeg" -> findGainmapInJpeg(file, root)
            else -> findGainmapFromXmpNodes(file, root)
        }
    }

    fun parseMpfEntries(reader: ByteReader, app2Offset: Long, app2Size: Long): List<MpfImageEntry> {
        val payloadStart = app2Offset + 4
        val payloadEnd = app2Offset + app2Size
        if (payloadEnd - payloadStart < 12) return emptyList()

        val id = reader.readBytes(payloadStart, 4)
        if (!id.contentEquals(byteArrayOf(0x4D, 0x50, 0x46, 0x00))) return emptyList()

        val tiffStart = payloadStart + 4
        if (payloadEnd - tiffStart < 8) return emptyList()

        val byteOrder0 = reader.readUInt8(tiffStart)
        val byteOrder1 = reader.readUInt8(tiffStart + 1)
        val isLittleEndian = (byteOrder0 == 0x49 && byteOrder1 == 0x49)
        val isBigEndian = (byteOrder0 == 0x4D && byteOrder1 == 0x4D)
        if (!isLittleEndian && !isBigEndian) return emptyList()

        fun readU16(pos: Long): Int {
            val b0 = reader.readUInt8(pos)
            val b1 = reader.readUInt8(pos + 1)
            return if (isLittleEndian) (b1 shl 8) or b0 else (b0 shl 8) or b1
        }

        fun readU32(pos: Long): Long {
            val b0 = reader.readUInt8(pos).toLong()
            val b1 = reader.readUInt8(pos + 1).toLong()
            val b2 = reader.readUInt8(pos + 2).toLong()
            val b3 = reader.readUInt8(pos + 3).toLong()
            return if (isLittleEndian) {
                (b3 shl 24) or (b2 shl 16) or (b1 shl 8) or b0
            } else {
                (b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3
            }
        }

        val magic = readU16(tiffStart + 2)
        if (magic != 42) return emptyList()

        val ifdOffset = readU32(tiffStart + 4)
        val ifdStart = tiffStart + ifdOffset
        if (ifdStart + 2 > payloadEnd) return emptyList()

        val tagCount = readU16(ifdStart)
        var numImages = 0
        var mpEntryOffset = 0L

        for (i in 0 until tagCount) {
            val entryPos = ifdStart + 2 + i * 12
            if (entryPos + 12 > payloadEnd) break
            val tag = readU16(entryPos)
            val valueOrOffset = readU32(entryPos + 8)

            when (tag) {
                0xB001 -> numImages = valueOrOffset.toInt()
                0xB002 -> mpEntryOffset = valueOrOffset
            }
        }

        if (numImages <= 0 || mpEntryOffset <= 0) return emptyList()
        val mpEntriesStart = tiffStart + mpEntryOffset
        if (mpEntriesStart + numImages * 16 > payloadEnd && mpEntriesStart + numImages * 16 > reader.length) return emptyList()

        val entries = mutableListOf<MpfImageEntry>()
        for (i in 0 until numImages) {
            val pos = mpEntriesStart + i * 16
            if (pos + 16 > reader.length) break
            val flags = readU32(pos)
            val size = readU32(pos + 4)
            val dataOffsetRel = readU32(pos + 8)
            val absOffset = if (i == 0) 0L else tiffStart + dataOffsetRel
            entries.add(MpfImageEntry(typeFlags = flags, size = size, offset = absOffset, isPrimary = (i == 0)))
        }
        return entries
    }

    fun parseGainmapParametersFromXmp(xmp: String): GainmapParsedParameters? {
        if (xmp.isBlank()) return null

        fun extractAttr(name: String): String? {
            val attrPattern = Regex("""(?:[a-zA-Z0-9_-]+:)?$name\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            attrPattern.find(xmp)?.let { return it.groupValues[1].trim() }

            val tagPattern = Regex("""<([a-zA-Z0-9_-]+:)?$name[^>]*>([^<]+)</\1?$name>""", RegexOption.IGNORE_CASE)
            tagPattern.find(xmp)?.let { return it.groupValues[2].trim() }
            return null
        }

        val version = extractAttr("Version") ?: extractAttr("HDRGainMapVersion") ?: extractAttr("gainMapVersion")
        val gainMapMinStr = extractAttr("GainMapMin") ?: extractAttr("gainMapMin")
        val gainMapMaxStr = extractAttr("GainMapMax") ?: extractAttr("gainMapMax") ?: extractAttr("HDRGain") ?: extractAttr("Stops")
        val gammaStr = extractAttr("Gamma") ?: extractAttr("gamma")
        val offsetSdrStr = extractAttr("OffsetSDR") ?: extractAttr("offsetSdr")
        val offsetHdrStr = extractAttr("OffsetHDR") ?: extractAttr("offsetHdr")
        val hdrCapacityMinStr = extractAttr("HDRCapacityMin") ?: extractAttr("hdrCapacityMin")
        val hdrCapacityMaxStr = extractAttr("HDRCapacityMax") ?: extractAttr("hdrCapacityMax")
        val baseRenditionIsHdrStr = extractAttr("BaseRenditionIsHDR") ?: extractAttr("baseRenditionIsHdr")
        val alternateColorSpace = extractAttr("AlternateColorSpace") ?: extractAttr("alternateColorSpace")

        val gainMapMin = gainMapMinStr?.toDoubleOrNull()
        val gainMapMax = gainMapMaxStr?.toDoubleOrNull()
        val gamma = gammaStr?.toDoubleOrNull()
        val offsetSdr = offsetSdrStr?.toDoubleOrNull()
        val offsetHdr = offsetHdrStr?.toDoubleOrNull()
        val hdrCapacityMin = hdrCapacityMinStr?.toDoubleOrNull()
        val hdrCapacityMax = hdrCapacityMaxStr?.toDoubleOrNull()
        val baseRenditionIsHdr = baseRenditionIsHdrStr?.lowercase()?.let { it == "true" || it == "1" }

        val stops = gainMapMax
        val linearMaxBoost = gainMapMax?.let { 2.0.pow(it) }
        val linearMinBoost = gainMapMin?.let { 2.0.pow(it) }

        val hasGainmapKeywords = xmp.contains("GainMap", ignoreCase = true) ||
            xmp.contains("hdrgm", ignoreCase = true) ||
            xmp.contains("HDRGainMap", ignoreCase = true) ||
            xmp.contains("21496:1") ||
            xmp.contains("21496-1")

        if (version == null && gainMapMax == null && gainMapMin == null && gamma == null && !hasGainmapKeywords) {
            return null
        }

        return GainmapParsedParameters(
            version = version,
            gainMapMin = gainMapMin,
            gainMapMax = gainMapMax,
            gamma = gamma,
            offsetSdr = offsetSdr,
            offsetHdr = offsetHdr,
            hdrCapacityMin = hdrCapacityMin,
            hdrCapacityMax = hdrCapacityMax,
            baseRenditionIsHdr = baseRenditionIsHdr,
            alternateColorSpace = alternateColorSpace,
            stops = stops,
            linearMaxBoost = linearMaxBoost,
            linearMinBoost = linearMinBoost,
        )
    }

    private fun findGainmapInJpeg(file: File, root: BoxNode): GainmapInfo? {
        val allXmpFields = mutableListOf<BoxField>()
        fun collectXmp(node: BoxNode) {
            for (f in node.fields) {
                if (f.name == "xmp") allXmpFields.add(f)
            }
            for (child in node.children) collectXmp(child)
        }
        collectXmp(root)

        val primaryXmp = allXmpFields.firstOrNull()?.value

        // 1. Check GContainer (Ultra HDR / Adobe Gain Map in JPEG)
        if (primaryXmp != null && primaryXmp.contains("GainMap", ignoreCase = true)) {
            val lengthPattern = Regex("""Item:Semantic\s*=\s*["']GainMap["'][^>]*Item:Length\s*=\s*["'](\d+)["']""", RegexOption.IGNORE_CASE)
            val altLengthPattern = Regex("""Item:Length\s*=\s*["'](\d+)["'][^>]*Item:Semantic\s*=\s*["']GainMap["']""", RegexOption.IGNORE_CASE)
            val match = lengthPattern.find(primaryXmp) ?: altLengthPattern.find(primaryXmp)
            val gainmapLength = match?.groupValues?.get(1)?.toLongOrNull()

            if (gainmapLength != null && gainmapLength > 0 && gainmapLength < file.length()) {
                val gainmapOffset = file.length() - gainmapLength
                val (secondaryXmp, secWidth, secHeight) = readJpegHeaderAndXmp(file, gainmapOffset, gainmapLength)
                val params = (secondaryXmp ?: primaryXmp).let { parseGainmapParametersFromXmp(it) }

                return GainmapInfo(
                    hasGainmap = true,
                    hasGainmapImage = true,
                    formatType = if (primaryXmp.contains("21496", ignoreCase = true)) GainmapFormatType.ISO_21496_1_JPEG else GainmapFormatType.ULTRA_HDR_JPEG,
                    rawXmp = secondaryXmp ?: primaryXmp,
                    secondaryXmp = secondaryXmp,
                    primaryXmp = primaryXmp,
                    parameters = params,
                    imageWidth = secWidth,
                    imageHeight = secHeight,
                    imageFormat = "JPEG",
                    byteOffset = gainmapOffset,
                    byteLength = gainmapLength,
                    summaryDescription = "Ultra HDR secondary JPEG (${gainmapLength / 1024} KB)",
                )
            }
        }

        // 2. Check MPF (Multi-Picture Format in APP2)
        try {
            ByteReader.open(file).use { reader ->
                for (app2Node in root.children.filter { it.type == "APP2" }) {
                    val entries = parseMpfEntries(reader, app2Node.offset, app2Node.size)
                    if (entries.size >= 2) {
                        for (i in 1 until entries.size) {
                            val entry = entries[i]
                            if (entry.offset > 0 && entry.offset + entry.size <= reader.length) {
                                val magic = reader.readBytes(entry.offset, 2)
                                if (magic[0] == 0xFF.toByte() && magic[1] == 0xD8.toByte()) {
                                    val (secXmp, w, h) = readJpegHeaderAndXmp(file, entry.offset, entry.size)
                                    val combinedXmp = secXmp ?: primaryXmp
                                    val params = combinedXmp?.let { parseGainmapParametersFromXmp(it) }

                                    val isGainmap = combinedXmp?.contains("GainMap", ignoreCase = true) == true ||
                                        combinedXmp?.contains("hdrgm", ignoreCase = true) == true ||
                                        combinedXmp?.contains("HDRGainMap", ignoreCase = true) == true ||
                                        entry.typeFlags and 0x00FFFFFFL == 0x020002L ||
                                        params?.gainMapMax != null

                                    if (isGainmap) {
                                        return GainmapInfo(
                                            hasGainmap = true,
                                            hasGainmapImage = true,
                                            formatType = GainmapFormatType.APPLE_MPF_JPEG,
                                            rawXmp = secXmp ?: primaryXmp,
                                            secondaryXmp = secXmp,
                                            primaryXmp = primaryXmp,
                                            parameters = params,
                                            imageWidth = w,
                                            imageHeight = h,
                                            imageFormat = "JPEG",
                                            byteOffset = entry.offset,
                                            byteLength = entry.size,
                                            summaryDescription = "MPF secondary JPEG image #${i + 1} (${entry.size / 1024} KB)",
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        // 3. Fallback: primary XMP containing standalone gainmap metadata (NO separate image data)
        if (primaryXmp != null) {
            val params = parseGainmapParametersFromXmp(primaryXmp)
            if (params?.gainMapMax != null || primaryXmp.contains("hdrgm", ignoreCase = true)) {
                return GainmapInfo(
                    hasGainmap = true,
                    hasGainmapImage = false,
                    formatType = GainmapFormatType.ADOBE_GAINMAP_JPEG,
                    rawXmp = primaryXmp,
                    primaryXmp = primaryXmp,
                    parameters = params,
                    imageFormat = "JPEG",
                    summaryDescription = "Adobe Gain Map metadata in primary XMP (No separate image)",
                )
            }
        }

        return null
    }

    private fun findGainmapInHeic(file: File, root: BoxNode): GainmapInfo? {
        val meta = findFirst(root) { it.type == "meta" } ?: return null
        val iprp = findFirst(meta) { it.type == "iprp" }
        val ipco = iprp?.children?.find { it.type == "ipco" }
        val properties = ipco?.children ?: emptyList()

        val auxCProps = properties.mapIndexedNotNull { index, boxNode ->
            if (boxNode.type == "auxC") (index + 1) to boxNode else null
        }

        for ((propIndex, auxC) in auxCProps) {
            val auxType = auxC.fields.find { it.name == "aux_type" }?.value?.lowercase() ?: ""
            val isGainmap = auxType.contains("hdrgainmap") ||
                auxType.contains("hdr-gain-map") ||
                auxType.contains("21496") ||
                auxType.contains("gainmap")

            if (isGainmap) {
                val ipma = iprp?.children?.find { it.type == "ipma" }
                var gainmapItemId: Long? = null

                if (ipma != null) {
                    for (assoc in ipma.children) {
                        val itemId = assoc.type.removePrefix("item_").toLongOrNull() ?: continue
                        val indices = assoc.fields.filter { it.name == "property_index" }.mapNotNull { it.value.toIntOrNull() }
                        if (propIndex in indices) {
                            gainmapItemId = itemId
                            break
                        }
                    }
                }

                if (gainmapItemId == null) {
                    val iref = findFirst(meta) { it.type == "iref" }
                    val auxl = iref?.children?.find { it.type == "auxl" }
                    gainmapItemId = auxl?.fields?.find { it.name == "from_item_ID" }?.value?.toLongOrNull()
                }

                if (gainmapItemId == null) continue

                val iinf = findFirst(meta) { it.type == "iinf" }
                val infe = iinf?.children?.find { it.type == "infe" && it.fields.find { f -> f.name == "item_ID" }?.value?.toLongOrNull() == gainmapItemId }
                val itemType = infe?.fields?.find { it.name == "item_type" }?.value ?: "unknown"

                var width: Int? = null
                var height: Int? = null
                val ispe = findItemProperty(meta, gainmapItemId, "ispe")
                if (ispe != null) {
                    width = ispe.fields.find { it.name == "image_width" }?.value?.toIntOrNull()
                    height = ispe.fields.find { it.name == "image_height" }?.value?.toIntOrNull()
                }

                val iloc = findFirst(meta) { it.type == "iloc" }
                val itemNode = iloc?.children?.find { it.type == "item_$gainmapItemId" }
                val extent = itemNode?.children?.firstOrNull()
                val offset = extent?.fields?.find { it.name == "offset" }?.value?.toLongOrNull()
                val length = extent?.fields?.find { it.name == "length" }?.value?.toLongOrNull()

                val xmpText = findHeicXmp(file, meta)
                val params = xmpText?.let { parseGainmapParametersFromXmp(it) }

                val formatType = if (auxType.contains("21496")) GainmapFormatType.ISO_21496_1_HEIC else GainmapFormatType.APPLE_HEIC

                return GainmapInfo(
                    hasGainmap = true,
                    hasGainmapImage = true,
                    formatType = formatType,
                    rawXmp = xmpText,
                    primaryXmp = xmpText,
                    parameters = params,
                    imageWidth = width,
                    imageHeight = height,
                    imageFormat = when (itemType.lowercase()) {
                        "hvc1" -> "HEVC (hvc1)"
                        "jpeg" -> "JPEG"
                        "av01" -> "AV1 (av01)"
                        "grid" -> "HEVC Tile Grid (grid)"
                        else -> itemType
                    },
                    byteOffset = offset,
                    byteLength = length,
                    itemId = gainmapItemId,
                    summaryDescription = "Auxiliary HDR Gain Map (Item $gainmapItemId, $itemType)",
                )
            }
        }

        return null
    }

    private fun findGainmapInAvif(file: File, root: BoxNode): GainmapInfo? {
        val meta = findFirst(root) { it.type == "meta" } ?: return null
        val xmpText = findHeicXmp(file, meta)
        val params = xmpText?.let { parseGainmapParametersFromXmp(it) }
        if (params?.gainMapMax != null || xmpText?.contains("21496") == true) {
            return GainmapInfo(
                hasGainmap = true,
                hasGainmapImage = false,
                formatType = GainmapFormatType.AVIF_GAINMAP,
                rawXmp = xmpText,
                primaryXmp = xmpText,
                parameters = params,
                imageFormat = "AV1",
                summaryDescription = "AVIF Tone Map / Gain Map in XMP (No separate image)",
            )
        }
        return null
    }

    private fun findGainmapFromXmpNodes(file: File, root: BoxNode): GainmapInfo? {
        var foundXmp: String? = null
        fun search(node: BoxNode) {
            for (f in node.fields) {
                if (f.name == "xmp") {
                    foundXmp = f.value
                    return
                }
            }
            for (child in node.children) {
                if (foundXmp != null) return
                search(child)
            }
        }
        search(root)

        val xmp = foundXmp ?: return null
        val params = parseGainmapParametersFromXmp(xmp)
        if (params?.gainMapMax != null || xmp.contains("GainMap", ignoreCase = true) || xmp.contains("hdrgm", ignoreCase = true)) {
            return GainmapInfo(
                hasGainmap = true,
                hasGainmapImage = false,
                formatType = GainmapFormatType.GENERIC_GAINMAP,
                rawXmp = xmp,
                primaryXmp = xmp,
                parameters = params,
                summaryDescription = "Gain Map metadata in XMP (No separate image)",
            )
        }
        return null
    }

    private fun findHeicXmp(file: File, meta: BoxNode): String? {
        val allXmp = mutableListOf<String>()
        fun search(node: BoxNode) {
            for (f in node.fields) {
                if (f.name == "xmp") allXmp.add(f.value)
            }
            for (child in node.children) search(child)
        }
        search(meta)
        if (allXmp.isNotEmpty()) return allXmp.joinToString("\n\n")

        val iinf = findFirst(meta) { it.type == "iinf" } ?: return null
        val iloc = findFirst(meta) { it.type == "iloc" } ?: return null
        for (infe in iinf.children) {
            val itemType = infe.fields.find { it.name == "item_type" }?.value ?: ""
            val contentType = infe.fields.find { it.name == "content_type" }?.value ?: ""
            val itemId = infe.fields.find { it.name == "item_ID" }?.value?.toLongOrNull() ?: continue
            if ((itemType == "mime" && contentType.contains("xml")) || itemType == "xmp ") {
                val itemNode = iloc.children.find { it.type == "item_$itemId" }
                val extent = itemNode?.children?.firstOrNull()
                val offset = extent?.fields?.find { it.name == "offset" }?.value?.toLongOrNull() ?: continue
                val length = extent.fields.find { it.name == "length" }?.value?.toLongOrNull() ?: continue
                if (length in 1..2_000_000) {
                    try {
                        ByteReader.open(file).use { reader ->
                            if (offset + length <= reader.length) {
                                val bytes = reader.readBytes(offset, length.toInt())
                                return String(bytes, Charsets.UTF_8).trimEnd(' ', Char(0))
                            }
                        }
                    } catch (e: Exception) {}
                }
            }
        }
        return null
    }

    private fun readJpegHeaderAndXmp(file: File, offset: Long, length: Long): Triple<String?, Int?, Int?> {
        var xmpText: String? = null
        var width: Int? = null
        var height: Int? = null

        try {
            ByteReader.open(file).use { reader ->
                if (offset + length > reader.length) return Triple(null, null, null)
                val segments = parseJpegSegments(reader, offset, offset + length)
                for (seg in segments) {
                    if (seg.type == "APP1") {
                        val f = seg.fields.find { it.name == "xmp" }
                        if (f != null && xmpText == null) {
                            xmpText = f.value
                        }
                    }
                    if (seg.type.startsWith("SOF") && width == null) {
                        for (f in seg.fields) {
                            if (f.name == "width") width = f.value.toIntOrNull()
                            if (f.name == "height") height = f.value.toIntOrNull()
                        }
                        if (width == null) {
                            val regex = Regex("""(\d+)x(\d+)""")
                            seg.summary?.let { s ->
                                regex.find(s)?.let {
                                    width = it.groupValues[1].toIntOrNull()
                                    height = it.groupValues[2].toIntOrNull()
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {}

        return Triple(xmpText, width, height)
    }

    fun extractGainmapBytes(file: File, root: BoxNode?, info: GainmapInfo): ByteArray? {
        if (info.byteOffset != null && info.byteLength != null && info.byteLength > 0) {
            return try {
                ByteReader.open(file).use { reader ->
                    if (info.byteOffset >= 0 && info.byteOffset + info.byteLength <= reader.length) {
                        reader.readBytes(info.byteOffset, info.byteLength.toInt())
                    } else null
                }
            } catch (e: Exception) {
                null
            }
        }
        if (info.itemId != null && root != null) {
            val meta = findFirst(root) { it.type == "meta" } ?: return null
            val iloc = findFirst(meta) { it.type == "iloc" } ?: return null
            val idatBase = findFirst(root) { it.type == "idat" }?.let { it.offset + it.headerSize } ?: 0L
            return try {
                ByteReader.open(file).use { reader ->
                    extractItemBytes(reader, iloc, info.itemId, idatBase)
                }
            } catch (e: Exception) {
                null
            }
        }
        return null
    }

    fun decodeGainmapBitmapAsync(
        file: File,
        root: BoxNode?,
        info: GainmapInfo,
        onResult: (ImageBitmap?) -> Unit,
    ) {
        Thread {
            val bitmap: ImageBitmap? = try {
                if (!info.hasGainmapImage) {
                    null
                } else if (info.itemId != null && root != null) {
                    // 1. Check if this gainmap item is a HEIC Tile Grid (common in high-res Samsung/Apple HEIC photos)
                    val tileGrid = findHeicTileGridForItem(file, root, info.itemId)
                    if (tileGrid != null) {
                        stitchHeicGridTiles(file, root, tileGrid) { annexB ->
                            decodeHevcBytesViaFfmpeg(annexB)
                        }
                    } else {
                        // 2. Single HEVC item
                        val annexB = extractHevcItemAnnexB(file, root, info.itemId)
                        if (annexB != null) {
                            decodeHevcBytesViaFfmpeg(annexB)
                        } else {
                            val bytes = extractGainmapBytes(file, root, info)
                            if (bytes != null && bytes.size >= 4 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()) {
                                Image.makeFromEncoded(bytes)?.toComposeImageBitmap()
                            } else null
                        }
                    }
                } else {
                    val bytes = extractGainmapBytes(file, root, info)
                    if (bytes != null && bytes.size >= 4 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()) {
                        Image.makeFromEncoded(bytes)?.toComposeImageBitmap()
                    } else null
                }
            } catch (_: Exception) {
                null
            }
            EventQueue.invokeLater { onResult(bitmap) }
        }.apply { isDaemon = true }.start()
    }

    private fun decodeHevcBytesViaFfmpeg(annexB: ByteArray): ImageBitmap? {
        val tempH265 = try {
            File.createTempFile("gainmap-hevc-", ".h265")
        } catch (_: Exception) {
            return null
        }
        return try {
            tempH265.writeBytes(annexB)
            // Explicitly specify -pix_fmt rgb24 so Grayscale/Monochrome HEVC tiles are decoded
            // as standard 24-bit sRGB PNG, preventing black/transparent rendering issues in Skia.
            FfmpegImageSnapshotDecoder.decodeSingleFrameToBitmap(
                listOf(FfmpegLocator.ffmpegPath(), "-y", "-f", "hevc", "-i", tempH265.absolutePath, "-pix_fmt", "rgb24", "-frames:v", "1", "-update", "1"),
            )
        } finally {
            tempH265.delete()
        }
    }

    fun extractGainmapImageToFile(
        file: File,
        root: BoxNode?,
        info: GainmapInfo,
        destination: File,
    ): Boolean {
        if (!info.hasGainmapImage) return false

        // 1. JPEG byte payload (Ultra HDR secondary JPEG or MPF entry)
        val bytes = extractGainmapBytes(file, root, info)
        if (bytes != null && bytes.size >= 4 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()) {
            destination.writeBytes(bytes)
            return true
        }

        // 2. HEIC tile grid or single HEVC
        if (info.itemId != null && root != null) {
            val tileGrid = findHeicTileGridForItem(file, root, info.itemId)
            val bitmap = if (tileGrid != null) {
                stitchHeicGridTiles(file, root, tileGrid) { annexB -> decodeHevcBytesViaFfmpeg(annexB) }
            } else {
                val annexB = extractHevcItemAnnexB(file, root, info.itemId)
                annexB?.let { decodeHevcBytesViaFfmpeg(it) }
            }
            if (bitmap != null) {
                val skia = bitmap.asSkiaBitmap()
                val image = Image.makeFromBitmap(skia)
                val pngData = image.encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)
                if (pngData != null) {
                    destination.writeBytes(pngData.bytes)
                    return true
                }
            }
        }

        if (bytes != null && bytes.isNotEmpty()) {
            destination.writeBytes(bytes)
            return true
        }
        return false
    }
}
