package com.multiviewer.parser

import kotlin.test.Test
import kotlin.test.assertNotEquals

class DecodersRegistrationTest {
    @Test
    fun `all box-detail-parsing decoders are registered, not falling back to LeafBoxDecoder`() {
        registerAllDecoders()
        val typesThatMustHaveADecoder = listOf(
            "avc1", "hvc1", "av01", "mp4a", "avcC", "hvcC", "elst",
            "dref", "url ", "urn ", "colr", "pasp", "iinf", "infe", "mpvd", "sefd", "iloc",
        )
        for (type in typesThatMustHaveADecoder) {
            assertNotEquals(LeafBoxDecoder, BoxRegistry.decoderFor(type), "type \"$type\" fell back to LeafBoxDecoder")
        }
    }
}
