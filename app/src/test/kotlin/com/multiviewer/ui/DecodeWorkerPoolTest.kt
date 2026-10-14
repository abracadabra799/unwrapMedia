package com.multiviewer.ui

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertTrue

class DecodeWorkerPoolTest {
    @Test
    fun `new request discards cancelled jobs waiting behind a busy decoder`() {
        val pool = Executors.newFixedThreadPool(1) as ThreadPoolExecutor
        val gate = CountDownLatch(1)
        try {
            val started = CountDownLatch(1)
            pool.submit { started.countDown(); gate.await() }
            assertTrue(started.await(2, TimeUnit.SECONDS))
            repeat(100) { pool.submit { }.cancel(true) }
            submitDiscardingCancelled(pool) { }
            assertTrue(pool.queue.size <= 1, "cancelled decode jobs remain queued: ${pool.queue.size}")
        } finally {
            gate.countDown()
            pool.shutdownNow()
        }
    }
}
