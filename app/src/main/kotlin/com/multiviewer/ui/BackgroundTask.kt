package com.multiviewer.ui

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
// Every background Thread in this app should be started through this helper instead of a raw
// Thread { ... }, so that guarantee is centralized rather than repeated (and easy to miss) at
// each call site.
fun runInBackground(block: () -> Unit) {
    Thread {
        try {
            block()
        } catch (e: InterruptedException) {
            // Expected during teardown (a tab closing interrupts its own in-flight background
            // work) -- not a real error, stay silent.
        } catch (e: Exception) {
            System.err.println("Background task failed: $e")
        }
    }.apply { isDaemon = true }.start()
}
