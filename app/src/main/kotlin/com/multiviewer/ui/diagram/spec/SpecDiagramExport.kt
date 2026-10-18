package com.multiviewer.ui.diagram.spec

import com.multiviewer.parser.diagram.RegionRole
import com.multiviewer.parser.diagram.RegionStatus
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage

private fun bannerColor(role: RegionRole): Int = when (role) {
    RegionRole.MARKER -> 0x0D381E
    RegionRole.CODEC_SETUP -> 0x0B2545
    RegionRole.METADATA -> 0x351C75
    RegionRole.MEDIA_DATA -> 0x111827
    RegionRole.CONTAINER -> 0x1F2937
    RegionRole.TRAILER -> 0x7C2D12
    RegionRole.SUMMARY -> 0x374151
    RegionRole.UNPARSED -> 0x4B5563
    RegionRole.OTHER -> 0x374151
}

private fun cellFillColor(role: RegionRole, isPadding: Boolean): Int = when {
    isPadding -> 0xE9ECEF
    role == RegionRole.MARKER -> 0xD4EDDA
    role == RegionRole.CODEC_SETUP -> 0xD1ECF1
    role == RegionRole.METADATA -> 0xE2D9F3
    role == RegionRole.MEDIA_DATA -> 0xFFF3CD
    role == RegionRole.UNPARSED -> 0xF8F9FA
    else -> 0xF1F3F5
}

private fun cellBorderColor(role: RegionRole, status: RegionStatus, isPadding: Boolean): Int = when {
    status == RegionStatus.WARNING -> 0xD97706
    isPadding -> 0xADB5BD
    role == RegionRole.MARKER -> 0x28A745
    role == RegionRole.CODEC_SETUP -> 0x17A2B8
    role == RegionRole.METADATA -> 0x6F42C1
    role == RegionRole.MEDIA_DATA -> 0xD39E00
    else -> 0x6C757D
}

private fun css(rgb: Int) = "#" + (rgb and 0xFFFFFF).toString(16).padStart(6, '0')

private fun xml(text: String): String = buildString {
    for (c in text) {
        when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&apos;")
            else -> append(c)
        }
    }
}

object SpecDiagramExporter {

    private val bold11Fm: java.awt.FontMetrics by lazy {
        val img = java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        val fm = g.getFontMetrics(java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.BOLD, 11))
        g.dispose()
        fm
    }

    private val plain10Fm: java.awt.FontMetrics by lazy {
        val img = java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        val fm = g.getFontMetrics(java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.PLAIN, 10))
        g.dispose()
        fm
    }

    private fun fitLabel(text: String, fm: java.awt.FontMetrics, maxW: Int): String {
        if (fm.stringWidth(text) <= maxW) return text
        if (maxW <= 12) return ""
        var t = text
        while (t.isNotEmpty() && fm.stringWidth("$t…") > maxW) {
            t = t.dropLast(1)
        }
        return if (t.isNotEmpty()) "$t…" else ""
    }

    fun renderSvg(layout: SpecLayout): String = buildString {
        appendLine("""<svg xmlns="http://www.w3.org/2000/svg" width="${layout.width}" height="${layout.height}" viewBox="0 0 ${layout.width} ${layout.height}" font-family="system-ui, sans-serif">""")
        appendLine("""<defs>""")
        appendLine("""  <marker id="arrow" markerWidth="8" markerHeight="8" refX="7" refY="4" orient="auto"><path d="M0,0 L8,4 L0,8 z" fill="#111827"/></marker>""")
        appendLine("""  <marker id="arrow-green" markerWidth="8" markerHeight="8" refX="7" refY="4" orient="auto"><path d="M0,0 L8,4 L0,8 z" fill="#107C41"/></marker>""")
        appendLine("""</defs>""")
        appendLine("""<rect width="100%" height="100%" fill="#ffffff"/>""")

        // Title
        appendLine("""<text x="24" y="32" font-size="18" font-weight="bold" fill="#111827">${xml(layout.title)}</text>""")

        // Top Ruler
        appendLine("""<text x="${layout.gridLeft - 84}" y="${layout.rulerY + 14}" font-size="11" font-weight="bold" fill="#4B5563">Byte offset:</text>""")
        for (i in 0 until layout.colCount) {
            val cx = layout.gridLeft + i * (layout.gridWidth / layout.colCount) + (layout.gridWidth / layout.colCount) / 2
            appendLine("""<text x="$cx" y="${layout.rulerY + 14}" font-size="11" text-anchor="middle" font-weight="600" fill="#4B5563">$i</text>""")
        }
        appendLine("""<line x1="${layout.gridLeft}" y1="${layout.rulerY + 20}" x2="${layout.gridLeft + layout.gridWidth}" y2="${layout.rulerY + 20}" stroke="#D1D5DB" stroke-width="1"/>""")

        // Blocks
        for (b in layout.blocks) {
            // Header Banner
            appendLine("""<rect x="${b.headerX}" y="${b.headerY}" width="${b.headerW}" height="${b.headerH}" rx="3" fill="${css(bannerColor(b.role))}"/>""")
            appendLine("""<text x="${b.headerX + 12}" y="${b.headerY + 20}" font-size="13" font-weight="bold" fill="#FFFFFF">${xml(b.title)}</text>""")
            if (b.subtitle != null) {
                appendLine("""<text x="${b.headerX + b.headerW - 12}" y="${b.headerY + 20}" font-size="12" text-anchor="end" font-weight="bold" fill="#E5E7EB">${xml(b.subtitle)}</text>""")
            }

            // Cells
            for (row in b.rows) {
                for (c in row.cells) {
                    val fill = css(cellFillColor(c.role, c.isPadding))
                    val stroke = css(cellBorderColor(c.role, c.status, c.isPadding))
                    val dash = if (c.isPadding || c.status == RegionStatus.UNPARSED) """ stroke-dasharray="3,3"""" else ""
                    appendLine("""<rect x="${c.x}" y="${c.y}" width="${c.w}" height="${c.h}" fill="$fill" stroke="$stroke" stroke-width="1"$dash/>""")
                    val textY = c.y + 18
                    if (c.value != null && c.w >= 100) {
                        val nameW = bold11Fm.stringWidth(c.name)
                        val valW = plain10Fm.stringWidth(c.value)
                        if (nameW + valW + 16 <= c.w) {
                            appendLine("""<text x="${c.x + 6}" y="$textY" font-size="11" font-weight="600" fill="#1F2937">${xml(c.name)}</text>""")
                            appendLine("""<text x="${c.x + c.w - 6}" y="$textY" font-size="10" text-anchor="end" fill="#4B5563">${xml(c.value)}</text>""")
                        } else {
                            val label = "${c.name}: ${c.value}"
                            val sw11 = bold11Fm.stringWidth(label)
                            if (sw11 <= c.w - 8) {
                                appendLine("""<text x="${c.x + c.w / 2}" y="$textY" font-size="11" text-anchor="middle" font-weight="600" fill="#1F2937">${xml(label)}</text>""")
                            } else {
                                val fitted = fitLabel(label, plain10Fm, c.w - 6)
                                appendLine("""<text x="${c.x + c.w / 2}" y="$textY" font-size="10" text-anchor="middle" fill="#1F2937">${xml(fitted)}</text>""")
                            }
                        }
                    } else {
                        val label = if (c.value != null) "${c.name}: ${c.value}" else c.name
                        val sw11 = bold11Fm.stringWidth(label)
                        if (sw11 <= c.w - 6) {
                            appendLine("""<text x="${c.x + c.w / 2}" y="$textY" font-size="11" text-anchor="middle" font-weight="600" fill="#1F2937">${xml(label)}</text>""")
                        } else {
                            val fitted = fitLabel(label, plain10Fm, c.w - 6)
                            appendLine("""<text x="${c.x + c.w / 2}" y="$textY" font-size="10" text-anchor="middle" fill="#1F2937">${xml(fitted)}</text>""")
                        }
                    }
                }
            }
        }

        // Brackets
        for (br in layout.brackets) {
            val color = css((br.color and 0xFFFFFF).toInt())
            val tipX = if (br.onLeft) br.x + 6 else br.x - 6
            appendLine("""<path d="M$tipX,${br.y1} H${br.x} V${br.y2} H$tipX" fill="none" stroke="$color" stroke-width="2"/>""")
            val midY = (br.y1 + br.y2) / 2
            val textAnchor = if (br.onLeft) "end" else "start"
            val textX = if (br.onLeft) br.x - 8 else br.x + 8
            appendLine("""<text x="$textX" y="$midY" font-size="11" font-weight="bold" text-anchor="$textAnchor" fill="$color">${xml(br.label)}</text>""")
        }

        // Arrows
        for (a in layout.arrows) {
            val color = css((a.color and 0xFFFFFF).toInt())
            val d = buildString {
                a.points.forEachIndexed { i, p ->
                    if (i == 0) append("M${p.first},${p.second} ")
                    else append("L${p.first},${p.second} ")
                }
            }
            appendLine("""<path d="$d" fill="none" stroke="$color" stroke-width="1.8" marker-end="url(#arrow)"/>""")
        }

        // Callouts
        for (co in layout.callouts) {
            appendLine("""<line x1="${co.anchorX}" y1="${co.anchorY}" x2="${co.boxX}" y2="${co.boxY + co.boxH / 2}" stroke="#DC2626" stroke-width="1" stroke-dasharray="4,2"/>""")
            appendLine("""<rect x="${co.boxX}" y="${co.boxY}" width="${co.boxW}" height="${co.boxH}" rx="4" fill="#FEF2F2" stroke="#DC2626" stroke-width="1" stroke-dasharray="4,2"/>""")
            appendLine("""<text x="${co.boxX + co.boxW / 2}" y="${co.boxY + 22}" font-size="10.5" font-weight="600" text-anchor="middle" fill="#991B1B">${xml(co.text)}</text>""")
        }

        appendLine("</svg>")
    }

    fun renderPng(layout: SpecLayout, maxPixels: Long = com.multiviewer.ui.MAX_DIAGRAM_PIXELS): BufferedImage {
        val scale = com.multiviewer.ui.cappedDiagramScale(layout.width, layout.height, 1.0f, maxPixels)
        val image = BufferedImage(
            (layout.width * scale).toInt().coerceAtLeast(1),
            (layout.height * scale).toInt().coerceAtLeast(1),
            BufferedImage.TYPE_INT_RGB,
        )
        val g = image.createGraphics()
        try {
            if (scale < 1.0f) g.scale(scale.toDouble(), scale.toDouble())
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g.color = Color.WHITE
            g.fillRect(0, 0, layout.width, layout.height)

            // Title
            g.font = Font(Font.SANS_SERIF, Font.BOLD, 18)
            g.color = Color(0x111827)
            g.drawString(layout.title, 24, 34)

            // Top Ruler
            g.font = Font(Font.SANS_SERIF, Font.BOLD, 11)
            g.color = Color(0x4B5563)
            g.drawString("Byte offset:", layout.gridLeft - 84, layout.rulerY + 14)
            val colW = layout.gridWidth / layout.colCount
            for (i in 0 until layout.colCount) {
                val cx = layout.gridLeft + i * colW + colW / 2
                val str = i.toString()
                val sw = g.fontMetrics.stringWidth(str)
                g.drawString(str, cx - sw / 2, layout.rulerY + 14)
            }
            g.color = Color(0xD1D5DB)
            g.drawLine(layout.gridLeft, layout.rulerY + 20, layout.gridLeft + layout.gridWidth, layout.rulerY + 20)

            val bold12 = Font(Font.SANS_SERIF, Font.BOLD, 12)
            val bold11 = Font(Font.SANS_SERIF, Font.BOLD, 11)
            val plain10 = Font(Font.SANS_SERIF, Font.PLAIN, 10)
            val dashed = BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, floatArrayOf(3f, 3f), 0f)

            // Blocks
            for (b in layout.blocks) {
                // Header Banner
                g.color = Color(bannerColor(b.role))
                g.fillRoundRect(b.headerX, b.headerY, b.headerW, b.headerH, 6, 6)
                g.color = Color.WHITE
                g.font = Font(Font.SANS_SERIF, Font.BOLD, 13)
                g.drawString(b.title, b.headerX + 12, b.headerY + 21)
                if (b.subtitle != null) {
                    g.font = bold12
                    g.color = Color(0xE5E7EB)
                    val sw = g.fontMetrics.stringWidth(b.subtitle)
                    g.drawString(b.subtitle, b.headerX + b.headerW - 12 - sw, b.headerY + 21)
                }

                // Cells
                for (row in b.rows) {
                    for (c in row.cells) {
                        g.color = Color(cellFillColor(c.role, c.isPadding))
                        g.fillRect(c.x, c.y, c.w, c.h)

                        g.color = Color(cellBorderColor(c.role, c.status, c.isPadding))
                        g.stroke = if (c.isPadding || c.status == RegionStatus.UNPARSED) dashed else BasicStroke(1f)
                        g.drawRect(c.x, c.y, c.w, c.h)

                        g.color = Color(0x1F2937)
                        g.font = bold11
                        val textY = c.y + 18
                        if (c.value != null && c.w >= 100) {
                            val nameW = bold11Fm.stringWidth(c.name)
                            val valW = plain10Fm.stringWidth(c.value)
                            if (nameW + valW + 16 <= c.w) {
                                g.font = bold11
                                g.color = Color(0x1F2937)
                                g.drawString(c.name, c.x + 6, textY)
                                g.font = plain10
                                g.color = Color(0x4B5563)
                                g.drawString(c.value, c.x + c.w - 6 - valW, textY)
                            } else {
                                val label = "${c.name}: ${c.value}"
                                val sw11 = bold11Fm.stringWidth(label)
                                if (sw11 <= c.w - 8) {
                                    g.font = bold11
                                    g.color = Color(0x1F2937)
                                    g.drawString(label, c.x + (c.w - sw11) / 2, textY)
                                } else {
                                    val fitted = fitLabel(label, plain10Fm, c.w - 6)
                                    g.font = plain10
                                    g.color = Color(0x1F2937)
                                    val fsw = plain10Fm.stringWidth(fitted)
                                    g.drawString(fitted, c.x + (c.w - fsw) / 2, textY)
                                }
                            }
                        } else {
                            val label = if (c.value != null) "${c.name}: ${c.value}" else c.name
                            val sw11 = bold11Fm.stringWidth(label)
                            if (sw11 <= c.w - 6) {
                                g.font = bold11
                                g.color = Color(0x1F2937)
                                g.drawString(label, c.x + (c.w - sw11) / 2, textY)
                            } else {
                                val fitted = fitLabel(label, plain10Fm, c.w - 6)
                                g.font = plain10
                                g.color = Color(0x1F2937)
                                val fsw = plain10Fm.stringWidth(fitted)
                                g.drawString(fitted, c.x + (c.w - fsw) / 2, textY)
                            }
                        }
                    }
                }
            }

            // Brackets
            g.stroke = BasicStroke(2f)
            for (br in layout.brackets) {
                g.color = Color((br.color and 0xFFFFFF).toInt())
                val tipX = if (br.onLeft) br.x + 6 else br.x - 6
                g.drawPolyline(intArrayOf(tipX, br.x, br.x, tipX), intArrayOf(br.y1, br.y1, br.y2, br.y2), 4)
                val midY = (br.y1 + br.y2) / 2
                g.font = bold11
                if (br.onLeft) {
                    val sw = g.fontMetrics.stringWidth(br.label)
                    g.drawString(br.label, br.x - 8 - sw, midY + 4)
                } else {
                    g.drawString(br.label, br.x + 8, midY + 4)
                }
            }

            // Arrows
            for (a in layout.arrows) {
                g.color = Color((a.color and 0xFFFFFF).toInt())
                g.stroke = BasicStroke(1.8f)
                val xs = a.points.map { it.first }.toIntArray()
                val ys = a.points.map { it.second }.toIntArray()
                g.drawPolyline(xs, ys, a.points.size)

                // Arrow head at last point
                if (a.points.size >= 2) {
                    val pEnd = a.points.last()
                    val pPrev = a.points[a.points.size - 2]
                    val angle = Math.atan2((pEnd.second - pPrev.second).toDouble(), (pEnd.first - pPrev.first).toDouble())
                    val arrowLen = 8.0
                    val x1 = (pEnd.first - arrowLen * Math.cos(angle - 0.5)).toInt()
                    val y1 = (pEnd.second - arrowLen * Math.sin(angle - 0.5)).toInt()
                    val x2 = (pEnd.first - arrowLen * Math.cos(angle + 0.5)).toInt()
                    val y2 = (pEnd.second - arrowLen * Math.sin(angle + 0.5)).toInt()
                    g.fillPolygon(intArrayOf(pEnd.first, x1, x2), intArrayOf(pEnd.second, y1, y2), 3)
                }
            }

            // Callouts
            for (co in layout.callouts) {
                g.color = Color(0xDC2626)
                g.stroke = dashed
                g.drawLine(co.anchorX, co.anchorY, co.boxX, co.boxY + co.boxH / 2)

                g.color = Color(0xFEF2F2)
                g.fillRoundRect(co.boxX, co.boxY, co.boxW, co.boxH, 6, 6)
                g.color = Color(0xDC2626)
                g.stroke = dashed
                g.drawRoundRect(co.boxX, co.boxY, co.boxW, co.boxH, 6, 6)

                g.font = bold11
                g.color = Color(0x991B1B)
                val sw = g.fontMetrics.stringWidth(co.text)
                g.drawString(co.text, co.boxX + (co.boxW - sw) / 2, co.boxY + 22)
            }
        } finally {
            g.dispose()
        }
        return image
    }
}
