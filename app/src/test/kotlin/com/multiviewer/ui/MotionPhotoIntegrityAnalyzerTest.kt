package com.multiviewer.ui

import com.multiviewer.parser.BoxField
import com.multiviewer.parser.BoxNode
import com.multiviewer.parser.ByteReader
import com.multiviewer.parser.SefIntegritySeverity
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Builds a temp file of `totalSize` zero bytes with the literal ASCII "ftyp" placed so that
// reading 4 bytes at (ftypBoxStart + 4) returns "ftyp" -- matching correctMp4StartOffset's own
// read pattern (box size field, then 4-byte box type).
private fun tempFileWithFtypAt(ftypBoxStart: Long, totalSize: Long, majorBrand: String = "isom"): File {
    val bytes = ByteArray(totalSize.toInt())
    "ftyp".toByteArray(Charsets.US_ASCII).copyInto(bytes, (ftypBoxStart + 4).toInt())
    majorBrand.toByteArray(Charsets.US_ASCII).copyInto(bytes, (ftypBoxStart + 8).toInt())
    val tmp = File.createTempFile("motion-photo-integrity-test", ".bin")
    tmp.deleteOnExit()
    tmp.writeBytes(bytes)
    return tmp
}

private fun tempFileWithNoFtyp(totalSize: Long): File {
    val tmp = File.createTempFile("motion-photo-integrity-test-noftyp", ".bin")
    tmp.deleteOnExit()
    tmp.writeBytes(ByteArray(totalSize.toInt()))
    return tmp
}

private fun googleDirectoryXmp(
    declaredLength: Long,
    mime: String = "video/mp4",
    padding: String? = null,
    presentationTimestampUs: Long? = null,
): String {
    val topLevelTimestampAttr = if (presentationTimestampUs != null) "\n            GCamera:MotionPhotoPresentationTimestampUs=\"$presentationTimestampUs\"" else ""
    val paddingAttr = if (padding != null) " Item:Padding=\"$padding\"" else ""
    return """
    <x:xmpmeta xmlns:x="adobe:ns:meta/">
      <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
        <rdf:Description
            xmlns:Container="http://ns.google.com/photos/1.0/container/"
            xmlns:Item="http://ns.google.com/photos/1.0/container/item/"
            xmlns:GCamera="http://ns.google.com/photos/1.0/camera/"
            GCamera:MotionPhoto="1"$topLevelTimestampAttr>
          <Container:Directory>
            <rdf:Seq>
              <rdf:li rdf:parseType="Resource">
                <Container:Item Item:Semantic="Primary" Item:Mime="image/jpeg"/>
              </rdf:li>
              <rdf:li rdf:parseType="Resource">
                <Container:Item Item:Semantic="MotionPhoto" Item:Mime="$mime" Item:Length="$declaredLength"$paddingAttr/>
              </rdf:li>
            </rdf:Seq>
          </Container:Directory>
        </rdf:Description>
      </rdf:RDF>
    </x:xmpmeta>
""".trimIndent()
}

private fun googleMicroVideoXmp(declaredOffset: Long): String = """
    <x:xmpmeta xmlns:x="adobe:ns:meta/">
      <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
        <rdf:Description
            xmlns:GCamera="http://ns.google.com/photos/1.0/camera/"
            GCamera:MicroVideo="1"
            GCamera:MicroVideoVersion="1"
            GCamera:MicroVideoOffset="$declaredOffset"/>
      </rdf:RDF>
    </x:xmpmeta>
""".trimIndent()

private fun rootWithXmp(xmpText: String, fileSize: Long): BoxNode = BoxNode(
    type = "root", offset = 0, headerSize = 0, size = fileSize,
    children = listOf(
        BoxNode(
            type = "APP1", offset = 2, headerSize = 4, size = xmpText.length.toLong(),
            fields = listOf(BoxField("xmp", xmpText, 2, xmpText.length.toLong())),
        ),
    ),
)

class MotionPhotoIntegrityAnalyzerTest {
    @Test
    fun `analyzeGoogleXmpSection returns empty when there is no XMP at all`() {
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = 1000)
        val file = tempFileWithNoFtyp(1000)
        ByteReader.open(file).use { reader ->
            assertEquals(emptyList(), analyzeGoogleXmpSection(root, reader, null))
        }
    }

    @Test
    fun `analyzeGoogleXmpSection returns empty when XMP exists but has no motion-photo markers`() {
        val xmp = """<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"><rdf:Description/></rdf:RDF></x:xmpmeta>"""
        val file = tempFileWithNoFtyp(1000)
        val root = rootWithXmp(xmp, 1000)
        ByteReader.open(file).use { reader ->
            assertEquals(emptyList(), analyzeGoogleXmpSection(root, reader, null))
        }
    }

    @Test
    fun `analyzeGoogleXmpSection reports PASS when the declared Directory Length exactly matches the real ftyp position`() {
        val fileSize = 1000L
        val declaredLength = 200L
        val approxStart = fileSize - declaredLength
        val file = tempFileWithFtypAt(approxStart, fileSize)
        val root = rootWithXmp(googleDirectoryXmp(declaredLength), fileSize)
        ByteReader.open(file).use { reader ->
            val checks = analyzeGoogleXmpSection(root, reader, null)
            assertTrue(checks.any { it.severity == SefIntegritySeverity.INFO && it.detail.contains("Directory") })
            assertTrue(checks.any { it.severity == SefIntegritySeverity.PASS })
            assertTrue(checks.none { it.severity == SefIntegritySeverity.WARNING || it.severity == SefIntegritySeverity.CRITICAL })
        }
    }

    @Test
    fun `analyzeGoogleXmpSection reports WARNING when the real ftyp is found only after correction`() {
        val fileSize = 1000L
        val declaredLength = 200L
        val approxStart = fileSize - declaredLength // 800
        val realStart = approxStart + 5 // off by 5 bytes, still within the +-1024 search window
        val file = tempFileWithFtypAt(realStart, fileSize)
        val root = rootWithXmp(googleDirectoryXmp(declaredLength), fileSize)
        ByteReader.open(file).use { reader ->
            val checks = analyzeGoogleXmpSection(root, reader, null)
            assertTrue(checks.any { it.severity == SefIntegritySeverity.WARNING && it.detail.contains("5") })
        }
    }

    @Test
    fun `analyzeGoogleXmpSection reports CRITICAL when no ftyp is found anywhere in the search window`() {
        val fileSize = 1000L
        val declaredLength = 200L
        val file = tempFileWithNoFtyp(fileSize)
        val root = rootWithXmp(googleDirectoryXmp(declaredLength), fileSize)
        ByteReader.open(file).use { reader ->
            val checks = analyzeGoogleXmpSection(root, reader, null)
            assertTrue(checks.any { it.severity == SefIntegritySeverity.CRITICAL })
        }
    }

    @Test
    fun `analyzeGoogleXmpSection reports CRITICAL when the declared length exceeds the file size`() {
        val fileSize = 1000L
        val file = tempFileWithNoFtyp(fileSize)
        val root = rootWithXmp(googleDirectoryXmp(declaredLength = 5000L), fileSize)
        ByteReader.open(file).use { reader ->
            val checks = analyzeGoogleXmpSection(root, reader, null)
            assertTrue(checks.any { it.severity == SefIntegritySeverity.CRITICAL })
        }
    }

    @Test
    fun `analyzeGoogleXmpSection detects the legacy MicroVideo schema and still verifies the offset`() {
        val fileSize = 1000L
        val declaredOffset = 200L
        val approxStart = fileSize - declaredOffset
        val file = tempFileWithFtypAt(approxStart, fileSize)
        val root = rootWithXmp(googleMicroVideoXmp(declaredOffset), fileSize)
        ByteReader.open(file).use { reader ->
            val checks = analyzeGoogleXmpSection(root, reader, null)
            assertTrue(checks.any { it.severity == SefIntegritySeverity.INFO && it.detail.contains("MicroVideo") })
            assertTrue(checks.any { it.severity == SefIntegritySeverity.PASS })
        }
    }

    @Test
    fun `analyzeGoogleXmpSection reports WARNING when the XMP text has motion-photo markers but fails to parse`() {
        val xmp = "MotionPhoto <this is not <<valid xml"
        val file = tempFileWithNoFtyp(1000)
        val root = rootWithXmp(xmp, 1000)
        ByteReader.open(file).use { reader ->
            val checks = analyzeGoogleXmpSection(root, reader, null)
            assertEquals(1, checks.size)
            assertEquals(SefIntegritySeverity.WARNING, checks.single().severity)
        }
    }

    @Test
    fun `analyzeGoogleXmpSection reports PASS for a valid non-negative Padding`() {
        val fileSize = 1000L
        val declaredLength = 200L
        val approxStart = fileSize - declaredLength
        val file = tempFileWithFtypAt(approxStart, fileSize)
        val root = rootWithXmp(googleDirectoryXmp(declaredLength, padding = "8"), fileSize)
        ByteReader.open(file).use { reader ->
            val checks = analyzeGoogleXmpSection(root, reader, null)
            assertTrue(checks.any { it.severity == SefIntegritySeverity.PASS && it.label.contains("Padding") })
        }
    }

    @Test
    fun `analyzeGoogleXmpSection reports WARNING for a non-numeric Padding`() {
        val fileSize = 1000L
        val declaredLength = 200L
        val approxStart = fileSize - declaredLength
        val file = tempFileWithFtypAt(approxStart, fileSize)
        val root = rootWithXmp(googleDirectoryXmp(declaredLength, padding = "not-a-number"), fileSize)
        ByteReader.open(file).use { reader ->
            val checks = analyzeGoogleXmpSection(root, reader, null)
            assertTrue(checks.any { it.severity == SefIntegritySeverity.WARNING && it.label.contains("Padding") })
        }
    }

    @Test
    fun `analyzeGoogleXmpSection reports PASS when declared Mime matches the real ftyp major_brand`() {
        val fileSize = 1000L
        val declaredLength = 200L
        val approxStart = fileSize - declaredLength
        val file = tempFileWithFtypAt(approxStart, fileSize, majorBrand = "isom")
        val root = rootWithXmp(googleDirectoryXmp(declaredLength, mime = "video/mp4"), fileSize)
        ByteReader.open(file).use { reader ->
            val checks = analyzeGoogleXmpSection(root, reader, null)
            assertTrue(checks.any { it.severity == SefIntegritySeverity.PASS && it.label.contains("Mime") })
        }
    }

    @Test
    fun `analyzeGoogleXmpSection reports WARNING when declared Mime disagrees with the real ftyp major_brand`() {
        val fileSize = 1000L
        val declaredLength = 200L
        val approxStart = fileSize - declaredLength
        val file = tempFileWithFtypAt(approxStart, fileSize, majorBrand = "qt  ")
        val root = rootWithXmp(googleDirectoryXmp(declaredLength, mime = "video/mp4"), fileSize)
        ByteReader.open(file).use { reader ->
            val checks = analyzeGoogleXmpSection(root, reader, null)
            assertTrue(checks.any { it.severity == SefIntegritySeverity.WARNING && it.label.contains("Mime") })
        }
    }

    @Test
    fun `analyzeGoogleXmpSection reports PASS when PresentationTimestampUs falls within the real video duration`() {
        val fileSize = 1000L
        val declaredLength = 200L
        val approxStart = fileSize - declaredLength
        val file = tempFileWithFtypAt(approxStart, fileSize)
        val root = rootWithXmp(googleDirectoryXmp(declaredLength, presentationTimestampUs = 500_000L), fileSize)
        ByteReader.open(file).use { reader ->
            val checks = analyzeGoogleXmpSection(root, reader, videoDurationUs = 1_000_000L)
            assertTrue(checks.any { it.severity == SefIntegritySeverity.PASS && it.label.contains("셔터") })
        }
    }

    @Test
    fun `analyzeGoogleXmpSection reports WARNING when PresentationTimestampUs exceeds the real video duration`() {
        val fileSize = 1000L
        val declaredLength = 200L
        val approxStart = fileSize - declaredLength
        val file = tempFileWithFtypAt(approxStart, fileSize)
        val root = rootWithXmp(googleDirectoryXmp(declaredLength, presentationTimestampUs = 5_000_000L), fileSize)
        ByteReader.open(file).use { reader ->
            val checks = analyzeGoogleXmpSection(root, reader, videoDurationUs = 1_000_000L)
            assertTrue(checks.any { it.severity == SefIntegritySeverity.WARNING && it.label.contains("셔터") })
        }
    }

    @Test
    fun `probeVideoDurationUs returns the real duration of a real clip`() {
        // generateRealClip (defined further down in this file, near the analyzeDecodability
        // tests) produces a 320x240, 10fps, exactly-1-second clip via real ffmpeg.
        val clip = generateRealClip("duration-probe")
        val video = embeddedVideoCoveringWholeFile(clip)
        val durationUs = probeVideoDurationUs(clip, video)
        assertTrue(durationUs != null && durationUs in 900_000..1_100_000) // ~1s, allow encoder rounding
    }

    @Test
    fun `analyzeAppleMpvdSection returns empty when there is no mpvd box`() {
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = 1000)
        assertEquals(emptyList(), analyzeAppleMpvdSection(root, 1000))
    }

    @Test
    fun `analyzeAppleMpvdSection reports CRITICAL when the mpvd box overruns the file`() {
        val root = BoxNode(
            type = "root", offset = 0, headerSize = 0, size = 1000,
            children = listOf(BoxNode(type = "mpvd", offset = 900, headerSize = 8, size = 500)),
        )
        val checks = analyzeAppleMpvdSection(root, fileLength = 1000)
        assertTrue(checks.any { it.severity == SefIntegritySeverity.CRITICAL })
    }

    @Test
    fun `analyzeAppleMpvdSection reports PASS with the major_brand when a valid ftyp child exists`() {
        val root = BoxNode(
            type = "root", offset = 0, headerSize = 0, size = 1000,
            children = listOf(
                BoxNode(
                    type = "mpvd", offset = 100, headerSize = 8, size = 500,
                    children = listOf(
                        BoxNode(
                            type = "ftyp", offset = 108, headerSize = 8, size = 20,
                            fields = listOf(BoxField("major_brand", "mp42", 108, 4)),
                        ),
                    ),
                ),
            ),
        )
        val checks = analyzeAppleMpvdSection(root, fileLength = 1000)
        assertTrue(checks.any { it.severity == SefIntegritySeverity.PASS && it.detail.contains("mp42") })
    }

    @Test
    fun `analyzeAppleMpvdSection reports WARNING when mpvd has no ftyp child`() {
        val root = BoxNode(
            type = "root", offset = 0, headerSize = 0, size = 1000,
            children = listOf(BoxNode(type = "mpvd", offset = 100, headerSize = 8, size = 500, children = emptyList())),
        )
        val checks = analyzeAppleMpvdSection(root, fileLength = 1000)
        assertTrue(checks.any { it.severity == SefIntegritySeverity.WARNING })
    }

    @Test
    fun `analyzeDecodability returns empty when there is no video`() {
        assertEquals(emptyList(), analyzeDecodability(File.createTempFile("no-video-test", ".bin").apply { deleteOnExit() }, null))
    }

    private fun generateRealClip(suffix: String): File {
        val file = File.createTempFile("motion-photo-decode-test-$suffix-", ".mp4")
        file.deleteOnExit()
        ProcessBuilder(
            "ffmpeg", "-y", "-f", "lavfi", "-i", "testsrc=size=320x240:rate=10:duration=1",
            "-c:v", "libx264", "-pix_fmt", "yuv420p", "-movflags", "+faststart", file.absolutePath,
        ).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start().waitFor()
        return file
    }

    private fun embeddedVideoCoveringWholeFile(file: File) =
        com.multiviewer.parser.EmbeddedVideo(start = 0L, end = file.length(), extension = "mp4")

    @Test
    fun `analyzeDecodability reports PASS for a real valid video`() {
        val clip = generateRealClip("valid")
        val checks = analyzeDecodability(clip, embeddedVideoCoveringWholeFile(clip))
        assertEquals(1, checks.size)
        assertEquals(SefIntegritySeverity.PASS, checks.single().severity)
    }

    @Test
    fun `analyzeDecodability reports CRITICAL for a video with corrupted frame data even when the container is intact`() {
        val clip = generateRealClip("corrupt-base")
        val corrupted = File.createTempFile("motion-photo-decode-test-corrupted-", ".mp4")
        corrupted.deleteOnExit()
        clip.copyTo(corrupted, overwrite = true)
        // Zero out a chunk well past the (faststart-relocated) moov atom, landing inside the
        // actual frame data -- this must NOT touch container framing, matching the exact
        // "concealed decode error" case exit-code-alone missed.
        java.io.RandomAccessFile(corrupted, "rw").use { raf ->
            val offset = corrupted.length() - 3000
            raf.seek(offset)
            raf.write(ByteArray(2000))
        }
        val checks = analyzeDecodability(corrupted, embeddedVideoCoveringWholeFile(corrupted))
        assertEquals(1, checks.size)
        assertEquals(SefIntegritySeverity.CRITICAL, checks.single().severity)
    }
}
