package com.multiviewer.parser

import kotlin.test.Test
import kotlin.test.assertEquals

class HdlrBoxDecoderTest {
    @Test
    fun `decodes handler_type and name`() {
        val body = byteArrayOf(
            0x00, 0x00, 0x00, 0x00, // version/flags
            0x00, 0x00, 0x00, 0x00, // pre_defined
            0x76, 0x69, 0x64, 0x65, // handler_type "vide"
            0x00, 0x00, 0x00, 0x00, // reserved[0]
            0x00, 0x00, 0x00, 0x00, // reserved[1]
            0x00, 0x00, 0x00, 0x00, // reserved[2]
            0x56, 0x69, 0x64, 0x65, 0x6F, 0x00, // name "Video\0"
        )
        val reader = byteReaderOf(body)
        val node = HdlrBoxDecoder.decode(reader, "hdlr", 0, 0, body.size.toLong(), emptyList())
        assertEquals("vide: Video", node.summary)
        reader.close()
    }
}
