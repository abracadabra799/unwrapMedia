package com.multiviewer.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CompareFileSelectionTest {
    private fun f(name: String) = File("/tmp/$name")

    // Picking two files in one browse is the whole point of turning on multi-select: it replaces
    // two separate trips through the file dialog with one.
    @Test
    fun `two picked files fill the slot that was clicked and the other one`() {
        val picked = listOf(f("b.mp4"), f("a.mp4"))

        val forA = resolveComparePick(picked, targetIsA = true)
        assertEquals(f("a.mp4"), forA.fileA)
        assertEquals(f("b.mp4"), forA.fileB)
    }

    // Sorted by name, not by the order the OS happened to report the selection in -- a dialog's
    // click order is not something the user can see afterwards, so the same two files must always
    // land the same way round. The window's ⇄ button is there for when that guess is wrong.
    @Test
    fun `two picked files are ordered by name regardless of pick order`() {
        val forward = resolveComparePick(listOf(f("a.mp4"), f("b.mp4")), targetIsA = true)
        val reversed = resolveComparePick(listOf(f("b.mp4"), f("a.mp4")), targetIsA = true)

        assertEquals(forward.fileA, reversed.fileA)
        assertEquals(forward.fileB, reversed.fileB)
    }

    // Browsing from the B button must not silently rewrite A.
    @Test
    fun `picking two files from the B slot still fills both, keeping name order`() {
        val result = resolveComparePick(listOf(f("z.mp4"), f("m.mp4")), targetIsA = false)

        assertEquals(f("m.mp4"), result.fileA)
        assertEquals(f("z.mp4"), result.fileB)
    }

    // The single-file case has to behave exactly as it did before multi-select existed.
    @Test
    fun `one picked file fills only the slot that was clicked`() {
        val forA = resolveComparePick(listOf(f("only.mp4")), targetIsA = true)
        assertEquals(f("only.mp4"), forA.fileA)
        assertNull(forA.fileB)

        val forB = resolveComparePick(listOf(f("only.mp4")), targetIsA = false)
        assertNull(forB.fileA)
        assertEquals(f("only.mp4"), forB.fileB)
    }

    // Comparing is strictly pairwise, and the native file dialog has no way to cap a selection
    // (java.awt.FileDialog exposes only setMultipleMode(boolean)). Silently keeping two of three
    // is the surprising part -- the extra files vanish with no explanation -- so an over-sized
    // selection is refused outright and the caller is told how many were picked.
    @Test
    fun `more than two picked files are refused and change nothing`() {
        val result = resolveComparePick(listOf(f("c.mp4"), f("a.mp4"), f("b.mp4")), targetIsA = true)

        assertEquals(3, result.refusedCount)
        assertNull(result.fileA)
        assertNull(result.fileB)
    }

    @Test
    fun `a valid pick carries no refusal`() {
        assertNull(resolveComparePick(listOf(f("a.mp4"), f("b.mp4")), targetIsA = true).refusedCount)
        assertNull(resolveComparePick(listOf(f("a.mp4")), targetIsA = true).refusedCount)
        assertNull(resolveComparePick(emptyList(), targetIsA = true).refusedCount)
    }

    @Test
    fun `an empty pick changes nothing`() {
        val result = resolveComparePick(emptyList(), targetIsA = true)
        assertNull(result.fileA)
        assertNull(result.fileB)
    }

    // A single file dropped on the left half of the window lands in A -- matches where
    // the A panel is drawn, so the drop location alone should predict the outcome.
    @Test
    fun `one dropped file on the left half fills A`() {
        val result = resolveDroppedFiles(listOf(f("only.mp4")), xFraction = 0.25f)
        assertEquals(f("only.mp4"), result.fileA)
        assertNull(result.fileB)
    }

    // Same file, dropped on the right half, fills B instead -- no button-click context
    // needed, unlike resolveComparePick's targetIsA.
    @Test
    fun `one dropped file on the right half fills B`() {
        val result = resolveDroppedFiles(listOf(f("only.mp4")), xFraction = 0.75f)
        assertNull(result.fileA)
        assertEquals(f("only.mp4"), result.fileB)
    }

    // Exact center is B, not A -- the boundary is "< 0.5f is A", so 0.5f itself falls
    // into the else branch.
    @Test
    fun `one dropped file exactly at the halfway point fills B`() {
        val result = resolveDroppedFiles(listOf(f("only.mp4")), xFraction = 0.5f)
        assertNull(result.fileA)
        assertEquals(f("only.mp4"), result.fileB)
    }

    // Two dropped files fill both slots, sorted by name -- same rule resolveComparePick
    // uses for a two-file browse, and drop position is irrelevant once there are two.
    @Test
    fun `two dropped files fill both slots ordered by name regardless of drop position`() {
        val result = resolveDroppedFiles(listOf(f("z.mp4"), f("m.mp4")), xFraction = 0.9f)
        assertEquals(f("m.mp4"), result.fileA)
        assertEquals(f("z.mp4"), result.fileB)
    }

    // More than two dropped files is refused exactly like an over-sized dialog
    // selection -- silently keeping two of three would drop the rest with no
    // explanation.
    @Test
    fun `more than two dropped files are refused and change nothing`() {
        val result = resolveDroppedFiles(listOf(f("a.mp4"), f("b.mp4"), f("c.mp4")), xFraction = 0.1f)
        assertEquals(3, result.refusedCount)
        assertNull(result.fileA)
        assertNull(result.fileB)
    }

    // An empty drop (everything filtered out upstream, e.g. a folder or a non-media
    // file) changes nothing and carries no refusal.
    @Test
    fun `an empty dropped list changes nothing`() {
        val result = resolveDroppedFiles(emptyList(), xFraction = 0.5f)
        assertNull(result.fileA)
        assertNull(result.fileB)
        assertNull(result.refusedCount)
    }
}
