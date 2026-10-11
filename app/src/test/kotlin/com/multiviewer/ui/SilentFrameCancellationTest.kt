package com.multiviewer.ui

import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

class SilentFrameCancellationTest {
    @Test
    fun `tab cancellation stops a probe that has not emitted any output`() {
        val dir = Files.createTempDirectory("silent-frame-cancel-").toFile()
        val fifo = dir.resolve("input.mp4")
        ProcessBuilder("mkfifo", fifo.absolutePath).start().waitFor()
        val cancelled = AtomicBoolean(false)
        val worker = Executors.newSingleThreadExecutor { Thread(it).apply { isDaemon = true } }
        var probe: ProcessHandle? = null
        val task = worker.submit {
            runBlocking { probeFrameTypesStreaming(fifo, isCancelled = cancelled::get).collect {} }
        }
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (probe == null && System.nanoTime() < deadline) {
                probe = ProcessHandle.current().descendants().use { handles ->
                    handles.filter { it.info().arguments().orElse(emptyArray()).contains(fifo.absolutePath) }
                        .findFirst().orElse(null)
                }
                if (probe == null) Thread.sleep(10)
            }
            assertNotNull(probe, "Diagnostic ffprobe must start before cancellation")
            cancelled.set(true)
            task.get(2, TimeUnit.SECONDS)
            assertFalse(probe.isAlive, "Silent ffprobe survived tab cancellation")
        } finally {
            cancelled.set(true)
            probe?.destroyForcibly()
            runCatching { task.get(2, TimeUnit.SECONDS) }
            task.cancel(true)
            worker.shutdownNow()
            fifo.delete()
            dir.delete()
        }
    }
}
