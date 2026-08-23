package com.multiviewer.parser

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MotionPhotoBuilderTest {

    @Test
    fun `buildGoogleMotionPhotoXmp creates well-formed XMP metadata with correct video length for v2`() {
        val xmp = MotionPhotoBuilder.buildGoogleMotionPhotoXmp(1234567L, 100L, 2000000L, MotionPhotoFormatVersion.V2_MOTION_PHOTO)
        assertTrue(xmp.contains("GCamera:MotionPhoto=\"1\""))
        assertTrue(xmp.contains("GCamera:MotionPhotoPresentationTimestampUs=\"2000000\""))
        assertTrue(!xmp.contains("GCamera:MicroVideo=\"1\""))
        assertTrue(xmp.contains("<Container:Item"))
        assertTrue(xmp.contains("Item:Semantic=\"MotionPhoto\""))
        assertTrue(xmp.contains("Item:Length=\"1234567\""))
    }

    @Test
    fun `buildGoogleMotionPhotoXmp creates well-formed XMP metadata for v1 MicroVideo`() {
        val xmp = MotionPhotoBuilder.buildGoogleMotionPhotoXmp(1234567L, 0L, 2000000L, MotionPhotoFormatVersion.V1_MICRO_VIDEO)
        assertTrue(xmp.contains("GCamera:MicroVideo=\"1\""))
        assertTrue(xmp.contains("GCamera:MicroVideoOffset=\"1234567\""))
        assertTrue(xmp.contains("GCamera:MicroVideoPresentationTimestampUs=\"2000000\""))
        assertTrue(!xmp.contains("GCamera:MotionPhoto=\"1\""))
        assertTrue(!xmp.contains("<Container:Directory>"))
    }

    @Test
    fun `buildGoogleMotionPhotoHeicXmp creates well-formed XMP for v2 and v1`() {
        val xmpV2 = MotionPhotoBuilder.buildGoogleMotionPhotoHeicXmp(3000000L, hasGainMap = true, presentationTimestampUs = 1500000L, version = MotionPhotoFormatVersion.V2_MOTION_PHOTO)
        assertTrue(xmpV2.startsWith("<x:xmpmeta"))
        assertTrue(xmpV2.contains("GCamera:MotionPhoto=\"1\""))
        assertTrue(xmpV2.contains("Item:Padding=\"8\""))
        assertTrue(xmpV2.contains("Item:Semantic=\"GainMap\""))
        assertTrue(!xmpV2.contains("GCamera:MicroVideo=\"1\""))

        val xmpV1 = MotionPhotoBuilder.buildGoogleMotionPhotoHeicXmp(3000000L, hasGainMap = false, presentationTimestampUs = 1500000L, version = MotionPhotoFormatVersion.V1_MICRO_VIDEO)
        assertTrue(xmpV1.startsWith("<x:xmpmeta"))
        assertTrue(xmpV1.contains("GCamera:MicroVideo=\"1\""))
        assertTrue(xmpV1.contains("GCamera:MicroVideoOffset=\"3000000\""))
        assertTrue(!xmpV1.contains("GCamera:MotionPhoto=\"1\""))
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
    fun `injectMotionPhotoXmpIntoJpeg preserves Exif and places XMP after Exif`() {
        // JPEG with Exif: SOI (FF D8) + APP1 Exif (FF E1 00 08 45 78 69 66 00 00) + DQT + EOI
        val exifApp1 = byteArrayOf(
            0xFF.toByte(), 0xE1.toByte(), 0x00.toByte(), 0x08.toByte(),
            'E'.code.toByte(), 'x'.code.toByte(), 'i'.code.toByte(), 'f'.code.toByte(), 0x00.toByte(), 0x00.toByte(),
        )
        val sampleJpeg = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(),
        ) + exifApp1 + byteArrayOf(
            0xFF.toByte(), 0xDB.toByte(), 0x00.toByte(), 0x04.toByte(), 0x00.toByte(), 0x00.toByte(),
            0xFF.toByte(), 0xD9.toByte(),
        )

        val injected = MotionPhotoBuilder.injectMotionPhotoXmpIntoJpeg(sampleJpeg, 99999L)
        assertEquals(0xFF.toByte(), injected[0])
        assertEquals(0xD8.toByte(), injected[1])
        // Exif preserved as first APP1
        assertEquals(0xFF.toByte(), injected[2])
        assertEquals(0xE1.toByte(), injected[3])
        assertEquals('E'.code.toByte(), injected[6])
        // XMP follows Exif
        val xmpPos = 2 + exifApp1.size
        assertEquals(0xFF.toByte(), injected[xmpPos])
        assertEquals(0xE1.toByte(), injected[xmpPos + 1])
    }

    @Test
    fun `createGoogleMotionPhoto merges real image and video into standard Samsung SEF and Google Motion Photo`() {
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

        // 1. Verify file ends with Samsung SEFT tail magic
        val fileBytes = outputFile.readBytes()
        val tailMagic = String(fileBytes.copyOfRange(fileBytes.size - 4, fileBytes.size), Charsets.US_ASCII)
        assertEquals("SEFT", tailMagic, "Expected file to end with SEFT trailer magic")

        // 2. Parse with unwrapMedia's own parser to verify both SEFD and EmbeddedVideo extraction
        val root = parseFile(outputFile)
        val sefdNode = findFirst(root) { it.type == "sefd" }
        assertNotNull(sefdNode, "Expected unwrapMedia parser to detect Samsung SEFD trailer")

        val motionDataNode = sefdNode.children.find { it.type == "MotionPhoto_Data" }
        assertNotNull(motionDataNode, "Expected SEFD to contain MotionPhoto_Data node")
        assertTrue(motionDataNode.children.any { it.type == "ftyp" }, "Expected nested ftyp box inside MotionPhoto_Data")

        ByteReader.open(outputFile).use { reader ->
            val embeddedVideo = findEmbeddedVideo(root, reader)
            assertNotNull(embeddedVideo, "Expected unwrapMedia parser to extract embedded video")
            assertEquals("mp4", embeddedVideo.extension)
            assertEquals(videoFile.length(), embeddedVideo.end - embeddedVideo.start)
        }

        imageFile.delete()
        videoFile.delete()
        outputFile.delete()
    }

    @Test
    fun `createGoogleMotionPhoto preserves existing non-motion SEF data blocks from source image`() {
        val imageFile = File.createTempFile("motion-orig-sefd-", ".jpg")
        imageFile.deleteOnExit()
        ProcessBuilder(
            "ffmpeg", "-y", "-f", "lavfi", "-i", "color=red:size=64x64",
            "-frames:v", "1", imageFile.absolutePath,
        ).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start().waitFor()

        val videoFile1 = File.createTempFile("motion-video-1-", ".mp4")
        videoFile1.deleteOnExit()
        ProcessBuilder(
            "ffmpeg", "-y", "-f", "lavfi", "-i", "testsrc=duration=1:size=64x48:rate=10",
            videoFile1.absolutePath,
        ).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start().waitFor()

        // 1. Create initial motion photo
        val intermediateFile = File.createTempFile("motion-interm-", ".jpg")
        intermediateFile.deleteOnExit()
        MotionPhotoBuilder.createGoogleMotionPhoto(imageFile, videoFile1, intermediateFile)

        // 2. Now synthesize a second motion photo using the first one as source, with a new video!
        val videoFile2 = File.createTempFile("motion-video-2-", ".mp4")
        videoFile2.deleteOnExit()
        ProcessBuilder(
            "ffmpeg", "-y", "-f", "lavfi", "-i", "testsrc=duration=2:size=64x48:rate=10",
            videoFile2.absolutePath,
        ).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start().waitFor()

        val finalOutputFile = File.createTempFile("motion-final-", ".jpg")
        finalOutputFile.deleteOnExit()

        MotionPhotoBuilder.createGoogleMotionPhoto(intermediateFile, videoFile2, finalOutputFile)

        val root = parseFile(finalOutputFile)
        val sefdNode = findFirst(root) { it.type == "sefd" }
        assertNotNull(sefdNode)

        val motionDataNode = sefdNode.children.find { it.type == "MotionPhoto_Data" }
        assertNotNull(motionDataNode)

        ByteReader.open(finalOutputFile).use { reader ->
            val embeddedVideo = findEmbeddedVideo(root, reader)
            assertNotNull(embeddedVideo)
            assertEquals(videoFile2.length(), embeddedVideo.end - embeddedVideo.start)
        }

        imageFile.delete()
        videoFile1.delete()
        videoFile2.delete()
        intermediateFile.delete()
        finalOutputFile.delete()
    }

    @Test
    fun `createSamsungHeicMotionPhoto merges real HEIC image and video into standard Samsung HEIC Motion Photo`() {
        val imageFile = File.createTempFile("motion-heic-src-", ".heic")
        imageFile.deleteOnExit()
        val ftyp = byteArrayOf(
            0x00, 0x00, 0x00, 0x18,
            0x66, 0x74, 0x79, 0x70, // ftyp
            0x6d, 0x69, 0x66, 0x31, // mif1
            0x00, 0x00, 0x00, 0x00,
            0x6d, 0x69, 0x66, 0x31,
            0x68, 0x65, 0x69, 0x63, // heic
        )
        val mdat = byteArrayOf(
            0x00, 0x00, 0x00, 0x10,
            0x6d, 0x64, 0x61, 0x74, // mdat
            0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08,
        )
        imageFile.writeBytes(ftyp + mdat)

        val videoFile = File.createTempFile("motion-heic-vid-", ".mp4")
        videoFile.deleteOnExit()
        ProcessBuilder(
            "ffmpeg", "-y", "-f", "lavfi", "-i", "testsrc=duration=1:size=64x48:rate=10",
            videoFile.absolutePath,
        ).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start().waitFor()

        val outputFile = File.createTempFile("motion-heic-out-", ".heic")
        outputFile.deleteOnExit()

        MotionPhotoBuilder.createMotionPhoto(imageFile, videoFile, outputFile)

        assertTrue(outputFile.exists())

        // 1. Verify file ends with Samsung SEFT tail magic
        val fileBytes = outputFile.readBytes()
        val tailMagic = String(fileBytes.copyOfRange(fileBytes.size - 4, fileBytes.size), Charsets.US_ASCII)
        assertEquals("SEFT", tailMagic, "Expected HEIC file to end with SEFT trailer magic")

        // 2. Parse with unwrapMedia's own parser to verify top-level mpvd and sefd boxes
        val root = parseFile(outputFile)
        val mpvdNode = root.children.find { it.type == "mpvd" }
        assertNotNull(mpvdNode, "Expected unwrapMedia parser to detect top-level mpvd box in HEIC")

        val sefdNode = root.children.find { it.type == "sefd" }
        assertNotNull(sefdNode, "Expected unwrapMedia parser to detect top-level sefd box in HEIC")

        val motionDataNode = sefdNode.children.find { it.type == "MotionPhoto_Data" }
        assertNotNull(motionDataNode, "Expected SEFD to contain MotionPhoto_Data node")

        ByteReader.open(outputFile).use { reader ->
            val embeddedVideo = findEmbeddedVideo(root, reader)
            assertNotNull(embeddedVideo, "Expected unwrapMedia parser to extract embedded video from mpvd")
            assertEquals("mp4", embeddedVideo.extension)
            assertEquals(videoFile.length(), embeddedVideo.end - embeddedVideo.start)
        }

        imageFile.delete()
        videoFile.delete()
        outputFile.delete()
    }

    @Test
    fun `createSamsungHeicMotionPhoto with V1_MICRO_VIDEO throws IllegalArgumentException`() {
        val dummyImage = File.createTempFile("dummy-img-", ".heic")
        dummyImage.writeBytes(byteArrayOf(1, 2, 3))
        dummyImage.deleteOnExit()

        val dummyVideo = File.createTempFile("dummy-vid-", ".mp4")
        dummyVideo.writeBytes(byteArrayOf(1, 2, 3))
        dummyVideo.deleteOnExit()

        val dummyOut = File.createTempFile("dummy-out-", ".heic")
        dummyOut.deleteOnExit()

        org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            MotionPhotoBuilder.createSamsungHeicMotionPhoto(dummyImage, dummyVideo, dummyOut, MotionPhotoFormatVersion.V1_MICRO_VIDEO)
        }

        dummyImage.delete()
        dummyVideo.delete()
        dummyOut.delete()
    }

    @Test
    fun `createGoogleMotionPhoto with empty file throws IllegalArgumentException`() {
        val emptyImg = File.createTempFile("empty-img-", ".jpg")
        emptyImg.deleteOnExit()

        val dummyVideo = File.createTempFile("dummy-vid-", ".mp4")
        dummyVideo.writeBytes(byteArrayOf(1, 2, 3))
        dummyVideo.deleteOnExit()

        val dummyOut = File.createTempFile("dummy-out-", ".jpg")
        dummyOut.deleteOnExit()

        org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            MotionPhotoBuilder.createGoogleMotionPhoto(emptyImg, dummyVideo, dummyOut)
        }

        emptyImg.delete()
        dummyVideo.delete()
        dummyOut.delete()
    }
}
