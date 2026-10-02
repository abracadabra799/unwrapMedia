package com.multiviewer.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class Av2FrameBitmapTest {
    @Test
    fun `large yuv420 frames are reduced to a 1280 pixel display edge`() {
        val format = Y4mFormat(2560, 2, 30, 1, "420", 8)
        val frame = Av2DecodedFrame(format, ByteArray(7680), 7)

        val displayFrame = frame.toDisplayFrame()

        assertEquals(1280, displayFrame.format.width)
        assertEquals(1, displayFrame.format.height)
        assertEquals(2560, displayFrame.yuv420Payload.size)
        assertEquals(7L, displayFrame.frameIndex)
    }
}
