package com.multiviewer.parser

import kotlin.test.Test
import kotlin.test.assertEquals

class MdhdBoxDecoderTest {
    @Test
    fun `decodes timescale, duration and packed ISO-639-2 language`() {
        // "und" (undetermined) = 0x55C4 packed: u=0x15, n=0x0E, d=0x04 -> ((0x15<<10)|(0x0E<<5)|0x04)
        val packed = ((('u'.code - 0x60) and 0x1F) shl 10) or ((('n'.code - 0x60) and 0x1F) shl 5) or (('d'.code - 0x60) and 0x1F)
        val body = byteArrayOf(
            0x00, 0x00, 0x00, 0x00, // version 0, flags 0
            0x00, 0x00, 0x00, 0x00, // creation_time
            0x00, 0x00, 0x00, 0x00, // modification_time
            0x00, 0x00, 0x03, 0xE8.toByte(), // timescale = 1000
            0x00, 0x00, 0x07, 0xD0.toByte(), // duration = 2000
            ((packed shr 8) and 0xFF).toByte(), (packed and 0xFF).toByte(), // language
            0x00, 0x00, // pre_defined
        )
        val reader = byteReaderOf(body)
        val node = MdhdBoxDecoder.decode(reader, "mdhd", 0, 0, body.size.toLong(), emptyList())
        assertEquals("timescale=1000, duration=2.000s, language=und", node.summary)
        reader.close()
    }
}
