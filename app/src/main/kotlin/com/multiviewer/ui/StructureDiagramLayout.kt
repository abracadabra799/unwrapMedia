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
    val multiline: Boolean = false,
    val titleLines: List<String> = listOf(title),
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

private val bold12Fm: java.awt.FontMetrics by lazy {
    val img = java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_RGB)
    val g = img.createGraphics()
    val fm = g.getFontMetrics(java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.BOLD, 12))
    g.dispose()
    fm
}

private val plain11Fm: java.awt.FontMetrics by lazy {
    val img = java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_RGB)
    val g = img.createGraphics()
    val fm = g.getFontMetrics(java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.PLAIN, 11))
    g.dispose()
    fm
}

private fun wrapStructureText(text: String, fm: java.awt.FontMetrics, maxW: Int): List<String> {
    if (fm.stringWidth(text) <= maxW) return listOf(text)
    val words = text.split(" ")
    val lines = mutableListOf<String>()
    var current = ""
    for (word in words) {
        val test = if (current.isEmpty()) word else "$current $word"
        if (fm.stringWidth(test) <= maxW) {
            current = test
        } else {
            if (current.isNotEmpty()) lines += current
            if (fm.stringWidth(word) > maxW) {
                var sub = ""
                for (ch in word) {
                    if (fm.stringWidth(sub + ch) <= maxW) {
                        sub += ch
                    } else {
                        lines += sub
                        sub = ch.toString()
                    }
                }
                current = sub
            } else {
                current = word
            }
        }
    }
    if (current.isNotEmpty()) lines += current
    return lines.ifEmpty { listOf(text) }
}

/** [depth] 0 = overview (top level only); higher values add nested levels. Not to scale on purpose. */
fun layoutStructureDiagram(diagram: StructureDiagram, depth: Int, title: String): DiagramLayout {
    val visible = regionsInTreeOrder(diagram).filter { it.depth <= depth }
    val drawn = visible.take(MAX_LAYOUT_ROWS)
    var curY = HEADER
    val boxes = mutableListOf<LayoutBox>()
    for (r in drawn) {
        val boxX = MARGIN + r.depth * INDENT
        val boxW = MAP_W - r.depth * INDENT
        val detailText = rangeText(r.offset, r.length)

        val titleW = bold12Fm.stringWidth(r.label)
        val detailW = plain11Fm.stringWidth(detailText)
        val availW = boxW - 16

        val isSingleLine = (titleW + detailW + 24 <= availW) && (titleW <= availW)

        if (isSingleLine) {
            boxes += LayoutBox(
                r.id, r.depth,
                x = boxX, y = curY, w = boxW, h = ROW_H,
                title = r.label, detail = detailText, role = r.role, status = r.status,
                multiline = false, titleLines = listOf(r.label),
            )
            curY += ROW_H + ROW_GAP
        } else {
            val tLines = wrapStructureText(r.label, bold12Fm, availW)
            val boxH = maxOf(42, 12 + (tLines.size + 1) * 16)
            boxes += LayoutBox(
                r.id, r.depth,
                x = boxX, y = curY, w = boxW, h = boxH,
                title = r.label, detail = detailText, role = r.role, status = r.status,
                multiline = true, titleLines = tLines,
            )
            curY += boxH + ROW_GAP
        }
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
    val rowsBottom = curY
    val notesTop = rowsBottom + 12
    return DiagramLayout(
        width = MARGIN + MAP_W + BRACKET_GAP + SIDE_W + MARGIN,
        height = notesTop + clipped.size * NOTE_H + MARGIN,
        title = title, boxes = boxes, brackets = brackets, arrows = arrows, notes = clipped, notesTop = notesTop,
    )
}
