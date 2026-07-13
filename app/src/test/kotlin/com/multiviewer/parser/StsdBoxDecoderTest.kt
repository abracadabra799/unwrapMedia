package com.multiviewer.parser

import kotlin.test.Test
import kotlin.test.assertEquals

class StsdBoxDecoderTest {
    @Test
    fun `stsd skips version, flags and entry_count before recursing into sample entries`() {
        BoxRegistry.register("stsd", ContainerBoxDecoder(childOffsetInPayload = 8, summarize = true))
        val reader = byteReaderOf(
            byteArrayOf(
                0x00, 0x00, 0x00, 0x1C, 0x73, 0x74, 0x73, 0x64, // "stsd", size 28
                0x00, 0x00, 0x00, 0x00,                         // version/flags
                0x00, 0x00, 0x00, 0x01,                         // entry_count = 1
                0x00, 0x00, 0x00, 0x0C, 0x68, 0x76, 0x63, 0x31, // sample entry "hvc1", size 12
                0x00, 0x00, 0x00, 0x00,                         // (dummy payload to reach size 12)
            )
        )
        val boxes = parseBoxes(reader, 0, reader.length)
        assertEquals(1, boxes[0].children.size)
        assertEquals("hvc1", boxes[0].children[0].type)
        assertEquals("1 entries", boxes[0].summary)
        reader.close()
    }
}
