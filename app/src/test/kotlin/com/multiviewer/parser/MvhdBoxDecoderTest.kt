package com.multiviewer.parser

import kotlin.test.Test
import kotlin.test.assertEquals

class MvhdBoxDecoderTest {
    @Test
    fun `decodes version 0 timescale and duration`() {
        val reader = byteReaderOf(
            byteArrayOf(
                0x00, 0x00, 0x00, 0x00, // version 0, flags 0
                0x00, 0x00, 0x00, 0x00, // creation_time
                0x00, 0x00, 0x00, 0x00, // modification_time
                0x00, 0x00, 0x02, 0x58, // timescale = 600
                0x00, 0x00, 0x04, 0xB0.toByte(), // duration = 1200
            )
        )
        val node = MvhdBoxDecoder.decode(reader, "mvhd", 0, 0, 20, emptyList())
        assertEquals("timescale=600, duration=2.000s", node.summary)
        reader.close()
    }

    @Test
    fun `too short for declared version fields produces a warning`() {
        val reader = byteReaderOf(byteArrayOf(0x00, 0x00, 0x00, 0x00, 0x00, 0x00))
        val node = MvhdBoxDecoder.decode(reader, "mvhd", 0, 0, 6, emptyList())
        assertEquals(1, node.warnings.size)
        reader.close()
    }
}
