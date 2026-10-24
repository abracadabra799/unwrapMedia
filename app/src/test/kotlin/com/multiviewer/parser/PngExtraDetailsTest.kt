package com.multiviewer.parser

import java.io.File
import kotlin.test.*

internal fun pngDetailChunk(type: String, data: ByteArray): ByteArray =
    byteArrayOf((data.size ushr 24).toByte(), (data.size ushr 16).toByte(), (data.size ushr 8).toByte(), data.size.toByte()) + type.toByteArray(Charsets.ISO_8859_1) + data + ByteArray(4)
internal fun pngDetailNodes(bytes: ByteArray): List<BoxNode> = byteReaderOf(bytes).use { parsePngChunks(it, 0, it.length) }
internal fun pngDetailValue(n: BoxNode, name: String): BoxField = assertNotNull(n.fields.find { it.name == name }, "${n.type}: $name")
internal fun pngDetailFile(name: String): List<BoxNode> = ByteReader.open(File("src/test/resources/png-details/$name")).use { parsePngChunks(it, 8, it.length) }

class PngExtraDetailsTest {
    @Test fun `actual APNG exposes controls frame delay and compressed data boundaries`() {
        val nodes = pngDetailFile("animation.png")
        val actl = nodes.first { it.type == "acTL" }
        assertEquals("2", pngDetailValue(actl, "num_frames").value)
        assertEquals("3", pngDetailValue(actl, "num_plays").value)
        val second = nodes.filter { it.type == "fcTL" }[1]
        assertEquals("1", pngDetailValue(second, "sequence_number").value)
        assertEquals("250", pngDetailValue(second, "delay_num").value)
        assertEquals("1000", pngDetailValue(second, "delay_den").value)
        assertEquals("1", pngDetailValue(second, "dispose_op").value)
        assertEquals("1", pngDetailValue(second, "blend_op").value)
        assertEquals(154L, pngDetailValue(second, "delay_num").offset)
        val data = nodes.first { it.type == "fdAT" }
        assertEquals("2", pngDetailValue(data, "sequence_number").value)
        assertEquals(176L, pngDetailValue(data, "frame_data").offset)
        assertEquals(23L, pngDetailValue(data, "frame_data").length)
        assertEquals(ValueInterpretation.UNINTERPRETED, detailInterpretation(data))
        assertEquals(ValueInterpretation.PARSED, detailInterpretation(nodes.last()))
    }
    @Test fun `HDR metadata uses PNG RGB order and fractional luminance units`() {
        val nodes = pngDetailFile("hdr-metadata.png")
        val cicp = nodes.first { it.type == "cICP" }
        assertEquals("9", pngDetailValue(cicp, "color_primaries").value)
        assertEquals("16", pngDetailValue(cicp, "transfer_characteristics").value)
        val mdcv = nodes.first { it.type == "mDCV" }
        assertEquals("0.68", pngDetailValue(mdcv, "red_x").value)
        assertEquals("0.265", pngDetailValue(mdcv, "green_x").value)
        assertEquals("1000.0000", pngDetailValue(mdcv, "max_luminance_cd_m2").value)
        assertEquals("0.0050", pngDetailValue(mdcv, "min_luminance_cd_m2").value)
        assertEquals(77L, pngDetailValue(mdcv, "min_luminance_cd_m2").offset)
        val clli = nodes.first { it.type == "cLLI" }
        assertEquals("400.0000", pngDetailValue(clli, "max_frame_average_light_level_cd_m2").value)
        assertEquals(4L, pngDetailValue(clli, "max_content_light_level_cd_m2").length)
    }
    @Test fun `zero denominator gets default while zero light level remains unknown`() {
        val control = hex("0000000000000001000000010000000000000000000100000000")
        val n = pngDetailNodes(pngDetailChunk("fcTL", control)).single()
        assertEquals("100", pngDetailValue(n, "effective_delay_den").value)
        assertEquals("0", pngDetailValue(n, "delay_den").value)
        val clli = pngDetailNodes(pngDetailChunk("cLLI", ByteArray(8))).single()
        assertTrue(pngDetailValue(clli, "max_content_light_level_cd_m2").value.contains("Unknown"))
    }
    @Test fun `fixed headers cannot borrow CRC bytes or accept trailing payload`() {
        for ((type, size) in listOf("acTL" to 8, "fcTL" to 26, "cICP" to 4, "mDCV" to 24, "cLLI" to 8)) {
            for (length in 0 until size) {
                val node = pngDetailNodes(pngDetailChunk(type, ByteArray(length))).single()
                assertEquals(ValueInterpretation.UNPARSABLE, detailInterpretation(node), "$type/$length")
                assertTrue(node.fields.all { it.offset + it.length <= 8L + length })
            }
            assertEquals(ValueInterpretation.UNPARSABLE, detailInterpretation(pngDetailNodes(pngDetailChunk(type, ByteArray(size+1))).single()))
        }
        assertEquals(ValueInterpretation.UNPARSABLE, detailInterpretation(pngDetailNodes(pngDetailChunk("fdAT", ByteArray(3))).single()))
        assertEquals(ValueInterpretation.PARSED, detailInterpretation(pngDetailNodes(pngDetailChunk("fdAT", ByteArray(4))).single()))
    }
    @Test fun `reserved animation operators retain numbers with uninterpreted status`() {
        val bytes = ByteArray(26).also { it[24]=3;it[25]=2 }
        val node = pngDetailNodes(pngDetailChunk("fcTL",bytes)).single()
        assertEquals("3",pngDetailValue(node,"dispose_op").value)
        assertEquals(ValueInterpretation.UNINTERPRETED,pngDetailValue(node,"dispose_op").interpretation)
        assertEquals(ValueInterpretation.UNINTERPRETED,pngDetailValue(node,"blend_op").interpretation)
    }
}
