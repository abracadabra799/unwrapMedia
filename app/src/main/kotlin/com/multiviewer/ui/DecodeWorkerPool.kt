package com.multiviewer.ui

import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.ThreadPoolExecutor

/** Fixed pools have unbounded queues by default; canceled futures otherwise remain until dequeued. */
internal fun submitDiscardingCancelled(executor: ExecutorService, work: () -> Unit): Future<*> {
    (executor as? ThreadPoolExecutor)?.purge()
    return executor.submit(work)
}
