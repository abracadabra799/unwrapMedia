package com.multiviewer.parser.motionphoto

import com.multiviewer.parser.ByteReader
import com.multiviewer.parser.ContainerFormat
import com.multiviewer.parser.SefIntegritySeverity
import com.multiviewer.parser.fileOf
import com.multiviewer.parser.hex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GoogleMotionPhotoLayoutTest {
    private fun be32(v: Long) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
    private fun box(type: String, payload: ByteArray) = be32(8L + payload.size) + type.toByteArray(Charsets.US_ASCII) + payload
    private fun mp4(brand: String = "isom") =
        box("ftyp", brand.toByteArray(Charsets.US_ASCII) + be32(0) + "isommp41".toByteArray(Charsets.US_ASCII)) +
            box("mdat", ByteArray(40) { 1 }) + box("moov", ByteArray(24))

    // SOI, APP0, SOS, entropy data with FF00 stuffing and an RST marker, EOI.
    private val jpeg = hex("FFD8 FFE0 0006 4A464946 FFDA 0008 010100003F00 1234FF0056FFD078 FFD9")

    private fun check(bytes: ByteArray, xmp: String, format: ContainerFormat = ContainerFormat.JPEG, name: String = "PXL_1.MP.jpg") =
        ByteReader.open(fileOf(bytes)).use { GoogleMotionPhotoSpecChecker.check(GmpSpecInput(name, format, xmp, 1_000_000), it) }

    private fun List<GoogleSpecFinding>.rule(id: String) = single { it.ruleId == id }
    private fun jpegItems(videoLength: Int, primary: Map<String, String> = emptyMap(), video: Map<String, String> = emptyMap()) = listOf(
        mapOf("Semantic" to "Primary", "Mime" to "image/jpeg") + primary,
        mapOf("Semantic" to "MotionPhoto", "Mime" to "video/mp4", "Length" to "$videoLength") + video,
    )
    private val bad = setOf(SefIntegritySeverity.CRITICAL, SefIntegritySeverity.WARNING)

    @Test
    fun `primaryJpegEnd walks segments and entropy data to the EOI`() {
        ByteReader.open(fileOf(jpeg + mp4())).use { assertEquals(jpeg.size.toLong(), primaryJpegEnd(it)) }
        ByteReader.open(fileOf(mp4())).use { assertNull(primaryJpegEnd(it)) }
    }

    @Test
    fun `walkBoxes stops at bytes that are not a box`() {
        val bytes = mp4() + ByteArray(10) { 0x0E } + "SEFT".toByteArray()
        ByteReader.open(fileOf(bytes)).use { reader ->
            val boxes = walkBoxes(reader, 0, reader.length)
            assertEquals(listOf("ftyp", "mdat", "moov"), boxes.map { it.type })
            assertEquals(mp4().size.toLong(), boxes.last().end)
        }
    }

    @Test
    fun `compliant JPEG motion photo has no FAIL or WARN`() {
        val video = mp4()
        val result = check(jpeg + video, gmpXmp(items = jpegItems(video.size)))
        assertTrue(result.none { it.severity in bad }, result.joinToString("\n"))
        listOf("gmp.layout.video_start", "gmp.layout.packed", "gmp.layout.video_last", "gmp.item.video_mime")
            .forEach { assertEquals(SefIntegritySeverity.PASS, result.rule(it).severity, it) }
    }

    @Test
    fun `Samsung-style JPEG fails video start and trailing bytes but is packed`() {
        val sefPrefix = ByteArray(161) { 0x0E }
        val sefTrailer = ByteArray(20) { 0x0E } + "SEFT".toByteArray()
        val video = mp4()
        val declared = sefPrefix.size + video.size + sefTrailer.size
        val result = check(jpeg + sefPrefix + video + sefTrailer, gmpXmp(items = jpegItems(declared)))
        assertEquals(SefIntegritySeverity.PASS, result.rule("gmp.layout.packed").severity)
        val start = result.rule("gmp.layout.video_start")
        assertEquals(SefIntegritySeverity.CRITICAL, start.severity)
        assertTrue(start.detail.contains("161"), start.detail)
        val last = result.rule("gmp.layout.video_last")
        assertEquals(SefIntegritySeverity.CRITICAL, last.severity)
        assertTrue(last.detail.contains("SEF"), last.detail)
    }

    @Test
    fun `gap between image and video fails packing unless Padding declares it`() {
        val video = mp4()
        val bytes = jpeg + ByteArray(10) + video
        val gap = check(bytes, gmpXmp(items = jpegItems(video.size))).rule("gmp.layout.packed")
        assertEquals(SefIntegritySeverity.CRITICAL, gap.severity)
        assertTrue(gap.detail.contains("10바이트"), gap.detail)
        assertEquals(SefIntegritySeverity.PASS,
            check(bytes, gmpXmp(items = jpegItems(video.size, primary = mapOf("Padding" to "10")))).rule("gmp.layout.packed").severity)
    }

    @Test
    fun `Length larger than the file fails video start`() {
        val result = check(jpeg + mp4(), gmpXmp(items = jpegItems(100_000)))
        assertEquals(SefIntegritySeverity.CRITICAL, result.rule("gmp.layout.video_start").severity)
    }

    @Test
    fun `QuickTime video declared as mp4 fails the video Mime rule`() {
        val video = mp4(brand = "qt  ")
        val result = check(jpeg + video, gmpXmp(items = jpegItems(video.size)))
        assertEquals(SefIntegritySeverity.CRITICAL, result.rule("gmp.item.video_mime").severity)
    }

    @Test
    fun `Ultra HDR needs a GainMap item before the video`() {
        val video = mp4()
        val hdr = "xmlns:hdrgm=\"$HDR_GAINMAP_NS\" hdrgm:Version=\"1.0\""
        val missing = check(jpeg + video, gmpXmp(items = jpegItems(video.size), extraDescriptionAttrs = hdr))
        assertEquals(SefIntegritySeverity.CRITICAL, missing.rule("gmp.gainmap.item").severity)

        val gainMap = jpeg
        val withGainMap = check(jpeg + gainMap + video, gmpXmp(items = listOf(
            mapOf("Semantic" to "Primary", "Mime" to "image/jpeg"),
            mapOf("Semantic" to "GainMap", "Mime" to "image/jpeg", "Length" to "${gainMap.size}"),
            mapOf("Semantic" to "MotionPhoto", "Mime" to "video/mp4", "Length" to "${video.size}"),
        ), extraDescriptionAttrs = hdr))
        assertEquals(SefIntegritySeverity.PASS, withGainMap.rule("gmp.gainmap.item").severity)
        assertEquals(SefIntegritySeverity.PASS, withGainMap.rule("gmp.layout.packed").severity)
    }

    private val heicFtyp = box("ftyp", "heic".toByteArray() + be32(0) + "mif1heic".toByteArray())
    private fun heicItems(videoLength: Int, padding: String? = "8") = listOf(
        mapOf("Semantic" to "Primary", "Mime" to "image/heic") + (if (padding != null) mapOf("Padding" to padding) else emptyMap()),
        mapOf("Semantic" to "MotionPhoto", "Mime" to "video/mp4", "Length" to "$videoLength"),
    )

    @Test
    fun `compliant HEIC motion photo passes the mpvd and layout rules`() {
        val video = mp4()
        val bytes = heicFtyp + box("meta", ByteArray(20)) + box("mpvd", video)
        val result = check(bytes, gmpXmp(items = heicItems(video.size)), ContainerFormat.HEIF, "a.MP.heic")
        assertTrue(result.none { it.severity in bad }, result.joinToString("\n"))
        listOf("gmp.mpvd.present", "gmp.mpvd.size", "gmp.mpvd.last", "gmp.mpvd.length", "gmp.layout.packed",
            "gmp.layout.video_start", "gmp.layout.video_last").forEach { assertEquals(SefIntegritySeverity.PASS, result.rule(it).severity, it) }
    }

    @Test
    fun `Samsung-style HEIC with a box after mpvd fails`() {
        val video = mp4()
        val mpvd = box("mpvd", video)
        val sefd = box("sefd", ByteArray(30))
        val bytes = heicFtyp + box("meta", ByteArray(20)) + mpvd + sefd
        val result = check(bytes, gmpXmp(items = heicItems(mpvd.size + sefd.size, padding = "0")), ContainerFormat.HEIF, "a.heic")
        listOf("gmp.item.padding_isobmff", "gmp.mpvd.last", "gmp.mpvd.length", "gmp.layout.video_start", "gmp.layout.video_last")
            .forEach { assertEquals(SefIntegritySeverity.CRITICAL, result.rule(it).severity, it) }
        assertTrue(result.rule("gmp.mpvd.last").detail.contains("sefd"))
    }

    @Test
    fun `HEIC without a top-level mpvd fails`() {
        val video = mp4()
        val result = check(heicFtyp + box("meta", ByteArray(20)) + video, gmpXmp(items = heicItems(video.size)), ContainerFormat.HEIF, "a.MP.heic")
        assertEquals(SefIntegritySeverity.CRITICAL, result.rule("gmp.mpvd.present").severity)
    }

    @Test
    fun `mpvd with size 0 fails`() {
        val video = mp4()
        val bytes = heicFtyp + box("meta", ByteArray(20)) + be32(0) + "mpvd".toByteArray() + video
        val result = check(bytes, gmpXmp(items = heicItems(video.size)), ContainerFormat.HEIF, "a.MP.heic")
        assertEquals(SefIntegritySeverity.CRITICAL, result.rule("gmp.mpvd.size").severity)
    }

    @Test
    fun `no usable MotionPhoto item skips the layout rules instead of passing them`() {
        val result = check(jpeg + mp4(), gmpXmp(items = listOf(mapOf("Semantic" to "Primary", "Mime" to "image/jpeg"))))
        assertEquals(SefIntegritySeverity.SKIPPED, result.rule("gmp.layout").severity)
        assertTrue(result.none { it.ruleId.startsWith("gmp.layout.") })
    }
}
