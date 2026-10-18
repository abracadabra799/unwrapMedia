package com.multiviewer.ui

import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory

// Fire-and-forget background work (image/video decode, ffprobe calls, etc.) must never let an
// exception escape uncaught. Two concrete ways that went wrong before this existed:
//  - FfmpegVideoPlayer's reader Thread had no try/catch at all -- closing a video tab calls
//    readerThread.interrupt() from onDispose, and if the thread happened to be in Thread.sleep()
//    at that moment (likely, since the loop spends most of its time there), that throws
//    InterruptedException with nothing to catch it.
//  - Several of AppState's background Threads (raw pixel decode, GOP frame analysis, motion photo
//    codec probing) had no top-level try/catch either, so any decode/IO failure -- not just tab
//    teardown -- would surface as an uncaught exception on a bare Thread instead of failing
//    quietly for a fire-and-forget task.
//
// This used to spawn a brand-new raw Thread per call with no limit at all -- opening two files
// close together (each open fires 1-2 of these for ffprobe + full-image decode/histogram) could
// pile up several genuinely CPU-heavy tasks running fully in parallel, which is what "opening two
// files makes the whole app stutter" actually was: not two tabs coexisting, but their one-time
// open-time decode work all fighting for the CPU at once. Routing through a small fixed pool
// instead serializes the overflow instead of letting it pile on -- a task queues briefly rather
// than racing every other task for CPU/IO. Sized at 2: a third task just waits its turn instead of
// adding a third contender. Everything here must be short (file-open parse/decode, snapshot decodes);
// anything that can run for minutes belongs on [longScanExecutor] instead.
internal val backgroundExecutor = Executors.newFixedThreadPool(
    2,
    ThreadFactory { runnable -> Thread(runnable).apply { isDaemon = true } },
)

// Multi-minute scans (GOP / frame-interval ffprobe -show_frames over a long recording) used to share
// backgroundExecutor, so two of them held both slots and every file open -- and the Compare window's
// HEIC fallback decode -- queued behind them until a scan finished. They get their own small pool so
// they can only delay each other. Per-tab ownership (and cancellation on tab close) still goes through
// TabState.longScanTasks.
internal val longScanExecutor = Executors.newFixedThreadPool(
    2,
    ThreadFactory { runnable -> Thread(runnable, "long-scan").apply { isDaemon = true } },
)

fun runInBackground(block: () -> Unit) {
    backgroundExecutor.execute {
        try {
            block()
        } catch (e: InterruptedException) {
            // Expected during teardown (a tab closing interrupts its own in-flight background
            // work) -- not a real error, stay silent.
        } catch (e: Exception) {
            System.err.println("Background task failed: $e")
        }
    }
}
