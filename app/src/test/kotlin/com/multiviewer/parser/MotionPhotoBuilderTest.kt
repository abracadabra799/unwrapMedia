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
    fun `createHeicXmpItem keeps the primary item's own iloc entry resolving to its original bytes`() {
        val fixture = HeicMetaFixture.build(xmpText = null) // no XMP item in this fixture at all

        val newXmpBytes = NEW_XMP_PACKET.toByteArray(Charsets.UTF_8)
        val result = MotionPhotoBuilder.createHeicXmpItem(fixture.heicBytes, newXmpBytes)

        // THE regression check the design spec calls out: "the pre-existing primary-image item's
        // `iloc` offset, after the insertion, still resolves to the exact same original bytes".
        //
        // That means reading the primary item's ACTUAL iloc entry fields (base_offset +
        // extent_offset + extent_length) out of the PRODUCED bytes and resolving them -- not reading
        // raw bytes at a position derived from the overall file-size delta. The latter passes even
        // with the offset-shift-correction pass entirely removed, because the primary's bytes
        // physically move via the straight byte copy regardless of whether the iloc entry that
        // describes their position was updated to match.
        val entriesBefore = readIlocEntries(fixture.heicBytes)
        val entriesAfter = readIlocEntries(result)

        val primaryAfter = entriesAfter[HeicMetaFixture.PRIMARY_ITEM_ID]
        assertNotNull(primaryAfter, "Expected the primary item's iloc entry to still be registered")
        assertEquals(0, primaryAfter.constructionMethod, "Primary item must still be an absolute-offset item")
        assertEquals(1, primaryAfter.extents.size, "Primary item's extent list must not have changed shape")
        assertTrue(
            primaryAfter.resolveBytes(result).contentEquals(fixture.primaryItemBytes),
            "The primary item's iloc entry must resolve to its original bytes byte-for-byte; " +
                "it resolves to ${primaryAfter.resolveBytes(result).toList()} instead",
        )

        // ...and the entry must genuinely have been REWRITTEN, by exactly meta's growth -- otherwise
        // the assertion above could be passing for the wrong reason (e.g. a fixture where nothing
        // needed to move).
        val metaGrowth = result.size - fixture.heicBytes.size - 8 - newXmpBytes.size
        assertTrue(metaGrowth > 0, "Expected meta to have grown (one new infe + one new iloc entry)")
        val primaryBefore = entriesBefore.getValue(HeicMetaFixture.PRIMARY_ITEM_ID)
        assertEquals(
            primaryBefore.absoluteStart() + metaGrowth,
            primaryAfter.absoluteStart(),
            "Primary item's resolved absolute offset must have been shifted forward by exactly meta's growth ($metaGrowth)",
        )
        assertEquals(primaryBefore.extents[0].second, primaryAfter.extents[0].second, "Primary item's extent_length must not change")

        // The new XMP item: registered with a non-colliding item_ID, and its own iloc entry resolves
        // to exactly the bytes handed in.
        assertEquals(fixture.existingItemCount + 1, entriesAfter.size, "Expected exactly one new iloc item entry")
        val newEntry = entriesAfter.values.last()
        assertTrue(
            newEntry.itemId !in entriesBefore.keys,
            "New item's item_ID (${newEntry.itemId}) must not collide with any pre-existing item ${entriesBefore.keys}",
        )
        assertEquals(0, newEntry.constructionMethod, "New XMP item must be registered as an absolute-offset item")
        assertTrue(newEntry.resolveBytes(result).contentEquals(newXmpBytes), "New XMP item's iloc entry must resolve to the XMP bytes")

        // Finally, this app's own HEIC reader must find it via its structured meta/iloc walk.
        val reExtent = MotionPhotoBuilder.findXmpExtentInHeic(result)
        assertNotNull(reExtent)
        val (xmpStart, xmpLen) = reExtent
        assertEquals(NEW_XMP_PACKET, String(result, xmpStart, xmpLen, Charsets.UTF_8))
    }

    @Test
    fun `createHeicXmpItem picks an item_ID that does not collide with any existing item`() {
        val fixture = HeicMetaFixture.build(xmpText = null)
        // A realistic packet shape (carries a literal rdf:Description, like mergeMotionPhotoXmp's own
        // output) so findXmpExtentInHeic's STRUCTURED meta/iloc walk can match it. A bare
        // "<x:xmpmeta/>" matches neither "Container" nor "rdf:Description" and is under its 20-byte
        // minimum, so the walk silently falls through to a whole-file raw pattern scan -- which only
        // proves some bytes were appended somewhere, not that the item is registered and locatable.
        val newXmpBytes = NEW_XMP_PACKET.toByteArray(Charsets.UTF_8)
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

        // The new infe's item_ID must match the new iloc entry's item_ID, and both must be distinct
        // from every pre-existing item -- the actual anti-collision claim, checked on real fields.
        val infeItemIds = iinfNode.children.map { infe -> infe.fields.first { it.name == "item_ID" }.value.toLong() }
        assertEquals(infeItemIds.size, infeItemIds.distinct().size, "infe item_IDs must be unique: $infeItemIds")
        assertEquals(infeItemIds.toSortedSet(), readIlocEntries(result).keys.toSortedSet(), "iinf and iloc must register the same item_ID set")

        // And the structured walk locates the new item's content via iloc (not via the fallback scan).
        val reExtent = MotionPhotoBuilder.findXmpExtentInHeic(result)
        assertNotNull(reExtent)
        assertEquals(NEW_XMP_PACKET, String(result, reExtent.first, reExtent.second, Charsets.UTF_8))
    }

    @Test
    fun `createHeicXmpItem leaves an idat-relative primary item's iloc offset unshifted`() {
        // construction_method=1 is idat-relative: its "offset" is an offset into the idat box, not an
        // absolute file offset, so meta growing must NOT change it. This exercises the exemption in
        // createHeicXmpItem's own offset-shift-correction pass (previously only covered for
        // repointHeicXmpItem's separate idat path).
        val fixture = HeicMetaFixture.build(xmpText = null, primaryConstructionMethod = 1)
        val newXmpBytes = NEW_XMP_PACKET.toByteArray(Charsets.UTF_8)
        val result = MotionPhotoBuilder.createHeicXmpItem(fixture.heicBytes, newXmpBytes)

        val metaGrowth = result.size - fixture.heicBytes.size - 8 - newXmpBytes.size
        assertTrue(metaGrowth > 0, "Expected meta to grow -- otherwise a missing exemption couldn't be observed")

        val before = readIlocEntries(fixture.heicBytes).getValue(HeicMetaFixture.PRIMARY_ITEM_ID)
        val after = readIlocEntries(result).getValue(HeicMetaFixture.PRIMARY_ITEM_ID)
        assertEquals(1, after.constructionMethod, "Fixture must actually have written construction_method=1")
        assertEquals(
            before.extents[0].first,
            after.extents[0].first,
            "An idat-relative (construction_method=1) extent_offset must be left untouched when meta grows by $metaGrowth",
        )
        assertEquals(before.baseOffset, after.baseOffset, "An idat-relative entry's base_offset must be left untouched too")
    }

    @Test
    fun `createHeicXmpItem bails out when iloc's item_count disagrees with iinf's infe count`() {
        // iloc's item_count and iinf's infe count are different fields in different boxes; nothing
        // structurally guarantees they agree. The offset-shift-correction pass is bounded by the
        // PRE-growth ILOC count specifically, so if the two disagree the premise is broken: an
        // undercount would leave the primary image's extent pointing into the grown meta box, an
        // overcount would double-shift the freshly-appended entry. Bail out unchanged instead.
        val fixture = HeicMetaFixture.build(xmpText = null)
        val corrupted = fixture.heicBytes.copyOf()
        val itemCountPos = fixture.primaryIlocEntryOffset.toInt() - 2 // entries start right after item_count (u16, iloc v1)
        assertEquals(1, ((corrupted[itemCountPos].toInt() and 0xFF) shl 8) or (corrupted[itemCountPos + 1].toInt() and 0xFF))
        corrupted[itemCountPos + 1] = 2 // claim 2 items while iinf still registers only 1

        val result = MotionPhotoBuilder.createHeicXmpItem(corrupted, NEW_XMP_PACKET.toByteArray(Charsets.UTF_8))
        assertTrue(result.contentEquals(corrupted), "Expected createHeicXmpItem to return the input untouched on an iinf/iloc count mismatch")
    }

    @Test
    fun `createHeicXmpItem bails out when the new item_ID would not fit in iloc's item_ID width`() {
        // iloc version 1 writes item_ID in 2 bytes. If the highest existing item_ID is already 0xFFFF,
        // the next ID (0x10000) cannot be written there -- silently truncating it to 0x0000 would
        // collide with a real item. (infe's item_ID width follows infe's OWN version rules, which is
        // why the two widths have to be checked separately.)
        val fixture = HeicMetaFixture.build(xmpText = null)
        val bytes = fixture.heicBytes.copyOf()

        // Raise the primary item's ID to 0xFFFF in both boxes. Both fields are 2 bytes wide here, so
        // this is a size-preserving in-place patch -- no offsets move.
        val infeIndex = indexOfAscii(bytes, "infe")
        assertTrue(infeIndex >= 0, "Expected to find an infe box in the fixture")
        val infeItemIdPos = infeIndex + 8 // "infe" at boxStart+4, item_ID at boxStart+12
        assertEquals(HeicMetaFixture.PRIMARY_ITEM_ID.toInt(), ((bytes[infeItemIdPos].toInt() and 0xFF) shl 8) or (bytes[infeItemIdPos + 1].toInt() and 0xFF))
        bytes[infeItemIdPos] = 0xFF.toByte()
        bytes[infeItemIdPos + 1] = 0xFF.toByte()
        val ilocItemIdPos = fixture.primaryIlocEntryOffset.toInt()
        bytes[ilocItemIdPos] = 0xFF.toByte()
        bytes[ilocItemIdPos + 1] = 0xFF.toByte()

        val result = MotionPhotoBuilder.createHeicXmpItem(bytes, NEW_XMP_PACKET.toByteArray(Charsets.UTF_8))
        assertTrue(result.contentEquals(bytes), "Expected createHeicXmpItem to bail out rather than truncate the new item_ID into a collision")
    }

    @Test
    fun `createHeicXmpItem handles a meta box that emits iloc before iinf`() {
        // ISOBMFF imposes no ordering on meta's children, and real encoders do emit iloc first. The
        // "copy the region between the two boxes" step previously assumed iinf came first, making that
        // copy a negative length -- an uncaught exception propagating out of createMotionPhoto and
        // aborting motion-photo creation entirely.
        val fixture = HeicMetaFixture.build(xmpText = null, ilocBeforeIinf = true)

        // Premise check: the fixture really did emit iloc first, so this test can't silently be
        // re-testing the ordinary iinf-first layout.
        val ilocIndex = indexOfAscii(fixture.heicBytes, "iloc")
        val iinfIndex = indexOfAscii(fixture.heicBytes, "iinf")
        assertTrue(ilocIndex in 0 until iinfIndex, "Expected the fixture to place iloc ($ilocIndex) before iinf ($iinfIndex)")

        val newXmpBytes = NEW_XMP_PACKET.toByteArray(Charsets.UTF_8)
        val result = MotionPhotoBuilder.createHeicXmpItem(fixture.heicBytes, newXmpBytes)

        assertTrue(result.size > fixture.heicBytes.size, "Expected the reversed-order file to actually be rewritten, not bailed out of")

        // Same guarantees as the iinf-first case: the primary item still resolves to its own bytes,
        // and the new XMP item is registered and locatable.
        val entriesAfter = readIlocEntries(result)
        val primary = entriesAfter.getValue(HeicMetaFixture.PRIMARY_ITEM_ID)
        assertTrue(primary.resolveBytes(result).contentEquals(fixture.primaryItemBytes), "Primary item's iloc entry must still resolve to its original bytes")
        assertEquals(fixture.existingItemCount + 1, entriesAfter.size, "Expected exactly one new iloc item entry")
        val newEntry = entriesAfter.values.last()
        assertTrue(newEntry.resolveBytes(result).contentEquals(newXmpBytes), "New XMP item's iloc entry must resolve to the XMP bytes")

        val reExtent = MotionPhotoBuilder.findXmpExtentInHeic(result)
        assertNotNull(reExtent, "Expected the new XMP item to be locatable via meta/iloc")
        assertEquals(NEW_XMP_PACKET, String(result, reExtent.first, reExtent.second, Charsets.UTF_8))

        // And this app's own parser still reads the rewritten meta box cleanly.
        val tmp = File.createTempFile("heic-iloc-first-", ".heic").apply { deleteOnExit(); writeBytes(result) }
        val root = parseFile(tmp)
        assertNotNull(findFirst(root) { it.type == "iinf" }, "Expected iinf to still be parseable after the rewrite")
        assertNotNull(findFirst(root) { it.type == "iloc" }, "Expected iloc to still be parseable after the rewrite")
        tmp.delete()
    }

    @Test
    fun `createSamsungHeicMotionPhoto merges an Adobe-style xpacket XMP in place instead of registering a duplicate item`() {
        // The Finding-4 root case. This packet is shaped like real camera/Adobe XMP: an
        // `<?xpacket begin ...?>` processing instruction ahead of `<x:xmpmeta`, then namespace
        // declarations, so `rdf:Description` lands well past byte 100 of the item's extent. That
        // defeats findXmpExtentInHeic's structured-walk content sniff (it only samples the first 100
        // bytes and needs "Container" or "rdf:Description" in them), which used to be read as "this
        // file has no XMP item" -- and a SECOND XMP item would be registered while the original stayed
        // registered earlier in the list, so every reader that returns the first match would keep
        // reading the STALE one. Locating the item structurally (iinf item_type/content_type, then
        // iloc by item_ID) makes the sniff irrelevant.
        val existingXmp = "<?xpacket begin=\"﻿\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>" +
            "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\" x:xmptk=\"Adobe XMP Core 5.6-c148 79.164036\">" +
            "<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">" +
            "<rdf:Description rdf:about=\"\" xmlns:tiff=\"http://ns.adobe.com/tiff/1.0/\" tiff:Make=\"SomeCamera\"/>" +
            "</rdf:RDF></x:xmpmeta><?xpacket end=\"w\"?>"

        // Confirm the premise: the content sniff really does mislocate this packet, so this test is
        // exercising the intended scenario rather than passing for an unrelated reason.
        val fixture = HeicMetaFixture.build(xmpText = existingXmp)
        val sniffed = MotionPhotoBuilder.findXmpExtentInHeic(fixture.heicBytes)
        assertNotNull(sniffed)
        val xmpItemStart = readIlocEntries(fixture.heicBytes).getValue(HeicMetaFixture.XMP_ITEM_ID).absoluteStart()
        assertTrue(
            sniffed.first.toLong() != xmpItemStart,
            "Premise check: expected the content sniff to MISS this packet's real item start ($xmpItemStart), got ${sniffed.first}",
        )

        val imageFile = File.createTempFile("heic-xpacket-", ".heic")
        imageFile.deleteOnExit()
        imageFile.writeBytes(fixture.heicBytes)

        val videoFile = File.createTempFile("heic-xpacket-vid-", ".mp4")
        videoFile.deleteOnExit()
        ProcessBuilder(
            "ffmpeg", "-y", "-f", "lavfi", "-i", "testsrc=duration=1:size=64x48:rate=10",
            videoFile.absolutePath,
        ).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start().waitFor()

        val outputFile = File.createTempFile("heic-xpacket-out-", ".heic")
        outputFile.deleteOnExit()
        MotionPhotoBuilder.createSamsungHeicMotionPhoto(imageFile, videoFile, outputFile)

        val outBytes = outputFile.readBytes()
        val entries = readIlocEntries(outBytes)
        assertEquals(
            fixture.existingItemCount,
            entries.size,
            "Expected the EXISTING XMP item to be repointed, not a second one registered alongside it (items: ${entries.keys})",
        )

        // The one registered XMP item must resolve to the merged content: the original attribute AND
        // the motion-photo marker.
        val mergedXmp = String(entries.getValue(HeicMetaFixture.XMP_ITEM_ID).resolveBytes(outBytes), Charsets.UTF_8)
        assertTrue(mergedXmp.contains("tiff:Make=\"SomeCamera\""), "Expected the original tiff:Make to survive the merge, got: $mergedXmp")
        assertTrue(mergedXmp.contains("GCamera:MotionPhoto=\"1\""), "Expected the motion-photo marker in the merged XMP, got: $mergedXmp")

        // ...and exactly one motion-photo XMP exists in the whole file, so no reader can pick a stale one.
        assertEquals(
            1,
            Regex("GCamera:MotionPhoto=\"1\"").findAll(String(outBytes, Charsets.ISO_8859_1)).count(),
            "Expected exactly one motion-photo XMP in the output file",
        )

        assertSefVideoOffsetMatchesMpvd(outputFile)

        imageFile.delete()
        videoFile.delete()
        outputFile.delete()
    }

    @Test
    fun `createSamsungHeicMotionPhoto leaves the file unchanged rather than duplicating an unrepointable XMP item`() {
        // A multi-extent XMP item can't be cheaply repointed (repointHeicXmpItem returns null). The
        // old fallback registered a brand-new item, leaving the original as an earlier, stale
        // duplicate that readers would find first. Now the base bytes pass through untouched -- worst
        // case "no new XMP", never a misleading duplicate -- and the motion photo is still created.
        val existingXmp = "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">" +
            "<rdf:Description rdf:about=\"\" xmlns:tiff=\"http://ns.adobe.com/tiff/1.0/\" tiff:Make=\"SomeCamera\"/></rdf:RDF></x:xmpmeta>"
        val fixture = HeicMetaFixture.build(xmpText = existingXmp, xmpExtentCount = 2)

        val imageFile = File.createTempFile("heic-multiextent-", ".heic")
        imageFile.deleteOnExit()
        imageFile.writeBytes(fixture.heicBytes)

        val videoFile = File.createTempFile("heic-multiextent-vid-", ".mp4")
        videoFile.deleteOnExit()
        ProcessBuilder(
            "ffmpeg", "-y", "-f", "lavfi", "-i", "testsrc=duration=1:size=64x48:rate=10",
            videoFile.absolutePath,
        ).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start().waitFor()

        val outputFile = File.createTempFile("heic-multiextent-out-", ".heic")
        outputFile.deleteOnExit()
        MotionPhotoBuilder.createSamsungHeicMotionPhoto(imageFile, videoFile, outputFile)

        val outBytes = outputFile.readBytes()
        val entries = readIlocEntries(outBytes)
        assertEquals(fixture.existingItemCount, entries.size, "No extra XMP item may be registered (items: ${entries.keys})")
        // The original XMP is still intact and is still the only one.
        assertEquals(
            existingXmp,
            String(entries.getValue(HeicMetaFixture.XMP_ITEM_ID).resolveBytes(outBytes), Charsets.UTF_8),
            "The original multi-extent XMP item must be left exactly as it was",
        )
        assertFalse(String(outBytes, Charsets.ISO_8859_1).contains("GCamera:MotionPhoto=\"1\""), "No competing motion-photo XMP may be written")

        // ...and the motion photo itself was still created.
        val root = parseFile(outputFile)
        assertNotNull(root.children.find { it.type == "mpvd" })
        assertNotNull(root.children.find { it.type == "sefd" })
        ByteReader.open(outputFile).use { reader ->
            val embeddedVideo = findEmbeddedVideo(root, reader)
            assertNotNull(embeddedVideo)
            assertEquals(videoFile.length(), embeddedVideo.end - embeddedVideo.start)
        }
        assertSefVideoOffsetMatchesMpvd(outputFile)

        imageFile.delete()
        videoFile.delete()
        outputFile.delete()
    }

    @Test
    fun `createSamsungHeicMotionPhoto still produces a valid motion photo when the XMP subsystem bails out`() {
        // iloc's own item_count deliberately disagrees with iinf's infe count. createHeicXmpItem must
        // refuse to do offset surgery on a premise that doesn't hold -- and, critically, the motion
        // photo must still be created: "a merge failure must never abort motion-photo creation".
        val fixture = HeicMetaFixture.build(xmpText = null)
        val corrupted = fixture.heicBytes.copyOf()
        // iloc's item_count is a u16 at iloc payload + 6 (version=1 in this fixture).
        val ilocEntryOffset = fixture.primaryIlocEntryOffset.toInt()
        val itemCountPos = ilocEntryOffset - 2 // the entry list starts immediately after item_count
        assertEquals(1, ((corrupted[itemCountPos].toInt() and 0xFF) shl 8) or (corrupted[itemCountPos + 1].toInt() and 0xFF))
        corrupted[itemCountPos + 1] = 2 // claim 2 items while only 1 entry is present

        val imageFile = File.createTempFile("heic-bad-iloc-count-", ".heic")
        imageFile.deleteOnExit()
        imageFile.writeBytes(corrupted)

        val videoFile = File.createTempFile("heic-bail-vid-", ".mp4")
        videoFile.deleteOnExit()
        ProcessBuilder(
            "ffmpeg", "-y", "-f", "lavfi", "-i", "testsrc=duration=1:size=64x48:rate=10",
            videoFile.absolutePath,
        ).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start().waitFor()

        val outputFile = File.createTempFile("heic-bail-out-", ".heic")
        outputFile.deleteOnExit()
        MotionPhotoBuilder.createSamsungHeicMotionPhoto(imageFile, videoFile, outputFile)

        assertTrue(outputFile.length() > 0, "Motion-photo creation must not be aborted by an XMP-subsystem bail-out")
        val root = parseFile(outputFile)
        assertNotNull(root.children.find { it.type == "mpvd" }, "Expected a top-level mpvd box")
        assertNotNull(root.children.find { it.type == "sefd" }, "Expected a top-level sefd box")
        ByteReader.open(outputFile).use { reader ->
            val embeddedVideo = findEmbeddedVideo(root, reader)
            assertNotNull(embeddedVideo, "Expected the embedded video to still be extractable")
            assertEquals(videoFile.length(), embeddedVideo.end - embeddedVideo.start)
        }
        // The base HEIC was passed through untouched, so the SEF pointer must still be exact.
        assertSefVideoOffsetMatchesMpvd(outputFile)

        imageFile.delete()
        videoFile.delete()
        outputFile.delete()
    }

    @Test
    fun `createSamsungHeicMotionPhoto registers a brand-new XMP item end-to-end when the source HEIC has none`() {
        // createHeicXmpItem's actual target scenario, through the full pipeline: a HEIC with a real
        // meta/iinf/iloc structure but NO existing XMP item. Neither pre-existing HEIC end-to-end
        // test reached this code path with a realistic file (one has no meta box at all, the other
        // always has an XMP item to repoint).
        val fixture = HeicMetaFixture.build(xmpText = null)
        val imageFile = File.createTempFile("heic-no-xmp-", ".heic")
        imageFile.deleteOnExit()
        imageFile.writeBytes(fixture.heicBytes)

        val videoFile = File.createTempFile("heic-create-vid-", ".mp4")
        videoFile.deleteOnExit()
        ProcessBuilder(
            "ffmpeg", "-y", "-f", "lavfi", "-i", "testsrc=duration=1:size=64x48:rate=10",
            videoFile.absolutePath,
        ).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start().waitFor()

        val outputFile = File.createTempFile("heic-create-out-", ".heic")
        outputFile.deleteOnExit()
        MotionPhotoBuilder.createSamsungHeicMotionPhoto(imageFile, videoFile, outputFile)

        // (a) The motion-photo container itself is still correct and fully locatable.
        val fileBytes = outputFile.readBytes()
        assertEquals("SEFT", String(fileBytes.copyOfRange(fileBytes.size - 4, fileBytes.size), Charsets.US_ASCII))
        val root = parseFile(outputFile)
        assertNotNull(root.children.find { it.type == "mpvd" }, "Expected a top-level mpvd box")
        val sefdNode = root.children.find { it.type == "sefd" }
        assertNotNull(sefdNode, "Expected a top-level sefd box")
        assertNotNull(sefdNode.children.find { it.type == "MotionPhoto_Data" }, "Expected SEFD to contain MotionPhoto_Data")
        ByteReader.open(outputFile).use { reader ->
            val embeddedVideo = findEmbeddedVideo(root, reader)
            assertNotNull(embeddedVideo, "Expected the embedded video to be extractable from mpvd")
            assertEquals("mp4", embeddedVideo.extension)
            assertEquals(videoFile.length(), embeddedVideo.end - embeddedVideo.start)
        }

        // (b) ...and the file now ALSO has a discoverable XMP item carrying the motion-photo marker,
        // registered by createHeicXmpItem.
        val xmpNode = findFirst(root) { it.fields.any { f -> f.name == "xmp" } }
        assertNotNull(xmpNode, "Expected the output HEIC to have a discoverable XMP item")
        val xmpValue = xmpNode.fields.first { it.name == "xmp" }.value
        assertTrue(xmpValue.contains("GCamera:MotionPhoto=\"1\""), "Expected the new XMP to carry GCamera:MotionPhoto=\"1\", got: $xmpValue")
        assertNotNull(MotionPhotoBuilder.findXmpExtentInHeic(fileBytes), "Expected findXmpExtentInHeic to locate the new item")

        // (c) The primary image item must still resolve to its original bytes in the final file.
        val primary = readIlocEntries(fileBytes).getValue(HeicMetaFixture.PRIMARY_ITEM_ID)
        assertTrue(
            primary.resolveBytes(fileBytes).contentEquals(fixture.primaryItemBytes),
            "The primary item's iloc entry must still resolve to its original bytes in the finished motion photo",
        )

        // (d) The SEF video pointer must agree with where the video actually is.
        assertSefVideoOffsetMatchesMpvd(outputFile)

        imageFile.delete()
        videoFile.delete()
        outputFile.delete()
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

        // The repoint path GROWS the base HEIC (it appends a new mdat), so this is the regression
        // guard for the SEF video pointer having been computed from the pre-growth base size.
        assertSefVideoOffsetMatchesMpvd(outputFile)

        imageFile.delete()
        videoFile.delete()
        outputFile.delete()
    }

    // ---------------------------------------------------------------------------------------------
    // Test-local helpers
    // ---------------------------------------------------------------------------------------------

    /**
     * A realistic XMP packet shape for tests that need findXmpExtentInHeic's STRUCTURED meta/iloc walk
     * to match on its own merits: it carries a literal `rdf:Description` within the first 100 bytes of
     * the extent and is comfortably over the walk's 20-byte minimum, matching real mergeMotionPhotoXmp
     * output. Placeholders lacking both "Container" and "rdf:Description" make the walk fall through to
     * a whole-file raw pattern scan, which proves nothing about iloc registration.
     */
    private val NEW_XMP_PACKET =
        "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:Description rdf:about=\"\">brand new</rdf:Description></x:xmpmeta>"

    /** Byte index of the first occurrence of [needle]'s ASCII bytes in [bytes], or -1. */
    private fun indexOfAscii(bytes: ByteArray, needle: String): Int {
        val pattern = needle.toByteArray(Charsets.US_ASCII)
        outer@ for (i in 0..bytes.size - pattern.size) {
            for (j in pattern.indices) {
                if (bytes[i + j] != pattern[j]) continue@outer
            }
            return i
        }
        return -1
    }

    /** One item entry read straight out of a HEIC's meta -> iloc box. */
    private data class TestIlocEntry(
        val itemId: Long,
        val entryOffset: Long,
        val constructionMethod: Int,
        val baseOffset: Long,
        /** (extent_offset, extent_length) pairs, in file order. */
        val extents: List<Pair<Long, Long>>,
    ) {
        /** The absolute file offset this entry's first extent resolves to. */
        fun absoluteStart(): Long = baseOffset + extents[0].first

        /** The bytes this entry actually points at, extents concatenated in order. */
        fun resolveBytes(bytes: ByteArray): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            for ((offset, length) in extents) {
                out.write(bytes, (baseOffset + offset).toInt(), length.toInt())
            }
            return out.toByteArray()
        }
    }

    /**
     * Reads every meta -> iloc item entry out of raw HEIC bytes, keyed by item_ID. Deliberately an
     * INDEPENDENT re-derivation of the ISO/IEC 14496-12 ItemLocationBox field layout rather than a
     * call into MotionPhotoBuilder's own walkers -- the point of these tests is to cross-check what
     * the production code wrote, which a shared reader could agree with while both are wrong.
     */
    private fun readIlocEntries(bytes: ByteArray): Map<Long, TestIlocEntry> {
        fun u(pos: Int, width: Int): Long {
            var value = 0L
            for (i in 0 until width) value = (value shl 8) or (bytes[pos + i].toLong() and 0xFF)
            return value
        }

        var ilocStart = -1
        var ilocEnd = -1
        var pos = 0
        while (pos + 8 <= bytes.size) {
            val size = u(pos, 4)
            if (size < 8 || pos + size > bytes.size) break
            val type = String(bytes, pos + 4, 4, Charsets.US_ASCII)
            val end = (pos + size).toInt()
            if (type == "meta") {
                var mp = pos + 12 // size + type + FullBox version/flags
                while (mp + 8 <= end) {
                    val childSize = u(mp, 4)
                    if (childSize < 8 || mp + childSize > end) break
                    if (String(bytes, mp + 4, 4, Charsets.US_ASCII) == "iloc") {
                        ilocStart = mp
                        ilocEnd = (mp + childSize).toInt()
                    }
                    mp += childSize.toInt()
                }
            }
            pos = end
        }
        assertTrue(ilocStart >= 0, "No meta -> iloc box found in these bytes")

        val p = ilocStart + 8
        val version = bytes[p].toInt() and 0xFF
        val offsetSize = (bytes[p + 4].toInt() and 0xFF) shr 4
        val lengthSize = (bytes[p + 4].toInt() and 0xFF) and 0x0F
        val baseOffsetSize = (bytes[p + 5].toInt() and 0xFF) shr 4
        val indexSize = (bytes[p + 5].toInt() and 0xFF) and 0x0F
        val itemCountWidth = if (version < 2) 2 else 4
        val itemCount = u(p + 6, itemCountWidth).toInt()
        val itemIdWidth = if (version < 2) 2 else 4
        val constructionMethodWidth = if (version in 1..2) 2 else 0

        var lp = p + 6 + itemCountWidth
        val entries = LinkedHashMap<Long, TestIlocEntry>()
        repeat(itemCount) {
            val entryOffset = lp.toLong()
            val itemId = u(lp, itemIdWidth); lp += itemIdWidth
            val constructionMethod = if (constructionMethodWidth > 0) (u(lp, 2).toInt() and 0x0F) else 0
            lp += constructionMethodWidth
            lp += 2 // data_reference_index
            val baseOffset = u(lp, baseOffsetSize); lp += baseOffsetSize
            val extentCount = u(lp, 2).toInt(); lp += 2
            val extents = (0 until extentCount).map {
                lp += indexSize
                val extentOffset = u(lp, offsetSize); lp += offsetSize
                val extentLength = u(lp, lengthSize); lp += lengthSize
                extentOffset to extentLength
            }
            entries[itemId] = TestIlocEntry(itemId, entryOffset, constructionMethod, baseOffset, extents)
        }
        // If item_count and the entries actually present disagree, the box is internally inconsistent
        // -- exactly the class of corruption these tests exist to catch.
        assertTrue(lp <= ilocEnd, "iloc's declared item_count ($itemCount) walked past the box end: $lp > $ilocEnd")
        assertEquals(itemCount, entries.size, "iloc contained duplicate item_IDs")
        return entries
    }

    /**
     * Asserts the Samsung SEF `MotionPhoto_Data` block's `video_offset` pointer -- which Samsung
     * Gallery uses to navigate to the video -- points at the video's genuine first byte, i.e. the
     * `mpvd` box's payload start. This app's own checker/extractor resolve the video structurally via
     * `mpvd` and so never exercise this pointer; nothing else in the suite validates it.
     */
    private fun assertSefVideoOffsetMatchesMpvd(outputFile: File) {
        val bytes = outputFile.readBytes()
        val root = parseFile(outputFile)
        val mpvdNode = root.children.find { it.type == "mpvd" }
        assertNotNull(mpvdNode, "Expected a top-level mpvd box to compare the SEF pointer against")
        val actualVideoStart = mpvdNode.offset + mpvdNode.headerSize

        // SEF MotionPhoto_Data block layout:
        // [type_code u16 LE][marker u16 LE][name_len u32 LE][name]["mpv2"][video_offset u32 BE][video_length u32 BE]
        val blockName = "MotionPhoto_Data"
        val nameIndex = indexOfAscii(bytes, blockName)
        assertTrue(nameIndex >= 0, "Expected a MotionPhoto_Data SEF block in the output")

        val payloadStart = nameIndex + blockName.length
        assertEquals("mpv2", String(bytes, payloadStart, 4, Charsets.US_ASCII), "Expected the mpv2 pointer payload")
        var declaredVideoOffset = 0L
        for (i in 0 until 4) declaredVideoOffset = (declaredVideoOffset shl 8) or (bytes[payloadStart + 4 + i].toLong() and 0xFF)
        var declaredVideoLength = 0L
        for (i in 0 until 4) declaredVideoLength = (declaredVideoLength shl 8) or (bytes[payloadStart + 8 + i].toLong() and 0xFF)

        assertEquals(
            actualVideoStart,
            declaredVideoOffset,
            "SEF MotionPhoto_Data video_offset must point at the video's actual first byte (mpvd payload start). " +
                "A pointer computed from the PRE-XMP-merge base size is short by exactly the merge's growth.",
        )
        assertEquals(mpvdNode.size - mpvdNode.headerSize, declaredVideoLength, "SEF video_length must match the mpvd payload length")
        // And the declared offset really does land on the video's ftyp box.
        assertEquals("ftyp", String(bytes, declaredVideoOffset.toInt() + 4, 4, Charsets.US_ASCII), "video_offset must land on the video's ftyp box")
    }
}
