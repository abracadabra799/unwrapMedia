package com.multiviewer.parser

import kotlin.test.*

class PngColorDetailsTest {
    private fun context(color: Int, depth: Int = 8) = pngDetailChunk("IHDR", hex("0000000200000002") + byteArrayOf(depth.toByte(), color.toByte(), 0, 0, 0))
    private fun node(type: String, bytes: ByteArray, color: Int? = null): BoxNode =
        pngDetailNodes((color?.let { context(it) } ?: byteArrayOf()) + pngDetailChunk(type, bytes)).last()

    @Test fun `indexed PNG exposes every transparency background histogram and significant bit value`() {
        val nodes = pngDetailFile("palette.png")
        val trns = nodes.first { it.type == "tRNS" }
        assertEquals(listOf("0", "64", "128", "255"), trns.fields.map { it.value })
        assertEquals(120L, pngDetailValue(trns, "alpha[2]").offset)
        assertEquals("2", pngDetailValue(nodes.first { it.type == "bKGD" }, "palette_index").value)
        val histogram = nodes.first { it.type == "hIST" }
        assertEquals("40", pngDetailValue(histogram, "frequency[3]").value)
        assertEquals(153L, pngDetailValue(histogram, "frequency[3]").offset)
        val significant = nodes.first { it.type == "sBIT" }
        assertEquals("6", pngDetailValue(significant, "green_bits").value)
        assertEquals(ValueInterpretation.PARSED, detailInterpretation(significant))
    }
    @Test fun `grayscale RGB and alpha color contexts select the correct layout`() {
        val gray = pngDetailFile("gray.png")
        assertEquals("7", pngDetailValue(gray.first { it.type == "tRNS" }, "gray_sample").value)
        assertEquals("17", pngDetailValue(gray.first { it.type == "bKGD" }, "gray_sample").value)
        val rgb = pngDetailFile("rgb.png")
        assertEquals("3", pngDetailValue(rgb.first { it.type == "tRNS" }, "blue_sample").value)
        assertEquals("30", pngDetailValue(rgb.first { it.type == "bKGD" }, "blue_sample").value)
        assertEquals("5", pngDetailValue(node("sBIT",hex("08070605"),6),"alpha_bits").value)
        assertEquals("7", pngDetailValue(node("sBIT",hex("0807"),4),"alpha_bits").value)
    }
    @Test fun `missing or forbidden context and invalid lengths are not silently parsed`() {
        assertEquals(ValueInterpretation.UNINTERPRETED,detailInterpretation(node("tRNS",hex("0001"))))
        for (color in listOf(4,6)) assertEquals(ValueInterpretation.UNPARSABLE,detailInterpretation(node("tRNS",hex("0001"),color)))
        for ((type,bytes,color) in listOf(Triple("tRNS",ByteArray(4),2),Triple("bKGD",ByteArray(2),3),Triple("sBIT",ByteArray(3),6),Triple("sBIT",hex("00"),0))) {
            assertEquals(ValueInterpretation.UNPARSABLE,detailInterpretation(node(type,bytes,color)),"$type/$color")
        }
        val palette = pngDetailChunk("PLTE",hex("000000ffffff"))
        val excessAlpha = pngDetailNodes(context(3)+palette+pngDetailChunk("tRNS",ByteArray(3))).last()
        assertEquals(ValueInterpretation.UNPARSABLE,detailInterpretation(excessAlpha))
        val shortHistogram = pngDetailNodes(context(3)+palette+pngDetailChunk("hIST",ByteArray(2))).last()
        assertEquals(ValueInterpretation.UNPARSABLE,detailInterpretation(shortHistogram))
    }
    @Test fun `indexed values remain visible but uninterpreted without a valid palette`() {
        for (palette in listOf(byteArrayOf(), pngDetailChunk("PLTE",ByteArray(2)))) {
            for (type in listOf("tRNS","bKGD")) {
                val value = if(type=="tRNS") 0 else 255
                val n = pngDetailNodes(context(3)+palette+pngDetailChunk(type,byteArrayOf(value.toByte()))).last()
                assertEquals(ValueInterpretation.UNINTERPRETED,detailInterpretation(n))
                assertEquals(value.toString(),pngDetailValue(n,if(type=="tRNS") "alpha[0]" else "palette_index").value)
                assertTrue(n.warnings.isNotEmpty())
            }
        }
    }
    @Test fun `suggested 16 bit palette preserves sample values frequencies and source ranges`() {
        val palette = pngDetailFile("palette.png").first { it.type == "sPLT" }
        assertEquals("Wide",pngDetailValue(palette,"palette_name").value)
        assertEquals("16",pngDetailValue(palette,"sample_depth").value)
        assertEquals(2,palette.children.size)
        assertEquals("32768",pngDetailValue(palette.children[0],"alpha").value)
        assertEquals("50",pngDetailValue(palette.children[1],"frequency").value)
        assertEquals(80L,pngDetailValue(palette.children[1],"frequency").offset)
        assertEquals(2L,pngDetailValue(palette.children[1],"frequency").length)
    }
    @Test fun `suggested palette rejects bad names depths partial entries and caps expansion`() {
        for (body in listOf(hex("0008"),hex("410009"),hex("4100080102"),hex("20410008")))
            assertEquals(ValueInterpretation.UNPARSABLE,detailInterpretation(node("sPLT",body)))
        val palette = node("sPLT",hex("410008")+ByteArray(6*4097))
        assertEquals(4096,palette.children.size)
        assertEquals(ValueInterpretation.UNINTERPRETED,detailInterpretation(palette))
        assertNotNull(palette.fields.find { it.name=="unparsed_bytes" })
        val eight = node("sPLT",hex("410008010203040005"))
        assertEquals("4",pngDetailValue(eight.children.single(),"alpha").value)
        assertEquals("5",pngDetailValue(eight.children.single(),"frequency").value)
    }
    @Test fun `complete palette exposes all 256 entries and invalid counts are explicit`() {
        val palette = node("PLTE",ByteArray(768) { it.toByte() })
        assertEquals(256,palette.fields.size)
        assertEquals("#FDFEFF",pngDetailValue(palette,"color_256").value)
        assertEquals(ValueInterpretation.UNPARSABLE,detailInterpretation(node("PLTE",ByteArray(771))))
        assertEquals(ValueInterpretation.UNPARSABLE,detailInterpretation(node("PLTE",byteArrayOf())))
    }
}
