package com.multiviewer.ui

import com.multiviewer.parser.diagram.DiagramRegion
import com.multiviewer.parser.diagram.LinkMeaning
import com.multiviewer.parser.diagram.RegionRole
import com.multiviewer.parser.diagram.RegionStatus
import com.multiviewer.parser.diagram.StructureDiagram
import java.util.Locale

/** Rows beyond this are listed in the table only, so a pasted image stays a sensible size. */
const val MAX_LAYOUT_ROWS = 400

/** Footer lines beyond this are left to the table, so a crafted file cannot grow the image without bound. */
const val MAX_LAYOUT_NOTES = 30

private const val MARGIN = 16
private const val HEADER = 52
private const val ROW_H = 30
private const val ROW_GAP = 4
private const val INDENT = 22
private const val MAP_W = 620
private const val BRACKET_GAP = 56
private const val SIDE_W = 300
private const val NOTE_H = 18
private const val MAX_NOTE_CHARS = 150

data class LayoutBox(
    val regionId: String,
    val depth: Int,
    val x: Int, val y: Int, val w: Int, val h: Int,
    val title: String,
    val detail: String,
    val role: RegionRole,
    val status: RegionStatus,
)

/** A semantic overlay (e.g. embedded video bytes) drawn as a bracket beside the rows it spans. */
data class LayoutBracket(
    val x: Int, val y1: Int, val y2: Int, val label: String, val detail: String,
    /** One line per evidence link, e.g. "← sefd: SEF trailer references". */
    val sources: List<String> = emptyList(),
)

/** Unlabeled on purpose: the bracket lists the link meaning, so text never collides with row ranges. */
data class LayoutArrow(val fromX: Int, val fromY: Int, val toX: Int, val toY: Int)

/** Positions and text computed once; the SVG and PNG renderers only draw them. */
data class DiagramLayout(
    val width: Int,
    val height: Int,
    val title: String,
    val boxes: List<LayoutBox>,
    val brackets: List<LayoutBracket>,
    val arrows: List<LayoutArrow>,
    val notes: List<String>,
    val notesTop: Int,
)

internal fun groupedBytes(v: Long): String = String.format(Locale.US, "%,d", v)

internal fun rangeText(offset: Long, length: Long) = "${groupedBytes(offset)} – ${groupedBytes(offset + length)} (${groupedBytes(length)} B)"

internal fun linkLabel(meaning: LinkMeaning) = when (meaning) {
    LinkMeaning.SEF_EMBEDDED_VIDEO -> "SEF trailer references"
    LinkMeaning.XMP_EMBEDDED_VIDEO -> "XMP declares"
    LinkMeaning.CONTAINER_EMBEDDED_VIDEO -> "box holds"
}

/** Regions in depth-first file order (a parent, then its children), as both the image and the table list them. */
internal fun regionsInTreeOrder(diagram: StructureDiagram): List<DiagramRegion> {
    val byParent = diagram.regions.groupBy { it.parentId }
    val out = mutableListOf<DiagramRegion>()
    fun visit(parentId: String?) {
        for (region in byParent[parentId].orEmpty()) {
            out += region
            if (region.nodePath != null) visit(region.id)
        }
    }
    visit(null)
    return out
}

/** [depth] 0 = overview (top level only); higher values add nested levels. Not to scale on purpose. */
fun layoutStructureDiagram(diagram: StructureDiagram, depth: Int, title: String): DiagramLayout {
    val visible = regionsInTreeOrder(diagram).filter { it.depth <= depth }
    val drawn = visible.take(MAX_LAYOUT_ROWS)
    val boxes = drawn.mapIndexed { row, r ->
        LayoutBox(
            r.id, r.depth,
            x = MARGIN + r.depth * INDENT, y = HEADER + row * (ROW_H + ROW_GAP),
            w = MAP_W - r.depth * INDENT, h = ROW_H,
            title = r.label, detail = rangeText(r.offset, r.length), role = r.role, status = r.status,
        )
    }
    val byId = boxes.associateBy { it.regionId }
    val regionById = diagram.regions.associateBy { it.id }
    val bracketX = MARGIN + MAP_W + BRACKET_GAP
    val brackets = mutableListOf<LayoutBracket>()
    val arrows = mutableListOf<LayoutArrow>()
    val notes = mutableListOf("Not to scale. Ranges are [start, end) in bytes; file size ${groupedBytes(diagram.fileSize)} B.")
    for (overlay in diagram.overlays) {
        val end = overlay.offset + overlay.length
        val spanned = boxes.filter { b ->
            val r = regionById.getValue(b.regionId)
            r.offset < end && r.end > overlay.offset
        }
        if (spanned.isEmpty()) continue
        val links = diagram.links.filter { it.toId == overlay.id }
        val bracket = LayoutBracket(
            bracketX, spanned.minOf { it.y }, spanned.maxOf { it.y + it.h }, overlay.label, rangeText(overlay.offset, overlay.length),
            sources = links.map { "← ${regionById[it.fromId]?.label ?: it.fromId}: ${linkLabel(it.meaning)}" },
        )
        brackets += bracket
        for (link in links) {
            val fromLabel = regionById[link.fromId]?.label ?: link.fromId
            notes += "$fromLabel → ${overlay.label} (${link.meaning.name}): ${link.evidence}"
            val from = byId[link.fromId] ?: continue
            arrows += LayoutArrow(from.x + from.w, from.y + from.h / 2, bracket.x - 6, (bracket.y1 + bracket.y2) / 2)
        }
    }
    if (visible.size > drawn.size) notes += "… ${visible.size - drawn.size} more regions not drawn (see the table)."
    notes += diagram.notes
    notes += diagram.warnings.map { "Warning: $it" }
    val capped = if (notes.size <= MAX_LAYOUT_NOTES) notes
        else notes.take(MAX_LAYOUT_NOTES) + "… ${groupedBytes((notes.size - MAX_LAYOUT_NOTES).toLong())} more warnings/notes (see the table)."
    val clipped = capped.map { if (it.length <= MAX_NOTE_CHARS) it else it.take(MAX_NOTE_CHARS - 1) + "…" }
    val rowsBottom = HEADER + drawn.size * (ROW_H + ROW_GAP)
    val notesTop = rowsBottom + 12
    return DiagramLayout(
        width = MARGIN + MAP_W + BRACKET_GAP + SIDE_W + MARGIN,
        height = notesTop + clipped.size * NOTE_H + MARGIN,
        title = title, boxes = boxes, brackets = brackets, arrows = arrows, notes = clipped, notesTop = notesTop,
    )
}
