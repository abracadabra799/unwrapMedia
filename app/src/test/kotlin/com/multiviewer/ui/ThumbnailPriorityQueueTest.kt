package com.multiviewer.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ThumbnailPriorityQueueTest {
    @Test
    fun `visible work is returned before prefetch work`() {
        val queue = ThumbnailPriorityQueue()
        queue.enqueue("prefetch", 1) {}
        queue.enqueue("visible", 0) {}

        val visibleWork = queue.takeNext()
        assertEquals("visible", visibleWork.key)
        assertEquals(0, visibleWork.priority)
        val prefetchWork = queue.takeNext()
        assertEquals("prefetch", prefetchWork.key)
        assertEquals(1, prefetchWork.priority)
    }

    @Test
    fun `same-priority work remains FIFO`() {
        val queue = ThumbnailPriorityQueue()
        queue.enqueue("first", 2) {}
        queue.enqueue("second", 2) {}

        val firstWork = queue.takeNext()
        assertEquals("first", firstWork.key)
        assertEquals(2, firstWork.priority)
        val secondWork = queue.takeNext()
        assertEquals("second", secondWork.key)
        assertEquals(2, secondWork.priority)
    }

    @Test
    fun `reprioritize promotes newly visible work and demotes off-screen work`() {
        val queue = ThumbnailPriorityQueue()
        queue.enqueue("old-visible", 0) {}
        queue.enqueue("new-visible", 2) {}

        queue.reprioritize { key -> if (key == "new-visible") 0 else 2 }

        val promotedWork = queue.takeNext()
        assertEquals("new-visible", promotedWork.key)
        assertEquals(0, promotedWork.priority)
        val demotedWork = queue.takeNext()
        assertEquals("old-visible", demotedWork.key)
        assertEquals(2, demotedWork.priority)
    }

    @Test
    fun `enqueue deduplicates a pending key`() {
        val queue = ThumbnailPriorityQueue()
        var originalActionRan = false
        queue.enqueue("same", 2) { originalActionRan = true }

        assertFalse(queue.enqueue("same", 0) { error("duplicate action must not replace original") })
        val scheduled = queue.takeNext()
        assertEquals("same", scheduled.key)
        assertEquals(2, scheduled.priority)
        scheduled.action()
        assertTrue(originalActionRan)
    }

    @Test
    fun `remove deletes only queued work`() {
        val queue = ThumbnailPriorityQueue()
        queue.enqueue("active", 0) {}
        queue.enqueue("pending", 1) {}

        assertEquals("active", queue.takeNext().key)
        assertFalse(queue.remove("active"))
        assertTrue(queue.remove("pending"))
        assertFalse(queue.remove("pending"))
    }
}
