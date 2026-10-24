package com.multiviewer.parser.motionphoto

import com.multiviewer.parser.ContainerFormat
import com.multiviewer.parser.SefIntegritySeverity
import com.multiviewer.parser.byteReaderOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GoogleMotionPhotoSpecCheckerTest {
    private fun findings(
        xmp: String?,
        format: ContainerFormat? = ContainerFormat.JPEG,
        fileName: String = "PXL_1.MP.jpg",
        durationUs: Long? = 1_000_000,
    ): List<GoogleSpecFinding> = byteReaderOf(ByteArray(16)).use {
        GoogleMotionPhotoSpecChecker.check(GmpSpecInput(fileName, format, xmp, durationUs), it)
    }.assertGmpCited()

    private fun List<GoogleSpecFinding>.rule(id: String) = single { it.ruleId == id }

    // XMP-level rules only: the byte-layout rules (Task 3) run on the dummy 16-byte file and are asserted elsewhere.
    private fun List<GoogleSpecFinding>.xmpRules() = filter {
        listOf("gmp.ns", "gmp.camera", "gmp.dir", "gmp.legacy", "gmp.filename", "gmp.xmp").any(it.ruleId::startsWith) ||
            (it.ruleId.startsWith("gmp.item") && it.ruleId != "gmp.item.video_mime")
    }

    private val bad = setOf(SefIntegritySeverity.CRITICAL, SefIntegritySeverity.WARNING)

    @Test
    fun `compliant JPEG XMP has no FAIL or WARN`() {
        val result = findings(gmpXmp()).xmpRules()
        assertTrue(result.none { it.severity in bad }, result.joinToString("\n"))
        assertEquals(SefIntegritySeverity.PASS, result.rule("gmp.camera.motionphoto").severity)
        assertEquals(SpecRequirement.MUST, result.rule("gmp.camera.motionphoto").requirement)
        assertEquals("3.1", result.rule("gmp.camera.motionphoto").section)
    }

    @Test
    fun `missing XMP fails MotionPhoto and Directory`() {
        val result = findings(null)
        assertEquals(SefIntegritySeverity.CRITICAL, result.rule("gmp.camera.motionphoto").severity)
        assertEquals(SefIntegritySeverity.CRITICAL, result.rule("gmp.dir.present").severity)
    }

    @Test
    fun `unparsable XMP fails and the remaining XMP rules are skipped, never passed`() {
        val result = findings("<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><broken")
        assertEquals(SefIntegritySeverity.CRITICAL, result.rule("gmp.xmp.parse").severity)
        assertEquals(SefIntegritySeverity.SKIPPED, result.rule("gmp.xmp.rules").severity)
        assertTrue(result.none { it.ruleId.startsWith("gmp.camera") && it.severity == SefIntegritySeverity.PASS })
    }

    @Test
    fun `wrong Camera namespace URI fails the namespace rule and MotionPhoto`() {
        val result = findings(gmpXmp(cameraNs = "http://ns.google.com/photos/1.0/camera"))
        assertEquals(SefIntegritySeverity.CRITICAL, result.rule("gmp.ns.uris").severity)
        assertEquals(SefIntegritySeverity.CRITICAL, result.rule("gmp.camera.motionphoto").severity)
    }

    @Test
    fun `MotionPhoto 0 fails`() {
        val result = findings(gmpXmp(camera = mapOf("MotionPhoto" to "0", "MotionPhotoVersion" to "1")))
        assertEquals(SefIntegritySeverity.CRITICAL, result.rule("gmp.camera.motionphoto").severity)
    }

    @Test
    fun `missing MotionPhotoVersion warns`() {
        val result = findings(gmpXmp(camera = mapOf("MotionPhoto" to "1")))
        assertEquals(SefIntegritySeverity.WARNING, result.rule("gmp.camera.version").severity)
    }

    @Test
    fun `presentation timestamp rules`() {
        fun pts(value: String?, duration: Long? = 1_000_000) = findings(
            gmpXmp(camera = mapOf("MotionPhoto" to "1", "MotionPhotoVersion" to "1") +
                (if (value != null) mapOf("MotionPhotoPresentationTimestampUs" to value) else emptyMap())),
            durationUs = duration,
        ).rule("gmp.camera.pts").severity
        assertEquals(SefIntegritySeverity.PASS, pts("-1"))
        assertEquals(SefIntegritySeverity.PASS, pts("500000"))
        assertEquals(SefIntegritySeverity.WARNING, pts("-5"))
        assertEquals(SefIntegritySeverity.WARNING, pts("2000000"))
        assertEquals(SefIntegritySeverity.WARNING, pts("abc"))
        assertEquals(SefIntegritySeverity.SKIPPED, pts("500000", duration = null))
        assertEquals(SefIntegritySeverity.INFO, pts(null))
    }

    @Test
    fun `legacy MicroVideo only fails the directory rule and notes the legacy fields`() {
        val result = findings(gmpXmp(camera = mapOf("MicroVideo" to "1", "MicroVideoOffset" to "100"), items = null))
        val dir = result.rule("gmp.dir.present")
        assertEquals(SefIntegritySeverity.CRITICAL, dir.severity)
        assertTrue(dir.detail.contains("MicroVideo"), dir.detail)
        assertEquals(SefIntegritySeverity.INFO, result.rule("gmp.legacy.microvideo").severity)
        assertEquals(SefIntegritySeverity.SKIPPED, result.rule("gmp.dir.items").severity)
    }

    @Test
    fun `legacy fields next to a directory are only informational`() {
        val result = findings(gmpXmp(camera = mapOf("MotionPhoto" to "1", "MotionPhotoVersion" to "1", "MicroVideoOffset" to "100")))
        assertEquals(SefIntegritySeverity.PASS, result.rule("gmp.dir.present").severity)
        assertEquals(SefIntegritySeverity.INFO, result.rule("gmp.legacy.microvideo").severity)
    }

    @Test
    fun `first item must be Primary and Primary and MotionPhoto must be unique`() {
        val swapped = findings(gmpXmp(items = listOf(
            mapOf("Semantic" to "MotionPhoto", "Mime" to "video/mp4", "Length" to "100"),
            mapOf("Semantic" to "Primary", "Mime" to "image/jpeg"),
        )))
        assertEquals(SefIntegritySeverity.CRITICAL, swapped.rule("gmp.dir.primary_first").severity)
        val twoVideos = findings(gmpXmp(items = listOf(
            mapOf("Semantic" to "Primary", "Mime" to "image/jpeg"),
            mapOf("Semantic" to "MotionPhoto", "Mime" to "video/mp4", "Length" to "50"),
            mapOf("Semantic" to "MotionPhoto", "Mime" to "video/mp4", "Length" to "50"),
        )))
        assertEquals(SefIntegritySeverity.CRITICAL, twoVideos.rule("gmp.dir.motionphoto_unique").severity)
        assertEquals(SefIntegritySeverity.PASS, twoVideos.rule("gmp.dir.primary_unique").severity)
    }

    @Test
    fun `Mime and Semantic are required and Mime must be an allowed value`() {
        val result = findings(gmpXmp(items = listOf(
            mapOf("Semantic" to "Primary"),
            mapOf("Mime" to "video/x-matroska", "Length" to "100"),
        )))
        assertEquals(SefIntegritySeverity.CRITICAL, result.rule("gmp.item.mime_required").severity)
        assertEquals(SefIntegritySeverity.CRITICAL, result.rule("gmp.item.semantic_required").severity)
        assertEquals(SefIntegritySeverity.CRITICAL, result.rule("gmp.item.mime_value").severity)
    }

    @Test
    fun `primary Mime must match the real image format`() {
        val result = findings(gmpXmp(), format = ContainerFormat.HEIF, fileName = "a.MP.heic")
        assertEquals(SefIntegritySeverity.CRITICAL, result.rule("gmp.item.primary_mime").severity)
    }

    @Test
    fun `secondary items need a Length and the video Length must be positive`() {
        val missing = findings(gmpXmp(items = listOf(
            mapOf("Semantic" to "Primary", "Mime" to "image/jpeg"),
            mapOf("Semantic" to "MotionPhoto", "Mime" to "video/mp4"),
        )))
        assertEquals(SefIntegritySeverity.CRITICAL, missing.rule("gmp.item.length_required").severity)
        val sharedGainMap = findings(gmpXmp(items = listOf(
            mapOf("Semantic" to "Primary", "Mime" to "image/heic", "Padding" to "8"),
            mapOf("Semantic" to "GainMap", "Mime" to "image/heic", "Length" to "0"),
            mapOf("Semantic" to "MotionPhoto", "Mime" to "video/mp4", "Length" to "100"),
        )), format = ContainerFormat.HEIF, fileName = "a.MP.heic")
        assertEquals(SefIntegritySeverity.PASS, sharedGainMap.rule("gmp.item.length_required").severity)
    }

    @Test
    fun `non-zero primary Length warns`() {
        val result = findings(gmpXmp(items = listOf(
            mapOf("Semantic" to "Primary", "Mime" to "image/jpeg", "Length" to "5"),
            mapOf("Semantic" to "MotionPhoto", "Mime" to "video/mp4", "Length" to "100"),
        )))
        assertEquals(SefIntegritySeverity.WARNING, result.rule("gmp.item.primary_length").severity)
    }

    @Test
    fun `Padding on a later item fails (Samsung writes it on the video item)`() {
        val result = findings(gmpXmp(items = listOf(
            mapOf("Semantic" to "Primary", "Mime" to "image/jpeg", "Padding" to "0"),
            mapOf("Semantic" to "MotionPhoto", "Mime" to "video/mp4", "Length" to "100", "Padding" to "0"),
        )))
        assertEquals(SefIntegritySeverity.CRITICAL, result.rule("gmp.item.padding_first_only").severity)
        assertEquals(SefIntegritySeverity.WARNING, result.rule("gmp.item.padding_jpeg").severity)
    }

    @Test
    fun `positive JPEG Padding passes`() {
        val result = findings(gmpXmp(items = listOf(
            mapOf("Semantic" to "Primary", "Mime" to "image/jpeg", "Padding" to "16"),
            mapOf("Semantic" to "MotionPhoto", "Mime" to "video/mp4", "Length" to "100"),
        )))
        assertEquals(SefIntegritySeverity.PASS, result.rule("gmp.item.padding_jpeg").severity)
    }

    @Test
    fun `HEIC Padding must be 8`() {
        fun heic(padding: String?) = findings(gmpXmp(items = listOf(
            mapOf("Semantic" to "Primary", "Mime" to "image/heic") + (if (padding != null) mapOf("Padding" to padding) else emptyMap()),
            mapOf("Semantic" to "MotionPhoto", "Mime" to "video/mp4", "Length" to "100"),
        )), format = ContainerFormat.HEIF, fileName = "a.MP.heic").rule("gmp.item.padding_isobmff").severity
        assertEquals(SefIntegritySeverity.PASS, heic("8"))
        assertEquals(SefIntegritySeverity.CRITICAL, heic("0"))
        assertEquals(SefIntegritySeverity.CRITICAL, heic(null))
    }

    @Test
    fun `filename pattern is a SHOULD`() {
        assertEquals(SefIntegritySeverity.WARNING, findings(gmpXmp(), fileName = "20260718_200439.jpg").rule("gmp.filename").severity)
        assertEquals(SefIntegritySeverity.PASS, findings(gmpXmp(), fileName = "PXL_20231010_123456789.MP.jpg").rule("gmp.filename").severity)
        assertEquals(SefIntegritySeverity.PASS, findings(gmpXmp(), fileName = "abcMP.HEIC").rule("gmp.filename").severity)
        assertEquals(SpecRequirement.SHOULD, findings(gmpXmp()).rule("gmp.filename").requirement)
    }

    @Test
    fun `citations point at the verified section anchors`() {
        fun url(ruleId: String, section: String) = GoogleMotionPhotoCitations.forRule(ruleId, section)?.url
        assertEquals("$GMP_DOC_URL#itemmime_type_values", url("gmp.item.mime_value", "3.4"))
        assertEquals("$GMP_DOC_URL#itemmime_type_values", url("gmp.item.video_mime", "3.4"))
        assertEquals("$GMP_DOC_URL#itemsemantic_values", url("gmp.layout.video_last", "3.4"))
        assertEquals("$GMP_DOC_URL#itemsemantic_values", url("gmp.gainmap.item", "3.4"))
        assertEquals("$GMP_DOC_URL#isobmff-image-specific-behavior", url("gmp.mpvd.last", "4"))
        assertEquals("§3.2 Container element", GoogleMotionPhotoCitations.forRule("gmp.dir.present", "3.2")?.section)
        assertEquals(null, GoogleMotionPhotoCitations.forRule("gmp.x", "9.9"), "unknown sections get no invented anchor")
        // Explicit id set, not a substring heuristic: a hypothetical §3.4 rule merely containing "mime" is Semantic.
        assertEquals("$GMP_DOC_URL#itemsemantic_values", url("gmp.dir.mimetype_hint", "3.4"))
    }

    @Test
    fun `presentation helpers carry section, requirement and group`() {
        val finding = findings(gmpXmp(), fileName = "x.jpg").rule("gmp.filename")
        assertEquals("§2.1 [SHOULD] 파일명 / Filename", finding.toSefCheckResult().label)
        assertEquals("파일명 / Filename", finding.groupLabel())
    }

    @Test
    fun `Primary Length is compared numerically`() {
        val items = listOf(
            mapOf("Semantic" to "Primary", "Mime" to "image/jpeg", "Length" to "00"),
            mapOf("Semantic" to "MotionPhoto", "Mime" to "video/mp4", "Length" to "100"),
        )
        assertEquals(SefIntegritySeverity.PASS, findings(gmpXmp(items = items)).rule("gmp.item.primary_length").severity)
    }

    @Test
    fun `group labels are bilingual`() {
        val all = findings(gmpXmp(), format = ContainerFormat.HEIF, fileName = "a.MP.heic")
        assertEquals("카메라 XMP / Camera XMP", all.rule("gmp.camera.motionphoto").groupLabel())
        assertEquals("HEIC·AVIF mpvd 박스 / HEIC·AVIF mpvd box", all.rule("gmp.mpvd.present").groupLabel())
    }
}
