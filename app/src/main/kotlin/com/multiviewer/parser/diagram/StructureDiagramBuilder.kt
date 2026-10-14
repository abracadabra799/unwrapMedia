package com.multiviewer.parser.diagram

import com.multiviewer.parser.BoxNode
import com.multiviewer.parser.EmbeddedVideo

/** Deepest level kept in the model (0-based); deeper structure stays in the tree view. */
const val MAX_DIAGRAM_DEPTH = 2

/** Siblings beyond this are folded into one summary region with a count (e.g. sample tables). */
const val MAX_REGIONS_PER_LEVEL = 24

private fun hex(v: Long) = "0x" + v.toString(16).uppercase()

private fun range(offset: Long, end: Long) = "$offset–$end (${hex(offset)}–${hex(end)})"

fun buildStructureDiagram(root: BoxNode, fileSize: Long, embeddedVideo: EmbeddedVideo?): StructureDiagram {
    val regions = mutableListOf<DiagramRegion>()
    val nodes = mutableMapOf<String, BoxNode>()
    val warnings = mutableListOf<String>()
    val notes = mutableListOf<String>()

    fun walk(parent: BoxNode, parentId: String?, path: List<Int>, contentStart: Long, contentEnd: Long, depth: Int) {
        val beyond = if (parentId == null) "end of file" else "end of ${parent.type}"
        // A nested node whose children point outside it (e.g. a TIFF IFD referencing its thumbnail) is a
        // logical grouping, not a byte container: list the children without gap/overlap claims.
        val physical = parentId == null || parent.children.all { it.offset >= contentStart && it.offset + it.size <= contentEnd }
        if (!physical) {
            notes += "Children of ${parent.type} lie outside it, so that level is listed as a logical grouping (no gap/overlap checks)"
        }
        val sorted = parent.children.withIndex().sortedBy { it.value.offset }
        if (physical && sorted.map { it.index } != parent.children.indices.toList()) {
            warnings += "Children of ${parent.type} are out of file order; drawn in offset order"
        }
        val shown = if (sorted.size > MAX_REGIONS_PER_LEVEL) sorted.take(MAX_REGIONS_PER_LEVEL - 1) else sorted
        val levelStart = regions.size
        var cursor = contentStart
        var previous: Int? = null // index in [regions] of the previous node region at this level
        for ((index, child) in shown) {
            val id = (path + index).joinToString(".")
            var status = if (child.warnings.isEmpty()) RegionStatus.VERIFIED else RegionStatus.WARNING
            if (!physical) {
                // Still bounded by the file itself.
                if (child.offset < 0 || child.offset + child.size > fileSize) {
                    status = RegionStatus.WARNING
                    warnings += "${child.type} at ${range(child.offset, child.offset + child.size)} extends past the end of file ($fileSize)"
                }
            } else if (child.offset > cursor) {
                regions += gap(parentId, depth, cursor, child.offset)
            } else if (child.offset < cursor) {
                status = RegionStatus.WARNING
                previous?.let { regions[it] = regions[it].copy(status = RegionStatus.WARNING) }
                warnings += "Overlap: ${child.type} at ${range(child.offset, child.offset + child.size)} overlaps the previous range"
            }
            val childEnd = child.offset + child.size
            if (physical && (child.offset < contentStart || childEnd > contentEnd || child.size < 0)) {
                status = RegionStatus.WARNING
                warnings += "${child.type} at ${range(child.offset, childEnd)} extends past the $beyond (${contentEnd})"
            }
            nodes[id] = child
            if (physical) previous = regions.size
            regions += DiagramRegion(id, child.type, child.offset, child.size, depth, roleOf(child), status, parentId, path + index)
            cursor = maxOf(cursor, childEnd)
        }
        if (shown.size < sorted.size) {
            val rest = sorted.drop(shown.size).map { it.value }
            val start = rest.minOf { it.offset }
            val end = rest.maxOf { it.offset + it.size }
            val kinds = rest.groupingBy { it.type }.eachCount().entries.sortedByDescending { it.value }
                .take(3).joinToString(", ") { "${it.key}×${it.value}" }
            // A folded node with a parser warning, or an overlap among the folded nodes, keeps the summary from looking clean.
            val overlapping = physical && (rest.zipWithNext().any { (a, b) -> b.offset < a.offset + a.size } || rest.first().offset < cursor)
            val warned = rest.count { it.warnings.isNotEmpty() }
            if (overlapping) warnings += "Overlap among the ${rest.size} folded children of ${parent.type}"
            if (warned > 0) warnings += "$warned folded children of ${parent.type} carry parser warnings"
            regions += DiagramRegion(
                "${parentId ?: "root"}+${rest.size}", "${rest.size} more ($kinds)", start, end - start, depth,
                RegionRole.SUMMARY, if (overlapping || warned > 0) RegionStatus.WARNING else RegionStatus.VERIFIED,
                parentId, null, summarizedCount = rest.size,
            )
            cursor = maxOf(cursor, end)
        }
        if (physical && cursor < contentEnd && (parent.children.isNotEmpty() || parentId == null)) {
            regions += gap(parentId, depth, cursor, contentEnd)
        }
        if (depth >= MAX_DIAGRAM_DEPTH) return
        // Recurse after the level is complete so each level stays in file order in [regions].
        for (region in regions.subList(levelStart, regions.size).toList()) {
            val node = region.nodePath?.let { nodes[region.id] } ?: continue
            if (node.children.isEmpty()) continue
            walk(node, region.id, region.nodePath, node.offset + node.headerSize, node.offset + node.size, depth + 1)
        }
    }
    walk(root, null, emptyList(), 0, fileSize, 0)

    val overlays = mutableListOf<DiagramOverlay>()
    val links = mutableListOf<DiagramLink>()
    if (embeddedVideo != null) {
        val (start, end) = embeddedVideo.start to embeddedVideo.end
        if (start < 0 || start >= end || end > fileSize) {
            warnings += "Embedded video range ${range(start, end)} is not inside the file; no link drawn"
        } else {
            // The range is only parser evidence when the tree holds an ftyp box at its first byte; an XMP-derived
            // start (end of file minus a declared length) can point anywhere.
            if (!hasParsedFtypAt(root, start)) {
                warnings += "Embedded video range ${range(start, end)} is not verified (no parsed ftyp box at its start); no link drawn"
                return StructureDiagram(fileSize, regions, overlays, links, warnings, notes)
            }
            val overlay = DiagramOverlay("embedded-video", "Embedded video (${embeddedVideo.extension})", start, end - start)
            overlays += overlay
            val evidence = "parser-verified video bytes ${range(start, end)}"
            fun containing(type: String) = regions.firstOrNull { r ->
                r.nodePath != null && nodes[r.id]?.type == type && r.offset <= start && end <= r.end
            }
            val sef = containing("sefd")
            val container = containing("mpvd") ?: containing("EmbeddedVideoData")
            val xmp = regions.firstOrNull { r ->
                nodes[r.id]?.fields?.any { f ->
                    f.name == "xmp" && MOTION_PHOTO_DECLARATION.containsMatchIn(f.value)
                } == true
            }
            when {
                sef != null -> links += DiagramLink(sef.id, overlay.id, LinkMeaning.SEF_EMBEDDED_VIDEO, "SEF trailer; $evidence")
                container != null -> links += DiagramLink(container.id, overlay.id, LinkMeaning.CONTAINER_EMBEDDED_VIDEO, "${container.label} box; $evidence")
                xmp != null -> links += DiagramLink(xmp.id, overlay.id, LinkMeaning.XMP_EMBEDDED_VIDEO, "XMP motion photo declaration; $evidence")
                else -> warnings += "Embedded video at ${range(start, end)} has no identified declaration; relationship unknown"
            }
        }
    }
    return StructureDiagram(fileSize, regions, overlays, links, warnings, notes)
}

// GCamera:MotionPhoto / MicroVideo set to 1 (attribute or element form), or a MicroVideoOffset.
private val MOTION_PHOTO_DECLARATION =
    Regex("""(MotionPhoto|MicroVideo)\s*=\s*["']1["']|(MotionPhoto|MicroVideo)>\s*1\s*<|MicroVideoOffset""")

private fun hasParsedFtypAt(node: BoxNode, offset: Long): Boolean =
    (node.type == "ftyp" && node.offset == offset) || node.children.any { hasParsedFtypAt(it, offset) }

private fun gap(parentId: String?, depth: Int, start: Long, end: Long) = DiagramRegion(
    "gap:${parentId ?: "root"}@$start", if (parentId == null) "unparsed" else "not broken down",
    start, end - start, depth, RegionRole.UNPARSED, RegionStatus.UNPARSED, parentId, null,
)

private val METADATA = setOf("COM", "XMP", "Exif", "EXIF", "tEXt", "iTXt", "zTXt", "eXIf", "iCCP", "udta", "uuid", "ICCP", "XMP ", "meta")
private val CODEC_SETUP = setOf("DQT", "DHT", "DRI", "IHDR", "PLTE", "stsd", "avcC", "hvcC", "av1C", "VP8X", "iprp")
private val MEDIA_DATA = setOf("SOS", "mdat", "idat", "IDAT", "VP8 ", "VP8L", "ANMF", "data")
private val MARKER = setOf("SOI", "EOI", "IEND", "ftyp", "RIFF")
private val TRAILER = setOf("sefd", "SEFH", "SEFT")

private fun roleOf(node: BoxNode): RegionRole = when {
    node.type in TRAILER -> RegionRole.TRAILER
    node.type in MARKER -> RegionRole.MARKER
    node.type in MEDIA_DATA -> RegionRole.MEDIA_DATA
    node.type in CODEC_SETUP || node.type.startsWith("SOF") -> RegionRole.CODEC_SETUP
    node.type in METADATA || node.type.startsWith("APP") || node.type.startsWith("IFD") -> RegionRole.METADATA
    node.children.isNotEmpty() -> RegionRole.CONTAINER
    else -> RegionRole.OTHER
}

/** The node a region was built from ([DiagramRegion.nodePath]), or null if the path no longer resolves. */
fun nodeAtPath(root: BoxNode, path: List<Int>): BoxNode? =
    path.fold(root as BoxNode?) { node, index -> node?.children?.getOrNull(index) }
