package com.multiviewer.ui

import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

// Runs `read` (the caller's own stdout-consuming logic -- readLines(), readText(), a custom
// streaming loop, whatever the call site already does) on a background thread, bounded by
// `timeoutSeconds`. Unlike calling `process.waitFor(timeout, ...)` AFTER an unbounded blocking
// read (this codebase's prior, buggy pattern at several call sites -- see
// docs/superpowers/specs/2026-08-16-process-timeout-and-apv-read-cap-design.md), this makes the
// timeout actually enforceable: if `read` hasn't returned within `timeoutSeconds`, the process is
// force-killed and this returns null, restoring the meaning the timeout value at each call site
// already implied but couldn't deliver.
fun <T> readProcessOutputWithTimeout(process: Process, timeoutSeconds: Long, read: () -> T): T? {
    val executor = Executors.newSingleThreadExecutor { Thread(it).apply { isDaemon = true } }
    val future = executor.submit(Callable(read))
    return try {
        val result = future.get(timeoutSeconds, TimeUnit.SECONDS)
        if (!process.waitFor(1, TimeUnit.SECONDS)) process.destroyForcibly()
        result
    } catch (e: TimeoutException) {
        future.cancel(true)
        process.destroyForcibly()
        null
    } catch (e: Exception) {
        process.destroyForcibly()
        null
    } finally {
        executor.shutdownNow()
    }
}
