package com.multiviewer.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DetailPanelWarningsTest {
    // The point of the badge: while the user is reading any other tab, the label alone has to say
    // whether this file has structural defects and how many. A bare "Warnings" would hide exactly
    // the fact that used to vanish the moment a tree node was selected.
    @Test
    fun `warningsTabLabel shows the defect count when there are warnings`() {
        assertEquals("⚠ Warnings 3", warningsTabLabel(3))
        assertEquals("⚠ Warnings 1", warningsTabLabel(1))
    }

    @Test
    fun `warningsTabLabel reads as a clean bill of health when there are none`() {
        assertEquals("✓ Warnings", warningsTabLabel(0))
    }

    // Large counts stay readable rather than widening the tab until the other two are squeezed out.
    @Test
    fun `warningsTabLabel caps an implausibly large count`() {
        assertEquals("⚠ Warnings 99+", warningsTabLabel(100))
        assertEquals("⚠ Warnings 99+", warningsTabLabel(4321))
        assertEquals("⚠ Warnings 99", warningsTabLabel(99))
    }

    @Test
    fun `warningsTabLabel treats a negative count as none`() {
        assertEquals("✓ Warnings", warningsTabLabel(-1))
    }

    // The tab must exist whether or not the file is clean -- it is a fixed destination, not
    // something that appears and disappears as files are opened.
    @Test
    fun `every label variant is non-blank so the tab is never unlabeled`() {
        listOf(0, 1, 7, 250).forEach { assertTrue(warningsTabLabel(it).isNotBlank(), "count=$it") }
    }
}
