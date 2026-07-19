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
}
