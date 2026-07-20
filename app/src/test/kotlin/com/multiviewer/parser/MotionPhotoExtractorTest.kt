package com.multiviewer.parser

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class MotionPhotoExtractorTest {
    @Test
    fun `finds embedded video via a top-level mpvd box and detects mp4 from major_brand`() {
        val nestedFtyp = BoxNode(
            type = "ftyp", offset = 24, headerSize = 8, size = 16,
            fields = listOf(BoxField("major_brand", "isom", 24, 4)),
        )
        val mpvd = BoxNode(type = "mpvd", offset = 16, headerSize = 8, size = 24, children = listOf(nestedFtyp))
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = 40, children = listOf(mpvd))

        val video = findEmbeddedVideo(root)

        assertEquals(24L, video?.start)
        assertEquals(40L, video?.end)
        assertEquals("mp4", video?.extension)
    }

    @Test
    fun `finds embedded video via a sefd field whose bytes were sniffed as an embedded MP4, and detects mov from major_brand`() {
        val nestedFtyp = BoxNode(
            type = "ftyp", offset = 104, headerSize = 8, size = 16,
            fields = listOf(BoxField("major_brand", "qt  ", 104, 4)),
        )
        val videoField = BoxNode(
            type = "MotionPhoto_Video", offset = 100, headerSize = 4, size = 20,
            children = listOf(nestedFtyp),
        )
        val sefd = BoxNode(type = "sefd", offset = 50, headerSize = 0, size = 70, children = listOf(videoField))
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = 120, children = listOf(sefd))

        val video = findEmbeddedVideo(root)

        assertEquals(104L, video?.start)
        assertEquals(120L, video?.end)
        assertEquals("mov", video?.extension)
    }

    @Test
    fun `prefers the sefd field named MotionPhoto_Data over an earlier MotionPhoto_AutoPlay preview clip`() {
        // Matches the real Samsung SEFD directory order: a short autoplay preview loop is listed
        // before the full-length video, and both start with a nested ftyp box.
        val autoPlayFtyp = BoxNode(
            type = "ftyp", offset = 200, headerSize = 8, size = 16,
            fields = listOf(BoxField("major_brand", "mp42", 200, 4)),
        )
        val autoPlayField = BoxNode(
            type = "MotionPhoto_AutoPlay", offset = 196, headerSize = 4, size = 20,
            children = listOf(autoPlayFtyp),
        )
        val dataFtyp = BoxNode(
            type = "ftyp", offset = 300, headerSize = 8, size = 16,
            fields = listOf(BoxField("major_brand", "mp42", 300, 4)),
        )
        val dataField = BoxNode(
            type = "MotionPhoto_Data", offset = 220, headerSize = 4, size = 4096,
            children = listOf(dataFtyp),
        )
        val sefd = BoxNode(
            type = "sefd", offset = 50, headerSize = 0, size = 4200,
            children = listOf(autoPlayField, dataField),
        )
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = 4250, children = listOf(sefd))

        val video = findEmbeddedVideo(root)

        assertEquals(224L, video?.start)
        assertEquals(4316L, video?.end)
    }

    @Test
    fun `returns null when neither mpvd nor a video-bearing sefd field is present`() {
        val ftyp = BoxNode(type = "ftyp", offset = 0, headerSize = 8, size = 16)
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = 16, children = listOf(ftyp))

        assertEquals(null, findEmbeddedVideo(root))
    }

    @Test
    fun `extractEmbeddedVideo copies exactly the requested byte range to the destination file`() {
        val sourceBytes = ByteArray(50) { it.toByte() }
        val source = File.createTempFile("motion-photo-extract-source", ".bin")
        source.deleteOnExit()
        source.writeBytes(sourceBytes)
        val destination = File.createTempFile("motion-photo-extract-dest", ".mp4")
        destination.deleteOnExit()

        extractEmbeddedVideo(source, EmbeddedVideo(start = 10, end = 30, extension = "mp4"), destination)

        assertContentEquals(sourceBytes.copyOfRange(10, 30), destination.readBytes())
    }

    @Test
    fun `extractEmbeddedVideo correctly copies a range spanning multiple 1MB chunks`() {
        val size = 3 * (1 shl 20) + 12345
        val sourceBytes = ByteArray(size) { (it % 256).toByte() }
        val source = File.createTempFile("motion-photo-extract-source-large", ".bin")
        source.deleteOnExit()
        source.writeBytes(sourceBytes)
        val destination = File.createTempFile("motion-photo-extract-dest-large", ".mp4")
        destination.deleteOnExit()

        extractEmbeddedVideo(source, EmbeddedVideo(start = 0, end = size.toLong(), extension = "mp4"), destination)

        assertContentEquals(sourceBytes, destination.readBytes())
    }

    @Test
    fun `extractEmbeddedVideo produces an empty file for a zero-length range`() {
        val source = File.createTempFile("motion-photo-extract-source-empty", ".bin")
        source.deleteOnExit()
        source.writeBytes(ByteArray(10))
        val destination = File.createTempFile("motion-photo-extract-dest-empty", ".mp4")
        destination.deleteOnExit()

        extractEmbeddedVideo(source, EmbeddedVideo(start = 5, end = 5, extension = "mp4"), destination)

        assertEquals(0, destination.length())
    }

    @Test
    fun `finds video via Google Container Directory XMP in element form`() {
        val xmp = """
            <x:xmpmeta xmlns:x="adobe:ns:meta/">
              <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
                <rdf:Description rdf:about=""
                    xmlns:Camera="http://ns.google.com/photos/1.0/camera/"
                    xmlns:Container="http://ns.google.com/photos/1.0/container/"
                    xmlns:Item="http://ns.google.com/photos/1.0/container/item/">
                  <Camera:MotionPhoto>1</Camera:MotionPhoto>
                  <Container:Directory>
                    <rdf:Seq>
                      <rdf:li rdf:parseType="Resource">
                        <Item:Mime>image/jpeg</Item:Mime>
                        <Item:Semantic>Primary</Item:Semantic>
                        <Item:Length>0</Item:Length>
                      </rdf:li>
                      <rdf:li rdf:parseType="Resource">
                        <Item:Mime>video/mp4</Item:Mime>
                        <Item:Semantic>MotionPhoto</Item:Semantic>
                        <Item:Length>12345</Item:Length>
                      </rdf:li>
                    </rdf:Seq>
                  </Container:Directory>
                </rdf:Description>
              </rdf:RDF>
            </x:xmpmeta>
        """.trimIndent()
        val app1 = BoxNode(type = "APP1", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("xmp", xmp, 0, 0)))
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = 100000, children = listOf(app1))

        val video = findEmbeddedVideo(root)

        assertEquals(100000L - 12345L, video?.start)
        assertEquals(100000L, video?.end)
        assertEquals("mp4", video?.extension)
    }

    @Test
    fun `finds video via Google Container Directory XMP in attribute form`() {
        val xmp = """
            <x:xmpmeta xmlns:x="adobe:ns:meta/">
              <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
                <rdf:Description rdf:about=""
                    xmlns:Container="http://ns.google.com/photos/1.0/container/"
                    xmlns:Item="http://ns.google.com/photos/1.0/container/item/">
                  <Container:Directory>
                    <rdf:Seq>
                      <rdf:li rdf:parseType="Resource" Item:Mime="image/jpeg" Item:Semantic="Primary" Item:Length="0"/>
                      <rdf:li rdf:parseType="Resource" Item:Mime="video/mp4" Item:Semantic="MotionPhoto" Item:Length="54321"/>
                    </rdf:Seq>
                  </Container:Directory>
                </rdf:Description>
              </rdf:RDF>
            </x:xmpmeta>
        """.trimIndent()
        val app1 = BoxNode(type = "APP1", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("xmp", xmp, 0, 0)))
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = 200000, children = listOf(app1))

        val video = findEmbeddedVideo(root)

        assertEquals(200000L - 54321L, video?.start)
        assertEquals(200000L, video?.end)
        assertEquals("mp4", video?.extension)
    }

    @Test
    fun `detects mov extension from Item Mime video quicktime`() {
        val xmp = """
            <x:xmpmeta xmlns:x="adobe:ns:meta/">
              <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
                <rdf:Description rdf:about=""
                    xmlns:Container="http://ns.google.com/photos/1.0/container/"
                    xmlns:Item="http://ns.google.com/photos/1.0/container/item/">
                  <Container:Directory>
                    <rdf:Seq>
                      <rdf:li rdf:parseType="Resource" Item:Mime="video/quicktime" Item:Semantic="MotionPhoto" Item:Length="9999"/>
                    </rdf:Seq>
                  </Container:Directory>
                </rdf:Description>
              </rdf:RDF>
            </x:xmpmeta>
        """.trimIndent()
        val app1 = BoxNode(type = "APP1", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("xmp", xmp, 0, 0)))
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = 50000, children = listOf(app1))

        val video = findEmbeddedVideo(root)

        assertEquals("mov", video?.extension)
    }

    @Test
    fun `falls back to legacy GCamera MicroVideoOffset when no Container Directory is present`() {
        val xmp = """
            <x:xmpmeta xmlns:x="adobe:ns:meta/">
              <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
                <rdf:Description rdf:about=""
                    xmlns:GCamera="http://ns.google.com/photos/1.0/camera/"
                    GCamera:MicroVideo="1"
                    GCamera:MicroVideoVersion="1"
                    GCamera:MicroVideoOffset="4022143"/>
              </rdf:RDF>
            </x:xmpmeta>
        """.trimIndent()
        val app1 = BoxNode(type = "APP1", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("xmp", xmp, 0, 0)))
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = 5000000, children = listOf(app1))

        val video = findEmbeddedVideo(root)

        assertEquals(5000000L - 4022143L, video?.start)
        assertEquals(5000000L, video?.end)
        assertEquals("mp4", video?.extension)
    }

    @Test
    fun `malformed XMP text does not throw and returns null`() {
        val app1 = BoxNode(type = "APP1", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("xmp", "<not valid xml", 0, 0)))
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = 1000, children = listOf(app1))

        assertEquals(null, findEmbeddedVideo(root))
    }

    @Test
    fun `XMP with no motion-photo markers at all returns null`() {
        val xmp = """
            <x:xmpmeta xmlns:x="adobe:ns:meta/">
              <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
                <rdf:Description rdf:about="" xmlns:dc="http://purl.org/dc/elements/1.1/">
                  <dc:creator>someone</dc:creator>
                </rdf:Description>
              </rdf:RDF>
            </x:xmpmeta>
        """.trimIndent()
        val app1 = BoxNode(type = "APP1", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("xmp", xmp, 0, 0)))
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = 1000, children = listOf(app1))

        assertEquals(null, findEmbeddedVideo(root))
    }

    @Test
    fun `prefers the sefd MotionPhoto_Data field over Google XMP when both are present`() {
        val ftyp = BoxNode(
            type = "ftyp", offset = 220, headerSize = 8, size = 16,
            fields = listOf(BoxField("major_brand", "mp42", 220, 4)),
        )
        val dataField = BoxNode(
            type = "MotionPhoto_Data", offset = 200, headerSize = 4, size = 4096,
            children = listOf(ftyp),
        )
        val sefd = BoxNode(type = "sefd", offset = 50, headerSize = 0, size = 4200, children = listOf(dataField))
        val xmp = """
            <x:xmpmeta xmlns:x="adobe:ns:meta/">
              <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
                <rdf:Description rdf:about=""
                    xmlns:Container="http://ns.google.com/photos/1.0/container/"
                    xmlns:Item="http://ns.google.com/photos/1.0/container/item/">
                  <Container:Directory>
                    <rdf:Seq>
                      <rdf:li rdf:parseType="Resource" Item:Mime="video/mp4" Item:Semantic="MotionPhoto" Item:Length="999"/>
                    </rdf:Seq>
                  </Container:Directory>
                </rdf:Description>
              </rdf:RDF>
            </x:xmpmeta>
        """.trimIndent()
        val app1 = BoxNode(type = "APP1", offset = 0, headerSize = 0, size = 0, fields = listOf(BoxField("xmp", xmp, 0, 0)))
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = 100000, children = listOf(sefd, app1))

        val video = findEmbeddedVideo(root)

        assertEquals(204L, video?.start)
        assertEquals(4296L, video?.end)
    }
}
