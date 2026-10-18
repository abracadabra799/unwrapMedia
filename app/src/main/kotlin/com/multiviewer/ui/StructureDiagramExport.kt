package com.multiviewer.ui

import com.multiviewer.parser.diagram.RegionRole
import com.multiviewer.parser.diagram.RegionStatus
import com.multiviewer.parser.diagram.StructureDiagram
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage

// Light palette on purpose: exports are pasted into documents and slides, not the app's dark UI.
private fun roleFill(role: RegionRole): Int = when (role) {
    RegionRole.MARKER -> 0xE8EAED
    RegionRole.METADATA -> 0xDCEBFF
    RegionRole.CODEC_SETUP -> 0xE6E0FF
    RegionRole.MEDIA_DATA -> 0xDDF3E4
    RegionRole.CONTAINER -> 0xF3F4F6
    RegionRole.TRAILER -> 0xFFE9D6
    RegionRole.SUMMARY -> 0xF5F5F5
    RegionRole.UNPARSED -> 0xFFFFFF
    RegionRole.OTHER -> 0xF1F3F4
}

private fun statusStroke(status: RegionStatus): Int = when (status) {
    RegionStatus.PARSED -> 0x5F6368
    RegionStatus.WARNING -> 0xD97706
    RegionStatus.UNPARSED -> 0x9AA0A6
}

private const val TEXT = 0x202124
private const val MUTED = 0x5F6368
private const val ACCENT = 0x7C3AED

private fun css(rgb: Int) = "#" + rgb.toString(16).padStart(6, '0')

private fun xml(text: String): String = buildString {
    var i = 0
    while (i < text.length) {
        val c = text[i]
        when {
            c == '&' -> append("&amp;")
            c == '<' -> append("&lt;")
            c == '>' -> append("&gt;")
            c == '"' -> append("&quot;")
            c == '\'' -> append("&apos;")
            // A valid surrogate pair is one supplementary character: keep it.
            c.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate() -> { append(c); append(text[i + 1]); i++ }
            // Characters XML 1.0 cannot carry at all: controls, lone surrogates, U+FFFE/U+FFFF.
            (c < ' ' && c != '\t') || c.isSurrogate() || c == '\uFFFE' || c == '\uFFFF' -> append('\uFFFD')
            else -> append(c)
        }
        i++
    }
}

fun renderStructureSvg(layout: DiagramLayout): String = buildString {
    appendLine("""<svg xmlns="http://www.w3.org/2000/svg" width="${layout.width}" height="${layout.height}" viewBox="0 0 ${layout.width} ${layout.height}" font-family="sans-serif">""")
    appendLine("""<defs><marker id="arrow" markerWidth="8" markerHeight="8" refX="7" refY="4" orient="auto"><path d="M0,0 L8,4 L0,8 z" fill="${css(ACCENT)}"/></marker></defs>""")
    appendLine("""<rect width="100%" height="100%" fill="#ffffff"/>""")
    appendLine("""<text x="16" y="30" font-size="16" font-weight="bold" fill="${css(TEXT)}">${xml(layout.title)}</text>""")
    for (b in layout.boxes) {
        val dash = if (b.status == RegionStatus.UNPARSED) """ stroke-dasharray="5,3"""" else ""
        val width = if (b.status == RegionStatus.WARNING) 2 else 1
        appendLine("""<rect x="${b.x}" y="${b.y}" width="${b.w}" height="${b.h}" rx="3" fill="${css(roleFill(b.role))}" stroke="${css(statusStroke(b.status))}" stroke-width="$width"$dash/>""")
        if (!b.multiline) {
            val textY = b.y + b.h / 2 + 4
            appendLine("""<text x="${b.x + 8}" y="$textY" font-size="12" font-weight="bold" fill="${css(TEXT)}">${xml(b.title)}</text>""")
            appendLine("""<text x="${b.x + b.w - 8}" y="$textY" font-size="11" text-anchor="end" fill="${css(MUTED)}">${xml(b.detail)}</text>""")
        } else {
            var lineY = b.y + 16
            for (tLine in b.titleLines) {
                appendLine("""<text x="${b.x + 8}" y="$lineY" font-size="12" font-weight="bold" fill="${css(TEXT)}">${xml(tLine)}</text>""")
                lineY += 15
            }
            appendLine("""<text x="${b.x + 8}" y="$lineY" font-size="11" fill="${css(MUTED)}">${xml(b.detail)}</text>""")
        }
    }
    for (br in layout.brackets) {
        appendLine("""<path d="M${br.x - 6},${br.y1} H${br.x} V${br.y2} H${br.x - 6}" fill="none" stroke="${css(ACCENT)}" stroke-width="2"/>""")
        val mid = (br.y1 + br.y2) / 2
        appendLine("""<text x="${br.x + 8}" y="${mid - 2}" font-size="12" font-weight="bold" fill="${css(ACCENT)}">${xml(br.label)}</text>""")
        appendLine("""<text x="${br.x + 8}" y="${mid + 13}" font-size="11" fill="${css(MUTED)}">${xml(br.detail)}</text>""")
        br.sources.forEachIndexed { i, source ->
            appendLine("""<text x="${br.x + 8}" y="${mid + 28 + i * 14}" font-size="11" fill="${css(ACCENT)}">${xml(source)}</text>""")
        }
    }
    for (a in layout.arrows) {
        appendLine("""<line x1="${a.fromX}" y1="${a.fromY}" x2="${a.toX}" y2="${a.toY}" stroke="${css(ACCENT)}" stroke-width="1.5" marker-end="url(#arrow)"/>""")
    }
    layout.notes.forEachIndexed { i, note ->
        appendLine("""<text x="16" y="${layout.notesTop + 13 + i * 18}" font-size="11" fill="${css(MUTED)}">${xml(note)}</text>""")
    }
    appendLine("</svg>")
}

fun renderStructurePng(layout: DiagramLayout): BufferedImage {
    val image = BufferedImage(layout.width, layout.height, BufferedImage.TYPE_INT_RGB)
    val g = image.createGraphics()
    try {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.color = Color.WHITE
        g.fillRect(0, 0, layout.width, layout.height)
        val bold12 = Font(Font.SANS_SERIF, Font.BOLD, 12)
        val plain11 = Font(Font.SANS_SERIF, Font.PLAIN, 11)
        g.font = Font(Font.SANS_SERIF, Font.BOLD, 16)
        g.color = Color(TEXT)
        g.drawString(layout.title, 16, 30)
        val dashed = BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, floatArrayOf(5f, 3f), 0f)
        for (b in layout.boxes) {
            g.color = Color(roleFill(b.role))
            g.fillRoundRect(b.x, b.y, b.w, b.h, 6, 6)
            g.color = Color(statusStroke(b.status))
            g.stroke = when (b.status) {
                RegionStatus.UNPARSED -> dashed
                RegionStatus.WARNING -> BasicStroke(2f)
                RegionStatus.PARSED -> BasicStroke(1f)
            }
            g.drawRoundRect(b.x, b.y, b.w, b.h, 6, 6)

            if (!b.multiline) {
                val textY = b.y + b.h / 2 + 4
                g.font = bold12
                g.color = Color(TEXT)
                g.drawString(b.title, b.x + 8, textY)
                g.font = plain11
                g.color = Color(MUTED)
                g.drawString(b.detail, b.x + b.w - 8 - g.fontMetrics.stringWidth(b.detail), textY)
            } else {
                var lineY = b.y + 16
                g.font = bold12
                g.color = Color(TEXT)
                for (tLine in b.titleLines) {
                    g.drawString(tLine, b.x + 8, lineY)
                    lineY += 15
                }
                g.font = plain11
                g.color = Color(MUTED)
                g.drawString(b.detail, b.x + 8, lineY)
            }
        }
        g.stroke = BasicStroke(2f)
        for (br in layout.brackets) {
            g.color = Color(ACCENT)
            g.drawPolyline(intArrayOf(br.x - 6, br.x, br.x, br.x - 6), intArrayOf(br.y1, br.y1, br.y2, br.y2), 4)
            val mid = (br.y1 + br.y2) / 2
            g.font = bold12
            g.drawString(br.label, br.x + 8, mid - 2)
            g.font = plain11
            g.color = Color(MUTED)
            g.drawString(br.detail, br.x + 8, mid + 13)
            g.color = Color(ACCENT)
            br.sources.forEachIndexed { i, source -> g.drawString(source, br.x + 8, mid + 28 + i * 14) }
        }
        g.stroke = BasicStroke(1.5f)
        for (a in layout.arrows) {
            g.color = Color(ACCENT)
            g.drawLine(a.fromX, a.fromY, a.toX, a.toY)
            val angle = Math.atan2((a.toY - a.fromY).toDouble(), (a.toX - a.fromX).toDouble())
            val xs = intArrayOf(a.toX, (a.toX - 8 * Math.cos(angle - 0.45)).toInt(), (a.toX - 8 * Math.cos(angle + 0.45)).toInt())
            val ys = intArrayOf(a.toY, (a.toY - 8 * Math.sin(angle - 0.45)).toInt(), (a.toY - 8 * Math.sin(angle + 0.45)).toInt())
            g.fillPolygon(xs, ys, 3)
        }
        g.font = plain11
        g.color = Color(MUTED)
        layout.notes.forEachIndexed { i, note -> g.drawString(note, 16, layout.notesTop + 13 + i * 18) }
    } finally {
        g.dispose()
    }
    return image
}

private fun cell(text: String) =
    text.replace("\r\n", " ").replace('\n', ' ').replace('\r', ' ').replace("\\", "\\\\").replace("|", "\\|")

/** Text form of the same diagram for docs/wikis/chat: every region (all depths), overlays, links and warnings. */
fun structureDiagramMarkdownTable(diagram: StructureDiagram): String = buildString {
    appendLine("Ranges are [offset, offset + length) in bytes; file size ${diagram.fileSize} B.")
    appendLine()
    appendLine("| Component | Offset | Offset (hex) | Length (B) | Role | Status |")
    appendLine("|---|---:|---|---:|---|---|")
    for (r in regionsInTreeOrder(diagram)) {
        val indent = if (r.depth == 0) "" else "↳".repeat(r.depth) + " "
        appendLine("| $indent${cell(r.label)} | ${r.offset} | 0x${r.offset.toString(16).uppercase()} | ${r.length} | ${r.role.name} | ${r.status.name} |")
    }
    if (diagram.overlays.isNotEmpty()) {
        appendLine()
        appendLine("Overlays (same bytes, semantic view):")
        for (o in diagram.overlays) appendLine("- ${cell(o.label)}: ${o.offset}–${o.offset + o.length} (${o.length} B)")
    }
    if (diagram.links.isNotEmpty()) {
        appendLine()
        appendLine("Relationships (parser evidence):")
        val labels = (diagram.regions.map { it.id to it.label } + diagram.overlays.map { it.id to it.label }).toMap()
        for (l in diagram.links) {
            appendLine("- ${cell(labels[l.fromId] ?: l.fromId)} → ${cell(labels[l.toId] ?: l.toId)} [${l.meaning.name}]: ${cell(l.evidence)}")
        }
    }
    if (diagram.notes.isNotEmpty()) {
        appendLine()
        appendLine("Notes:")
        for (n in diagram.notes) appendLine("- ${cell(n)}")
    }
    if (diagram.warnings.isNotEmpty()) {
        appendLine()
        appendLine("Warnings:")
        for (w in diagram.warnings) appendLine("- ${cell(w)}")
    }
}
