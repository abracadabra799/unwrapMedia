package com.multiviewer.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class ThumbnailLookaheadSchedulerTest {
    @Test
    fun `viewport schedules exactly the immediately following row`() {
        val queue = ThumbnailPriorityQueue()
        val files = (0 until 12).map { "item-$it" }
        val scheduler = ThumbnailLookaheadScheduler<String> { _, item ->
            queue.enqueue(item, priority = 1) {}
        }

        scheduler.update(files, visibleIndices = setOf(4, 5, 6, 7), columnCount = 4)

        val scheduled = List(4) { queue.takeNext() }
        assertEquals(listOf("item-8", "item-9", "item-10", "item-11"), scheduled.map { it.key })
        assertEquals(listOf(1, 1, 1, 1), scheduled.map { it.priority })
    }
}
