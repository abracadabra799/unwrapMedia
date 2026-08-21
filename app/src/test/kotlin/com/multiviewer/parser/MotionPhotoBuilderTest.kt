package com.multiviewer.parser

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MotionPhotoBuilderTest {

    @Test
    fun `buildGoogleMotionPhotoXmp creates well-formed XMP metadata with correct video length`() {
        val xmp = MotionPhotoBuilder.buildGoogleMotionPhotoXmp(1234567L)
        assertTrue(xmp.contains("GCamera:MotionPhoto=\"1\""))
        assertTrue(xmp.contains("GCamera:MicroVideoOffset=\"1234567\""))
        assertTrue(xmp.contains("<Item:Length>1234567</Item:Length>"))
        assertTrue(xmp.contains("<Item:Semantic>MotionPhoto</Item:Semantic>"))
    }

    @Test
    fun `buildApp1XmpSegment builds valid APP1 marker with XMP identifier`() {
        val xmpText = "<xmp>test</xmp>"
        val segment = MotionPhotoBuilder.buildApp1XmpSegment(xmpText)

        assertEquals(0xFF.toByte(), segment[0])
        assertEquals(0xE1.toByte(), segment[1])

        val length = ((segment[2].toInt() and 0xFF) shl 8) or (segment[3].toInt() and 0xFF)
        assertEquals(segment.size - 2, length)

        val idString = String(segment.copyOfRange(4, 33), Charsets.US_ASCII)
        assertEquals("http://ns.adobe.com/xap/1.0/\u0000", idString)
    }

    @Test
    fun `injectMotionPhotoXmpIntoJpeg places XMP APP1 segment right after SOI`() {
        // Minimal valid JPEG: SOI (FF D8) + DQT (FF DB 00 04 00 00) + EOI (FF D9)
        val sampleJpeg = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(),
            0xFF.toByte(), 0xDB.toByte(), 0x00.toByte(), 0x04.toByte(), 0x00.toByte(), 0x00.toByte(),
            0xFF.toByte(), 0xD9.toByte(),
        )

        val injected = MotionPhotoBuilder.injectMotionPhotoXmpIntoJpeg(sampleJpeg, 99999L)
        assertEquals(0xFF.toByte(), injected[0])
        assertEquals(0xD8.toByte(), injected[1])
        assertEquals(0xFF.toByte(), injected[2])
        assertEquals(0xE1.toByte(), injected[3]) // APP1 immediately after SOI
    }

    @Test
    fun `createGoogleMotionPhoto merges real image and video into a parsable Motion Photo file`() {
        val imageFile = File.createTempFile("motion-build-image-", ".jpg")
        imageFile.deleteOnExit()
        ProcessBuilder(
            "ffmpeg", "-y", "-f", "lavfi", "-i", "color=blue:size=64x64",
            "-frames:v", "1", imageFile.absolutePath,
        ).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start().waitFor()

        val videoFile = File.createTempFile("motion-build-video-", ".mp4")
        videoFile.deleteOnExit()
        ProcessBuilder(
            "ffmpeg", "-y", "-f", "lavfi", "-i", "testsrc=duration=1:size=64x48:rate=10",
            videoFile.absolutePath,
        ).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start().waitFor()

        val outputFile = File.createTempFile("motion-build-out-", ".jpg")
        outputFile.deleteOnExit()

        MotionPhotoBuilder.createGoogleMotionPhoto(imageFile, videoFile, outputFile)

        assertTrue(outputFile.exists())
        assertTrue(outputFile.length() > imageFile.length() + videoFile.length() - 100)

        // Parse with unwrapMedia's own parser to verify complete round-trip compatibility!
        val root = parseFile(outputFile)
        ByteReader.open(outputFile).use { reader ->
            val embeddedVideo = findEmbeddedVideo(root, reader)
            assertNotNull(embeddedVideo, "Expected unwrapMedia parser to detect Google Motion Photo embedded video")
            assertEquals("mp4", embeddedVideo.extension)
            assertEquals(outputFile.length(), embeddedVideo.end)
            assertEquals(outputFile.length() - videoFile.length(), embeddedVideo.start)
        }

        imageFile.delete()
        videoFile.delete()
        outputFile.delete()
    }
}
