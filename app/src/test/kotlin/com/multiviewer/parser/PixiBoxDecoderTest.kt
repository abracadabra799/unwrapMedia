package com.multiviewer.parser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PixiBoxDecoderTest {
    @Test
    fun `decodes 3 channels of 8-bit each`() {
        val body = byteArrayOf(
            0x00, 0x00, 0x00, 0x00, // version/flags
            0x03, // num_channels
            0x08, 0x08, 0x08, // bits_per_channel
        )
        val reader = byteReaderOf(body)
        val node = PixiBoxDecoder.decode(reader, "pixi", 0, 0, body.size.toLong(), emptyList())
        assertEquals("8, 8, 8", node.fields.first { it.name == "bits_per_channel" }.value)
        assertEquals("8-bit, 8-bit, 8-bit", node.summary)
        reader.close()
    }

    @Test
    fun `a declared channel count exceeding the remaining bytes produces a warning and no fields`() {
        val body = byteArrayOf(
            0x00, 0x00, 0x00, 0x00, // version/flags
            0x05, // num_channels = 5, but only 1 byte follows
            0x0A,
        )
        val reader = byteReaderOf(body)
        val node = PixiBoxDecoder.decode(reader, "pixi", 0, 0, body.size.toLong(), emptyList())
        assertTrue(node.warnings.isNotEmpty())
        assertTrue(node.fields.isEmpty())
        reader.close()
    }
}
