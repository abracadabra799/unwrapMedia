package com.multiviewer.ui

import com.multiviewer.parser.BoxField
import com.multiviewer.parser.BoxNode
import com.multiviewer.parser.ByteReader
import com.multiviewer.parser.SefIntegritySeverity
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Duplicated from SefIntegrityAnalyzerTest.kt (a different file/package) rather than shared
// cross-package: this codebase has an established convention of small per-file duplicate
// test/parsing helpers. Assembles a complete, well-formed SEF trailer from a list of fields.
private fun ByteArray.putUInt16LE(offset: Int, value: Int) {
    this[offset] = (value and 0xFF).toByte()
    this[offset + 1] = ((value shr 8) and 0xFF).toByte()
}

private fun ByteArray.putUInt32LE(offset: Int, value: Long) {
    this[offset] = (value and 0xFF).toByte()
    this[offset + 1] = ((value shr 8) and 0xFF).toByte()
    this[offset + 2] = ((value shr 16) and 0xFF).toByte()
    this[offset + 3] = ((value shr 24) and 0xFF).toByte()
}

private data class SefTestField(val marker: Int, val name: String, val data: ByteArray)

private fun buildSefTrailer(fields: List<SefTestField>): ByteArray {
    val blockBytesList = fields.map { f ->
        val nameBytes = f.name.toByteArray(Charsets.UTF_8) + byteArrayOf(0)
        val header = ByteArray(8)
        header.putUInt16LE(2, f.marker)
        header.putUInt32LE(4, nameBytes.size.toLong())
        header + nameBytes + f.data
    }
    val blocksTotalSize = blockBytesList.sumOf { it.size }

    val dirSize = 12 + fields.size * 12
    val dir = ByteArray(dirSize)
    dir[0] = 'S'.code.toByte(); dir[1] = 'E'.code.toByte(); dir[2] = 'F'.code.toByte(); dir[3] = 'H'.code.toByte()
    dir.putUInt32LE(8, fields.size.toLong())
    var remaining = blocksTotalSize.toLong()
    for ((idx, f) in fields.withIndex()) {
        val entryPos = 12 + idx * 12
        dir.putUInt16LE(entryPos + 2, f.marker)
        dir.putUInt32LE(entryPos + 4, remaining)
        dir.putUInt32LE(entryPos + 8, blockBytesList[idx].size.toLong())
        remaining -= blockBytesList[idx].size
    }

    val seft = ByteArray(8)
    seft.putUInt32LE(0, dirSize.toLong())
    seft[4] = 'S'.code.toByte(); seft[5] = 'E'.code.toByte(); seft[6] = 'F'.code.toByte(); seft[7] = 'T'.code.toByte()

    var result = ByteArray(0)
    for (b in blockBytesList) result += b
    result += dir
    result += seft
    return result
}

class MotionPhotoIntegrityAnalyzerTest {
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

    @Test
    fun `detectedFormats includes SAMSUNG_SEF for a structurally broken SEF trailer even without MotionPhoto_Data`() {
        // 20 zero bytes: long enough to pass the "at least 12 bytes" check but missing the "SEFT"
        // magic in the last 4 bytes -- SefIntegrityAnalyzer.analyze bails with a structural CRITICAL
        // and an empty directoryEntries list, so MotionPhoto_Data can never be found here. This is
        // exactly the corrupted-trailer case the detection gate must still treat as "detected".
        val bytes = ByteArray(20)
        val file = File.createTempFile("motion-photo-broken-sef", ".bin")
        file.deleteOnExit()
        file.writeBytes(bytes)
        val sefdNode = BoxNode(type = "sefd", offset = 0, headerSize = 0, size = bytes.size.toLong())
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = bytes.size.toLong(), children = listOf(sefdNode))
        val report = MotionPhotoIntegrityAnalyzer.analyze(file, root)
        assertTrue(MotionPhotoFormat.SAMSUNG_SEF in report.detectedFormats)
    }

    @Test
    fun `detectedFormats excludes SAMSUNG_SEF for a well-formed sefd trailer with no MotionPhoto_Data`() {
        // regression lock for the Task 2 detection-gate fix: an ordinary SEF trailer (EXIF-style
        // fields only, no motion video) must not be misclassified as a motion photo.
        val trailerBytes = buildSefTrailer(listOf(SefTestField(0x0a01, "Image_UTC_Data", "1700000000000".toByteArray())))
        val file = File.createTempFile("motion-photo-plain-sef", ".bin")
        file.deleteOnExit()
        file.writeBytes(trailerBytes)
        val sefdNode = BoxNode(type = "sefd", offset = 0, headerSize = 0, size = trailerBytes.size.toLong())
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = trailerBytes.size.toLong(), children = listOf(sefdNode))
        val report = MotionPhotoIntegrityAnalyzer.analyze(file, root)
        assertTrue(MotionPhotoFormat.SAMSUNG_SEF !in report.detectedFormats)
    }

    @Test
    fun `detectedFormats includes SAMSUNG_SEF for a well-formed sefd trailer with MotionPhoto_Data`() {
        val payload = "mpv2".toByteArray() + ByteArray(8).also {
            it[3] = 10 // video_offset = 10 (big-endian uint32)
            it[7] = 5  // video_length = 5 (big-endian uint32)
        }
        val trailerBytes = buildSefTrailer(listOf(SefTestField(0x0d01, "MotionPhoto_Data", payload)))
        val file = File.createTempFile("motion-photo-with-data-sef", ".bin")
        file.deleteOnExit()
        file.writeBytes(trailerBytes)
        val sefdNode = BoxNode(type = "sefd", offset = 0, headerSize = 0, size = trailerBytes.size.toLong())
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = trailerBytes.size.toLong(), children = listOf(sefdNode))
        val report = MotionPhotoIntegrityAnalyzer.analyze(file, root)
        assertTrue(MotionPhotoFormat.SAMSUNG_SEF in report.detectedFormats)
        // Google Motion Photo 1.0 is checked for every motion photo: a SEF-only file has no Camera:MotionPhoto.
        assertEquals(SefIntegritySeverity.CRITICAL, report.googleSpec.single { it.ruleId == "gmp.camera.motionphoto" }.severity)
        assertEquals(SefIntegritySeverity.CRITICAL, report.overallSeverity)
    }

    @Test
    fun `analyze populates deepAnalysis compatibilityMatrix and checks`() {
        val payload = "mpv2".toByteArray() + ByteArray(8).also {
            it[3] = 10
            it[7] = 5
        }
        val trailerBytes = buildSefTrailer(listOf(SefTestField(0x0d01, "MotionPhoto_Data", payload)))
        val file = File.createTempFile("motion-photo-deep-test", ".bin")
        file.deleteOnExit()
        file.writeBytes(trailerBytes)
        val sefdNode = BoxNode(
            type = "sefd", offset = 0, headerSize = 0, size = trailerBytes.size.toLong(),
            children = listOf(BoxNode(type = "MotionPhoto_Data", offset = 0, headerSize = 0, size = 16)),
        )
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = trailerBytes.size.toLong(), children = listOf(sefdNode))
        val report = MotionPhotoIntegrityAnalyzer.analyze(file, root)

        kotlin.test.assertNotNull(report.deepAnalysis)
        kotlin.test.assertNotNull(report.deepAnalysis?.compatibilityMatrix)
        assertTrue(report.deepAnalysis?.compatibilityMatrix?.entries?.isNotEmpty() == true)
    }

    @Test
    fun `a bare sefd trailer without motion data runs no Google spec rules`() {
        val trailerBytes = buildSefTrailer(listOf(SefTestField(0x0a01, "Image_UTC_Data", "1700000000000".toByteArray())))
        val file = File.createTempFile("motion-photo-plain-sef-spec", ".bin")
        file.deleteOnExit()
        file.writeBytes(trailerBytes)
        val root = BoxNode(type = "root", offset = 0, headerSize = 0, size = trailerBytes.size.toLong(),
            children = listOf(BoxNode(type = "sefd", offset = 0, headerSize = 0, size = trailerBytes.size.toLong())))
        assertTrue(MotionPhotoIntegrityAnalyzer.analyze(file, root).googleSpec.isEmpty())
    }
}
