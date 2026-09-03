package com.multiviewer.parser

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.abs

class GainmapParserTest {

    @Test
    fun `parses Adobe HDR Gain Map XMP parameters with attributes`() {
        val xmp = """
            <x:xmpmeta xmlns:x="adobe:ns:meta/">
              <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
                <rdf:Description rdf:about=""
                    xmlns:hdrgm="http://ns.adobe.com/hdr-gain-map/1.0/"
                    hdrgm:Version="1.0"
                    hdrgm:GainMapMin="0.0"
                    hdrgm:GainMapMax="2.5"
                    hdrgm:Gamma="1.0"
                    hdrgm:OffsetSDR="0.015625"
                    hdrgm:OffsetHDR="0.015625"
                    hdrgm:HDRCapacityMin="0.0"
                    hdrgm:HDRCapacityMax="2.5"
                    hdrgm:BaseRenditionIsHDR="False" />
              </rdf:RDF>
            </x:xmpmeta>
        """.trimIndent()

        val params = GainmapParser.parseGainmapParametersFromXmp(xmp)
        assertNotNull(params)
        assertEquals("1.0", params?.version)
        assertEquals(0.0, params?.gainMapMin)
        assertEquals(2.5, params?.gainMapMax)
        assertEquals(1.0, params?.gamma)
        assertEquals(0.015625, params?.offsetSdr)
        assertEquals(0.015625, params?.offsetHdr)
        assertEquals(0.0, params?.hdrCapacityMin)
        assertEquals(2.5, params?.hdrCapacityMax)
        assertEquals(false, params?.baseRenditionIsHdr)
        assertEquals(2.5, params?.stops)
        assertTrue(abs((params?.linearMaxBoost ?: 0.0) - 5.65685) < 0.001)
    }

    @Test
    fun `parses ISO 21496-1 Gain Map XMP parameters with XML tags`() {
        val xmp = """
            <x:xmpmeta xmlns:x="adobe:ns:meta/">
              <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
                <rdf:Description rdf:about="" xmlns:gainmap="urn:iso:std:iso:ts:21496:1">
                  <gainmap:version>1.0</gainmap:version>
                  <gainmap:gainMapMin>0.0</gainmap:gainMapMin>
                  <gainmap:gainMapMax>3.0</gainmap:gainMapMax>
                  <gainmap:gamma>1.0</gainmap:gamma>
                  <gainmap:baseRenditionIsHdr>True</gainmap:baseRenditionIsHdr>
                </rdf:Description>
              </rdf:RDF>
            </x:xmpmeta>
        """.trimIndent()

        val params = GainmapParser.parseGainmapParametersFromXmp(xmp)
        assertNotNull(params)
        assertEquals("1.0", params?.version)
        assertEquals(0.0, params?.gainMapMin)
        assertEquals(3.0, params?.gainMapMax)
        assertEquals(true, params?.baseRenditionIsHdr)
        assertEquals(8.0, params?.linearMaxBoost)
    }

    @Test
    fun `parses Apple HDR Gain Map XMP metadata`() {
        val xmp = """
            <x:xmpmeta xmlns:x="adobe:ns:meta/">
              <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
                <rdf:Description rdf:about=""
                    xmlns:apple-hdr-gain-map="http://ns.apple.com/HDRGainMap/1.0/"
                    apple-hdr-gain-map:HDRGainMapVersion="1.0"
                    apple-hdr-gain-map:HDRGain="2.0" />
              </rdf:RDF>
            </x:xmpmeta>
        """.trimIndent()

        val params = GainmapParser.parseGainmapParametersFromXmp(xmp)
        assertNotNull(params)
        assertEquals("1.0", params?.version)
        assertEquals(2.0, params?.gainMapMax)
        assertEquals(4.0, params?.linearMaxBoost)
    }

    @Test
    fun `returns null for non-gainmap XMP`() {
        val xmp = """
            <x:xmpmeta xmlns:x="adobe:ns:meta/">
              <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
                <rdf:Description rdf:about="" xmlns:dc="http://purl.org/dc/elements/1.1/">
                  <dc:title>Regular Image</dc:title>
                </rdf:Description>
              </rdf:RDF>
            </x:xmpmeta>
        """.trimIndent()

        val params = GainmapParser.parseGainmapParametersFromXmp(xmp)
        assertNull(params)
    }

    @Test
    fun `parses MPF entries from APP2 segment`(@TempDir tempDir: File) {
        val out = ByteArrayOutputStream()
        // MPF Header (4 bytes)
        out.write("MPF\u0000".toByteArray(Charsets.US_ASCII))
        // TIFF Header (Little Endian "II", tag 42, IFD offset 8)
        out.write(byteArrayOf(0x49, 0x49, 0x2A, 0x00, 0x08, 0x00, 0x00, 0x00))
        // IFD: tagCount = 2
        out.write(byteArrayOf(0x02, 0x00))

        // Tag 0xB001 (Number of Images = 2)
        out.write(byteArrayOf(0x01, 0xB0.toByte(), 0x04, 0x00, 0x01, 0x00, 0x00, 0x00, 0x02, 0x00, 0x00, 0x00))
        // Tag 0xB002 (MP Entry Offset = 38 (0x26))
        out.write(byteArrayOf(0x02, 0xB0.toByte(), 0x07, 0x00, 0x20, 0x00, 0x00, 0x00, 0x26, 0x00, 0x00, 0x00))
        // Next IFD offset = 0
        out.write(byteArrayOf(0x00, 0x00, 0x00, 0x00))

        // MP Entry 1 (Primary Image: size 5000, offset 0)
        out.write(byteArrayOf(0x00, 0x00, 0x03, 0x00)) // flags: 0x030000
        out.write(byteArrayOf(0x88.toByte(), 0x13, 0x00, 0x00)) // size: 5000
        out.write(byteArrayOf(0x00, 0x00, 0x00, 0x00)) // offset: 0
        out.write(byteArrayOf(0x00, 0x00, 0x00, 0x00)) // dep

        // MP Entry 2 (Secondary Image: size 1200, offset 5000)
        out.write(byteArrayOf(0x02, 0x00, 0x02, 0x00)) // flags: 0x020002 (Disparity / Gain Map)
        out.write(byteArrayOf(0xB0.toByte(), 0x04, 0x00, 0x00)) // size: 1200
        out.write(byteArrayOf(0x88.toByte(), 0x13, 0x00, 0x00)) // offset: 5000 from TIFF start
        out.write(byteArrayOf(0x00, 0x00, 0x00, 0x00)) // dep

        val mpfPayload = out.toByteArray()
        val file = File(tempDir, "test.jpg")
        val app2Segment = byteArrayOf(0xFF.toByte(), 0xE2.toByte(), ((mpfPayload.size + 2) shr 8).toByte(), ((mpfPayload.size + 2) and 0xFF).toByte()) + mpfPayload
        file.writeBytes(app2Segment)

        ByteReader.open(file).use { reader ->
            val entries = GainmapParser.parseMpfEntries(reader, 0, app2Segment.size.toLong())
            assertEquals(2, entries.size)
            assertTrue(entries[0].isPrimary)
            assertEquals(5000L, entries[0].size)
            assertEquals(0L, entries[0].offset)

            assertFalse(entries[1].isPrimary)
            assertEquals(1200L, entries[1].size)
            assertEquals(8L + 5000L, entries[1].offset) // tiffStart (8) + 5000
        }
    }

    @Test
    fun `detects Ultra HDR JPEG with GContainer GainMap item`(@TempDir tempDir: File) {
        val secondaryJpegBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte())
        val secondaryLen = secondaryJpegBytes.size.toLong()

        val primaryXmp = """
            <x:xmpmeta xmlns:x="adobe:ns:meta/">
              <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
                <rdf:Description rdf:about=""
                    xmlns:Container="http://ns.google.com/photos/1.0/container/"
                    xmlns:Item="http://ns.google.com/photos/1.0/container/item/">
                  <Container:Directory>
                    <rdf:Seq>
                      <rdf:li rdf:parseType="Resource">
                        <Container:Item Item:Mime="image/jpeg" Item:Semantic="Primary" Item:Length="0"/>
                      </rdf:li>
                      <rdf:li rdf:parseType="Resource">
                        <Container:Item Item:Mime="image/jpeg" Item:Semantic="GainMap" Item:Length="$secondaryLen"/>
                      </rdf:li>
                    </rdf:Seq>
                  </Container:Directory>
                </rdf:Description>
              </rdf:RDF>
            </x:xmpmeta>
        """.trimIndent()

        val primaryJpegHeader = byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + ByteArray(100) + byteArrayOf(0xFF.toByte(), 0xD9.toByte())
        val fullFile = File(tempDir, "ultrahdr.jpg")
        fullFile.writeBytes(primaryJpegHeader + secondaryJpegBytes)

        val root = BoxNode(
            type = "root", offset = 0, headerSize = 0, size = fullFile.length(),
            children = listOf(
                BoxNode(
                    type = "APP1", offset = 2, headerSize = 4, size = 200,
                    fields = listOf(BoxField("xmp", primaryXmp, 6, primaryXmp.length.toLong())),
                ),
            ),
        )

        val info = GainmapParser.findGainmapInfo(fullFile, root)
        assertNotNull(info)
        assertTrue(info?.hasGainmap == true)
        assertEquals(GainmapFormatType.ULTRA_HDR_JPEG, info?.formatType)
        assertEquals(secondaryLen, info?.byteLength)
        assertEquals(fullFile.length() - secondaryLen, info?.byteOffset)
    }

    @Test
    fun `detects HEIC Apple HDR Gain Map from auxC property`(@TempDir tempDir: File) {
        val file = File(tempDir, "photo.heic")
        file.writeBytes(ByteArray(256))

        val auxCNode = BoxNode(
            type = "auxC", offset = 100, headerSize = 8, size = 40,
            fields = listOf(BoxField("aux_type", "urn:com:apple:photo:2020:aux:hdrgainmap", 108, 30)),
        )
        val ipcoNode = BoxNode(type = "ipco", offset = 80, headerSize = 8, size = 100, children = listOf(auxCNode))
        val ipmaNode = BoxNode(
            type = "ipma", offset = 180, headerSize = 8, size = 30,
            children = listOf(
                BoxNode(
                    type = "item_2", offset = 188, headerSize = 0, size = 10,
                    fields = listOf(BoxField("property_index", "1", 188, 2)),
                ),
            ),
        )
        val iprpNode = BoxNode(type = "iprp", offset = 60, headerSize = 8, size = 160, children = listOf(ipcoNode, ipmaNode))
        val infeNode = BoxNode(
            type = "infe", offset = 40, headerSize = 8, size = 20,
            fields = listOf(
                BoxField("item_ID", "2", 48, 2),
                BoxField("item_type", "hvc1", 50, 4),
            ),
        )
        val iinfNode = BoxNode(type = "iinf", offset = 30, headerSize = 8, size = 30, children = listOf(infeNode))
        val metaNode = BoxNode(type = "meta", offset = 0, headerSize = 8, size = 250, children = listOf(iinfNode, iprpNode))
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = 256, children = listOf(metaNode))

        val info = GainmapParser.findGainmapInfo(file, root)
        assertNotNull(info)
        assertTrue(info?.hasGainmap == true)
        assertTrue(info?.hasGainmapImage == true)
        assertEquals(GainmapFormatType.APPLE_HEIC, info?.formatType)
        assertEquals(2L, info?.itemId)
        assertEquals("HEVC (hvc1)", info?.imageFormat)
    }

    @Test
    fun `detects ISO 21496-1 HEIC Gain Map from auxC property`(@TempDir tempDir: File) {
        val file = File(tempDir, "iso_gainmap.heic")
        file.writeBytes(ByteArray(256))

        val auxCNode = BoxNode(
            type = "auxC", offset = 100, headerSize = 8, size = 40,
            fields = listOf(BoxField("aux_type", "urn:iso:std:iso:ts:21496:-1", 108, 30)),
        )
        val ipcoNode = BoxNode(type = "ipco", offset = 80, headerSize = 8, size = 100, children = listOf(auxCNode))
        val ipmaNode = BoxNode(
            type = "ipma", offset = 180, headerSize = 8, size = 30,
            children = listOf(
                BoxNode(
                    type = "item_3", offset = 188, headerSize = 0, size = 10,
                    fields = listOf(BoxField("property_index", "1", 188, 2)),
                ),
            ),
        )
        val iprpNode = BoxNode(type = "iprp", offset = 60, headerSize = 8, size = 160, children = listOf(ipcoNode, ipmaNode))
        val infeNode = BoxNode(
            type = "infe", offset = 40, headerSize = 8, size = 20,
            fields = listOf(
                BoxField("item_ID", "3", 48, 2),
                BoxField("item_type", "jpeg", 50, 4),
            ),
        )
        val iinfNode = BoxNode(type = "iinf", offset = 30, headerSize = 8, size = 30, children = listOf(infeNode))
        val metaNode = BoxNode(type = "meta", offset = 0, headerSize = 8, size = 250, children = listOf(iinfNode, iprpNode))
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = 256, children = listOf(metaNode))

        val info = GainmapParser.findGainmapInfo(file, root)
        assertNotNull(info)
        assertTrue(info?.hasGainmap == true)
        assertTrue(info?.hasGainmapImage == true)
        assertEquals(GainmapFormatType.ISO_21496_1_HEIC, info?.formatType)
        assertEquals(3L, info?.itemId)
        assertEquals("JPEG", info?.imageFormat)
    }

    @Test
    fun `sets hasGainmapImage false when only XMP metadata exists`(@TempDir tempDir: File) {
        val file = File(tempDir, "meta_only.jpg")
        file.writeBytes(ByteArray(100))

        val xmpXml = """
            <x:xmpmeta xmlns:x="adobe:ns:meta/">
              <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
                <rdf:Description rdf:about=""
                    xmlns:hdrgm="http://ns.adobe.com/hdr-gain-map/1.0/"
                    hdrgm:Version="1.0"
                    hdrgm:GainMapMax="2.0" />
              </rdf:RDF>
            </x:xmpmeta>
        """.trimIndent()

        val root = BoxNode(
            type = "root", offset = 0, headerSize = 0, size = 100,
            fields = listOf(BoxField("xmp", xmpXml, 0L, xmpXml.length.toLong())),
        )

        val info = GainmapParser.findGainmapInfo(file, root)
        assertNotNull(info)
        assertTrue(info?.hasGainmap == true)
        assertFalse(info?.hasGainmapImage == true)
    }

    @Test
    fun `returns null for image with no gain map metadata`(@TempDir tempDir: File) {
        val file = File(tempDir, "plain.jpg")
        file.writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte()))

        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = 4)
        val info = GainmapParser.findGainmapInfo(file, root)
        assertNull(info)
    }
}
