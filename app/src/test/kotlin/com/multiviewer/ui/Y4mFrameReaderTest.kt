package com.multiviewer.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import java.io.ByteArrayInputStream

class Y4mFrameReaderTest {
    @Test
    fun `parses supported 8 bit 420 header`() {
        val format = parseY4mHeader("YUV4MPEG2 W1920 H1080 F30000:1001 Ip A0:0 C420jpeg")
        assertEquals(1920, format.width)
        assertEquals(1080, format.height)
        assertEquals(8, format.bitDepth)
    }

    @Test
    fun `accepts 10 bit 420 output`() {
        assertEquals(10, parseY4mHeader("YUV4MPEG2 W8 H8 F24:1 C420p10").bitDepth)
    }

    @Test
    fun `rejects unsupported chroma`() {
        assertFailsWith<IllegalArgumentException> { parseY4mHeader("YUV4MPEG2 W8 H8 F24:1 C444") }
    }

    @Test
    fun `reads one 420 frame payload`() {
        val format = parseY4mHeader("YUV4MPEG2 W2 H2 F24:1 C420")
        val input = ByteArrayInputStream("FRAME\n123456\n".toByteArray())
        assertEquals(6, readY4mFrame(input, format)!!.size)
    }

    @Test
    fun `streams frames through format and frame callbacks`() {
        val input = ByteArrayInputStream("YUV4MPEG2 W2 H2 F24:1 C420\nFRAME\n123456".toByteArray())
        var seen = 0
        assertEquals(true, streamY4mFrames(input, { assertEquals(2, it.width) }) { _, frame ->
            seen = frame.size
            true
        })
        assertEquals(6, seen)
    }
}
