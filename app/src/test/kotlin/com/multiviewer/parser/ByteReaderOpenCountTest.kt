package com.multiviewer.parser

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class ByteReaderOpenCountTest {
    // Same fixture shape as MediaSummaryBuilderReaderOverloadTest's thumbnail case -- rich
    // enough to exercise buildMotionPhotoVideoSummary's real path (not just its early return)
    // and buildThumbnail's real read, matching the shape a real camera JPEG's IFD1 thumbnail
    // takes through this pipeline.
    private fun imageRootWithThumbnail(thumbnailBytes: ByteArray): BoxNode {
        val thumbNode = BoxNode(type = "ThumbnailImage", offset = 100, headerSize = 0, size = thumbnailBytes.size.toLong())
        val exif = BoxNode(type = "Exif", offset = 0, headerSize = 0, size = 100, children = listOf(thumbNode))
        val soi = BoxNode(type = "SOI", offset = 0, headerSize = 0, size = 2)
        return BoxNode(type = "root", offset = 0, headerSize = 0, size = (100 + thumbnailBytes.size).toLong(), children = listOf(soi, exif))
    }

    @Test
    fun `parseFile, buildMediaSummary, and analyze share one reader when the caller opens one`() {
        val thumbnailBytes = byteArrayOf(0x11, 0x22, 0x33, 0x44)
        val tmp = File.createTempFile("multiviewer-open-count", ".jpg")
        tmp.deleteOnExit()
        tmp.writeBytes(ByteArray(2) + ByteArray(98) + thumbnailBytes) // room for the fixture's offsets

        val root = imageRootWithThumbnail(thumbnailBytes)

        val before = ByteReader.openCallCount
        ByteReader.open(tmp).use { reader ->
            parseFile(tmp, reader)
            val summary = buildMediaSummary(root, tmp, reader)
            ImageAnalyzer.analyze(tmp, root, reader)
            summary
        }
        val opensForSharedPath = ByteReader.openCallCount - before

        assertEquals(1, opensForSharedPath, "expected exactly one ByteReader.open() for the whole shared-reader sequence")
    }

    @Test
    fun `the same sequence via the file-only overloads still opens three times`() {
        // Documents the current file-only baseline. This dropped from the original 4 to 3 as a
        // side effect of Task 2's own refactor -- buildMediaSummary's plain wrapper now shares
        // one reader between its two internal helpers (buildMotionPhotoVideoSummary and
        // buildThumbnail) instead of each opening separately. Task 4's actual wiring below
        // reduces the real per-file-open paths further, to 1.
        val thumbnailBytes = byteArrayOf(0x11, 0x22, 0x33, 0x44)
        val tmp = File.createTempFile("multiviewer-open-count", ".jpg")
        tmp.deleteOnExit()
        tmp.writeBytes(ByteArray(2) + ByteArray(98) + thumbnailBytes)

        val root = imageRootWithThumbnail(thumbnailBytes)

        val before = ByteReader.openCallCount
        val fileOnlyRoot = parseFile(tmp)
        buildMediaSummary(fileOnlyRoot, tmp)
        ImageAnalyzer.analyze(tmp, fileOnlyRoot)
        val opensForFileOnlyPath = ByteReader.openCallCount - before

        assertEquals(3, opensForFileOnlyPath)
    }
}
