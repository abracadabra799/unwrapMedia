package com.multiviewer.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import java.util.concurrent.FutureTask

class ComparisonPreviewCadenceTest {
    @Test
    fun `slow comparison decoder keeps one job and then runs latest requested time`() {
        val launched = mutableListOf<Double>()
        val callbacks = mutableListOf<(String?) -> Unit>()
        val published = mutableListOf<String>()
        val jobs = mutableListOf<FutureTask<Unit>>()
        val scheduler = ComparisonFrameScheduler<String>(
            decode = { pts, callback ->
                launched += pts
                callbacks += callback
                FutureTask<Unit>({ }).also(jobs::add)
            },
            publish = { it?.let(published::add) },
        )
        scheduler.request(0.0)
        scheduler.request(0.25)
        scheduler.request(0.5)
        assertEquals(listOf(0.0), launched)
        callbacks[0]("frame0")
        assertEquals(listOf(0.0, 0.5), launched)
        scheduler.close()
        assertTrue(jobs[1].isCancelled)
        callbacks[1]("late")
        scheduler.request(0.75)
        assertEquals(listOf("frame0"), published)
        assertEquals(listOf(0.0, 0.5), launched)
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
