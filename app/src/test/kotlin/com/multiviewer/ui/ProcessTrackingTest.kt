package com.multiviewer.ui

import com.multiviewer.cache.MediaIndexCache
import com.multiviewer.util.ProcessManager
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

// Verifies that the long-running ffmpeg/ffprobe invocations are tracked by ProcessManager, so the
// window-close handler and the JVM shutdown hook (both of which call ProcessManager.destroyAll())
// actually kill them instead of leaving zombies behind.
//
// Each test blocks its subject process on an unfed FIFO: opening a FIFO for reading blocks until a
// writer appears, so ffmpeg/ffprobe hangs deterministically rather than racing a real workload to
// completion (a full 1800-frame PSNR pass finishes in ~0.15s, far too fast to catch mid-run).
class ProcessTrackingTest {
    private fun makeFifo(suffix: String): File {
        val fifo = File.createTempFile("process-tracking-$suffix-", ".fifo")
        fifo.delete()
        ProcessBuilder("mkfifo", fifo.absolutePath).start().waitFor()
        fifo.deleteOnExit()
        return fifo
    }

    private fun generateTestClip(suffix: String): File {
        val file = File.createTempFile("process-tracking-$suffix-", ".mp4")
        file.deleteOnExit()
        ProcessBuilder(
            "ffmpeg", "-y", "-f", "lavfi", "-i", "testsrc=size=64x48:rate=10:duration=1",
            "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p", file.absolutePath,
        ).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start().waitFor()
        return file
    }

    private fun descendantPids(): Set<Long> =
        ProcessHandle.current().descendants().map { it.pid() }.toList().toSet()

    // Polls until a process that wasn't running before the subject started appears, returning its
    // handle. Fails the test if nothing shows up within the deadline.
    private fun awaitNewDescendant(before: Set<Long>, what: String): ProcessHandle {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            val candidate = ProcessHandle.current().descendants()
                .filter { it.pid() !in before }
                .filter { handle -> handle.info().command().orElse("").contains(what) }
                .findFirst()
            if (candidate.isPresent) return candidate.get()
            Thread.sleep(50)
        }
        fail("No new $what process appeared within 10s")
    }

    private fun assertDiesPromptly(handle: ProcessHandle) {
        val deadline = System.currentTimeMillis() + 3_000
        while (System.currentTimeMillis() < deadline && handle.isAlive) {
            Thread.sleep(50)
        }
        if (handle.isAlive) {
            handle.destroyForcibly()
            fail("Process ${handle.pid()} survived ProcessManager.destroyAll() -- it was never registered")
        }
        assertTrue(!handle.isAlive)
    }

    // Guards the premise that let these paths drop their deleteOnExit() fallback: each one deletes
    // its own temp files in a finally, so nothing is left behind even on the failure path. (Their
    // deleteOnExit() calls were removed because the JVM's DeleteOnExitHook set is never pruned, so
    // on repeatable decode paths it grew for the life of the process.)
    @Test
    fun `snapshot decode leaves no temp file behind even when ffmpeg fails`() {
        val tempDir = File(System.getProperty("java.io.tmpdir"))
        fun snapshotTemps() = tempDir.listFiles { f -> f.name.startsWith("ffmpeg-snapshot-") }?.size ?: 0
        val before = snapshotTemps()

        // A nonexistent input makes ffmpeg exit non-zero -- the failure path, where an early return
        // would strand the temp file if the cleanup weren't in a finally.
        val result = FfmpegImageSnapshotDecoder.decodeSingleFrameToBitmap(
            listOf(FfmpegLocator.ffmpegPath(), "-y", "-i", "/nonexistent/does-not-exist.mp4", "-frames:v", "1"),
            timeoutMs = 10_000L,
        )

        assertNull(result)
        assertEquals(before, snapshotTemps(), "decodeSingleFrameToBitmap stranded a temp file on the failure path")
    }

    // Closing a tab interrupts its background thread (see BackgroundTask.kt), which makes waitFor
    // throw InterruptedException long before the bounded wait is up. The pre-existing
    // `catch (e: Exception) { null }` swallowed that and returned without killing the ffmpeg, so the
    // 8s bound never applied to it either -- the process outlived the work that started it, with no
    // upper limit. The FIFO input makes that outcome deterministic: ffmpeg blocks on it forever, so
    // a surviving process is unambiguously a leak rather than one that just hadn't finished yet.
    @Test
    fun `runBoundedFfmpeg kills its process when the calling thread is interrupted`() {
        val fifo = makeFifo("interrupted")
        val out = File.createTempFile("process-tracking-interrupted-out-", ".bgra")
        out.deleteOnExit()
        val before = descendantPids()

        Thread.currentThread().interrupt()
        val finished = try {
            runBoundedFfmpeg(
                listOf(
                    FfmpegLocator.ffmpegPath(), "-y",
                    "-f", "rawvideo", "-pix_fmt", "yuv420p", "-s", "64x64", "-i", fifo.absolutePath,
                    "-f", "rawvideo", "-pix_fmt", "bgra", "-frames:v", "1", out.absolutePath,
                ),
            )
        } finally {
            Thread.interrupted() // clear the flag so it can't leak into the next test
        }

        assertTrue(!finished, "An interrupted run should report failure, not success")
        val survivors = ProcessHandle.current().descendants()
            .filter { it.pid() !in before }
            .filter { it.info().command().orElse("").contains("ffmpeg") }
            .toList()
        survivors.forEach { it.destroyForcibly() }
        assertTrue(survivors.isEmpty(), "Interrupted run left ${survivors.size} ffmpeg process(es) blocked on the FIFO forever")
        fifo.delete(); out.delete()
    }

    @Test
    fun `runPsnrPass ffmpeg process is killed by ProcessManager destroyAll`() {
        val comparison = generateTestClip("psnr-cmp")
        val reference = makeFifo("psnr-ref")
        val before = descendantPids()

        Thread {
            runPsnrPass(comparison, reference, onProgress = { _, _ -> }, isCancelled = { false })
        }.apply { isDaemon = true }.start()

        val ffmpeg = awaitNewDescendant(before, "ffmpeg")
        ProcessManager.destroyAll()

        assertDiesPromptly(ffmpeg)
        comparison.delete(); reference.delete()
    }

    // Closing a tab mid-scan must actually stop the scan: runInBackground's pool has only 2 threads,
    // so a scan nobody is watching any more holds one of them (and its ffprobe) until the whole file
    // is read -- head-of-line blocking every other tab's background work behind a dead tab.
    @Test
    fun `probeFrameTypesStreaming stops early and kills its ffprobe once isCancelled turns true`() {
        val video = File.createTempFile("process-tracking-cancel-", ".mp4")
        video.deleteOnExit()
        ProcessBuilder(
            "ffmpeg", "-y", "-f", "lavfi", "-i", "testsrc=size=64x48:rate=10:duration=2",
            video.absolutePath,
        ).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start().waitFor()
        val before = descendantPids()

        val collected = mutableListOf<FrameAnalysisProgress>()
        runBlocking {
            probeFrameTypesStreaming(video, isCancelled = { true }).collect { collected.add(it) }
        }

        assertTrue(collected.isEmpty(), "A cancelled scan should emit nothing, got ${collected.size} emissions")
        // A partial frame list must never be cached -- a later scan would read it back as complete.
        assertNull(MediaIndexCache.get(video), "A cancelled scan must not poison the index cache")
        val survivors = ProcessHandle.current().descendants()
            .filter { it.pid() !in before }
            .filter { it.info().command().orElse("").contains("ffprobe") }
            .toList()
        assertTrue(survivors.isEmpty(), "Cancelled scan left ${survivors.size} ffprobe process(es) running")
        video.delete()
    }

    @Test
    fun `probeFrameTypesStreaming ffprobe process is killed by ProcessManager destroyAll`() {
        val fifo = makeFifo("frame-types")
        val before = descendantPids()

        Thread {
            runBlocking { probeFrameTypesStreaming(fifo).collect { } }
        }.apply { isDaemon = true }.start()

        val ffprobe = awaitNewDescendant(before, "ffprobe")
        ProcessManager.destroyAll()

        assertDiesPromptly(ffprobe)
        fifo.delete()
    }
}
