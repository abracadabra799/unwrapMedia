package com.multiviewer.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HexRowLayoutCacheTest {
    @Test fun keepsOnlyRowsNearTheVisibleWindow() {
        val cache = (0 until 20_000).associateWith { "row$it" }.toMutableMap()
        pruneRowLayoutCache(cache, visibleRows = 10_000..10_040, margin = 32)
        assertEquals((10_000 - 32..10_040 + 32).toSet(), cache.keys)
    }

    @Test fun sizeStaysBoundedAfterASweep() {
        val cache = mutableMapOf<Int, String>()
        for (first in 0 until 20_000 step 40) {
            val visible = first..first + 40
            for (r in visible) cache[r] = "row$r"
            pruneRowLayoutCache(cache, visible, margin = 32)
            assertTrue(cache.size <= 41 + 2 * 32, "size ${cache.size} at $first")
        }
    }

    @Test fun emptyVisibleWindowLeavesTheCacheAlone() {
        val cache = mutableMapOf(1 to "a", 2 to "b")
        pruneRowLayoutCache(cache, visibleRows = IntRange.EMPTY, margin = 32)
        assertEquals(setOf(1, 2), cache.keys)
    }

    @Test fun worksOnTheComposeStateMapHexViewUses() {
        val cache = androidx.compose.runtime.mutableStateMapOf<Int, String>()
        for (r in 0 until 500) cache[r] = "row$r"
        pruneRowLayoutCache(cache, visibleRows = 200..210, margin = 5)
        assertEquals((195..215).toSet(), cache.keys.toSet())
    }
}
