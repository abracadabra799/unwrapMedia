package com.multiviewer.ui.diagram.hierarchical

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage

object HierarchicalDiagramExporter {

    private fun css(color: Long): String = "#" + (color and 0xFFFFFF).toString(16).padStart(6, '0')

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

    fun renderSvg(layout: HierarchicalLayout): String = buildString {
        appendLine("""<svg xmlns="http://www.w3.org/2000/svg" width="${layout.width}" height="${layout.height}" viewBox="0 0 ${layout.width} ${layout.height}" font-family="system-ui, -apple-system, sans-serif">""")
        appendLine("""<rect width="100%" height="100%" fill="#FFFFFF"/>""")

        // Title
        appendLine("""<text x="32" y="38" font-size="18" font-weight="bold" fill="#0F172A">${xml(layout.title)} — File Structure Architecture</text>""")

        // Projections (fig2d.jpg trapezoids / projection lines)
        for (proj in layout.projections) {
            val strokeColor = css(proj.color)
            val polyPoints = "${proj.p1Top.first},${proj.p1Top.second} ${proj.p2Top.first},${proj.p2Top.second} ${proj.p2Bottom.first},${proj.p2Bottom.second} ${proj.p1Bottom.first},${proj.p1Bottom.second}"
            // Shaded fill between projection lines
            appendLine("""<polygon points="$polyPoints" fill="rgba(59, 130, 246, 0.05)"/>""")
            // Top projection line
            appendLine("""<line x1="${proj.p1Top.first}" y1="${proj.p1Top.second}" x2="${proj.p2Top.first}" y2="${proj.p2Top.second}" stroke="$strokeColor" stroke-width="2.5" stroke-linecap="round"/>""")
            // Bottom projection line
            appendLine("""<line x1="${proj.p1Bottom.first}" y1="${proj.p1Bottom.second}" x2="${proj.p2Bottom.first}" y2="${proj.p2Bottom.second}" stroke="$strokeColor" stroke-width="2.5" stroke-linecap="round"/>""")
        }

        // Columns
        for (col in layout.columns) {
            // Header banner
            appendLine("""<rect x="${col.x}" y="${col.y}" width="${col.w}" height="${col.headerH}" rx="3" fill="#1E293B"/>""")
            appendLine("""<text x="${col.x + 12}" y="${col.y + 24}" font-size="13" font-weight="bold" fill="#FFFFFF">${xml(col.title)}</text>""")
            if (col.subtitle != null) {
                appendLine("""<text x="${col.x + col.w - 12}" y="${col.y + 24}" font-size="11" text-anchor="end" fill="#94A3B8">${xml(col.subtitle)}</text>""")
            }

            // Table rows
            for (sec in col.rows) {
                val markerW = HIER_MARKER_WIDTH
                val nameW = sec.w - markerW

                // Left cell: Marker
                appendLine("""<rect x="${sec.x}" y="${sec.y}" width="$markerW" height="${sec.h}" fill="#F8FAFC" stroke="#CBD5E1" stroke-width="1"/>""")
                val markerLineH = 14
                val markerStartY = sec.y + (sec.h - sec.markerLines.size * markerLineH) / 2 + 11
                for ((mIdx, mLine) in sec.markerLines.withIndex()) {
                    appendLine("""<text x="${sec.x + markerW / 2}" y="${markerStartY + mIdx * markerLineH}" font-size="11" font-weight="bold" text-anchor="middle" fill="#0F172A">${xml(mLine)}</text>""")
                }

                // Right cell: Name & Description
                appendLine("""<rect x="${sec.x + markerW}" y="${sec.y}" width="$nameW" height="${sec.h}" fill="#FFFFFF" stroke="#CBD5E1" stroke-width="1"/>""")
                if (sec.isSingleLine) {
                    val textY = sec.y + sec.h / 2 + 4
                    appendLine("""<text x="${sec.x + markerW + 10}" y="$textY" font-size="11" fill="#1E293B">${xml(sec.name)}</text>""")
                    if (sec.description != null) {
                        appendLine("""<text x="${sec.x + sec.w - 10}" y="$textY" font-size="10.5" text-anchor="end" fill="#64748B">${xml(sec.description)}</text>""")
                    }
                } else {
                    var curY = sec.y + 16
                    for (nLine in sec.nameLines) {
                        appendLine("""<text x="${sec.x + markerW + 10}" y="$curY" font-size="11" font-weight="bold" fill="#1E293B">${xml(nLine)}</text>""")
                        curY += 15
                    }
                    for (dLine in sec.descLines) {
                        appendLine("""<text x="${sec.x + markerW + 10}" y="$curY" font-size="10.5" fill="#64748B">${xml(dLine)}</text>""")
                        curY += 15
                    }
                }
            }
        }

        appendLine("</svg>")
    }

    fun renderPng(layout: HierarchicalLayout, scale: Float = 2.0f): BufferedImage {
        val scaledWidth = (layout.width * scale).toInt()
        val scaledHeight = (layout.height * scale).toInt()
        val image = BufferedImage(scaledWidth, scaledHeight, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()

        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)

            g.scale(scale.toDouble(), scale.toDouble())

            // Canvas background
            g.color = Color.WHITE
            g.fillRect(0, 0, layout.width, layout.height)

            // Title
            g.font = Font(Font.SANS_SERIF, Font.BOLD, 18)
            g.color = Color(0x0F, 0x17, 0x2A)
            g.drawString("${layout.title} — File Structure Architecture", 32, 38)

            // Projections (fig2d.jpg lines & subtle fill)
            for (proj in layout.projections) {
                val color = Color((proj.color and 0xFFFFFF).toInt())
                val polyX = intArrayOf(proj.p1Top.first, proj.p2Top.first, proj.p2Bottom.first, proj.p1Bottom.first)
                val polyY = intArrayOf(proj.p1Top.second, proj.p2Top.second, proj.p2Bottom.second, proj.p1Bottom.second)

                // Fill
                g.color = Color(59, 130, 246, 12)
                g.fillPolygon(polyX, polyY, 4)

                // Lines
                g.color = color
                g.stroke = BasicStroke(2.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                g.drawLine(proj.p1Top.first, proj.p1Top.second, proj.p2Top.first, proj.p2Top.second)
                g.drawLine(proj.p1Bottom.first, proj.p1Bottom.second, proj.p2Bottom.first, proj.p2Bottom.second)
            }

            // Columns
            val fontBold13 = Font(Font.SANS_SERIF, Font.BOLD, 13)
            val fontBold11 = Font(Font.SANS_SERIF, Font.BOLD, 11)
            val fontPlain11 = Font(Font.SANS_SERIF, Font.PLAIN, 11)
            val fontMuted10 = Font(Font.SANS_SERIF, Font.PLAIN, 10)

            val colorBorder = Color(0xCB, 0xD5, 0xE1)
            val colorMarkerBg = Color(0xF8, 0xFA, 0xFC)
            val colorBannerBg = Color(0x1E, 0x29, 0x3B)
            val colorTextDark = Color(0x0F, 0x17, 0x2A)
            val colorTextSecondary = Color(0x64, 0x74, 0x8B)

            for (col in layout.columns) {
                // Header banner
                g.color = colorBannerBg
                g.fillRoundRect(col.x, col.y, col.w, col.headerH, 6, 6)

                g.font = fontBold13
                g.color = Color.WHITE
                g.drawString(col.title, col.x + 12, col.y + 24)

                if (col.subtitle != null) {
                    g.font = fontBold11
                    g.color = Color(0x94, 0xA3, 0xB8)
                    val sw = g.fontMetrics.stringWidth(col.subtitle)
                    g.drawString(col.subtitle, col.x + col.w - 12 - sw, col.y + 24)
                }

                // Table rows
                g.stroke = BasicStroke(1f)
                for (sec in col.rows) {
                    val markerW = HIER_MARKER_WIDTH
                    val nameW = sec.w - markerW

                    // Marker box
                    g.color = colorMarkerBg
                    g.fillRect(sec.x, sec.y, markerW, sec.h)
                    g.color = colorBorder
                    g.drawRect(sec.x, sec.y, markerW, sec.h)

                    g.font = fontBold11
                    g.color = colorTextDark
                    val markerLineH = 14
                    val markerStartY = sec.y + (sec.h - sec.markerLines.size * markerLineH) / 2 + 11
                    for ((mIdx, mLine) in sec.markerLines.withIndex()) {
                        val msw = g.fontMetrics.stringWidth(mLine)
                        g.drawString(mLine, sec.x + (markerW - msw) / 2, markerStartY + mIdx * markerLineH)
                    }

                    // Name box
                    g.color = Color.WHITE
                    g.fillRect(sec.x + markerW, sec.y, nameW, sec.h)
                    g.color = colorBorder
                    g.drawRect(sec.x + markerW, sec.y, nameW, sec.h)

                    if (sec.isSingleLine) {
                        val textY = sec.y + sec.h / 2 + 4
                        g.font = fontPlain11
                        g.color = colorTextDark
                        g.drawString(sec.name, sec.x + markerW + 10, textY)

                        if (sec.description != null) {
                            g.font = fontMuted10
                            g.color = colorTextSecondary
                            val dsw = g.fontMetrics.stringWidth(sec.description)
                            g.drawString(sec.description, sec.x + sec.w - 10 - dsw, textY)
                        }
                    } else {
                        var curY = sec.y + 16
                        g.font = fontBold11
                        g.color = colorTextDark
                        for (nLine in sec.nameLines) {
                            g.drawString(nLine, sec.x + markerW + 10, curY)
                            curY += 15
                        }
                        g.font = fontMuted10
                        g.color = colorTextSecondary
                        for (dLine in sec.descLines) {
                            g.drawString(dLine, sec.x + markerW + 10, curY)
                            curY += 15
                        }
                    }
                }
            }
        } finally {
            g.dispose()
        }

        return image
    }
}
