package com.multiviewer.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PanelPreferencesTest {

    @Test
    fun testLeftPanelWidthDefaultAndClamping() {
        // Range: 180f .. 800f
        saveLeftPanelWidth(350f)
        assertEquals(350f, loadLeftPanelWidth(330f))

        saveLeftPanelWidth(100f) // below min
        assertEquals(180f, loadLeftPanelWidth(330f))

        saveLeftPanelWidth(1200f) // above max
        assertEquals(800f, loadLeftPanelWidth(330f))

        // Reset to default
        saveLeftPanelWidth(330f)
        assertEquals(330f, loadLeftPanelWidth())
    }

    @Test
    fun testRightPanelWidthDefaultAndClamping() {
        // Range: 240f .. 1000f
        saveRightPanelWidth(450f)
        assertEquals(450f, loadRightPanelWidth(430f))

        saveRightPanelWidth(150f) // below min
        assertEquals(240f, loadRightPanelWidth(430f))

        saveRightPanelWidth(1500f) // above max
        assertEquals(1000f, loadRightPanelWidth(430f))

        // Reset to default
        saveRightPanelWidth(430f)
        assertEquals(430f, loadRightPanelWidth())
    }

    @Test
    fun testVerticalSplitDefaultAndClamping() {
        // Range: 0.2f .. 0.85f
        saveVerticalSplit(0.65f)
        assertEquals(0.65f, loadVerticalSplit(0.60f))

        saveVerticalSplit(0.1f) // below min
        assertEquals(0.2f, loadVerticalSplit(0.60f))

        saveVerticalSplit(0.95f) // above max
        assertEquals(0.85f, loadVerticalSplit(0.60f))

        // Reset to default
        saveVerticalSplit(0.60f)
        assertEquals(0.60f, loadVerticalSplit())
    }
}
