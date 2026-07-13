package com.multiviewer.parser

import kotlin.test.Test
import kotlin.test.assertEquals

class StszBoxDecoderTest {
    @Test
    fun `uniform sample size is reported as fields, not a table`() {
        val body = byteArrayOf(
            0x00, 0x00, 0x00, 0x00, // version/flags
            0x00, 0x00, 0x04, 0x00, // sample_size = 1024 (uniform)
            0x00, 0x00, 0x00, 0x05, // sample_count = 5
        )
        val reader = byteReaderOf(body)
        val node = StszBoxDecoder().decode(reader, "stsz", 0, 0, body.size.toLong(), emptyList())
        assertEquals("5 samples, uniform size 1024", node.summary)
        assertEquals(null, node.table)
        reader.close()
    }

    @Test
    fun `sample_size 0 means variable sizes follow as a table`() {
        val body = byteArrayOf(
            0x00, 0x00, 0x00, 0x00, // version/flags
            0x00, 0x00, 0x00, 0x00, // sample_size = 0 (variable)
            0x00, 0x00, 0x00, 0x02, // sample_count = 2
            0x00, 0x00, 0x01, 0x00, // size[0] = 256
            0x00, 0x00, 0x02, 0x00, // size[1] = 512
        )
        val reader = byteReaderOf(body)
        val node = StszBoxDecoder().decode(reader, "stsz", 0, 0, body.size.toLong(), emptyList())
        assertEquals("2 entries (variable size)", node.summary)
        assertEquals(listOf(listOf(256L), listOf(512L)), node.table?.rows)
        reader.close()
    }
}
