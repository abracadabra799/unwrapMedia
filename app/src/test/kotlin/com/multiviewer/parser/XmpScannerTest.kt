package com.multiviewer.parser

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class XmpScannerTest {
    private fun packet(body: String) =
        """<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF>$body</rdf:RDF></x:xmpmeta>"""

    private fun fileWith(vararg parts: ByteArray): File {
        val f = File.createTempFile("xmp-scanner-test-", ".bin")
        f.deleteOnExit()
        f.outputStream().use { out -> parts.forEach { out.write(it) } }
        return f
    }

    private fun junk(n: Int) = ByteArray(n) { (it % 251).toByte() }

    // The real shape this exists for: every gain map JPEG/HEIC in the wild carries two packets --
    // one for the primary image and one inside the embedded gain map image, far apart in the file.
    // The structure walker only ever reaches the first, which is why this scans raw bytes.
    @Test
    fun `scanXmpPackets finds every packet in the file, in file order`() {
        val first = packet("<first/>")
        val second = packet("<second/>")
        val f = fileWith(
            junk(64), first.toByteArray(), junk(4096), second.toByteArray(), junk(64),
        )

        val packets = scanXmpPackets(f)

        assertEquals(2, packets.size)
        assertEquals(listOf(0, 1), packets.map { it.index })
        assertTrue(packets[0].text.contains("<first/>"), packets[0].text)
        assertTrue(packets[1].text.contains("<second/>"), packets[1].text)
        assertTrue(packets[0].offset < packets[1].offset)
        f.delete()
    }

    @Test
    fun `scanXmpPackets reports each packet's real offset and byte length`() {
        val xmp = packet("<only/>")
        val f = fileWith(junk(100), xmp.toByteArray(), junk(10))

        val packet = scanXmpPackets(f).single()

        assertEquals(100L, packet.offset)
        assertEquals(xmp.toByteArray().size, packet.byteLength)
        assertEquals(xmp, packet.text)
        f.delete()
    }

    // A packet split across the scanner's internal read chunks must still be found whole -- the
    // gain map packet sits ~1.8 MB into a real file, well past the first chunk boundary.
    @Test
    fun `scanXmpPackets finds a packet that starts far beyond the first read chunk`() {
        val xmp = packet("<deep/>")
        val f = fileWith(junk(3_000_000), xmp.toByteArray(), junk(1000))

        val packet = scanXmpPackets(f).single()

        assertEquals(3_000_000L, packet.offset)
        assertTrue(packet.text.contains("<deep/>"))
        f.delete()
    }

    @Test
    fun `scanXmpPackets returns nothing for a file with no XMP`() {
        val f = fileWith(junk(5000))
        assertEquals(emptyList(), scanXmpPackets(f))
        f.delete()
    }

    // A truncated file can leave an opening tag with no close; reporting a "packet" that runs to
    // EOF would show megabytes of binary as if it were metadata.
    @Test
    fun `scanXmpPackets ignores an unterminated packet`() {
        val f = fileWith(junk(50), """<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF>""".toByteArray(), junk(5000))
        assertEquals(emptyList(), scanXmpPackets(f))
        f.delete()
    }

    @Test
    fun `scanXmpPackets stops at maxPackets`() {
        val parts = mutableListOf<ByteArray>()
        repeat(5) { parts.add(packet("<p$it/>").toByteArray()); parts.add(junk(32)) }
        val f = fileWith(*parts.toTypedArray())

        assertEquals(3, scanXmpPackets(f, maxPackets = 3).size)
        f.delete()
    }

    @Test
    fun `scanXmpPackets returns nothing for a missing file instead of throwing`() {
        assertEquals(emptyList(), scanXmpPackets(File("/nonexistent/nope.jpg")))
    }

    // Tab labels need something better than "XMP 1 / XMP 2" to tell packets apart. Verified against
    // the real files this was built for: the primary packet carries Container/MotionPhoto/GCamera,
    // the one inside the embedded gain map image carries GainMap/hdrgm.
    @Test
    fun `xmpPacketTopics names the schemas present in a packet`() {
        val primary = """<x:xmpmeta><rdf:RDF xmlns:Container="..." xmlns:GCamera="...">
            <Container:Directory/><GCamera:MotionPhoto>1</GCamera:MotionPhoto></rdf:RDF></x:xmpmeta>"""

        val topics = xmpPacketTopics(primary)

        assertTrue("Container" in topics, topics.toString())
        assertTrue("MotionPhoto" in topics, topics.toString())
        assertTrue("GCamera" in topics, topics.toString())
        assertTrue("GainMap" !in topics, topics.toString())
    }

    @Test
    fun `xmpPacketTopics identifies a gain map packet`() {
        val gainmap = """<x:xmpmeta><rdf:RDF xmlns:hdrgm="http://ns.adobe.com/hdr-gain-map/1.0/">
            <hdrgm:GainMapMin>0</hdrgm:GainMapMin></rdf:RDF></x:xmpmeta>"""

        val topics = xmpPacketTopics(gainmap)

        assertTrue("GainMap" in topics, topics.toString())
        assertTrue("hdrgm" in topics, topics.toString())
    }

    @Test
    fun `xmpPacketTopics returns an empty list when nothing recognizable is present`() {
        assertEquals(emptyList(), xmpPacketTopics("""<x:xmpmeta><rdf:RDF/></x:xmpmeta>"""))
    }
}
