package com.multiviewer.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CompareExplorerSelectionTest {
    private fun file(folder: String, name: String) = File("/media/$folder/$name")

    @Test
    fun `assigning files to separate slots supports different folders`() {
        val selection = CompareExplorerSelection()
            .assign(slotIndex = 0, file("reference", "a.jpg"))
            .assign(slotIndex = 1, file("target", "b.jpg"))

        assertEquals(file("reference", "a.jpg"), selection.slotA)
        assertEquals(file("target", "b.jpg"), selection.slotB)
        assertTrue(selection.isReadyToCompare)
        assertEquals(
            listOf(file("reference", "a.jpg"), file("target", "b.jpg")),
            selection.filesForCompare(),
        )
    }

    @Test
    fun `replacing one slot leaves the other slot unchanged`() {
        val originalA = file("one", "a.jpg")
        val originalB = file("two", "b.jpg")
        val replacement = file("three", "c.jpg")

        val selection = CompareExplorerSelection(originalA, originalB).assign(1, replacement)

        assertEquals(originalA, selection.slotA)
        assertEquals(replacement, selection.slotB)
    }

    @Test
    fun `thumbnail selection still fills a pair and drops the oldest on a third pick`() {
        val first = file("one", "a.jpg")
        val second = file("two", "b.jpg")
        val third = file("three", "c.jpg")

        val selection = CompareExplorerSelection().toggleThumbnail(first)
            .toggleThumbnail(second).toggleThumbnail(third)

        assertEquals(second, selection.slotA)
        assertEquals(third, selection.slotB)
    }

    @Test
    fun `comparison is disabled until both slots are assigned`() {
        assertFalse(CompareExplorerSelection(slotB = file("elsewhere", "b.jpg")).isReadyToCompare)
    }
}
