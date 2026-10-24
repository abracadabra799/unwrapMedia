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
        ByteReader.open(fileOf(bytes)).use { GoogleMotionPhotoSpecChecker.check(GmpSpecInput(name, format, xmp, 1_000_000), it) }.assertGmpCited()

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
        val layout = result.filter { it.ruleId.startsWith("gmp.layout.") || it.ruleId == "gmp.item.video_mime" }
        assertTrue(layout.none { it.severity == SefIntegritySeverity.PASS }, layout.joinToString("\n"))
        listOf("gmp.layout.video_start", "gmp.item.video_mime", "gmp.layout.packed", "gmp.layout.video_last")
            .forEach { assertEquals(SefIntegritySeverity.SKIPPED, result.rule(it).severity, it) }
    }

    @Test
    fun `Length larger than the file skips the rules that need the video position`() {
        val result = check(jpeg + mp4(), gmpXmp(items = jpegItems(100_000)))
        assertEquals(SefIntegritySeverity.CRITICAL, result.rule("gmp.layout.video_start").severity)
        listOf("gmp.item.video_mime", "gmp.layout.packed", "gmp.layout.video_last")
            .forEach { assertEquals(SefIntegritySeverity.SKIPPED, result.rule(it).severity, it) }
    }

    @Test
    fun `HEIC without a usable MotionPhoto item skips mpvd length and the gain map rule`() {
        val bytes = heicFtyp + box("meta", ByteArray(20)) + box("mpvd", mp4())
        val hdr = "xmlns:hdrgm=\"$HDR_GAINMAP_NS\" hdrgm:Version=\"1.0\""
        val result = check(bytes, gmpXmp(items = listOf(mapOf("Semantic" to "Primary", "Mime" to "image/heic", "Padding" to "8")),
            extraDescriptionAttrs = hdr), ContainerFormat.HEIF, "a.MP.heic")
        listOf("gmp.mpvd.length", "gmp.gainmap.item", "gmp.layout.video_start", "gmp.layout.video_last")
            .forEach { assertEquals(SefIntegritySeverity.SKIPPED, result.rule(it).severity, it) }
    }

    // ---- C1: HEIC/AVIF video located structurally from mpvd ----

    @Test
    fun `HEIC Length equal to the file size never treats the image ftyp as the video`() {
        val bytes = heicFtyp + box("meta", ByteArray(20)) + box("mpvd", mp4())
        val result = check(bytes, gmpXmp(items = heicItems(bytes.size)), ContainerFormat.HEIF, "a.MP.heic")
        assertEquals(SefIntegritySeverity.CRITICAL, result.rule("gmp.layout.video_start").severity)
        listOf("gmp.item.video_mime", "gmp.layout.video_last")
            .forEach { assertTrue(result.rule(it).severity != SefIntegritySeverity.PASS, "$it: ${result.rule(it)}") }
    }

    @Test
    fun `HEIC declared start off the mpvd payload start fails with the byte difference`() {
        val video = mp4()
        val bytes = heicFtyp + box("meta", ByteArray(20)) + box("mpvd", video)
        val payloadStart = heicFtyp.size + 28 + 8
        val start = check(bytes, gmpXmp(items = heicItems(video.size + 3)), ContainerFormat.HEIF, "a.MP.heic").rule("gmp.layout.video_start")
        assertEquals(SefIntegritySeverity.CRITICAL, start.severity)
        assertTrue(start.detail.contains("${payloadStart - 3}") && start.detail.contains("$payloadStart") && start.detail.contains("3바이트 차이"), start.detail)
    }

    @Test
    fun `HEIC mpvd payload that is not an ftyp fails video start and skips the video rules`() {
        val payload = box("free", ByteArray(16)) + mp4()
        val bytes = heicFtyp + box("meta", ByteArray(20)) + box("mpvd", payload)
        val result = check(bytes, gmpXmp(items = heicItems(payload.size)), ContainerFormat.HEIF, "a.MP.heic")
        val start = result.rule("gmp.layout.video_start")
        assertEquals(SefIntegritySeverity.CRITICAL, start.severity)
        assertTrue(start.detail.contains("mpvd 데이터가 ftyp로 시작하지 않습니다"), start.detail)
        listOf("gmp.item.video_mime", "gmp.layout.video_last")
            .forEach { assertEquals(SefIntegritySeverity.SKIPPED, result.rule(it).severity, it) }
    }

    @Test
    fun `HEIC without mpvd skips the rules that need the video position`() {
        val video = mp4()
        val result = check(heicFtyp + box("meta", ByteArray(20)) + video, gmpXmp(items = heicItems(video.size)), ContainerFormat.HEIF, "a.MP.heic")
        listOf("gmp.layout.video_start", "gmp.item.video_mime", "gmp.layout.video_last")
            .forEach { assertEquals(SefIntegritySeverity.SKIPPED, result.rule(it).severity, it) }
    }

    @Test
    fun `Samsung 223828-shaped HEIC with Padding 8 passes video start but fails what follows mpvd`() {
        val video = mp4()
        val sefd = box("sefd", ByteArray(30))
        val bytes = heicFtyp + box("meta", ByteArray(20)) + box("mpvd", video) + sefd
        val result = check(bytes, gmpXmp(items = heicItems(video.size + sefd.size, padding = "8")), ContainerFormat.HEIF, "a.heic")
        assertEquals(SefIntegritySeverity.PASS, result.rule("gmp.layout.video_start").severity, result.rule("gmp.layout.video_start").detail)
        assertEquals(SefIntegritySeverity.PASS, result.rule("gmp.layout.packed").severity)
        listOf("gmp.mpvd.last", "gmp.mpvd.length", "gmp.layout.video_last")
            .forEach { assertEquals(SefIntegritySeverity.CRITICAL, result.rule(it).severity, it) }
    }

    @Test
    fun `nearestVideoFtyp finds a real ftyp before and after the declared start`() {
        val video = mp4()
        val bytes = jpeg + ByteArray(13) + video
        val ftyp = (jpeg.size + 13).toLong()
        ByteReader.open(fileOf(bytes)).use { reader ->
            assertEquals(ftyp, nearestVideoFtyp(reader, ftyp + 7))
            assertEquals(ftyp, nearestVideoFtyp(reader, ftyp - 13))
        }
        val before = check(bytes, gmpXmp(items = jpegItems(video.size + 13))).rule("gmp.layout.video_start")
        assertEquals(SefIntegritySeverity.CRITICAL, before.severity)
        assertTrue(before.detail.contains("13바이트 떨어진"), before.detail)
        val after = check(bytes, gmpXmp(items = jpegItems(video.size - 7))).rule("gmp.layout.video_start")
        assertEquals(SefIntegritySeverity.CRITICAL, after.severity)
        assertTrue(after.detail.contains("-7바이트 떨어진"), after.detail)
    }

    // ---- C2: Ultra HDR gain-map JPEG after the primary EOI ----

    private fun app1Xmp(xmp: String): ByteArray {
        val payload = "http://ns.adobe.com/xap/1.0/\u0000".toByteArray() + xmp.toByteArray()
        val len = payload.size + 2
        return hex("FFE1") + byteArrayOf((len shr 8).toByte(), len.toByte()) + payload
    }
    private val gainMapJpeg = hex("FFD8") +
        app1Xmp("<x:xmpmeta xmlns:x=\"adobe:ns:meta/\" xmlns:hdrgm=\"$HDR_GAINMAP_NS\" hdrgm:Version=\"1.0\"/>") +
        jpeg.copyOfRange(2, jpeg.size)

    @Test
    fun `Samsung-style undeclared gain-map JPEG after the primary fails the gain map and packing rules`() {
        val video = mp4()
        val result = check(jpeg + gainMapJpeg + video, gmpXmp(items = jpegItems(video.size)))
        assertEquals(SefIntegritySeverity.CRITICAL, result.rule("gmp.gainmap.item").severity)
        val packed = result.rule("gmp.layout.packed")
        assertEquals(SefIntegritySeverity.CRITICAL, packed.severity)
        assertTrue(packed.detail.contains("기본 이미지 뒤 ${jpeg.size}에 디렉터리에 없는 JPEG(게인맵) ${gainMapJpeg.size}바이트가 있습니다"), packed.detail)
    }

    @Test
    fun `a trailing JPEG without hdrgm XMP is not a gain map`() {
        val video = mp4()
        val result = check(jpeg + jpeg + video, gmpXmp(items = jpegItems(video.size)))
        assertTrue(result.none { it.ruleId == "gmp.gainmap.item" })
    }

    @Test
    fun `GainMap as the first item gets its own detail`() {
        val video = mp4()
        val hdr = "xmlns:hdrgm=\"$HDR_GAINMAP_NS\" hdrgm:Version=\"1.0\""
        val result = check(jpeg + video, gmpXmp(items = listOf(
            mapOf("Semantic" to "GainMap", "Mime" to "image/jpeg"),
            mapOf("Semantic" to "MotionPhoto", "Mime" to "video/mp4", "Length" to "${video.size}"),
        ), extraDescriptionAttrs = hdr))
        val gain = result.rule("gmp.gainmap.item")
        assertEquals(SefIntegritySeverity.CRITICAL, gain.severity)
        assertEquals("GainMap 항목이 첫 항목입니다 (Primary 다음에 와야 함)", gain.detail)
    }
}
