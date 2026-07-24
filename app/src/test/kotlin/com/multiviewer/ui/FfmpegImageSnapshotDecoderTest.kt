package com.multiviewer.ui

import androidx.compose.ui.graphics.ImageBitmap
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FfmpegImageSnapshotDecoderTest {
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
