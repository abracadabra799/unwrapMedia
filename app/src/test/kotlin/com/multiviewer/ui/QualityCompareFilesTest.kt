package com.multiviewer.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class QualityCompareFilesTest {
    private val raw = File("raw.yuv")
    private val a = File("a.mp4")
    private val b = File("b.mp4")
    private val slots = mapOf("raw" to raw, "encodedA" to a, "encodedB" to b)

    @Test
    fun `files not in any queued pair are excluded`() {
        val queued = determineComparisonPairs(raw, a, b).filter { it.id == "raw_a" }
        assertEquals(mapOf("raw" to raw, "encodedA" to a), filesForPairs(queued, slots))
    }

    @Test
    fun `all pairs queued keeps every file and no pairs keeps none`() {
        assertEquals(slots, filesForPairs(determineComparisonPairs(raw, a, b), slots))
        assertEquals(emptyMap(), filesForPairs(emptyList(), slots))
    }
}
