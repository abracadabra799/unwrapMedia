package com.multiviewer.parser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Av2CBoxDecoderTest {
    private fun decode(payload: ByteArray): BoxNode {
        val headerSize = 8
        val file = fileOf(ByteArray(headerSize) + payload)
        return ByteReader.open(file).use { reader ->
            Av2CBoxDecoder.decode(reader, "av2C", 0, headerSize, (headerSize + payload.size).toLong(), emptyList())
        }
    }

    private fun fieldValue(node: BoxNode, name: String): String? = node.fields.find { it.name == name }?.value

    @Test
    fun `decode exposes each bounded AV2 configuration OBU with its exact range`() {
        // reserved=0, count-minus-one=1, then a one-byte Sequence Header OBU and a two-byte
        // Layer Configuration Record OBU whose extension identifies embedded layer 5 / xlayer 17.
        val node = decode(byteArrayOf(0, 1, 1, 0x04, 2, 0xc3.toByte(), 0xb1.toByte()))

        assertEquals("0", fieldValue(node, "reserved"))
        assertEquals("1", fieldValue(node, "config_obus_count_minus1"))
        assertEquals(2, node.children.size)
        assertEquals("OBU_SEQUENCE_HEADER", node.children[0].type)
        assertEquals(11L, node.children[0].offset)
        assertEquals(1L, node.children[0].size)
        assertEquals("1", fieldValue(node.children[0], "declared_size"))
        assertEquals("OBU_LAYER_CONFIGURATION_RECORD", node.children[1].type)
        assertEquals(13L, node.children[1].offset)
        assertEquals(2L, node.children[1].size)
        assertEquals("5", fieldValue(node.children[1], "embedded_layer_id"))
        assertEquals("17", fieldValue(node.children[1], "extended_layer_id"))
    }

    @Test
    fun `decode retains an unknown valid AV2 OBU`() {
        val node = decode(byteArrayOf(0, 1, 1, 0x04, 1, 0x78)) // required sequence header, then type 30

        assertEquals(2, node.children.size)
        assertEquals("unknown(30)", node.children.last().type)
        assertTrue(node.warnings.isEmpty())
    }

    @Test
    fun `decode reports malformed AV2 configuration framing without throwing`() {
        val shortPrefix = decode(byteArrayOf(0))
        val truncatedLeb128 = decode(byteArrayOf(0, 0, 0x80.toByte()))
        val oversizedObu = decode(byteArrayOf(0, 0, 2, 0x04))
        val truncatedHeader = decode(byteArrayOf(0, 0, 1, 0x80.toByte()))

        assertTrue(shortPrefix.warnings.any { it.contains("too short") })
        assertTrue(truncatedLeb128.warnings.any { it.contains("leb128") })
        assertTrue(oversizedObu.warnings.any { it.contains("extends") })
        assertTrue(truncatedHeader.warnings.any { it.contains("truncated") })
    }

    @Test
    fun `decode warns when AV2 configuration violates reserved and required OBU rules`() {
        val node = decode(byteArrayOf(1, 0, 1, 0x08)) // nonzero reserved, only forbidden temporal delimiter

        assertTrue(node.warnings.any { it.contains("reserved") })
        assertTrue(node.warnings.any { it.contains("forbidden") })
        assertTrue(node.warnings.any { it.contains("must contain") })
    }
}
