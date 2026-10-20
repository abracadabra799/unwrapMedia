package com.multiviewer.parser.motionphoto

import com.multiviewer.parser.BoxField
import com.multiviewer.parser.BoxNode
import com.multiviewer.parser.SefIntegritySeverity
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppleLivePhotoPairCheckerTest {

    @Test
    fun `extracts still metadata from QuickTime meta and EXIF`() {
        val root = BoxNode(
            type = "root", offset = 0, headerSize = 0, size = 1000,
            children = listOf(
                BoxNode(
                    type = "meta", offset = 0, headerSize = 8, size = 500,
                    fields = listOf(
                        BoxField("com.apple.quicktime.content.identifier", "E8B15F6A-3F98-4B52-871A-7A5E9A98E9B1", 0, 0),
                    ),
                ),
                BoxNode(
                    type = "Exif", offset = 500, headerSize = 8, size = 500,
                    fields = listOf(
                        BoxField("DateTimeOriginal", "2026:10:12 14:30:00", 508, 19),
                    ),
                ),
            ),
        )

        val meta = extractAppleStillMetadata(root)
        assertNotNull(meta)
        assertEquals("E8B15F6A-3F98-4B52-871A-7A5E9A98E9B1", meta.contentIdentifier)
        assertEquals("2026:10:12 14:30:00", meta.dateTimeOriginal)
    }

    @Test
    fun `extracts still metadata from MakerNote`() {
        val root = BoxNode(
            type = "root", offset = 0, headerSize = 0, size = 1000,
            children = listOf(
                BoxNode(
                    type = "MakerNote", offset = 100, headerSize = 8, size = 200,
                    fields = listOf(
                        BoxField("ContentIdentifier", "12345678-ABCD-EF01-2345-6789ABCDEF01", 108, 36),
                    ),
                ),
            ),
        )

        val meta = extractAppleStillMetadata(root)
        assertNotNull(meta)
        assertEquals("12345678-ABCD-EF01-2345-6789ABCDEF01", meta.contentIdentifier)
    }

    @Test
    fun `extracts video metadata from QuickTime keys and metadata`() {
        val root = BoxNode(
            type = "root", offset = 0, headerSize = 0, size = 2000,
            children = listOf(
                BoxNode(
                    type = "moov", offset = 0, headerSize = 8, size = 1000,
                    children = listOf(
                        BoxNode(
                            type = "meta", offset = 8, headerSize = 8, size = 500,
                            fields = listOf(
                                BoxField("com.apple.quicktime.content.identifier", "E8B15F6A-3F98-4B52-871A-7A5E9A98E9B1", 16, 36),
                                BoxField("com.apple.quicktime.creationdate", "2026-10-12T14:30:00+0900", 52, 24),
                                BoxField("com.apple.quicktime.live-photo.still-image-time", "1500000", 76, 7),
                                BoxField("com.apple.quicktime.live-photo.vitality-score", "0.85", 83, 4),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val meta = extractAppleVideoMetadata(root)
        assertNotNull(meta)
        assertEquals("E8B15F6A-3F98-4B52-871A-7A5E9A98E9B1", meta.contentIdentifier)
        assertEquals("2026-10-12T14:30:00+0900", meta.creationDate)
        assertEquals(1_500_000L, meta.stillImageTime)
        assertEquals(0.85f, meta.vitalityScore)
    }

    @Test
    fun `inspectLivePhotoPair returns MATCHED when UUIDs match`() {
        val still = AppleStillMetadata(
            contentIdentifier = "E8B15F6A-3F98-4B52-871A-7A5E9A98E9B1",
            dateTimeOriginal = "2026:10:12 14:30:00",
        )
        val video = AppleVideoMetadata(
            contentIdentifier = "e8b15f6a-3f98-4b52-871a-7a5e9a98e9b1", // lowercase match
            creationDate = "2026-10-12T14:30:00+09:00",
            stillImageTime = 1_500_000L,
            vitalityScore = 0.9f,
        )

        val report = inspectLivePhotoPair(still, video)
        assertEquals(LivePhotoPairStatus.MATCHED, report.status)
        assertTrue(report.uuidMatch)
        assertTrue(report.checks.any { it.label == "Content Identifier UUID 일치" && it.severity == SefIntegritySeverity.PASS })
    }

    @Test
    fun `inspectLivePhotoPair returns MISMATCHED_UUID when UUIDs differ`() {
        val still = AppleStillMetadata(
            contentIdentifier = "E8B15F6A-3F98-4B52-871A-7A5E9A98E9B1",
            dateTimeOriginal = "2026:10:12 14:30:00",
        )
        val video = AppleVideoMetadata(
            contentIdentifier = "FFFFFFFF-3F98-4B52-871A-7A5E9A98E9B1",
            creationDate = "2026-10-12T14:30:00+09:00",
            stillImageTime = 1_500_000L,
            vitalityScore = null,
        )

        val report = inspectLivePhotoPair(still, video)
        assertEquals(LivePhotoPairStatus.MISMATCHED_UUID, report.status)
        assertFalse(report.uuidMatch)
        assertTrue(report.checks.any { it.label == "Content Identifier UUID 일치" && it.severity == SefIntegritySeverity.CRITICAL })
    }

    @Test
    fun `inspectLivePhotoPair flags missing still or video UUID`() {
        val stillNoUuid = AppleStillMetadata(contentIdentifier = null)
        val videoWithUuid = AppleVideoMetadata(contentIdentifier = "E8B15F6A-3F98-4B52-871A-7A5E9A98E9B1")

        val report1 = inspectLivePhotoPair(stillNoUuid, videoWithUuid)
        assertEquals(LivePhotoPairStatus.MISSING_STILL_ID, report1.status)

        val report2 = inspectLivePhotoPair(videoWithUuid.let { AppleStillMetadata(contentIdentifier = it.contentIdentifier) }, AppleVideoMetadata(contentIdentifier = null))
        assertEquals(LivePhotoPairStatus.MISSING_VIDEO_ID, report2.status)
    }

    @Test
    fun `findLivePhotoPairCandidate finds MOV file for HEIC still`() {
        val tempDir = File.createTempFile("livephoto_test", "").apply {
            delete()
            mkdir()
            deleteOnExit()
        }
        val stillFile = File(tempDir, "IMG_5678.HEIC").apply { writeBytes(ByteArray(10)); deleteOnExit() }
        val videoFile = File(tempDir, "IMG_5678.MOV").apply { writeBytes(ByteArray(20)); deleteOnExit() }

        val candidate = findLivePhotoPairCandidate(stillFile)
        assertNotNull(candidate)
        assertEquals(videoFile.absolutePath, candidate.absolutePath)

        val stillCandidate = findLivePhotoPairCandidate(videoFile)
        assertNotNull(stillCandidate)
        assertEquals(stillFile.absolutePath, stillCandidate.absolutePath)
    }

    @Test
    fun `findLivePhotoPairCandidate returns null when no matching pair exists`() {
        val tempDir = File.createTempFile("livephoto_test2", "").apply {
            delete()
            mkdir()
            deleteOnExit()
        }
        val loneFile = File(tempDir, "IMG_9999.JPG").apply { writeBytes(ByteArray(10)); deleteOnExit() }
        assertNull(findLivePhotoPairCandidate(loneFile))
    }

    @Test
    fun `findLivePhotoPairCandidate pairs mis-named files by content`() {
        val tempDir = kotlin.io.path.createTempDirectory("livephoto_content").toFile().apply { deleteOnExit() }
        fun fixture(name: String) = File(AppleLivePhotoPairCheckerTest::class.java.getResource("/format-sniff/$name")!!.toURI())
        val still = File(tempDir, "IMG_1.jpg").also { fixture("a.heic").copyTo(it); it.deleteOnExit() }
        val video = File(tempDir, "IMG_1.bin").also { fixture("a.mov").copyTo(it); it.deleteOnExit() }
        File(tempDir, "IMG_1.txt").apply { writeText("notes"); deleteOnExit() }

        assertEquals(video.absolutePath, findLivePhotoPairCandidate(still)?.absolutePath)
        assertEquals(still.absolutePath, findLivePhotoPairCandidate(video)?.absolutePath)
    }
}
