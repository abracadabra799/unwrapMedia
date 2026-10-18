package com.multiviewer.ui.diagram.hierarchical

import com.multiviewer.parser.diagram.RegionRole
import com.multiviewer.parser.diagram.RegionStatus
import com.multiviewer.parser.diagram.hierarchical.HierarchicalDiagram

const val HIER_COL_WIDTH = 420
const val HIER_COL_GAP = 130
const val HIER_MARKER_WIDTH = 90
const val HIER_NAME_WIDTH = HIER_COL_WIDTH - HIER_MARKER_WIDTH // 330
const val HIER_HEADER_HEIGHT = 38
const val HIER_ROW_HEIGHT = 32

data class LayoutHierSection(
    val id: String,
    val x: Int,
    val y: Int,
    val w: Int,
    val h: Int,
    val marker: String,
    val name: String,
    val description: String?,
    val role: RegionRole,
    val status: RegionStatus,
    val isOptional: Boolean,
    val markerLines: List<String> = listOf(marker),
    val nameLines: List<String> = listOf(name),
    val descLines: List<String> = if (description != null) listOf(description) else emptyList(),
    val isSingleLine: Boolean = true,
)

data class LayoutHierColumn(
    val id: String,
    val title: String,
    val subtitle: String?,
    val x: Int,
    val y: Int,
    val w: Int,
    val headerH: Int,
    val rows: List<LayoutHierSection>,
    val bottomY: Int,
)

data class LayoutHierProjection(
    val fromSectionId: String,
    val toColumnId: String,
    val p1Top: Pair<Int, Int>,
    val p1Bottom: Pair<Int, Int>,
    val p2Top: Pair<Int, Int>,
    val p2Bottom: Pair<Int, Int>,
    val color: Long,
    val label: String?,
)

data class HierarchicalLayout(
    val width: Int,
    val height: Int,
    val title: String,
    val columns: List<LayoutHierColumn>,
    val projections: List<LayoutHierProjection>,
)

object HierarchicalLayoutEngine {

    private val plainFm: java.awt.FontMetrics by lazy {
        val img = java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        val fm = g.getFontMetrics(java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.PLAIN, 11))
        g.dispose()
        fm
    }

    private val boldFm: java.awt.FontMetrics by lazy {
        val img = java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        val fm = g.getFontMetrics(java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.BOLD, 11))
        g.dispose()
        fm
    }

    private fun wrapMarker(text: String, maxW: Int = 82): List<String> {
        if (boldFm.stringWidth(text) <= maxW) return listOf(text)
        val tokens = if (text.contains('_')) {
            val parts = text.split('_')
            parts.mapIndexed { idx, p -> if (idx < parts.lastIndex) p + "_" else p }
        } else {
            listOf(text)
        }
        val lines = mutableListOf<String>()
        var current = ""
        for (token in tokens) {
            val test = if (current.isEmpty()) token else current + token
            if (boldFm.stringWidth(test) <= maxW) {
                current = test
            } else {
                if (current.isNotEmpty()) lines += current
                if (boldFm.stringWidth(token) > maxW) {
                    var sub = ""
                    for (ch in token) {
                        if (boldFm.stringWidth(sub + ch) <= maxW) {
                            sub += ch
                        } else {
                            lines += sub
                            sub = ch.toString()
                        }
                    }
                    current = sub
                } else {
                    current = token
                }
            }
        }
        if (current.isNotEmpty()) lines += current
        return lines.ifEmpty { listOf(text) }
    }

    private fun wrapText(text: String, fm: java.awt.FontMetrics, maxW: Int = 308): List<String> {
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

    fun layout(diagram: HierarchicalDiagram): HierarchicalLayout {
        val topPadding = 64
        val leftMargin = 32
        val layoutColumns = mutableListOf<LayoutHierColumn>()
        val sectionMap = mutableMapOf<String, LayoutHierSection>()
        val columnMap = mutableMapOf<String, LayoutHierColumn>()

        var maxColBottomY = topPadding

        for ((colIdx, col) in diagram.columns.withIndex()) {
            val colX = leftMargin + colIdx * (HIER_COL_WIDTH + HIER_COL_GAP)
            val headerY = topPadding
            var currentY = headerY + HIER_HEADER_HEIGHT

            val rows = mutableListOf<LayoutHierSection>()
            for (sec in col.sections) {
                val markerLines = wrapMarker(sec.marker)
                val nameW = plainFm.stringWidth(sec.name)
                val descW = if (sec.description != null) plainFm.stringWidth(sec.description) else 0

                val isSingleLine = if (sec.description == null) {
                    nameW <= 308
                } else {
                    nameW + descW + 20 <= 308
                }

                val nameLines = wrapText(sec.name, plainFm, 308)
                val descLines = if (sec.description != null) wrapText(sec.description, plainFm, 308) else emptyList()

                val textLinesCount = if (isSingleLine) 1 else (nameLines.size + descLines.size)
                val totalLines = maxOf(markerLines.size, textLinesCount)
                val rowH = if (totalLines <= 1) HIER_ROW_HEIGHT else maxOf(32, 12 + totalLines * 16)

                val s = LayoutHierSection(
                    id = sec.id,
                    x = colX,
                    y = currentY,
                    w = HIER_COL_WIDTH,
                    h = rowH,
                    marker = sec.marker,
                    name = sec.name,
                    description = sec.description,
                    role = sec.role,
                    status = sec.status,
                    isOptional = sec.isOptional,
                    markerLines = markerLines,
                    nameLines = nameLines,
                    descLines = descLines,
                    isSingleLine = isSingleLine,
                )
                rows += s
                sectionMap[sec.id] = s
                currentY += rowH
            }

            val lCol = LayoutHierColumn(
                id = col.id,
                title = col.title,
                subtitle = col.subtitle,
                x = colX,
                y = headerY,
                w = HIER_COL_WIDTH,
                headerH = HIER_HEADER_HEIGHT,
                rows = rows,
                bottomY = currentY,
            )
            layoutColumns += lCol
            columnMap[col.id] = lCol
            maxColBottomY = maxOf(maxColBottomY, currentY)
        }

        // Layout Projections (trapezoids / projection lines)
        val layoutProjections = mutableListOf<LayoutHierProjection>()
        for (proj in diagram.projections) {
            val fromSec = sectionMap[proj.fromSectionId]
            val toCol = columnMap[proj.toColumnId]
            if (fromSec != null && toCol != null) {
                val p1Top = (fromSec.x + fromSec.w) to fromSec.y
                val p1Bottom = (fromSec.x + fromSec.w) to (fromSec.y + fromSec.h)
                val p2Top = toCol.x to toCol.y
                val p2Bottom = toCol.x to toCol.bottomY

                layoutProjections += LayoutHierProjection(
                    fromSectionId = proj.fromSectionId,
                    toColumnId = proj.toColumnId,
                    p1Top = p1Top,
                    p1Bottom = p1Bottom,
                    p2Top = p2Top,
                    p2Bottom = p2Bottom,
                    color = proj.color,
                    label = proj.label,
                )
            }
        }

        val totalWidth = if (layoutColumns.isNotEmpty()) {
            layoutColumns.last().x + layoutColumns.last().w + 64
        } else 1200
        val totalHeight = maxColBottomY + 60

        return HierarchicalLayout(
            width = totalWidth,
            height = totalHeight,
            title = diagram.title,
            columns = layoutColumns,
            projections = layoutProjections,
        )
    }
}
