package com.multiviewer.ui

import java.awt.EventQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CompareLoadSessionTest {
    @Test
    fun `closing compare load cancels nested jobs and rejects posted results`() {
        val session = CompareLoadSession()
        val parse = FutureTask<Unit>({ })
        val decode = FutureTask<Unit>({ })
        session.track(parse)
        session.track(decode)
        val delivered = CountDownLatch(1)
        session.close()
        session.post { delivered.countDown() }
        EventQueue.invokeAndWait { }
        assertTrue(parse.isCancelled)
        assertTrue(decode.isCancelled)
        assertFalse(delivered.await(100, TimeUnit.MILLISECONDS))
    }
}
