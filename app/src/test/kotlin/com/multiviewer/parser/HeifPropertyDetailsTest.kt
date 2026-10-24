package com.multiviewer.parser

import java.io.File
import kotlin.test.*

class HeifPropertyDetailsTest {
    private fun decode(type: String, bytes: ByteArray, size: Long = bytes.size.toLong()): BoxNode =
        byteReaderOf(bytes).use {
            registerAllDecoders()
            BoxRegistry.decoderFor(type).decode(it, type, 0, 0, size, emptyList())
        }
    @Test fun `real HEIC exposes full headers and channel count with byte ranges`() {
        val root = parseFile(File("src/test/resources/c2pa/s26-motion-photo.heic"))
        val pixi = assertNotNull(findFirst(root) { it.type == "pixi" })
        val count = assertNotNull(pixi.fields.find { it.name == "num_channels" })
        assertEquals("3", count.value)
        assertEquals(3016172L, count.offset)
        assertEquals("8, 8, 8", pixi.fields.first { it.name == "bits_per_channel" }.value)
        val aux = assertNotNull(findFirst(root) { it.type == "auxC" })
        assertEquals("0", aux.fields.find { it.name == "version" }?.value)
        assertEquals("urn:com:samsung:photo:2024:aux:hdrgainmap", aux.fields.first { it.name == "aux_type" }.value)
    }
    @Test fun `auxiliary subtype bytes are visible without invented semantics`() {
        val node = decode("auxC", hex("00000000 61626300 01ff02"))
        val field = assertNotNull(node.fields.find { it.name == "aux_subtype" })
        assertEquals("01 ff 02", field.value)
        assertEquals(8L, field.offset)
        assertEquals(3L, field.length)
    }
    @Test fun `malformed properties warn and retain available fields`() {
        val short = decode("pixi", hex("00000000 03 08"))
        assertEquals("3", short.fields.find { it.name == "num_channels" }?.value)
        assertTrue(short.warnings.isNotEmpty())
        assertTrue(decode("auxC", hex("00000000 616263")).warnings.isNotEmpty())
        for (type in listOf("pixi", "auxC")) {
            assertTrue(decode(type, hex("01000000 00")).warnings.isNotEmpty())
            assertTrue(decode(type, hex("00000000 00"), 100).warnings.isNotEmpty())
        }
    }
    @Test fun `large auxiliary payloads have bounded previews and explicit omitted ranges`() {
        val node = decode("auxC", hex("00000000 6100") + ByteArray(5000) { 0x12 })
        val subtype = node.fields.first { it.name == "aux_subtype" }
        assertEquals(4096L, subtype.length)
        assertEquals("5000", node.fields.first { it.name == "aux_subtype_length" }.value)
        val omitted = node.fields.first { it.name == "unshown_subtype_bytes" }
        assertEquals("904", omitted.value)
        assertEquals(4102L, omitted.offset)
        assertEquals(904L, omitted.length)
        val longType = decode("auxC", hex("00000000") + ByteArray(5000) { 65 })
        assertTrue(longType.warnings.isNotEmpty())
        assertTrue(longType.fields.none { it.name == "aux_type" })
        assertEquals("904", longType.fields.first { it.name == "unparsed_payload_bytes" }.value)
    }

}
