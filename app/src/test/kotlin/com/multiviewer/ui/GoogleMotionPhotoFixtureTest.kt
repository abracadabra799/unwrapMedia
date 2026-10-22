package com.multiviewer.ui

import com.multiviewer.parser.SefIntegritySeverity
import com.multiviewer.parser.parseFile
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GoogleMotionPhotoFixtureTest {
    private fun analyze(name: String): MotionPhotoIntegrityReport {
        val file = File(javaClass.getResource("/google-motion-photo/$name")!!.toURI())
        return MotionPhotoIntegrityAnalyzer.analyze(file, parseFile(file))
    }

    private fun MotionPhotoIntegrityReport.rule(id: String) = googleSpec.single { it.ruleId == id }.severity

    @Test
    fun `compliant JPEG motion photo passes every Google rule and decodes`() {
        val report = analyze("PXL_compliant.MP.jpg")
        val bad = report.googleSpec.filter { it.severity == SefIntegritySeverity.CRITICAL || it.severity == SefIntegritySeverity.WARNING }
        assertTrue(bad.isEmpty(), bad.joinToString("\n"))
        assertEquals(SefIntegritySeverity.PASS, report.rule("gmp.layout.packed"))
        assertEquals(SefIntegritySeverity.PASS, report.rule("gmp.layout.video_last"))
        assertEquals(SefIntegritySeverity.PASS, report.decodeChecks.single().severity)
        assertTrue(MotionPhotoFormat.GOOGLE_XMP in report.detectedFormats)
    }

    @Test
    fun `Samsung-style JPEG fails the MUST rules it breaks`() {
        val report = analyze("samsung-style.jpg")
        assertEquals(SefIntegritySeverity.CRITICAL, report.rule("gmp.layout.video_start"))
        assertEquals(SefIntegritySeverity.CRITICAL, report.rule("gmp.layout.video_last"))
        assertEquals(SefIntegritySeverity.CRITICAL, report.rule("gmp.item.padding_first_only"))
        assertEquals(SefIntegritySeverity.PASS, report.rule("gmp.layout.packed"))
        assertEquals(SefIntegritySeverity.WARNING, report.rule("gmp.item.padding_jpeg"))
        assertEquals(SefIntegritySeverity.WARNING, report.rule("gmp.filename"))
        assertEquals(SefIntegritySeverity.INFO, report.rule("gmp.legacy.microvideo"))
        assertEquals(SefIntegritySeverity.CRITICAL, report.overallSeverity)
    }

    @Test
    fun `legacy MicroVideo-only file is still recognized but fails 1_0`() {
        val report = analyze("legacy-microvideo.MP.jpg")
        assertTrue(MotionPhotoFormat.GOOGLE_XMP in report.detectedFormats)
        assertEquals(SefIntegritySeverity.CRITICAL, report.rule("gmp.dir.present"))
        assertEquals(SefIntegritySeverity.CRITICAL, report.rule("gmp.camera.motionphoto"))
        assertEquals(SefIntegritySeverity.PASS, report.decodeChecks.single().severity) // still extracted and played
    }
}
