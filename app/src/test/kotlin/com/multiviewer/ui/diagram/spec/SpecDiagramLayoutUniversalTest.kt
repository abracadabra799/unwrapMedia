package com.multiviewer.ui.diagram.spec

import com.multiviewer.parser.diagram.RegionRole
import com.multiviewer.parser.diagram.spec.SpecArrow
import com.multiviewer.parser.diagram.spec.SpecBlock
import com.multiviewer.parser.diagram.spec.SpecBracket
import com.multiviewer.parser.diagram.spec.SpecCell
import com.multiviewer.parser.diagram.spec.SpecDiagram
import com.multiviewer.parser.diagram.spec.SpecRow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SpecDiagramLayoutUniversalTest {

    @Test
    fun `verifies universal brackets match arbitrary offset ranges and avoid collision`() {
        val block1 = SpecBlock(
            id = "block_main",
            title = "Main Image Block",
            offset = 0L,
            length = 2000L,
            role = RegionRole.MEDIA_DATA,
            rows = listOf(
                SpecRow(listOf(SpecCell("cell_1", "Main Data", "Val", 2000L, colSpan = 12)), 0L)
            )
        )
        val block2 = SpecBlock(
            id = "block_aux",
            title = "Auxiliary Image Block",
            offset = 2000L,
            length = 1000L,
            role = RegionRole.MEDIA_DATA,
            rows = listOf(
                SpecRow(listOf(SpecCell("cell_2", "Aux Data", "Val", 1000L, colSpan = 12)), 2000L)
            )
        )

        val diagram = SpecDiagram(
            title = "Universal Test",
            fileSize = 3000L,
            blocks = listOf(block1, block2),
            brackets = listOf(
                SpecBracket(0L, 3000L, "File: 3000B", color = 0xFF00FF00L),
                SpecBracket(0L, 2000L, "Main Image: 2000B", color = 0xFF107C41L),
                SpecBracket(2000L, 3000L, "Aux Image: 1000B", color = 0xFFD97706L),
            ),
            arrows = listOf(
                SpecArrow("cell_1", "block_aux", color = 0xFF0078D4L, label = "Ref")
            )
        )

        val layout = SpecDiagramLayoutEngine.layout(diagram)
        assertNotNull(layout)
        assertEquals(3, layout.brackets.size)

        val leftBracket = layout.brackets.find { it.onLeft }
        assertNotNull(leftBracket)
        assertEquals("File: 3000B", leftBracket!!.label)

        val rightBrackets = layout.brackets.filter { !it.onLeft }
        assertEquals(2, rightBrackets.size)
        // Ensure right brackets don't overlap in X position
        assertFalse(rightBrackets[0].x == rightBrackets[1].x, "Right brackets should have distinct staggered X positions")

        // Arrow layout
        assertEquals(1, layout.arrows.size)
        val arrow = layout.arrows.first()
        assertTrue(arrow.points.size >= 4)
    }
}
