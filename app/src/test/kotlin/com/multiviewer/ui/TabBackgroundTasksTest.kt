package com.multiviewer.ui

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TabBackgroundTasksTest {
    @Test
    fun `closing owner interrupts running work and discards queued work`() {
        val pool = Executors.newFixedThreadPool(1)
        val tasks = TabBackgroundTasks(pool)
        val started = CountDownLatch(1)
        val interrupted = CountDownLatch(1)
        val queuedRan = AtomicBoolean(false)
        try {
            tasks.submit {
                started.countDown()
                try { CountDownLatch(1).await() } finally { interrupted.countDown() }
            }
            assertTrue(started.await(2, TimeUnit.SECONDS))
            tasks.submit { queuedRan.set(true) }
            tasks.close()
            assertTrue(interrupted.await(2, TimeUnit.SECONDS))
            pool.submit {}.get(2, TimeUnit.SECONDS)
            assertFalse(queuedRan.get(), "A closed tab must not start its queued decode")
            assertNull(tasks.submit { queuedRan.set(true) }, "A closed tab must reject new work")
        } finally {
            tasks.close()
            pool.shutdownNow()
        }
    }
}
