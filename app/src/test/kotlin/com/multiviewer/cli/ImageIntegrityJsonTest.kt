package com.multiviewer.cli

import com.multiviewer.parser.integrity.jpegBytes
import java.io.File
import com.multiviewer.parser.SefCheckResult
import com.multiviewer.parser.SefIntegritySeverity
import com.multiviewer.ui.MotionPhotoFormat
import com.multiviewer.ui.MotionPhotoIntegrityReport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ImageIntegrityJsonTest {
    private fun tempJpeg(bytes: ByteArray): File =
        File.createTempFile("cli-integrity-", ".jpg").apply { deleteOnExit(); writeBytes(bytes) }

    @Test
    fun `check json for an image includes the structure report and NOT_RUN decode`() {
        val result = checkFile(tempJpeg(jpegBytes(withEoi = false))) as CheckResult.Success
        assertTrue(result.json.contains("\"imageIntegrity\""), result.json)
        assertTrue(result.json.contains("\"id\": \"jpeg.eoi\""), result.json)
        assertTrue(result.json.contains("\"overall\": \"FAIL\""), result.json)
        assertTrue(result.json.contains("\"status\": \"NOT_RUN\""), result.json)
    }

    @Test
    fun `analysis case json carries file identity and integrity`() {
        val file = tempJpeg(jpegBytes())
        val parsed = com.multiviewer.parser.parseFile(file)
        val structure = com.multiviewer.parser.integrity.ImageIntegrityChecker.check(file, parsed)
        val case = buildImageIntegrityCaseJson(file, structure, null)
        assertTrue(case.contains("\"sha256\""), case)
        assertTrue(case.contains("\"imageIntegrity\""), case)
        assertTrue(!case.contains(file.parent), "case must not contain the absolute path")
    }

    @Test
    fun `motionPhotoJson is null when not detected and NOT_RUN when detected without report`() {
        assertNull(motionPhotoJson(false, null))
        assertTrue(motionPhotoJson(true, null)!!.render().contains("\"status\": \"NOT_RUN\""))
    }

    @Test
    fun `motionPhotoJson maps severities and sections`() {
        val report = MotionPhotoIntegrityReport(
            detectedFormats = listOf(MotionPhotoFormat.GOOGLE_XMP),
            sefSection = null,
            googleXmpChecks = listOf(SefCheckResult(SefIntegritySeverity.WARNING, "xmp", "odd")),
            appleMpvdChecks = emptyList(),
            decodeChecks = listOf(SefCheckResult(SefIntegritySeverity.CRITICAL, "decode", "broken")),
            overallSeverity = SefIntegritySeverity.CRITICAL,
        )
        val out = motionPhotoJson(true, report)!!.render()
        assertTrue(out.contains("\"status\": \"FAIL\""), out)
        assertTrue(out.contains("GOOGLE_XMP"), out)
        assertTrue(Regex("\"section\": \"google_xmp\",\\s*\"status\": \"WARN\"").containsMatchIn(out), out)
        assertTrue(Regex("\"section\": \"video_decode\",\\s*\"status\": \"FAIL\"").containsMatchIn(out), out)
    }

    @Test
    fun `motionPhotoJson scrubs temp directory and inspected file paths from details`() {
        val tmp = System.getProperty("java.io.tmpdir").trimEnd('/', '\\')
        val inspected = File(tmp, "secret-dir/photo.jpg")
        val detail = "$tmp/motion-photo-decode-check123.mp4: Invalid data; ${inspected.absolutePath} bad"
        val report = MotionPhotoIntegrityReport(
            detectedFormats = listOf(MotionPhotoFormat.GOOGLE_XMP),
            sefSection = null,
            googleXmpChecks = emptyList(),
            appleMpvdChecks = emptyList(),
            decodeChecks = listOf(SefCheckResult(SefIntegritySeverity.CRITICAL, "decode", detail)),
            overallSeverity = SefIntegritySeverity.CRITICAL,
        )
        val out = motionPhotoJson(true, report, file = inspected)!!.render()
        assertTrue(out.contains("motion-photo-decode-check123.mp4: Invalid data"), out)
        assertTrue(out.contains("photo.jpg bad"), out)
        assertTrue(!out.contains(tmp), out)
        // Verify that subpath "secret-dir" doesn't leak when inspected file is under tmpdir
        assertTrue(!out.contains("secret-dir"), "Subpath should not leak: $out")
    }

    @Test
    fun `scrubPaths does not strip a tmpdir that is preceded by a word character`() {
        val tmp = System.getProperty("java.io.tmpdir").trimEnd('/', '\\')
        val text = "abc$tmp/keep.txt"
        assertEquals(text, scrubPaths(text, null))
    }

    @Test
    fun `motionPhotoJson renders error as FAIL`() {
        val out = motionPhotoJson(true, null, error = "boom")!!.render()
        assertTrue(Regex("\"status\": \"FAIL\"").containsMatchIn(out), out)
        assertTrue(out.contains("\"error\": \"boom\""), out)
    }

    @Test
    fun `plain jpeg check has no motionPhoto key`() {
        val result = checkFile(tempJpeg(jpegBytes())) as CheckResult.Success
        assertEquals(false, result.json.contains("motionPhoto"), result.json)
    }
}
