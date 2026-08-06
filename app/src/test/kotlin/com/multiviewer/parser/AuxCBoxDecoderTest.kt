package com.multiviewer.parser

import kotlin.test.Test
import kotlin.test.assertEquals

class AuxCBoxDecoderTest {
    @Test
    fun `decodes a null-terminated aux_type string`() {
        val auxType = "urn:mpeg:mpegB:cicp:systems:auxiliary:alpha".toByteArray(Charsets.US_ASCII)
        val body = byteArrayOf(0x00, 0x00, 0x00, 0x00) + auxType + byteArrayOf(0x00)
        val reader = byteReaderOf(body)
        val node = AuxCBoxDecoder.decode(reader, "auxC", 0, 0, body.size.toLong(), emptyList())
        assertEquals("urn:mpeg:mpegB:cicp:systems:auxiliary:alpha", node.fields.first { it.name == "aux_type" }.value)
        reader.close()
    }
}
