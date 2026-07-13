package com.multiviewer.parser

import kotlin.test.Test
import kotlin.test.assertEquals

class IspeBoxDecoderTest {
    @Test
    fun `decodes image_width and image_height`() {
        val body = byteArrayOf(
            0x00, 0x00, 0x00, 0x00, // version/flags
            0x00, 0x00, 0x0F, 0x00, // image_width = 3840
            0x00, 0x00, 0x08, 0x70, // image_height = 2160
        )
        val reader = byteReaderOf(body)
        val node = IspeBoxDecoder.decode(reader, "ispe", 0, 0, body.size.toLong(), emptyList())
        assertEquals("3840x2160", node.summary)
        reader.close()
    }
}
