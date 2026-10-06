package com.multiviewer.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ThumbnailViewportPriorityTest {
    @Test
    fun prioritizes_visible_items_then_exactly_one_following_row() {
        val visible = setOf(4, 5, 6, 7)
        val prefetch = thumbnailPrefetchIndices(visible, columnCount = 4, itemCount = 20)

        assertEquals(setOf(8, 9, 10, 11), prefetch)
        assertEquals(0, thumbnailPriorityForIndex(5, visible, prefetch))
        assertEquals(1, thumbnailPriorityForIndex(9, visible, prefetch))
        assertEquals(2, thumbnailPriorityForIndex(12, visible, prefetch))

        assertEquals(setOf(16, 17, 18), thumbnailPrefetchIndices(setOf(15), columnCount = 4, itemCount = 19))
    }

    @Test
    fun `empty or out-of-range visible indices do not prefetch`() {
        assertEquals(emptySet(), thumbnailPrefetchIndices(emptySet(), columnCount = 3, itemCount = 10))
        assertEquals(emptySet(), thumbnailPrefetchIndices(setOf(-1, 10), columnCount = 3, itemCount = 10))
        assertEquals(emptySet(), thumbnailPrefetchIndices(setOf(0), columnCount = 3, itemCount = 0))
    }

    @Test
    fun `column count must be positive`() {
        assertFailsWith<IllegalArgumentException> {
            thumbnailPrefetchIndices(setOf(0), columnCount = 0, itemCount = 10)
        }
    }
}
