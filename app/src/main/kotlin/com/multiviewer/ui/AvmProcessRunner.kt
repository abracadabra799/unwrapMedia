package com.multiviewer.ui

import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.util.concurrent.Executors
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeoutException
import java.util.concurrent.TimeUnit
import com.multiviewer.util.ProcessManager

data class AvmProcessResult(val exitCode: Int, val stderr: String, val stderrTruncated: Boolean = false, val callbackError: String? = null)

private class DiagnosticTail(private val capacity: Int = 64 * 1024) {
    private val text = StringBuilder(capacity)
    var truncated: Boolean = false
        private set

    @Synchronized fun appendChunk(chunk: CharArray, length: Int) {
        if (length >= capacity) {
            text.setLength(0)
            text.append(chunk, length - capacity, capacity)
            truncated = true
            return
        }
        val excess = text.length + length - capacity
        if (excess > 0) {
            text.delete(0, excess)
            truncated = true
        }
        text.append(chunk, 0, length)
    }

    @Synchronized override fun toString(): String = text.toString()
}

private fun drainDiagnostics(input: InputStream, tail: DiagnosticTail) {
    val reader = InputStreamReader(input)
    val chunk = CharArray(4096)
    while (true) {
        val count = reader.read(chunk)
        if (count < 0) return
        tail.appendChunk(chunk, count)
    }
}

/** Runs a packaged AVM command while continuously draining stderr and supporting cancellation. */
fun runAvmProcess(
    command: List<String>,
    workingDirectory: File? = null,
    onStdout: (java.io.InputStream) -> Unit = { it.copyTo(OutputStream.nullOutputStream()) },
    cancelRequested: () -> Boolean = { false },
): AvmProcessResult {
    require(command.isNotEmpty()) { "AVM command must not be empty" }
    val process = ProcessManager.register(ProcessBuilder(command).apply { workingDirectory?.let { directory(it) } }.start())
    val stderr = DiagnosticTail()
    val drain = Executors.newFixedThreadPool(2) { task -> Thread(task, "avm-output-reader").apply { isDaemon = true } }
    val drainFuture = drain.submit { process.errorStream.use { drainDiagnostics(it, stderr) } }
    val stdoutFuture = drain.submit { process.inputStream.use(onStdout) }
    var wasCancelled = false
    var callbackError: String? = null
    try {
        while (process.isAlive) {
            if (cancelRequested()) {
                wasCancelled = true
                ProcessManager.terminate(process)
                break
            }
            if (stdoutFuture.isDone) {
                val failure = try {
                    stdoutFuture.get(0, TimeUnit.MILLISECONDS)
                    null
                } catch (e: ExecutionException) {
                    e.cause?.message ?: "AVM stdout consumer failed"
                } catch (e: java.util.concurrent.CancellationException) {
                    "AVM stdout consumer was cancelled"
                } catch (_: TimeoutException) {
                    null
                }
                if (failure != null) {
                    callbackError = failure
                    ProcessManager.terminate(process)
                    process.waitFor(2, TimeUnit.SECONDS)
                    break
                }
            }
            Thread.sleep(20)
        }
        // Cancellation can arrive after the child exits while the stdout callback is still
        // back-pressured (for example, its bounded frame queue is no longer being consumed).
        while (!wasCancelled && callbackError == null && (!stdoutFuture.isDone || !drainFuture.isDone)) {
            if (cancelRequested()) {
                wasCancelled = true
                break
            }
            Thread.sleep(20)
        }
        if (!wasCancelled && callbackError == null) {
            process.waitFor()
            // The process can exit while buffered stdout is still being consumed. In AV2 playback
            // the consumer may be applying back-pressure through a bounded frame queue; returning
            // before this future completes would cancel and silently discard its final frames.
            stdoutFuture.get()
            drainFuture.get()
        }
        return AvmProcessResult(if (wasCancelled || callbackError != null) -1 else process.exitValue(), stderr.toString(), stderr.truncated, callbackError)
    } finally {
        if (process.isAlive) ProcessManager.terminate(process)
        process.inputStream.close()
        process.errorStream.close()
        process.outputStream.close()
        if (!stdoutFuture.isDone) stdoutFuture.cancel(true)
        if (!drainFuture.isDone) drainFuture.cancel(true)
        drain.shutdownNow()
        ProcessManager.unregister(process)
    }
}
