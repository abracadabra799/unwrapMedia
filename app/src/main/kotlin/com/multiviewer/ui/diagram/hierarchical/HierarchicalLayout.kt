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
                val s = LayoutHierSection(
                    id = sec.id,
                    x = colX,
                    y = currentY,
                    w = HIER_COL_WIDTH,
                    h = HIER_ROW_HEIGHT,
                    marker = sec.marker,
                    name = sec.name,
                    description = sec.description,
                    role = sec.role,
                    status = sec.status,
                    isOptional = sec.isOptional,
                )
                rows += s
                sectionMap[sec.id] = s
                currentY += HIER_ROW_HEIGHT
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
