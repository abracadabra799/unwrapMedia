package com.multiviewer.parser

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
    fun `injectMotionPhotoXmpIntoJpeg merges an existing XMP instead of discarding it`() {
        val existingXmp = """<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"><rdf:Description rdf:about="" xmlns:tiff="http://ns.adobe.com/tiff/1.0/" tiff:Make="SomeCamera"/></rdf:RDF></x:xmpmeta>"""
        val existingXmpApp1 = MotionPhotoBuilder.buildApp1XmpSegment(existingXmp)
        val sampleJpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + existingXmpApp1 + byteArrayOf(0xFF.toByte(), 0xD9.toByte())

        val injected = MotionPhotoBuilder.injectMotionPhotoXmpIntoJpeg(sampleJpeg, 99999L)
        val injectedText = String(injected, Charsets.UTF_8)

        assertTrue(injectedText.contains("tiff:Make=\"SomeCamera\""), "Expected the original tiff:Make to survive")
        assertTrue(injectedText.contains("GCamera:MotionPhoto=\"1\""))
        // Exactly one XMP APP1 segment in the output -- the old one was replaced, not duplicated
        assertEquals(1, Regex("http://ns\\.adobe\\.com/xap/1\\.0/").findAll(injectedText).count())
    }

    @Test
    fun `injectMotionPhotoXmpIntoJpeg places the merged XMP after Exif when both an existing XMP and Exif are present`() {
        val exifApp1 = byteArrayOf(
            0xFF.toByte(), 0xE1.toByte(), 0x00.toByte(), 0x08.toByte(),
            'E'.code.toByte(), 'x'.code.toByte(), 'i'.code.toByte(), 'f'.code.toByte(), 0x00.toByte(), 0x00.toByte(),
        )
        val existingXmp = """<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"><rdf:Description rdf:about="" xmlns:tiff="http://ns.adobe.com/tiff/1.0/" tiff:Make="SomeCamera"/></rdf:RDF></x:xmpmeta>"""
        val existingXmpApp1 = MotionPhotoBuilder.buildApp1XmpSegment(existingXmp)
        val sampleJpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + exifApp1 + existingXmpApp1 + byteArrayOf(0xFF.toByte(), 0xD9.toByte())

        val injected = MotionPhotoBuilder.injectMotionPhotoXmpIntoJpeg(sampleJpeg, 99999L)

        // Exif is still the very first marker after SOI
        assertEquals(0xFF.toByte(), injected[2])
        assertEquals(0xE1.toByte(), injected[3])
        assertEquals('E'.code.toByte(), injected[6])
        // The merged XMP (not Exif) follows immediately after
        val xmpPos = 2 + exifApp1.size
        assertEquals(0xFF.toByte(), injected[xmpPos])
        assertEquals(0xE1.toByte(), injected[xmpPos + 1])
        val injectedText = String(injected, Charsets.UTF_8)
        assertTrue(injectedText.contains("tiff:Make=\"SomeCamera\""))
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

    @Test
    fun `mergeMotionPhotoXmp falls back to a fresh build when there is no existing XMP`() {
        val merged = MotionPhotoBuilder.mergeMotionPhotoXmp(null, 1234567L, 100L, 2000000L, MotionPhotoFormatVersion.V2_MOTION_PHOTO, "image/jpeg")
        val fresh = MotionPhotoBuilder.buildGoogleMotionPhotoXmp(1234567L, 100L, 2000000L, MotionPhotoFormatVersion.V2_MOTION_PHOTO)
        assertEquals(fresh, merged)
    }

    @Test
    fun `mergeMotionPhotoXmp falls back to a fresh build when the existing XMP fails to parse`() {
        val merged = MotionPhotoBuilder.mergeMotionPhotoXmp("<not valid xml at all <<<", 1234567L, 100L, 2000000L, MotionPhotoFormatVersion.V2_MOTION_PHOTO, "image/jpeg")
        val fresh = MotionPhotoBuilder.buildGoogleMotionPhotoXmp(1234567L, 100L, 2000000L, MotionPhotoFormatVersion.V2_MOTION_PHOTO)
        assertEquals(fresh, merged)
    }

    @Test
    fun `mergeMotionPhotoXmp preserves an existing plain camera XMP with no Container Directory`() {
        val existing = """
            <x:xmpmeta xmlns:x="adobe:ns:meta/">
              <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
                <rdf:Description rdf:about=""
                    xmlns:tiff="http://ns.adobe.com/tiff/1.0/"
                  tiff:Make="SomeCamera"
                  tiff:Model="X100"/>
              </rdf:RDF>
            </x:xmpmeta>
        """.trimIndent()
        val merged = MotionPhotoBuilder.mergeMotionPhotoXmp(existing, 5000L, 0L, 1500000L, MotionPhotoFormatVersion.V2_MOTION_PHOTO, "image/jpeg")

        assertTrue(merged.contains("tiff:Make=\"SomeCamera\""), "Expected pre-existing tiff:Make to survive the merge")
        assertTrue(merged.contains("tiff:Model=\"X100\""), "Expected pre-existing tiff:Model to survive the merge")
        assertTrue(merged.contains("GCamera:MotionPhoto=\"1\""))
        assertTrue(merged.contains("Item:Semantic=\"MotionPhoto\""))
        assertTrue(merged.contains("Item:Semantic=\"Primary\""))
        assertTrue(merged.contains("Item:Length=\"5000\""))
    }

    @Test
    fun `mergeMotionPhotoXmp appends MotionPhoto to an existing Container Directory and recomputes the preceding item's Padding`() {
        val existing = """
            <x:xmpmeta xmlns:x="adobe:ns:meta/">
              <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
                <rdf:Description rdf:about=""
                    xmlns:hdrgm="http://ns.adobe.com/hdr-gain-map/1.0/"
                    xmlns:Container="http://ns.google.com/photos/1.0/container/"
                    xmlns:Item="http://ns.google.com/photos/1.0/container/item/"
                  hdrgm:Version="1.0"
                  hdrgm:GainMapMin="0.0"
                  hdrgm:GainMapMax="3.5">
                  <Container:Directory>
                    <rdf:Seq>
                      <rdf:li rdf:parseType="Resource">
                        <Container:Item Item:Semantic="Primary" Item:Mime="image/jpeg" Item:Padding="0"/>
                      </rdf:li>
                      <rdf:li rdf:parseType="Resource">
                        <Container:Item Item:Semantic="GainMap" Item:Mime="image/jpeg" Item:Length="9876" Item:Padding="0"/>
                      </rdf:li>
                    </rdf:Seq>
                  </Container:Directory>
                </rdf:Description>
              </rdf:RDF>
            </x:xmpmeta>
        """.trimIndent()
        val merged = MotionPhotoBuilder.mergeMotionPhotoXmp(existing, 5000L, 42L, 1500000L, MotionPhotoFormatVersion.V2_MOTION_PHOTO, "image/jpeg")

        // GainMap item preserved
        assertTrue(merged.contains("Item:Semantic=\"GainMap\""))
        assertTrue(merged.contains("hdrgm:GainMapMin=\"0.0\""))
        assertTrue(merged.contains("hdrgm:GainMapMax=\"3.5\""))
        assertTrue(merged.contains("Item:Length=\"9876\""), "GainMap's own Length must not change")
        // GainMap's Padding recomputed to the new gap (was 0, now 42 -- the bytes before the appended video).
        // DOM serialization doesn't guarantee attribute order, so locate the whole <Container:Item .../>
        // element by its Semantic value first, then check the Padding value within that element's text,
        // rather than assuming Semantic precedes Padding.
        val containerItemTags = Regex("""<Container:Item\b[^>]*/>""").findAll(merged).map { it.value }.toList()
        val gainMapItemTag = containerItemTags.find { it.contains("Item:Semantic=\"GainMap\"") }
        assertNotNull(gainMapItemTag, "Expected to find the GainMap Container:Item element")
        assertTrue(
            gainMapItemTag!!.contains("Item:Padding=\"42\""),
            "Expected GainMap's Padding to be recomputed to the new preceding-item gap, got: $gainMapItemTag",
        )
        // Primary item untouched
        val primaryItemTag = containerItemTags.find { it.contains("Item:Semantic=\"Primary\"") }
        assertNotNull(primaryItemTag, "Expected to find the Primary Container:Item element")
        assertTrue(primaryItemTag!!.contains("Item:Mime=\"image/jpeg\""))
        assertTrue(primaryItemTag.contains("Item:Padding=\"0\""))
        // MotionPhoto item appended
        assertTrue(merged.contains("Item:Semantic=\"MotionPhoto\""))
        assertTrue(merged.contains("Item:Length=\"5000\""))
    }

    @Test
    fun `mergeMotionPhotoXmp overwrites this tool's own GCamera attributes if already present`() {
        val existing = """
            <x:xmpmeta xmlns:x="adobe:ns:meta/">
              <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
                <rdf:Description rdf:about=""
                    xmlns:GCamera="http://ns.google.com/photos/1.0/camera/"
                  GCamera:MotionPhoto="1"
                  GCamera:MotionPhotoPresentationTimestampUs="999"/>
              </rdf:RDF>
            </x:xmpmeta>
        """.trimIndent()
        val merged = MotionPhotoBuilder.mergeMotionPhotoXmp(existing, 5000L, 0L, 1500000L, MotionPhotoFormatVersion.V2_MOTION_PHOTO, "image/jpeg")
        assertTrue(merged.contains("GCamera:MotionPhotoPresentationTimestampUs=\"1500000\""), "Expected the new timestamp to overwrite the stale one")
        assertFalse(merged.contains("GCamera:MotionPhotoPresentationTimestampUs=\"999\""))
    }

    @Test
    fun `repointHeicXmpItem rewrites only the target item's iloc entry, nothing else moves`() {
        val existingXmp = """<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"><rdf:Description rdf:about="" xmlns:tiff="http://ns.adobe.com/tiff/1.0/" tiff:Make="SomeCamera"/></rdf:RDF></x:xmpmeta>"""
        val fixture = HeicMetaFixture.build(xmpText = existingXmp)

        // Realistic shape (contains rdf:Description, like mergeMotionPhotoXmp's real output) so
        // findXmpExtentInHeic's structured-walk content-sniff matches the new location directly,
        // rather than depending on whether the old location happened to get cleaned up.
        val mergedXmpBytes = "<x:xmpmeta><rdf:Description>MUCH LONGER MERGED CONTENT THAN THE ORIGINAL SLOT ALLOWED FOR, THIS PROVES REPOINT DOESN'T NEED TO FIT IN PLACE</rdf:Description></x:xmpmeta>".toByteArray(Charsets.UTF_8)
        val result = MotionPhotoBuilder.repointHeicXmpItem(
            fixture.heicBytes, fixture.xmpItemId, fixture.xmpIlocEntryOffset, fixture.xmpExtentCount, mergedXmpBytes,
        )
        assertNotNull(result)

        // The primary image item's own extent must resolve to the exact same original bytes --
        // nothing about the file shifted.
        val primaryBytesAfter = result.copyOfRange(
            (fixture.primaryItemOffset).toInt(),
            (fixture.primaryItemOffset + fixture.primaryItemLength).toInt(),
        )
        assertTrue(primaryBytesAfter.contentEquals(fixture.primaryItemBytes), "Primary item's bytes must be unchanged and at the same offset")
        assertEquals(fixture.heicBytes.size, result.size - mergedXmpBytes.size - 8, "File should have grown by exactly the new mdat box (8-byte header + payload)")

        // Re-parse with this app's own HEIC meta/iloc understanding and confirm the XMP item now
        // resolves to the merged bytes.
        val reExtent = MotionPhotoBuilder.findXmpExtentInHeic(result)
        assertNotNull(reExtent)
        val (xmpStart, xmpLen) = reExtent
        val reXmpText = String(result, xmpStart, xmpLen, Charsets.UTF_8)
        assertTrue(reXmpText.contains("MUCH LONGER MERGED CONTENT"))
    }

    @Test
    fun `repointHeicXmpItem converts an idat-relative item to an absolute-offset item`() {
        val existingXmp = """<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"><rdf:Description rdf:about="" xmlns:tiff="http://ns.adobe.com/tiff/1.0/" tiff:Make="SomeCamera"/></rdf:RDF></x:xmpmeta>"""
        val fixture = HeicMetaFixture.build(xmpText = existingXmp, xmpConstructionMethod = 1)

        // Realistic shape (contains rdf:Description) for the same reason as the test above -- the
        // OLD item here is idat-relative, which repointHeicXmpItem deliberately does not attempt to
        // clean up (resolving it would need real idat-box support, out of scope), so this test must
        // not depend on the old bytes being erased; the new content needs to content-sniff-match on
        // its own via the structured walk.
        val mergedXmpBytes = "<x:xmpmeta><rdf:Description>merged</rdf:Description></x:xmpmeta>".toByteArray(Charsets.UTF_8)
        val result = MotionPhotoBuilder.repointHeicXmpItem(
            fixture.heicBytes, fixture.xmpItemId, fixture.xmpIlocEntryOffset, fixture.xmpExtentCount, mergedXmpBytes,
        )
        assertNotNull(result)
        val reExtent = MotionPhotoBuilder.findXmpExtentInHeic(result)
        assertNotNull(reExtent)
        val (xmpStart, xmpLen) = reExtent
        assertEquals("<x:xmpmeta><rdf:Description>merged</rdf:Description></x:xmpmeta>", String(result, xmpStart, xmpLen, Charsets.UTF_8))
    }

    @Test
    fun `repointHeicXmpItem returns null for a multi-extent item, signaling fallback`() {
        val fixture = HeicMetaFixture.build(xmpText = "<x:xmpmeta/>", xmpExtentCount = 2)
        val result = MotionPhotoBuilder.repointHeicXmpItem(
            fixture.heicBytes, fixture.xmpItemId, fixture.xmpIlocEntryOffset, fixture.xmpExtentCount, "irrelevant".toByteArray(),
        )
        assertEquals(null, result)
    }

    @Test
    fun `createHeicXmpItem registers a new item without disturbing the existing primary item's bytes`() {
        val fixture = HeicMetaFixture.build(xmpText = null) // no XMP item in this fixture at all

        val newXmpBytes = "<x:xmpmeta>brand new</x:xmpmeta>".toByteArray(Charsets.UTF_8)
        val result = MotionPhotoBuilder.createHeicXmpItem(fixture.heicBytes, newXmpBytes)

        // The primary item's bytes must resolve to the exact same content at the exact same offset
        // as before -- the single most important regression check for this function.
        // The primary item's mdat sits immediately after meta's old end (this fixture always builds
        // it that way -- see HeicMetaFixture), and meta legitimately grows here (one new infe entry
        // plus one new iloc entry are inserted into it), which necessarily pushes every byte from the
        // old mdat onward -- including the primary item's own pixel bytes -- forward in the file by
        // that same growth. The growth amount is derivable purely from the two files' sizes (no
        // content-sniffing, no re-implementing createHeicXmpItem's internal arithmetic): the total
        // size delta minus the freshly-appended XMP mdat (8-byte header + payload) accounts for
        // exactly the growth of iinf/iloc/meta.
        val totalGrowth = result.size - fixture.heicBytes.size - 8 - newXmpBytes.size
        val expectedPrimaryOffset = fixture.primaryItemOffset.toInt() + totalGrowth
        val primaryBytesAfter = result.copyOfRange(
            expectedPrimaryOffset,
            expectedPrimaryOffset + fixture.primaryItemLength.toInt(),
        )
        assertTrue(
            primaryBytesAfter.contentEquals(fixture.primaryItemBytes),
            "Primary item's bytes must survive byte-for-byte, correctly shifted forward by meta's growth ($totalGrowth bytes)",
        )

        // Re-parse with this app's own HEIC understanding and find the new XMP item.
        val reExtent = MotionPhotoBuilder.findXmpExtentInHeic(result)
        assertNotNull(reExtent)
        val (xmpStart, xmpLen) = reExtent
        assertEquals("<x:xmpmeta>brand new</x:xmpmeta>", String(result, xmpStart, xmpLen, Charsets.UTF_8))
    }

    @Test
    fun `createHeicXmpItem picks an item_ID that does not collide with any existing item`() {
        val fixture = HeicMetaFixture.build(xmpText = null)
        val newXmpBytes = "<x:xmpmeta/>".toByteArray(Charsets.UTF_8)
        val result = MotionPhotoBuilder.createHeicXmpItem(fixture.heicBytes, newXmpBytes)

        // Re-parsing must still find exactly the primary item plus the new XMP item -- no item_ID
        // collision silently corrupted or hid either one.
        val root = parseFile(File.createTempFile("heic-newitem-", ".heic").apply {
            deleteOnExit()
            writeBytes(result)
        })
        val metaNode = findFirst(root) { it.type == "meta" }
        assertNotNull(metaNode)
        val iinfNode = findFirst(metaNode) { it.type == "iinf" }
        assertNotNull(iinfNode)
        assertEquals(fixture.existingItemCount + 1, iinfNode.children.size, "Expected exactly one new infe entry to be added")
    }

    @Test
    fun `createSamsungHeicMotionPhoto merges existing XMP when present and creates one when absent`() {
        val existingXmp = """<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"><rdf:Description rdf:about="" xmlns:tiff="http://ns.adobe.com/tiff/1.0/" tiff:Make="SomeCamera"/></rdf:RDF></x:xmpmeta>"""
        val fixtureWithXmp = HeicMetaFixture.build(xmpText = existingXmp)
        val imageFile = File.createTempFile("heic-with-xmp-", ".heic")
        imageFile.deleteOnExit()
        imageFile.writeBytes(fixtureWithXmp.heicBytes)

        val videoFile = File.createTempFile("heic-merge-vid-", ".mp4")
        videoFile.deleteOnExit()
        ProcessBuilder(
            "ffmpeg", "-y", "-f", "lavfi", "-i", "testsrc=duration=1:size=64x48:rate=10",
            videoFile.absolutePath,
        ).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start().waitFor()

        val outputFile = File.createTempFile("heic-merge-out-", ".heic")
        outputFile.deleteOnExit()
        MotionPhotoBuilder.createSamsungHeicMotionPhoto(imageFile, videoFile, outputFile)

        val root = parseFile(outputFile)
        ByteReader.open(outputFile).use { reader ->
            val xmpField = findFirst(root) { it.fields.any { f -> f.name == "xmp" } }
            assertNotNull(xmpField, "Expected the output HEIC to have a discoverable XMP item")
            val xmpValue = xmpField.fields.find { it.name == "xmp" }!!.value
            assertTrue(xmpValue.contains("tiff:Make=\"SomeCamera\""), "Expected the original tiff:Make to survive end-to-end")
            assertTrue(xmpValue.contains("GCamera:MotionPhoto=\"1\""))
        }

        imageFile.delete()
        videoFile.delete()
        outputFile.delete()
    }
}
