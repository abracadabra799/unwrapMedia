package com.multiviewer.ui.diagram.spec

import com.multiviewer.parser.diagram.RegionRole
import com.multiviewer.parser.diagram.RegionStatus
import com.multiviewer.parser.diagram.spec.SpecDiagram

const val SPEC_GRID_LEFT = 120
const val SPEC_GRID_WIDTH = 480
const val SPEC_COL_COUNT = 12
const val SPEC_COL_WIDTH = SPEC_GRID_WIDTH / SPEC_COL_COUNT // 40px per byte
const val SPEC_ROW_HEIGHT = 28
const val SPEC_HEADER_HEIGHT = 32
const val SPEC_RIGHT_MARGIN = 320
const val SPEC_TOTAL_WIDTH = SPEC_GRID_LEFT + SPEC_GRID_WIDTH + SPEC_RIGHT_MARGIN // 920px

data class LayoutSpecCell(
    val id: String,
    val x: Int,
    val y: Int,
    val w: Int,
    val h: Int,
    val name: String,
    val value: String?,
    val role: RegionRole,
    val status: RegionStatus,
    val isPadding: Boolean,
)

data class LayoutSpecRow(
    val cells: List<LayoutSpecCell>,
    val y: Int,
)

data class LayoutSpecBlock(
    val id: String,
    val title: String,
    val subtitle: String?,
    val headerX: Int,
    val headerY: Int,
    val headerW: Int,
    val headerH: Int,
    val rows: List<LayoutSpecRow>,
    val role: RegionRole,
)

data class LayoutSpecArrow(
    val points: List<Pair<Int, Int>>, // Polyline path
    val color: Long,
    val label: String?,
)

data class LayoutSpecBracket(
    val x: Int,
    val y1: Int,
    val y2: Int,
    val label: String,
    val color: Long,
    val onLeft: Boolean = false,
)

data class LayoutSpecCallout(
    val boxX: Int,
    val boxY: Int,
    val boxW: Int,
    val boxH: Int,
    val anchorX: Int,
    val anchorY: Int,
    val text: String,
)

data class SpecLayout(
    val width: Int,
    val height: Int,
    val title: String,
    val rulerY: Int,
    val gridLeft: Int,
    val gridWidth: Int,
    val colCount: Int,
    val blocks: List<LayoutSpecBlock>,
    val arrows: List<LayoutSpecArrow>,
    val brackets: List<LayoutSpecBracket>,
    val callouts: List<LayoutSpecCallout>,
)

object SpecDiagramLayoutEngine {

    fun layout(diagram: SpecDiagram): SpecLayout {
        var currentY = 56 // Top padding + title
        val rulerY = currentY
        currentY += 26 // Space for top ruler

        val layoutBlocks = mutableListOf<LayoutSpecBlock>()
        val cellMap = mutableMapOf<String, LayoutSpecCell>()
        val blockHeaderMap = mutableMapOf<String, LayoutSpecBlock>()

        for (block in diagram.blocks) {
            val hY = currentY
            currentY += SPEC_HEADER_HEIGHT

            val rows = mutableListOf<LayoutSpecRow>()
            for (r in block.rows) {
                var cellX = SPEC_GRID_LEFT
                val cells = mutableListOf<LayoutSpecCell>()
                for (c in r.cells) {
                    val cellW = c.colSpan * SPEC_COL_WIDTH
                    val cell = LayoutSpecCell(
                        id = c.id,
                        x = cellX,
                        y = currentY,
                        w = cellW,
                        h = SPEC_ROW_HEIGHT,
                        name = c.name,
                        value = c.value,
                        role = c.role,
                        status = c.status,
                        isPadding = c.isPadding,
                    )
                    cells += cell
                    cellMap[c.id] = cell
                    cellX += cellW
                }
                rows += LayoutSpecRow(cells, currentY)
                currentY += SPEC_ROW_HEIGHT
            }

            val lBlock = LayoutSpecBlock(
                id = block.id,
                title = block.title,
                subtitle = block.subtitle,
                headerX = SPEC_GRID_LEFT,
                headerY = hY,
                headerW = SPEC_GRID_WIDTH,
                headerH = SPEC_HEADER_HEIGHT,
                rows = rows,
                role = block.role,
            )
            layoutBlocks += lBlock
            blockHeaderMap[block.id] = lBlock
            currentY += 12 // Gap between blocks
        }

        // Layout Brackets (e.g. File Size bracket on left, semantic block brackets on right)
        val layoutBrackets = mutableListOf<LayoutSpecBracket>()
        var rightBracketIndex = 0
        for (b in diagram.brackets) {
            if (b.fromOffset == 0L && b.toOffset == diagram.fileSize) {
                // File Size: Left side bracket spanning from top of first block to bottom of last block
                val topY = layoutBlocks.firstOrNull()?.headerY ?: rulerY
                val bottomY = layoutBlocks.lastOrNull()?.rows?.lastOrNull()?.let { it.y + SPEC_ROW_HEIGHT } ?: currentY
                layoutBrackets += LayoutSpecBracket(SPEC_GRID_LEFT - 30, topY, bottomY, b.label, b.color, onLeft = true)
            } else {
                // Find blocks in diagram matching the offset range
                val matchingBlocks = layoutBlocks.filter { lBlock ->
                    val sBlock = diagram.blocks.find { it.id == lBlock.id }
                    if (sBlock != null) {
                        val sStart = sBlock.offset
                        val sEnd = sBlock.offset + sBlock.length
                        (maxOf(sStart, b.fromOffset) < minOf(sEnd, b.toOffset)) ||
                            (b.fromOffset == sStart && b.toOffset == sEnd)
                    } else false
                }
                if (matchingBlocks.isNotEmpty()) {
                    val y1 = matchingBlocks.first().headerY
                    val lastB = matchingBlocks.last()
                    val y2 = lastB.rows.lastOrNull()?.let { it.y + SPEC_ROW_HEIGHT } ?: (lastB.headerY + lastB.headerH)
                    val bracketX = SPEC_GRID_LEFT + SPEC_GRID_WIDTH + 20 + (rightBracketIndex * 26)
                    layoutBrackets += LayoutSpecBracket(bracketX, y1, y2, b.label, b.color, onLeft = false)
                    rightBracketIndex++
                } else {
                    // Fallback: check if id matches (e.g. block_dib_header)
                    val dibBlock = layoutBlocks.find { it.id == "block_dib_header" }
                    if (dibBlock != null && dibBlock.rows.isNotEmpty()) {
                        val y1 = dibBlock.headerY
                        val y2 = dibBlock.rows.last().y + SPEC_ROW_HEIGHT
                        val bracketX = SPEC_GRID_LEFT + SPEC_GRID_WIDTH + 20 + (rightBracketIndex * 26)
                        layoutBrackets += LayoutSpecBracket(bracketX, y1, y2, b.label, b.color, onLeft = false)
                        rightBracketIndex++
                    }
                }
            }
        }

        // Layout Arrows (e.g. bfOffBits -> PixelArray block)
        val layoutArrows = mutableListOf<LayoutSpecArrow>()
        for (a in diagram.arrows) {
            val fromCell = cellMap[a.fromCellId]
            val toBlock = blockHeaderMap[a.toBlockId]
            if (fromCell != null && toBlock != null) {
                // Route: fromCell center-left -> go left -> go down -> point to toBlock header-left
                val startX = fromCell.x
                val startY = fromCell.y + fromCell.h / 2
                val midX = SPEC_GRID_LEFT - 14
                val endX = toBlock.headerX - 4
                val endY = toBlock.headerY + toBlock.headerH / 2

                layoutArrows += LayoutSpecArrow(
                    points = listOf(
                        startX to startY,
                        midX to startY,
                        midX to endY,
                        endX to endY,
                    ),
                    color = a.color,
                    label = a.label,
                )
            }
        }

        // Layout Callouts (e.g. 4-byte padding note on right side)
        val layoutCallouts = mutableListOf<LayoutSpecCallout>()
        for (c in diagram.callouts) {
            val targetBlock = blockHeaderMap[c.targetCellOrBlockId]
            if (targetBlock != null) {
                val lastRow = targetBlock.rows.lastOrNull()
                val anchorY = lastRow?.let { it.y + it.cells.first().h / 2 } ?: (targetBlock.headerY + 20)
                val anchorX = SPEC_GRID_LEFT + SPEC_GRID_WIDTH
                layoutCallouts += LayoutSpecCallout(
                    boxX = anchorX + 60,
                    boxY = anchorY - 18,
                    boxW = 240,
                    boxH = 36,
                    anchorX = anchorX + 4,
                    anchorY = anchorY,
                    text = c.text,
                )
            }
        }

        val totalH = currentY + 40
        return SpecLayout(
            width = SPEC_TOTAL_WIDTH,
            height = totalH,
            title = diagram.title,
            rulerY = rulerY,
            gridLeft = SPEC_GRID_LEFT,
            gridWidth = SPEC_GRID_WIDTH,
            colCount = SPEC_COL_COUNT,
            blocks = layoutBlocks,
            arrows = layoutArrows,
            brackets = layoutBrackets,
            callouts = layoutCallouts,
        )
    }
}
