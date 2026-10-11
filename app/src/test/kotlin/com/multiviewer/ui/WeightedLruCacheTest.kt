package com.multiviewer.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class WeightedLruCacheTest {
    @Test
    fun `byte budget evicts older frames while preserving recently visible frames`() {
        val cache = WeightedLruCache<Int, Int>(maxBytes = 12, maxEntries = 10) { it.toLong() }
        cache.putAll(mapOf(1 to 4, 2 to 4, 3 to 4))
        cache.touch(listOf(1))
        cache.putAll(mapOf(4 to 4))
        assertEquals(setOf(1, 3, 4), cache.snapshot().keys)
        cache.putAll(mapOf(5 to 20))
        assertFalse(cache.snapshot().containsKey(5), "An oversized bitmap must not be retained")
    }

    @Test
    fun `entry cap and replacing existing entries keep accounting correct`() {
        val cache = WeightedLruCache<Int, Int>(maxBytes = 12, maxEntries = 2) { it.toLong() }
        cache.putAll(mapOf(1 to 4, 2 to 4, 3 to 4))
        assertEquals(setOf(2, 3), cache.snapshot().keys)
        cache.putAll(mapOf(3 to 8))
        assertEquals(setOf(2, 3), cache.snapshot().keys)
        cache.putAll(mapOf(4 to 4))
        assertEquals(setOf(3, 4), cache.snapshot().keys)
        cache.clear()
        cache.putAll(mapOf(5 to 12))
        assertEquals(mapOf(5 to 12), cache.snapshot())
    }

    @Test
    fun `keeps protected visible entries when delayed offscreen results arrive`() {
        val cache = WeightedLruCache<Int, String>(maxBytes = 3, maxEntries = 3) { 1 }
        cache.putAll(mapOf(0 to "zero", 1 to "one", 2 to "two"))

        cache.protect(setOf(1))
        cache.putAll(mapOf(3 to "three", 4 to "four", 5 to "five"))

        assertEquals("one", cache.snapshot()[1])
        assertFalse(cache.snapshot().containsKey(0))
    }

    @Test
    fun `late batch protects the latest viewport rather than the request viewport`() {
        val cache = WeightedLruCache<Int, Int>(maxBytes = 12, maxEntries = 3) { it.toLong() }
        cache.putAll(mapOf(0 to 4, 1 to 4, 2 to 4))
        cache.protect(1..1)
        // The old batch is still in flight when scrolling changes the viewport.
        cache.protect(0..0)
        cache.putAll(mapOf(3 to 4, 4 to 4, 5 to 4))
        assertEquals(setOf(0, 4, 5), cache.snapshot().keys)
    }

    @Test
    fun `protecting more pixels than the budget never exceeds the hard cap`() {
        val cache = WeightedLruCache<Int, Int>(maxBytes = 8, maxEntries = 3) { it.toLong() }
        cache.protect(0..10)
        cache.putAll((0..10).associateWith { 4 })
        assertEquals(8, cache.snapshot().values.sum())
        cache.clear()
        cache.putAll(mapOf(0 to 4, 1 to 4, 12 to 4))
        assertEquals(setOf(1, 12), cache.snapshot().keys, "clear must also discard viewport protection")
    }
}
