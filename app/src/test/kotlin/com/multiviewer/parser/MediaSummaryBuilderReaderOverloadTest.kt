package com.multiviewer.parser

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class MediaSummaryBuilderReaderOverloadTest {
    // A root with an EXIF ThumbnailImage node exercises buildThumbnail's real-data path
    // (not just its early return), which is exactly the path this task moves a reader
    // through -- the most important case to prove equivalent.
    private fun imageRootWithThumbnail(thumbnailBytes: ByteArray): BoxNode {
        val thumbNode = BoxNode(
            type = "ThumbnailImage", offset = 100L, headerSize = 0, size = thumbnailBytes.size.toLong(),
        )
        val exif = BoxNode(type = "Exif", offset = 0L, headerSize = 0, size = 100L, children = listOf(thumbNode))
        val soi = BoxNode(type = "SOI", offset = 0L, headerSize = 0, size = 2L)
        return BoxNode(type = "root", offset = 0L, headerSize = 0, size = (100 + thumbnailBytes.size).toLong(), children = listOf(soi, exif))
    }

    @Test
    fun `reader-accepting overload produces the same summary as the file-only overload for an image with a thumbnail`() {
        val thumbnailBytes = byteArrayOf(0x11, 0x22, 0x33, 0x44)
        val root = imageRootWithThumbnail(thumbnailBytes)
        val tmp = File.createTempFile("multiviewer-reader-overload", ".jpg")
        tmp.deleteOnExit()
        // Byte 100 onward must actually be the thumbnail bytes buildThumbnail will read.
        tmp.writeBytes(ByteArray(100) + thumbnailBytes)

        val viaFileOnly = buildMediaSummary(root, tmp)
        val viaSharedReader = ByteReader.open(tmp).use { reader -> buildMediaSummary(root, tmp, reader) }

        assertEquals(viaFileOnly, viaSharedReader)
    }

    @Test
    fun `reader-accepting overload produces the same summary as the file-only overload for a non-image root`() {
        // A video root never touches buildMotionPhotoVideoSummary/buildThumbnail at all --
        // confirms the split didn't change behavior for the common non-image path either.
        val ftyp = BoxNode(type = "ftyp", offset = 0L, headerSize = 8, size = 16L)
        val moov = BoxNode(type = "moov", offset = 16L, headerSize = 8, size = 8L)
        val root = BoxNode(type = "root", offset = 0L, headerSize = 0, size = 24L, children = listOf(ftyp, moov))
        val tmp = File.createTempFile("multiviewer-reader-overload", ".mp4")
        tmp.deleteOnExit()
        tmp.writeBytes(ByteArray(24))

        val viaFileOnly = buildMediaSummary(root, tmp)
        val viaSharedReader = ByteReader.open(tmp).use { reader -> buildMediaSummary(root, tmp, reader) }

        assertEquals(viaFileOnly, viaSharedReader)
    }
}
