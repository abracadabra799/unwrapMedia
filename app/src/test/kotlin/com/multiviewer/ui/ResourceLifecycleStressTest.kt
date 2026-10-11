package com.multiviewer.ui

import com.multiviewer.util.ProcessManager
import java.awt.EventQueue
import java.awt.image.BufferedImage
import java.io.File
import java.lang.management.ManagementFactory
import java.lang.ref.WeakReference
import java.nio.file.Files
import java.util.concurrent.FutureTask
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ResourceLifecycleStressTest {
    private fun <T> onEdt(block: () -> T): T {
        val task = FutureTask(block)
        EventQueue.invokeAndWait(task)
        return task.get()
    }

    private fun await(description: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!condition()) {
            check(System.nanoTime() < deadline) { "Timed out: $description" }
            Thread.sleep(10)
        }
    }

    @Test
    fun `repeated image open and close releases cached pixels and closed tabs`() {
        val file = File.createTempFile("lifecycle-repeat-", ".png")
        ImageIO.write(BufferedImage(256, 256, BufferedImage.TYPE_INT_RGB), "png", file)
        val state = onEdt { AppState() }
        fun cycle(): WeakReference<TabState> {
            val tab = onEdt { state.openFile(file); state.tabs.single() }
            await("image decode") { onEdt { !tab.isLoading && tab.imageForensic?.bitmap != null } }
            onEdt {
                val bitmap = assertNotNull(tab.imageForensic?.bitmap)
                tab.filmstripThumbnails.putAll((0..40).associateWith { bitmap })
                tab.thumbnailCache = tab.filmstripThumbnails.snapshot()
                tab.fullSizeFrameBitmap = bitmap
                state.closeTab(0)
                assertTrue(tab.isClosed)
                assertTrue(tab.thumbnailCache.isEmpty())
                assertTrue(tab.filmstripThumbnails.snapshot().isEmpty())
                assertEquals(null, tab.fullSizeFrameBitmap)
                assertEquals(null, tab.backgroundTasks.submit { error("closed tab accepted work") })
            }
            return WeakReference(tab)
        }
        try {
            // Warm up native/JVM image libraries before taking the memory sample.
            repeat(3) { cycle() }
            System.gc()
            val before = ManagementFactory.getMemoryMXBean().heapMemoryUsage.used
            val closedTabs = List(20) { cycle() }
            onEdt { assertTrue(state.tabs.isEmpty()) }
            repeat(3) { System.gc(); Thread.sleep(100) }
            val after = ManagementFactory.getMemoryMXBean().heapMemoryUsage.used
            // GC samples are diagnostic, not a flaky assertion on collector scheduling.
            println("LIFECYCLE image cycles=20 heapBefore=$before heapAfter=$after retainedClosedTabs=${closedTabs.count { it.get() != null }}")
        } finally {
            onEdt { while (state.tabs.isNotEmpty()) state.closeTab(0) }
            file.delete()
        }
    }

    @Test
    fun `repeated tab close terminates silent frame analysis processes`() {
        val directory = Files.createTempDirectory("lifecycle-probe-").toFile()
        val fifo = directory.resolve("blocked.mp4")
        val baseline = ProcessManager.activeCount
        val state = onEdt { AppState() }
        val handles = mutableListOf<ProcessHandle>()
        try {
            assertEquals(0, ProcessBuilder("mkfifo", fifo.absolutePath).start().waitFor())
            repeat(10) {
                onEdt {
                    val tab = TabState(fifo)
                    state.tabs.add(tab)
                    state.analyzeFrames(tab)
                }
                var child: ProcessHandle? = null
                await("ffprobe startup") {
                    child = ProcessHandle.current().descendants().use { processes ->
                        processes.filter { it.info().arguments().orElse(emptyArray()).contains(fifo.absolutePath) }
                            .findFirst().orElse(null)
                    }
                    child != null
                }
                val probe = assertNotNull(child)
                handles.add(probe)
                onEdt { state.closeTab(0) }
                await("ffprobe termination") { !probe.isAlive && ProcessManager.activeCount == baseline }
                assertFalse(probe.isAlive)
            }
            println("LIFECYCLE probe cycles=10 survivingProcesses=${handles.count { it.isAlive }} trackedProcesses=${ProcessManager.activeCount}")
        } finally {
            onEdt { while (state.tabs.isNotEmpty()) state.closeTab(0) }
            handles.filter { it.isAlive }.forEach { it.destroyForcibly() }
            fifo.delete()
            directory.delete()
        }
    }
}
