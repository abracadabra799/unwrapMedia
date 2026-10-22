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

    // ---- synthetic JPEG building blocks (MPF / XMP segments, verified by reading them back below) ----
    private fun u16(v: Int) = byteArrayOf((v shr 8).toByte(), v.toByte())
    private fun u32(v: Long) = byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())
    private fun segment(marker: Int, payload: ByteArray) = byteArrayOf(0xFF.toByte(), marker.toByte()) + u16(payload.size + 2) + payload
    private fun insertAfterSoi(jpeg: ByteArray, vararg segments: ByteArray) =
        jpeg.copyOfRange(0, 2) + segments.fold(ByteArray(0)) { acc, s -> acc + s } + jpeg.copyOfRange(2, jpeg.size)
    private fun xmpApp1(xml: String) = segment(0xE1, "http://ns.adobe.com/xap/1.0/\u0000".toByteArray(Charsets.US_ASCII) + xml.toByteArray(Charsets.UTF_8))

    /** Big-endian MPF APP2: IFD (B000, B001, B002) at TIFF+8, two 16-byte MP entries at TIFF+50. TIFF header = segment start + 8. */
    private fun mpfApp2(primarySize: Long, secondSize: Long, secondOffset: Long): ByteArray {
        val ifd = u16(3) +
            u16(0xB000) + u16(7) + u32(4) + "0100".toByteArray(Charsets.US_ASCII) +
            u16(0xB001) + u16(4) + u32(1) + u32(2) +
            u16(0xB002) + u16(7) + u32(32) + u32(50) +
            u32(0)
        val entries = u32(0x030000) + u32(primarySize) + u32(0) + u16(0) + u16(0) +
            u32(0) + u32(secondSize) + u32(secondOffset) + u16(0) + u16(0)
        return segment(0xE2, "MPF\u0000".toByteArray(Charsets.US_ASCII) + "MM\u0000\u002A".toByteArray(Charsets.US_ASCII) + u32(8) + ifd + entries)
    }

    private fun jpegBytes(name: String, size: String) = File(dir, name).also {
        if (!it.exists()) run("ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-f", "lavfi", "-i", "testsrc2=size=$size", "-frames:v", "1", it.absolutePath)
    }.readBytes()

    private val hdrgmXmp = "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">" +
        "<rdf:Description xmlns:hdrgm=\"http://ns.adobe.com/hdr-gain-map/1.0/\" hdrgm:Version=\"1.0\" hdrgm:GainMapMax=\"2.0\"/></rdf:RDF></x:xmpmeta>"

    /** Positions of APP2 "MPF\0" segments before the first SOS of the JPEG at offset 0. */
    private fun mpfSegmentPositions(bytes: ByteArray): List<Int> {
        fun u8(i: Int) = bytes[i].toInt() and 0xFF
        val out = mutableListOf<Int>()
        var pos = 2
        while (pos + 4 <= bytes.size && u8(pos) == 0xFF && u8(pos + 1) != 0xDA) {
            val len = (u8(pos + 2) shl 8) or u8(pos + 3)
            if (u8(pos + 1) == 0xE2 && String(bytes, pos + 4, 4, Charsets.ISO_8859_1) == "MPF\u0000") out += pos
            pos += 2 + len
        }
        return out
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray, from: Int = 0): Int {
        outer@ for (i in from..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }

    /** Container:Item elements of the FIRST (primary) Container:Directory, as attribute maps in document order. */
    private fun directoryItems(bytes: ByteArray): List<Map<String, String>> {
        val text = String(bytes, Charsets.ISO_8859_1)
        val start = text.indexOf("Container:Directory")
        val end = text.indexOf("</Container:Directory>", start)
        if (start < 0 || end < 0) return emptyList()
        return Regex("<Container:Item\\b([^>]*)>").findAll(text.substring(start, end)).map { m ->
            Regex("Item:(\\w+)=\"([^\"]*)\"").findAll(m.groupValues[1]).associate { it.groupValues[1] to it.groupValues[2] }
        }.toList()
    }

    private fun analyzeAs(bytes: ByteArray, name: String): MotionPhotoIntegrityReport {
        val src = File(dir, "src-$name").apply { writeBytes(bytes) }
        val out = File(dir, name)
        MotionPhotoBuilder.createMotionPhoto(src, video(), out, layout = MotionPhotoLayout.GOOGLE_SPEC)
        return MotionPhotoIntegrityAnalyzer.analyze(out, parseFile(out))
    }

    @Test fun `Google-spec JPEG drops a non-gain-map trailing JPEG together with the MPF that points at it`() {
        val base = jpegBytes("mpf-primary.jpg", "64x48")
        val preview = jpegBytes("mpf-preview.jpg", "32x24")
        val mpfTiffStart = 2 + 8 // SOI, then FFE2 + length + "MPF\0"
        val primarySize = base.size + mpfApp2(0, 0, 0).size
        val primary = insertAfterSoi(base, mpfApp2(primarySize.toLong(), preview.size.toLong(), (primarySize - mpfTiffStart).toLong()))
        assertEquals(primarySize, primary.size)
        val report = analyzeAs(primary + preview, "mpf_preview_MP.jpg")
        val out = File(dir, "mpf_preview_MP.jpg").readBytes()
        assertEquals(-1, indexOf(out, preview), "the non-gain-map trailing JPEG must be dropped")
        assertEquals(emptyList(), mpfSegmentPositions(out), "an MPF pointing at dropped bytes must be removed")
        assertTrue(directoryItems(out).none { it["Semantic"] == "GainMap" }, directoryItems(out).toString())
        assertGoogleClean(report)
    }

    @Test fun `Google-spec JPEG removes an existing GainMap item whose trailing JPEG is not a gain map`() {
        val preview = jpegBytes("gm-preview.jpg", "32x24")
        val xmp = "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">" +
            "<rdf:Description xmlns:Container=\"http://ns.google.com/photos/1.0/container/\" xmlns:Item=\"http://ns.google.com/photos/1.0/container/item/\">" +
            "<Container:Directory><rdf:Seq>" +
            "<rdf:li rdf:parseType=\"Resource\"><Container:Item Item:Semantic=\"Primary\" Item:Mime=\"image/jpeg\"/></rdf:li>" +
            "<rdf:li rdf:parseType=\"Resource\"><Container:Item Item:Semantic=\"GainMap\" Item:Mime=\"image/jpeg\" Item:Length=\"${preview.size}\"/></rdf:li>" +
            "</rdf:Seq></Container:Directory></rdf:Description></rdf:RDF></x:xmpmeta>"
        val primary = insertAfterSoi(jpegBytes("gm-primary.jpg", "64x48"), xmpApp1(xmp))
        val report = analyzeAs(primary + preview, "stale_gainmap_MP.jpg")
        val out = File(dir, "stale_gainmap_MP.jpg").readBytes()
        val items = directoryItems(out)
        assertEquals(listOf("Primary", "MotionPhoto"), items.map { it["Semantic"] }, items.toString())
        assertGoogleClean(report)
    }

    @Test fun `Google-spec JPEG keeps a gain map described by MPF with offsets still valid after the XMP rewrite`() {
        val base = jpegBytes("mpf-gm-primary.jpg", "64x48")
        val gainMap = insertAfterSoi(jpegBytes("mpf-gm-gainmap.jpg", "32x24"), xmpApp1(hdrgmXmp))
        // MPF before an existing XMP: the new XMP is inserted ahead of the MPF while the old one (after it) is removed,
        // so the distance from the MPF header to the gain map changes.
        val oldXmp = xmpApp1("<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">" +
            "<rdf:Description xmlns:tiff=\"http://ns.adobe.com/tiff/1.0/\" tiff:Make=\"${"X".repeat(300)}\"/></rdf:RDF></x:xmpmeta>")
        val primarySize = base.size + mpfApp2(0, 0, 0).size + oldXmp.size
        val primary = insertAfterSoi(base, mpfApp2(primarySize.toLong(), gainMap.size.toLong(), (primarySize - 10).toLong()), oldXmp)
        assertEquals(primarySize, primary.size)
        val report = analyzeAs(primary + gainMap, "mpf_gainmap_MP.jpg")
        val out = File(dir, "mpf_gainmap_MP.jpg").readBytes()
        val gainMapStart = indexOf(out, gainMap)
        assertTrue(gainMapStart > 0, "the hdrgm gain map must be kept")
        val mpf = mpfSegmentPositions(out).single()
        val tiff = mpf + 8
        fun be32(i: Int) = ((out[i].toLong() and 0xFF) shl 24) or ((out[i + 1].toLong() and 0xFF) shl 16) or
            ((out[i + 2].toLong() and 0xFF) shl 8) or (out[i + 3].toLong() and 0xFF)
        assertEquals(gainMapStart.toLong(), tiff + be32(tiff + 50 + 16 + 8), "MP entry 2 offset")
        assertEquals(gainMap.size.toLong(), be32(tiff + 50 + 16 + 4), "MP entry 2 size")
        assertEquals(gainMapStart.toLong(), be32(tiff + 50 + 4), "MP entry 1 size = primary image size")
        val items = directoryItems(out)
        assertEquals(listOf("Primary", "GainMap", "MotionPhoto"), items.map { it["Semantic"] }, items.toString())
        assertEquals(gainMap.size.toString(), items[1]["Length"])
        assertGoogleClean(report)
    }

    @Test fun `an Ultra HDR source without a GainMap item gets one before MotionPhoto in both modes`() {
        val gainMap = insertAfterSoi(jpegBytes("uhdr-gainmap.jpg", "32x24"), xmpApp1(hdrgmXmp))
        val src = File(dir, "uhdr-src.jpg").apply { writeBytes(jpegBytes("uhdr-primary.jpg", "64x48") + gainMap) }
        for (layout in MotionPhotoLayout.values()) {
            val out = File(dir, "uhdr_${layout.name}_MP.jpg")
            MotionPhotoBuilder.createMotionPhoto(src, video(), out, layout = layout)
            val items = directoryItems(out.readBytes())
            assertEquals(listOf("Primary", "GainMap", "MotionPhoto"), items.map { it["Semantic"] }, "$layout: $items")
            assertEquals(gainMap.size.toString(), items[1]["Length"], "$layout GainMap Length")
        }
    }

    @Test fun `createMotionPhoto reports dropped SEF only for a SEF source in Google-spec mode`() {
        assertTrue(MotionPhotoBuilder.createMotionPhoto(samsungSource(), video(), File(dir, "ret_sef_MP.jpg"), layout = MotionPhotoLayout.GOOGLE_SPEC))
        assertEquals(false, MotionPhotoBuilder.createMotionPhoto(stillJpeg(), video(), File(dir, "ret_plain_MP.jpg"), layout = MotionPhotoLayout.GOOGLE_SPEC))
        assertEquals(false, MotionPhotoBuilder.createMotionPhoto(samsungSource(), video(), File(dir, "ret_sef.jpg"), layout = MotionPhotoLayout.SAMSUNG_COMPATIBLE))
        assertEquals(false, MotionPhotoBuilder.createMotionPhoto(stillJpeg(), video(), File(dir, "ret_plain.jpg"), layout = MotionPhotoLayout.SAMSUNG_COMPATIBLE))
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
