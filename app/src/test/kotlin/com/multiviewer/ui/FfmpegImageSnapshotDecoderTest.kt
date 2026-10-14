package com.multiviewer.ui

import androidx.compose.ui.graphics.ImageBitmap
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import java.nio.file.Files

class FfmpegImageSnapshotDecoderTest {
    @Test
    fun `cancelled first-frame fallback terminates ffmpeg and suppresses callback`() {
        if (System.getProperty("os.name").lowercase().contains("win")) return
        val directory = Files.createTempDirectory("snapshot-cancel-").toFile()
        val fifo = directory.resolve("blocked.mp4")
        val callback = CountDownLatch(1)
        try {
            assertEquals(0, ProcessBuilder("mkfifo", fifo.absolutePath).start().waitFor())
            val job = assertNotNull(FfmpegImageSnapshotDecoder.decodeFirstFrameAsync(fifo) { callback.countDown() })
            var child: ProcessHandle? = null
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (child == null && System.nanoTime() < deadline) {
                child = ProcessHandle.current().descendants().use { stream ->
                    stream.filter { it.info().arguments().orElse(emptyArray()).contains(fifo.absolutePath) }
                        .findFirst().orElse(null)
                }
                if (child == null) Thread.sleep(10)
            }
            val process = assertNotNull(child, "ffmpeg did not start")
            job.cancel(true)
            while (process.isAlive && System.nanoTime() < deadline) Thread.sleep(10)
            assertFalse(process.isAlive, "cancelled ffmpeg survived")
            assertFalse(callback.await(300, TimeUnit.MILLISECONDS), "cancelled decode posted a result")
        } finally {
            fifo.delete()
            directory.delete()
        }
    }

    @Test
    fun `thumbnail request registry notifies every listener waiting for the same file`() {
        val registry = ThumbnailRequestRegistry<String>()
        val delivered = mutableListOf<String>()

        assertTrue(registry.add("photo.heic") { delivered += "first:$it" })
        assertTrue(!registry.add("photo.heic") { delivered += "second:$it" })

        registry.complete("photo.heic", "thumbnail").forEach { it() }

        assertEquals(listOf("first:thumbnail", "second:thumbnail"), delivered)
    }

    @Test
    fun `thumbnail decode uses a complex filter so HEIC display transforms can coexist`() {
        val args = FfmpegImageSnapshotDecoder.thumbnailDecodeArguments(File("photo.heic"), longestEdge = 240)

        assertTrue("-filter_complex" in args)
        assertTrue("[0:v]scale=240:240:force_original_aspect_ratio=decrease[thumbnail]" in args)
        assertTrue("-map" in args)
        assertTrue("[thumbnail]" in args)
        assertTrue("-vf" !in args)
    }

    @Test
    fun `decodeFirstFrameAsync delivers null for a file ffmpeg cannot decode, within the timeout window`() {
        val file = File.createTempFile("ffmpeg-snapshot-garbage-test", ".heic")
        file.deleteOnExit()
        file.writeBytes(ByteArray(300)) // not a real media file — ffmpeg will exit non-zero quickly

        val latch = CountDownLatch(1)
        var result: ImageBitmap? = ImageBitmap(1, 1) // sentinel, overwritten by onResult

        FfmpegImageSnapshotDecoder.decodeFirstFrameAsync(file) { bitmap ->
            result = bitmap
            latch.countDown()
        }

        val delivered = latch.await(10, TimeUnit.SECONDS)
        assertTrue(delivered, "onResult was not called within 10 seconds")
        assertEquals(null, result)
    }
}
