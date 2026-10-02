package com.multiviewer.ui

import java.io.File
import java.io.OutputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class AvmProcessResult(val exitCode: Int, val stderr: String)

/** Runs a packaged AVM command while continuously draining stderr and supporting cancellation. */
fun runAvmProcess(
    command: List<String>,
    workingDirectory: File? = null,
    onStdout: (java.io.InputStream) -> Unit = { it.copyTo(OutputStream.nullOutputStream()) },
    cancelRequested: () -> Boolean = { false },
): AvmProcessResult {
    require(command.isNotEmpty()) { "AVM command must not be empty" }
    val process = ProcessBuilder(command).apply { workingDirectory?.let { directory(it) } }.start()
    val stderr = StringBuilder()
    val drain = Executors.newFixedThreadPool(2)
    val drainFuture = drain.submit { process.errorStream.bufferedReader().useLines { lines -> lines.forEach { stderr.appendLine(it) } } }
    val stdoutFuture = drain.submit { process.inputStream.use(onStdout) }
    var wasCancelled = false
    try {
        while (process.isAlive) {
            if (cancelRequested()) {
                wasCancelled = true
                process.destroy()
                if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
                process.waitFor()
                break
            }
            Thread.sleep(20)
        }
        // Cancellation can arrive after the child exits while the stdout callback is still
        // back-pressured (for example, its bounded frame queue is no longer being consumed).
        while (!wasCancelled && (!stdoutFuture.isDone || !drainFuture.isDone)) {
            if (cancelRequested()) {
                wasCancelled = true
                break
            }
            Thread.sleep(20)
        }
        if (!wasCancelled) {
            process.waitFor()
            // The process can exit while buffered stdout is still being consumed. In AV2 playback
            // the consumer may be applying back-pressure through a bounded frame queue; returning
            // before this future completes would cancel and silently discard its final frames.
            stdoutFuture.get()
            drainFuture.get()
        }
        return AvmProcessResult(if (wasCancelled) -1 else process.exitValue(), stderr.toString())
    } finally {
        if (process.isAlive) process.destroyForcibly()
        process.inputStream.close()
        process.errorStream.close()
        process.outputStream.close()
        if (!stdoutFuture.isDone) stdoutFuture.cancel(true)
        if (!drainFuture.isDone) drainFuture.cancel(true)
        drain.shutdownNow()
    }
}
