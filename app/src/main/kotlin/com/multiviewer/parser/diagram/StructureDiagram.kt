package com.multiviewer.parser.diagram

/**
 * The actual byte layout of one parsed file, for the structure diagram/table. Every range comes from
 * the parsed [com.multiviewer.parser.BoxNode] tree or a parser-verified embedded media range; nothing
 * is inferred from the format in general.
 */
data class StructureDiagram(
    val fileSize: Long,
    /** Physical regions in file order per level; [DiagramRegion.depth] 0 is the top level. */
    val regions: List<DiagramRegion>,
    /** Semantic ranges drawn over the physical map (e.g. embedded video bytes), never extra allocations. */
    val overlays: List<DiagramOverlay>,
    val links: List<DiagramLink>,
    /** Overlaps, out-of-range children, unverified references: shown, never hidden. */
    val warnings: List<String>,
    /** Informational facts about how the map was drawn (e.g. a logical, non-containing level). */
    val notes: List<String> = emptyList(),
)

data class DiagramRegion(
    val id: String,
    val label: String,
    val offset: Long,
    val length: Long,
    val depth: Int,
    val role: RegionRole,
    val status: RegionStatus,
    val parentId: String?,
    /** Child-index path from the root to the underlying node; null for gaps and summaries. */
    val nodePath: List<Int>?,
    /** For a summary region: how many sibling nodes it stands for (0 otherwise). */
    val summarizedCount: Int = 0,
) {
    val end: Long get() = offset + length
}

data class DiagramOverlay(val id: String, val label: String, val offset: Long, val length: Long)

data class DiagramLink(val fromId: String, val toId: String, val meaning: LinkMeaning, val evidence: String)

enum class LinkMeaning { SEF_EMBEDDED_VIDEO, XMP_EMBEDDED_VIDEO, CONTAINER_EMBEDDED_VIDEO }

enum class RegionRole { MARKER, METADATA, CODEC_SETUP, MEDIA_DATA, CONTAINER, TRAILER, SUMMARY, UNPARSED, OTHER }

/** VERIFIED = a parsed node with a consistent range; UNPARSED = bytes no node accounts for. */
enum class RegionStatus { VERIFIED, WARNING, UNPARSED }
