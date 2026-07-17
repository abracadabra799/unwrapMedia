package com.multiviewer.parser

import kotlin.test.Test
import kotlin.test.assertEquals

class DrefBoxDecoderTest {
    @Test
    fun `dref skips version, flags and entry_count before recursing into data entries`() {
        BoxRegistry.register("dref", ContainerBoxDecoder(childOffsetInPayload = 8, summarize = true))
        val reader = byteReaderOf(
            byteArrayOf(
                0x00, 0x00, 0x00, 0x1C, 0x64, 0x72, 0x65, 0x66, // "dref", size 28
                0x00, 0x00, 0x00, 0x00,                         // version/flags
                0x00, 0x00, 0x00, 0x01,                         // entry_count = 1
                0x00, 0x00, 0x00, 0x0C, 0x75, 0x72, 0x6C, 0x20, // "url ", size 12
                0x00, 0x00, 0x00, 0x01,                         // url version/flags = self-contained
            )
        )
        val boxes = parseBoxes(reader, 0, reader.length)
        assertEquals(1, boxes[0].children.size)
        assertEquals("url ", boxes[0].children[0].type)
        assertEquals("1 entry", boxes[0].summary)
        reader.close()
    }
}
