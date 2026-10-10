package com.multiviewer.ui

import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DetachedBlockingTest {
    @Test
    fun `exception is delivered to caller catch and does not cancel a plain parent job`() = runBlocking {
        val parent = Job()
        val scope = CoroutineScope(parent)
        var caught: String? = null
        scope.launch {
            try {
                awaitDetached<Int> { throw IllegalStateException("boom") }
            } catch (e: IllegalStateException) {
                caught = e.message
            }
        }.join()
        assertEquals("boom", caught)
        assertTrue(parent.isActive, "parent job must stay active")
        var second = 0
        scope.launch { second = awaitDetached { 7 } }.join()
        assertEquals(7, second)
    }
}
