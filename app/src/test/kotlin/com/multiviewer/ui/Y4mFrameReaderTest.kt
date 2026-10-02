package com.multiviewer.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

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
}
