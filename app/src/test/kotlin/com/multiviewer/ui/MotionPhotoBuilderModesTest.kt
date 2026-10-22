package com.multiviewer.ui

import com.multiviewer.parser.MotionPhotoBuilder
import com.multiviewer.parser.MotionPhotoLayout
import com.multiviewer.parser.SefIntegritySeverity
import com.multiviewer.parser.parseFile
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MotionPhotoBuilderModesTest {
    private val dir: File = Files.createTempDirectory("mp-builder-modes").toFile().apply { deleteOnExit() }

    private fun run(vararg cmd: String) {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        check(p.waitFor() == 0) { "${cmd.joinToString(" ")} failed: $out" }
    }

    private fun stillJpeg() = File(dir, "still.jpg").also {
        if (!it.exists()) run("ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-f", "lavfi", "-i", "testsrc2=size=64x48", "-frames:v", "1", it.absolutePath)
    }
    private fun stillHeic() = File(dir, "still.heic").also {
        if (!it.exists()) run("sips", "-s", "format", "heic", stillJpeg().absolutePath, "--out", it.absolutePath)
    }
    private fun video() = File(dir, "clip.mp4").also {
        if (!it.exists()) run("ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-f", "lavfi", "-i", "testsrc2=size=64x48:rate=8:duration=1",
            "-pix_fmt", "yuv420p", "-c:v", "libx264", it.absolutePath)
    }
    private fun samsungSource() = File(dir, "src-sef.jpg").also {
        if (!it.exists()) File(javaClass.getResource("/google-motion-photo/samsung-style.jpg")!!.toURI()).copyTo(it)
    }

    private fun build(image: File, name: String, layout: MotionPhotoLayout): MotionPhotoIntegrityReport {
        val out = File(dir, name)
        MotionPhotoBuilder.createMotionPhoto(image, video(), out, layout = layout)
        return MotionPhotoIntegrityAnalyzer.analyze(out, parseFile(out))
    }

    private fun MotionPhotoIntegrityReport.ids(severity: SefIntegritySeverity) =
        googleSpec.filter { it.severity == severity }.map { it.ruleId }.toSet()

    private fun assertGoogleClean(report: MotionPhotoIntegrityReport) {
        val bad = report.googleSpec.filter { it.severity == SefIntegritySeverity.CRITICAL || it.severity == SefIntegritySeverity.WARNING }
        assertTrue(bad.isEmpty(), bad.joinToString("\n"))
        assertEquals(SefIntegritySeverity.PASS, report.decodeChecks.single().severity)
    }

    @Test fun `Google-spec JPEG from a plain JPEG passes every rule`() =
        assertGoogleClean(build(stillJpeg(), "plain_MP.jpg", MotionPhotoLayout.GOOGLE_SPEC))

    @Test fun `Google-spec JPEG from a SEF source drops the SEF and passes every rule`() =
        assertGoogleClean(build(samsungSource(), "sef_MP.jpg", MotionPhotoLayout.GOOGLE_SPEC))

    @Test fun `Google-spec HEIC passes every rule`() =
        assertGoogleClean(build(stillHeic(), "still_MP.heic", MotionPhotoLayout.GOOGLE_SPEC))

    @Test fun `Samsung-compatible JPEG only fails the bytes-after-video rule`() {
        val report = build(stillJpeg(), "plain_motion.jpg", MotionPhotoLayout.SAMSUNG_COMPATIBLE)
        assertEquals(setOf("gmp.layout.video_last"), report.ids(SefIntegritySeverity.CRITICAL), report.googleSpec.joinToString("\n"))
        assertTrue(report.ids(SefIntegritySeverity.WARNING) - "gmp.filename" == emptySet<String>(), report.googleSpec.joinToString("\n"))
        assertTrue(MotionPhotoFormat.SAMSUNG_SEF in report.detectedFormats)
        assertEquals(SefIntegritySeverity.PASS, report.decodeChecks.single().severity)
    }

    @Test fun `Samsung-compatible HEIC only fails the bytes-after-video rules`() {
        val report = build(stillHeic(), "still_motion.heic", MotionPhotoLayout.SAMSUNG_COMPATIBLE)
        assertTrue(report.ids(SefIntegritySeverity.CRITICAL) - setOf("gmp.layout.video_last", "gmp.mpvd.last", "gmp.mpvd.length") == emptySet<String>(),
            report.googleSpec.joinToString("\n"))
        assertTrue(report.ids(SefIntegritySeverity.WARNING) - "gmp.filename" == emptySet<String>(), report.googleSpec.joinToString("\n"))
        assertEquals(SefIntegritySeverity.PASS, report.decodeChecks.single().severity)
    }
}
