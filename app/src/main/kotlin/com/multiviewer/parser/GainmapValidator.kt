package com.multiviewer.parser

import com.multiviewer.parser.integrity.CheckStatus
import com.multiviewer.parser.integrity.CheckStatus.FAIL
import com.multiviewer.parser.integrity.CheckStatus.INFO
import com.multiviewer.parser.integrity.CheckStatus.PASS
import com.multiviewer.parser.integrity.CheckStatus.SKIP
import com.multiviewer.parser.integrity.CheckStatus.WARN
import com.multiviewer.parser.integrity.IntegrityCheckItem
import java.io.File
import kotlin.math.abs

/**
 * Gain map conformance checks (ISO 21496-1 / Ultra HDR / HEIF tmap). Missing or unreadable inputs
 * surface as WARN/FAIL/SKIP, never as PASS.
 */
object GainmapValidator {
    private const val TOLERANCE = 1e-3

    fun validate(file: File, root: BoxNode, info: GainmapInfo, baseWidth: Int?, baseHeight: Int?): List<IntegrityCheckItem> = buildList {
        val p = info.parameters
        val isJpeg = file.extension.lowercase().let { it == "jpg" || it == "jpeg" }

        add(
            when {
                p != null -> IntegrityCheckItem("gainmap.metadata", "Gain map metadata", PASS, "Parameters from ${if (info.isoMetadata != null) "ISO 21496-1" else "XMP"}")
                info.hasGainmapImage -> IntegrityCheckItem("gainmap.metadata", "Gain map metadata", FAIL, "A gain map image exists but no ISO 21496-1 or hdrgm parameters were found -- decoders cannot apply it")
                else -> IntegrityCheckItem("gainmap.metadata", "Gain map metadata", WARN, "Gain map signalled but no parameters could be read")
            },
        )

        if (p == null) {
            add(IntegrityCheckItem("gainmap.params.range", "Parameter ranges", SKIP, "No parameters"))
        } else {
            val problems = buildList {
                val min = p.gainMapMin; val max = p.gainMapMax
                if (min != null && max != null && max < min) add("GainMapMax ($max) < GainMapMin ($min)")
                p.gamma?.let { if (it <= 0.0) add("Gamma must be > 0 (is $it)") }
                val cMin = p.hdrCapacityMin; val cMax = p.hdrCapacityMax
                if (cMin != null && cMax != null && cMax < cMin) add("HDRCapacityMax ($cMax) < HDRCapacityMin ($cMin)")
                if (max == null) add("GainMapMax missing")
            }
            add(
                if (problems.isEmpty()) {
                    IntegrityCheckItem("gainmap.params.range", "Parameter ranges", PASS, "min ≤ max, gamma > 0, capacity min ≤ max")
                } else {
                    IntegrityCheckItem("gainmap.params.range", "Parameter ranges", FAIL, problems.joinToString("; "))
                },
            )
            val max = p.gainMapMax
            val cMax = p.hdrCapacityMax
            add(
                when {
                    max == null || cMax == null -> IntegrityCheckItem("gainmap.params.capacity", "Capacity vs boost", SKIP, "GainMapMax or HDRCapacityMax missing")
                    cMax + TOLERANCE < max -> IntegrityCheckItem("gainmap.params.capacity", "Capacity vs boost", WARN, "HDRCapacityMax %.3f < GainMapMax %.3f: the top %.2f stops of boost are never fully applied".format(cMax, max, max - cMax))
                    else -> IntegrityCheckItem("gainmap.params.capacity", "Capacity vs boost", PASS, "HDRCapacityMax %.3f covers GainMapMax %.3f".format(cMax, max))
                },
            )
        }

        add(xmpVsIso(info))
        add(aspect(info, baseWidth, baseHeight))
        if (isJpeg) addAll(jpegChecks(file, info)) else addAll(heifChecks(file, root, info))
    }

    private fun xmpVsIso(info: GainmapInfo): IntegrityCheckItem {
        val id = "gainmap.xmp_iso"
        val title = "XMP ↔ ISO 21496-1 agreement"
        val iso = info.isoMetadata?.toParsedParameters()
        val xmp = info.xmpParameters?.takeIf { it.gainMapMax != null }
        return when {
            iso != null && xmp != null -> {
                val fields = listOf(
                    Triple("GainMapMin", xmp.gainMapMin, iso.gainMapMin),
                    Triple("GainMapMax", xmp.gainMapMax, iso.gainMapMax),
                    Triple("Gamma", xmp.gamma, iso.gamma),
                    Triple("OffsetSDR", xmp.offsetSdr, iso.offsetSdr),
                    Triple("OffsetHDR", xmp.offsetHdr, iso.offsetHdr),
                    Triple("HDRCapacityMin", xmp.hdrCapacityMin, iso.hdrCapacityMin),
                    Triple("HDRCapacityMax", xmp.hdrCapacityMax, iso.hdrCapacityMax),
                )
                val diffs = fields.filter { (_, x, i) -> x != null && i != null && abs(x - i) > TOLERANCE }
                if (diffs.isEmpty()) {
                    IntegrityCheckItem(id, title, PASS, "All ${fields.count { it.second != null }} shared fields agree within $TOLERANCE")
                } else {
                    IntegrityCheckItem(
                        id, title, WARN,
                        "Decoders reading XMP and decoders reading ISO will render differently: " +
                            diffs.joinToString("; ") { (n, x, i) -> "$n XMP=%.4f ISO=%.4f".format(x, i) },
                        info.isoPayloadOffset,
                    )
                }
            }
            iso != null -> IntegrityCheckItem(id, title, INFO, "ISO 21496-1 only (no hdrgm XMP): older Ultra HDR v1.0 readers won't see the parameters")
            xmp != null -> IntegrityCheckItem(id, title, INFO, "hdrgm XMP only (no ISO 21496-1 binary): ISO-first decoders fall back to XMP")
            else -> IntegrityCheckItem(id, title, SKIP, "No parameters to compare")
        }
    }

    private fun aspect(info: GainmapInfo, baseWidth: Int?, baseHeight: Int?): IntegrityCheckItem {
        val id = "gainmap.aspect"
        val title = "Gain map aspect ratio"
        val gw = info.imageWidth; val gh = info.imageHeight
        if (baseWidth == null || baseHeight == null || gw == null || gh == null || baseHeight == 0 || gh == 0) {
            return IntegrityCheckItem(id, title, SKIP, "Base or gain map dimensions unknown")
        }
        val baseRatio = baseWidth.toDouble() / baseHeight
        val gainRatio = gw.toDouble() / gh
        val off = abs(gainRatio - baseRatio) / baseRatio
        val scale = "%.2f".format(baseWidth.toDouble() / gw)
        return if (off <= 0.01) {
            IntegrityCheckItem(id, title, PASS, "${gw}x$gh for a ${baseWidth}x$baseHeight base (1/$scale scale)")
        } else {
            IntegrityCheckItem(id, title, WARN, "${gw}x$gh vs base ${baseWidth}x$baseHeight: aspect ratios differ by %.1f%% -- the map is stretched when applied".format(off * 100))
        }
    }

    private fun jpegChecks(file: File, info: GainmapInfo): List<IntegrityCheckItem> = buildList {
        val offset = info.byteOffset
        val length = info.byteLength
        if (info.hasGainmapImage) {
            val soiOk = offset != null && length != null && offset + length <= file.length() && try {
                ByteReader.open(file).use { r -> r.readBytes(offset, 2).let { it[0] == 0xFF.toByte() && it[1] == 0xD8.toByte() } }
            } catch (_: Exception) {
                false
            }
            add(
                if (soiOk) {
                    IntegrityCheckItem("gainmap.jpeg.soi", "Gain map image location", PASS, "SOI at $offset, $length bytes, inside the file", offset, length)
                } else {
                    IntegrityCheckItem("gainmap.jpeg.soi", "Gain map image location", FAIL, "Declared gain map (offset=$offset, length=$length) is not a JPEG inside the file (size ${file.length()})", offset, length)
                },
            )
        }

        val declared = info.primaryXmp?.let { xmp ->
            (
                Regex("""Item:Semantic\s*=\s*["']GainMap["'][^>]*?Item:Length\s*=\s*["'](\d+)["']""", RegexOption.IGNORE_CASE).find(xmp)
                    ?: Regex("""Item:Length\s*=\s*["'](\d+)["'][^>]*?Item:Semantic\s*=\s*["']GainMap["']""", RegexOption.IGNORE_CASE).find(xmp)
                )?.groupValues?.get(1)?.toLongOrNull()
        }
        add(
            when {
                GainmapMetadataSource.GCONTAINER !in info.metadataSources -> IntegrityCheckItem("gainmap.jpeg.gcontainer_length", "GContainer gain map length", SKIP, "No GContainer directory")
                declared == null -> IntegrityCheckItem("gainmap.jpeg.gcontainer_length", "GContainer gain map length", WARN, "GContainer directory has no Item:Length for the GainMap item")
                length == null -> IntegrityCheckItem("gainmap.jpeg.gcontainer_length", "GContainer gain map length", WARN, "Declared $declared bytes but the gain map image could not be located")
                declared == length -> IntegrityCheckItem("gainmap.jpeg.gcontainer_length", "GContainer gain map length", PASS, "Item:Length $declared matches the gain map image", offset, length)
                else -> IntegrityCheckItem("gainmap.jpeg.gcontainer_length", "GContainer gain map length", FAIL, "Item:Length declares $declared bytes but the gain map image is $length bytes", offset, length)
            },
        )

        add(
            when {
                info.isoMetadata == null -> IntegrityCheckItem("gainmap.jpeg.iso_primary", "Primary ISO version block", SKIP, "No ISO 21496-1 metadata in the gain map")
                info.hasIsoVersionBlockInPrimary -> IntegrityCheckItem("gainmap.jpeg.iso_primary", "Primary ISO version block", PASS, "Primary image advertises ISO 21496-1 (version-only APP2)")
                else -> IntegrityCheckItem("gainmap.jpeg.iso_primary", "Primary ISO version block", WARN, "Gain map has ISO 21496-1 metadata but the primary image lacks the version-only APP2 that ISO readers look for first")
            },
        )
    }

    private fun heifChecks(file: File, root: BoxNode, info: GainmapInfo): List<IntegrityCheckItem> = buildList {
        val tmap = info.tmapItem
        if (tmap == null) {
            add(IntegrityCheckItem("gainmap.heif.tmap", "HEIF tmap item", INFO, "No tmap derived item: auxiliary-image gain map (Apple-style)${info.auxType?.let { " · auxC $it" } ?: ""}"))
            return@buildList
        }
        val brands = ftypBrands(file, root)
        add(
            when {
                brands == null -> IntegrityCheckItem("gainmap.heif.tmap_brand", "ftyp 'tmap' brand", WARN, "ftyp could not be read")
                "tmap" in brands -> IntegrityCheckItem("gainmap.heif.tmap_brand", "ftyp 'tmap' brand", PASS, "Compatible brands: ${brands.joinToString()}")
                else -> IntegrityCheckItem("gainmap.heif.tmap_brand", "ftyp 'tmap' brand", WARN, "tmap item present but 'tmap' is not a compatible brand (${brands.joinToString()}) -- readers may skip it")
            },
        )
        add(
            if (tmap.version == 0) {
                IntegrityCheckItem("gainmap.heif.tmap_version", "tmap item version", PASS, "version 0, ${tmap.payload?.size ?: 0}-byte ISO payload")
            } else {
                IntegrityCheckItem("gainmap.heif.tmap_version", "tmap item version", WARN, "Unexpected tmap version ${tmap.version}")
            },
        )
        val meta = root.children.firstOrNull { it.type == "meta" }
        val primaryId = meta?.let { findFirst(it) { n -> n.type == "pitm" } }?.fields?.find { it.name == "primary_item_ID" }?.value?.toLongOrNull()
        val expected = listOfNotNull(primaryId, info.itemId)
        add(
            when {
                tmap.inputItemIds.size != 2 -> IntegrityCheckItem("gainmap.heif.tmap_inputs", "tmap inputs (dimg)", FAIL, "tmap must reference exactly the base and the gain map; dimg lists ${tmap.inputItemIds}")
                tmap.inputItemIds == expected -> IntegrityCheckItem("gainmap.heif.tmap_inputs", "tmap inputs (dimg)", PASS, "dimg ${tmap.itemId} → base #${expected[0]}, gain map #${expected[1]}")
                else -> IntegrityCheckItem("gainmap.heif.tmap_inputs", "tmap inputs (dimg)", WARN, "dimg ${tmap.itemId} → ${tmap.inputItemIds}, expected base #$primaryId then gain map #${info.itemId}")
            },
        )
        val groups = altrGroups(file, meta)
        add(
            when {
                groups == null -> IntegrityCheckItem("gainmap.heif.altr", "altr group", WARN, "No grpl/altr group: readers can't pick the base as the fallback for the tmap image")
                groups.any { it.contains(tmap.itemId) && primaryId != null && it.contains(primaryId) } ->
                    IntegrityCheckItem("gainmap.heif.altr", "altr group", PASS, "altr groups ${groups.map { it.toList() }} contain tmap #${tmap.itemId} and base #$primaryId")
                else -> IntegrityCheckItem("gainmap.heif.altr", "altr group", WARN, "No altr group holds both tmap #${tmap.itemId} and base #$primaryId (groups: ${groups.map { it.toList() }})")
            },
        )
    }

    private fun ftypBrands(file: File, root: BoxNode): List<String>? {
        val ftyp = root.children.firstOrNull { it.type == "ftyp" } ?: return null
        return try {
            ByteReader.open(file).use { r ->
                val body = r.readBytes(ftyp.offset + ftyp.headerSize, (ftyp.size - ftyp.headerSize).toInt())
                buildList {
                    add(String(body, 0, 4, Charsets.US_ASCII))
                    var i = 8
                    while (i + 4 <= body.size) { add(String(body, i, 4, Charsets.US_ASCII)); i += 4 }
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    /** Entity id sets of each `altr` group in `grpl` (EntityToGroupBox: v/flags, group_id, count, ids). */
    private fun altrGroups(file: File, meta: BoxNode?): List<Set<Long>>? {
        val grpl = meta?.children?.firstOrNull { it.type == "grpl" } ?: return null
        return try {
            ByteReader.open(file).use { r ->
                val body = r.readBytes(grpl.offset + grpl.headerSize, (grpl.size - grpl.headerSize).toInt())
                fun u32(at: Int): Long = ((body[at].toLong() and 0xFF) shl 24) or ((body[at + 1].toLong() and 0xFF) shl 16) or
                    ((body[at + 2].toLong() and 0xFF) shl 8) or (body[at + 3].toLong() and 0xFF)
                val groups = mutableListOf<Set<Long>>()
                var pos = 0
                while (pos + 20 <= body.size) {
                    val size = u32(pos).toInt()
                    if (size < 20 || pos + size > body.size) break
                    val type = String(body, pos + 4, 4, Charsets.US_ASCII)
                    if (type == "altr") {
                        val count = u32(pos + 16).toInt()
                        if (20 + count * 4 <= size) groups += (0 until count).map { u32(pos + 20 + it * 4) }.toSet()
                    }
                    pos += size
                }
                groups.takeIf { it.isNotEmpty() }
            }
        } catch (_: Exception) {
            null
        }
    }
}

/** Worst status of a gain map check list, treating INFO as PASS and ignoring SKIP. */
fun gainmapOverall(items: List<IntegrityCheckItem>): CheckStatus = when {
    items.any { it.status == FAIL } -> FAIL
    items.any { it.status == WARN } -> WARN
    items.any { it.status == PASS || it.status == INFO } -> PASS
    else -> WARN // nothing could be checked: never a silent PASS
}
