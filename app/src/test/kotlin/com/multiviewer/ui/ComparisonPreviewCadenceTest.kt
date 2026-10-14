package com.multiviewer.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import java.util.concurrent.FutureTask

class ComparisonPreviewCadenceTest {
    @Test
    fun `disposed comparison decode cancels work and ignores late callback`() {
        val lifetime = ComparisonDecodeLifetime()
        val work = FutureTask<Unit>({ })
        lifetime.track(work)
        var delivered = false
        assertTrue(lifetime.deliver { delivered = true })
        lifetime.close()
        assertTrue(work.isCancelled)
        assertFalse(lifetime.deliver { delivered = true })
        delivered = false
        assertFalse(lifetime.deliver { delivered = true })
        assertFalse(delivered)
    }

    @Test
    fun `playback samples at four previews per second while paused seek stays exact`() {
        assertEquals(0.0, comparisonPreviewTime(0.04, playing = true))
        assertEquals(0.0, comparisonPreviewTime(0.24, playing = true))
        assertEquals(0.25, comparisonPreviewTime(0.25, playing = true))
        assertEquals(1.5, comparisonPreviewTime(1.74, playing = true))
        assertEquals(1.74, comparisonPreviewTime(1.74, playing = false))
    }
}
