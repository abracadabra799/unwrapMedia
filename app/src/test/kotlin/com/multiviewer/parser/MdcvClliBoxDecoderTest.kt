package com.multiviewer.parser

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MdcvClliBoxDecoderTest {
    private fun fixture(name: String) = File(javaClass.getResource("/hdr/$name")!!.toURI())

    @Test
    fun `mdcv fields and summary`() {
        val body = hex("33c286c41d4c0bb884d03e803d134042 00989680 00000032")
        val reader = byteReaderOf(body)
        val node = MdcvBoxDecoder.decode(reader, "mdcv", 0, 0, body.size.toLong(), emptyList())
        assertEquals("0.26500, 0.69000", node.fields[0].value)
        assertEquals("display_primaries G (x, y)", node.fields[0].name)
        assertEquals("0.68000, 0.32000", node.fields[2].value)
        assertEquals("1000.0000 cd/m²", node.fields[4].value)
        assertEquals("0.0050 cd/m²", node.fields[5].value)
        assertEquals("max 1000 / min 0.0050 cd/m²", node.summary)
        reader.close()
    }

    @Test
    fun `short mdcv warns`() {
        val body = ByteArray(10)
        val reader = byteReaderOf(body)
        val node = MdcvBoxDecoder.decode(reader, "mdcv", 0, 0, body.size.toLong(), emptyList())
        assertTrue(node.warnings.isNotEmpty())
        reader.close()
    }

    @Test
    fun `clli fields and summary`() {
        val body = hex("03e8 0190")
        val reader = byteReaderOf(body)
        val node = ClliBoxDecoder.decode(reader, "clli", 0, 0, body.size.toLong(), emptyList())
        assertEquals("1000", node.fields[0].value)
        assertEquals("400", node.fields[1].value)
        assertEquals("MaxCLL 1000 / MaxFALL 400", node.summary)
        reader.close()
    }

    @Test
    fun `real file exposes mdcv and clli in the tree and as container static`() {
        val file = fixture("hevc_box.mp4")
        val root = parseFile(file)
        assertNotNull(findFirst(root) { it.type == "mdcv" }?.summary)
        ByteReader.open(file).use { reader ->
            val s = readContainerHdrStatic(reader, root)
            assertEquals(1000.0, assertNotNull(s.masteringDisplay).maxLuminance, 1e-9)
            assertEquals(ContentLightLevel(1000, 400), s.contentLightLevel)
        }
    }

    @Test
    fun `file without boxes has empty container static`() {
        val file = fixture("hevc_hdr10plus.mp4")
        ByteReader.open(file).use { reader ->
            val s = readContainerHdrStatic(reader, parseFile(file))
            assertNull(s.masteringDisplay)
            assertNull(s.contentLightLevel)
        }
    }
}
