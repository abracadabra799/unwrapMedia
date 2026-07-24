package com.multiviewer.ui

import androidx.compose.ui.graphics.ImageBitmap
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VlcImageSnapshotDecoderTest {
    @Test
    fun `decodeFirstFrameAsync delivers null for a file VLC cannot decode, within the timeout window`() {
        val file = File.createTempFile("vlc-snapshot-garbage-test", ".heic")
        file.deleteOnExit()
        file.writeBytes(ByteArray(300)) // not a real media file — VLC will never produce a frame

        val latch = CountDownLatch(1)
        var result: ImageBitmap? = ImageBitmap(1, 1) // sentinel, overwritten by onResult

        VlcImageSnapshotDecoder.decodeFirstFrameAsync(file) { bitmap ->
            result = bitmap
            latch.countDown()
        }

        val delivered = latch.await(7, TimeUnit.SECONDS)
        assertTrue(delivered, "onResult was not called within 7 seconds")
        assertEquals(null, result)
    }
}
