package com.multiviewer.ui

import java.io.File
import java.io.OutputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class AvmProcessResult(val exitCode: Int, val stderr: String)

/** Runs a packaged AVM command while continuously draining stderr and supporting cancellation. */
fun runAvmProcess(command: List<String>, workingDirectory: File? = null, cancelRequested: () -> Boolean = { false }): AvmProcessResult {
    require(command.isNotEmpty()) { "AVM command must not be empty" }
    val process = ProcessBuilder(command).apply { workingDirectory?.let { directory(it) } }.start()
    val stderr = StringBuilder()
    val drain = Executors.newFixedThreadPool(2)
    val drainFuture = drain.submit { process.errorStream.bufferedReader().useLines { lines -> lines.forEach { stderr.appendLine(it) } } }
    val stdoutFuture = drain.submit { process.inputStream.copyTo(OutputStream.nullOutputStream()) }
    try {
        while (process.isAlive) {
            if (cancelRequested()) {
                process.destroy()
                if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
                return AvmProcessResult(-1, stderr.toString())
            }
            Thread.sleep(20)
        }
        process.waitFor()
        return AvmProcessResult(process.exitValue(), stderr.toString())
    } finally {
        drainFuture.cancel(true)
        stdoutFuture.cancel(true)
        drain.shutdownNow()
        process.inputStream.close()
        process.errorStream.close()
        process.outputStream.close()
    }
}
