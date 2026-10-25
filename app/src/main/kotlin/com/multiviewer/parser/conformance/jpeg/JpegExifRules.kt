package com.multiviewer.parser.conformance.jpeg

import com.multiviewer.parser.conformance.ConformanceFinding
import com.multiviewer.parser.conformance.ConformanceStatus.NOT_APPLICABLE
import com.multiviewer.parser.conformance.ConformanceStatus.NOT_CHECKED
import com.multiviewer.parser.conformance.jpeg.JpegContext.Companion.hex
import com.multiviewer.parser.conformance.jpeg.JpegContext.Companion.markerName

/**
 * CIPA DC-008-2026 (Exif 3.1) rules for the primary image's Exif APP1 ("Exif\0\0"), compressed (JPEG) primary image
 * column of Tables 17-21. They apply only when such an APP1 exists.
 */
internal object JpegExifRules {
    private val IDS = listOf("exif.app1.position", "exif.tiff.header", "exif.ifd.structure", "exif.ifd.order", "exif.tag.type",
        "exif.tag.ascii", "exif.tags.mandatory", "exif.tags.not_allowed", "exif.tags.recommended", "exif.dimensions", "exif.thumbnail")
    private const val HEADER_BAD = "TIFF 헤더 오류로 판단 불가 / TIFF header invalid"

    /** Table 17/18 "M" (compressed column) and §4.6.7.1.1 GPSVersionID (mandatory when a GPS tag is present). */
    private val MANDATORY_0TH = mapOf(0x8769 to "ExifIFDPointer", 0x0213 to "YCbCrPositioning")
    private val MANDATORY_EXIF = mapOf(0x9000 to "ExifVersion", 0x9101 to "ComponentsConfiguration", 0xA001 to "ColorSpace",
        0xA002 to "PixelXDimension", 0xA003 to "PixelYDimension")
    /** Table 21 "M" for a compressed thumbnail. */
    private val MANDATORY_1ST = mapOf(0x0103 to "Compression", 0x0201 to "JPEGInterchangeFormat", 0x0202 to "JPEGInterchangeFormatLength")
    /** Table 17 "N"/"J" (compressed column). */
    private val NOT_ALLOWED_0TH = mapOf(0x0100 to "ImageWidth", 0x0101 to "ImageLength", 0x0102 to "BitsPerSample", 0x0103 to "Compression",
        0x0106 to "PhotometricInterpretation", 0x0111 to "StripOffsets", 0x0115 to "SamplesPerPixel", 0x0116 to "RowsPerStrip",
        0x0117 to "StripByteCounts", 0x011C to "PlanarConfiguration", 0x0201 to "JPEGInterchangeFormat",
        0x0202 to "JPEGInterchangeFormatLength", 0x0212 to "YCbCrSubSampling")
    /** Table 21 "N"/"J" (compressed thumbnail column). */
    private val NOT_ALLOWED_1ST = mapOf(0x0100 to "ImageWidth", 0x0101 to "ImageLength", 0x0102 to "BitsPerSample",
        0x0106 to "PhotometricInterpretation", 0x0111 to "StripOffsets", 0x0115 to "SamplesPerPixel", 0x0116 to "RowsPerStrip",
        0x0117 to "StripByteCounts", 0x011C to "PlanarConfiguration", 0x0212 to "YCbCrSubSampling")
    /** Table 17/18 "R" (compressed column). */
    private val RECOMMENDED_0TH = mapOf(0x010E to "ImageDescription", 0x010F to "Make", 0x0110 to "Model", 0x0112 to "Orientation", 0x0132 to "DateTime")
    private val RECOMMENDED_EXIF = mapOf(0x829A to "ExposureTime", 0x9209 to "Flash", 0xA402 to "ExposureMode", 0xA403 to "WhiteBalance",
        0xA406 to "SceneCaptureType", 0xA460 to "CompositeImage")

    fun evaluate(ctx: JpegContext): List<ConformanceFinding> {
        val app1 = ctx.exifSegment ?: return IDS.map { ctx.skip(it, NOT_APPLICABLE, "Exif APP1 없음 / no Exif APP1") }
        val tiff = ctx.exif!!
        val head = listOf(position(ctx, app1), header(ctx, app1, tiff))
        if (!tiff.headerOk) return head + IDS.drop(2).map { ctx.skip(it, NOT_CHECKED, HEADER_BAD) }
        return head + listOf(structure(ctx, app1, tiff), order(ctx, tiff), types(ctx, tiff), ascii(ctx, tiff), mandatory(ctx, tiff),
            notAllowed(ctx, tiff), recommended(ctx, tiff), dimensions(ctx, tiff), thumbnail(ctx, app1, tiff))
    }

    private fun position(ctx: JpegContext, s: JpegSegment): ConformanceFinding {
        val ok = ctx.segments.getOrNull(0)?.marker == 0xD8 && ctx.segments.getOrNull(1) == s
        val prev = ctx.segments.getOrNull(ctx.segments.indexOf(s) - 1)
        return ctx.finding("exif.app1.position",
            if (ok) emptyList() else listOf("Exif APP1 @${hex(s.offset)}가 SOI 바로 뒤가 아님 — 앞: ${prev?.let { markerName(it.marker) + " @" + hex(it.offset) }} / not immediately after SOI" +
                (if (prev?.marker == 0xE0 && ctx.startsWith(prev, JpegContext.JFIF_ID)) " — JFIF(T.871 §6.3)와 Exif(DC-008 §4.7.2.1)는 둘 다 SOI 바로 뒤를 요구하므로 동시에 만족할 수 없음 / JFIF and Exif both require the segment right after SOI, so a file cannot satisfy both" else "")),
            if (ok) emptyList() else listOf(s.range), listOf("Exif APP1 @${hex(s.offset)} = SOI 바로 뒤 / immediately after SOI"))
    }

    private fun header(ctx: JpegContext, s: JpegSegment, t: ExifTiff): ConformanceFinding {
        val v = ArrayList<String>()
        val p = ctx.payload(s)
        if (p.size < 6 || p[5] != 0.toByte()) v += "Exif ID 코드가 \"Exif\" + 00.H 2바이트가 아님 / Exif ID code is not 'Exif' + two 00.H (§4.7.2.3, p.116)"
        v += t.headerProblems
        return ctx.finding("exif.tiff.header", v.map { "Exif APP1 @${hex(s.offset)}: $it" }, if (v.isEmpty()) emptyList() else listOf(s.range),
            listOf("${if (t.littleEndian) "II" else "MM"}, 42, 0th IFD 오프셋 ${t.firstIfd}"))
    }

    private fun structure(ctx: JpegContext, s: JpegSegment, t: ExifTiff): ConformanceFinding =
        ctx.finding("exif.ifd.structure", t.structureProblems.map { "Exif APP1 @${hex(s.offset)}: $it" },
            if (t.structureProblems.isEmpty()) emptyList() else listOf(s.range),
            listOf("IFD: " + t.ifds.values.joinToString { "${it.kind.label} (${it.entries.size})" }))

    private fun order(ctx: JpegContext, t: ExifTiff): ConformanceFinding {
        val v = t.ifds.values.mapNotNull { ifd ->
            val tags = ifd.entries.map { it.tag }
            val bad = tags.zipWithNext().firstOrNull { (a, b) -> b <= a } ?: return@mapNotNull null
            "${ifd.kind.label}: 태그 0x%04X 뒤에 0x%04X — 오름차순 아님(중복 포함) / not in ascending order".format(bad.first, bad.second)
        }
        return ctx.finding("exif.ifd.order", v, emptyList(), listOf("모든 IFD의 태그가 오름차순 / every IFD is in ascending tag order"))
    }

    private fun types(ctx: JpegContext, t: ExifTiff): ConformanceFinding {
        val v = ArrayList<String>()
        t.ifds.values.forEach { ifd ->
            ifd.entries.forEach { e ->
                val def = ExifTagTable.def(ifd.kind, e.tag)
                val name = ExifTagTable.name(ifd.kind, e.tag)
                if (e.type !in ExifTagTable.EXIF_TYPES) v += "${ifd.kind.label} $name: Type ${e.type} — Exif에서 쓰는 Type이 아님 / not an Exif type (§4.6.2, p.38)"
                else if (def != null && (e.type !in def.types || (def.count != null && e.count != def.count)))
                    v += "${ifd.kind.label} $name: Type ${e.type} Count ${e.count} ≠ 정의 Type ${def.types.joinToString("/")} Count ${def.count ?: "Any"} (p.${def.page})"
            }
        }
        return ctx.finding("exif.tag.type", v, emptyList(), listOf("정의된 태그의 Type·Count 일치 / Type and Count match the definitions"))
    }

    private fun ascii(ctx: JpegContext, t: ExifTiff): ConformanceFinding {
        val v = ArrayList<String>()
        t.ifds.values.forEach { ifd ->
            ifd.entries.filter { it.type == 2 || it.type == 129 }.forEach { e ->
                val b = t.valueBytes(e) ?: return@forEach
                val name = ExifTagTable.name(ifd.kind, e.tag)
                if (b.isEmpty() || b.last() != 0.toByte()) v += "${ifd.kind.label} $name: ${if (e.type == 2) "ASCII" else "UTF-8"} Count ${e.count}에 NULL 종료 미포함 / count does not include the NULL terminator"
                if (e.type == 129 && b.size >= 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte())
                    v += "${ifd.kind.label} $name: UTF-8 BOM 사용 / BOM shall not be used (p.39)"
            }
        }
        return ctx.finding("exif.tag.ascii", v, emptyList(), listOf("ASCII/UTF-8 값이 NULL로 끝남 / ASCII and UTF-8 values are NULL-terminated"))
    }

    private fun compressedThumbnail(t: ExifTiff): Boolean? = t.ifds[ExifIfd.IFD1]?.let { ifd -> ifd.entry(0x0103)?.let { t.uint(it) == 6L } }

    private fun mandatory(ctx: JpegContext, t: ExifTiff): ConformanceFinding {
        val v = ArrayList<String>()
        val ifd0 = t.ifds[ExifIfd.IFD0]
        if (ExifIfd.IFD0 !in t.unreadable) MANDATORY_0TH.forEach { (tag, name) -> if (ifd0?.entry(tag) == null) v += "0th IFD: $name 없음 / missing" }
        val exif = t.ifds[ExifIfd.EXIF]
        if (ExifIfd.EXIF !in t.unreadable) MANDATORY_EXIF.forEach { (tag, name) -> if (exif?.entry(tag) == null) v += "Exif IFD: $name 없음 / missing" }
        t.ifds[ExifIfd.GPS]?.let { if (it.entries.isNotEmpty() && it.entry(0x0000) == null) v += "GPS IFD: GPSVersionID 없음 — GPS 태그가 있으면 필수 (§4.6.7.1.1, p.94) / mandatory when a GPS tag is present" }
        val notes = ArrayList<String>()
        t.ifds[ExifIfd.IFD1]?.let { ifd1 ->
            if (ifd1.entry(0x0103) == null) v += "1st IFD: Compression 없음 / missing"
            when (compressedThumbnail(t)) {
                true -> MANDATORY_1ST.forEach { (tag, name) -> if (ifd1.entry(tag) == null) v += "1st IFD: $name 없음 / missing" }
                else -> notes += "1st IFD가 JPEG 압축 썸네일이 아님 — 비압축 썸네일 필수 태그는 1단계 범위 밖 / uncompressed thumbnail tags not checked"
            }
        }
        val mandatoryKinds = setOf(ExifIfd.IFD0, ExifIfd.EXIF, ExifIfd.GPS, ExifIfd.IFD1)
        val cannot = unreadableNotes(t, mandatoryKinds)
        if (v.isEmpty() && cannot.isNotEmpty()) return ctx.skip("exif.tags.mandatory", NOT_CHECKED, cannot.joinToString("; "))
        return ctx.finding("exif.tags.mandatory", v + cannot, emptyList(), listOf("필수(M) 태그 모두 있음 / all mandatory tags present") + notes)
    }

    private fun notAllowed(ctx: JpegContext, t: ExifTiff): ConformanceFinding {
        val v = ArrayList<String>()
        t.ifds[ExifIfd.IFD0]?.let { ifd -> NOT_ALLOWED_0TH.forEach { (tag, name) -> if (ifd.entry(tag) != null) v += "0th IFD: $name — 압축 주 이미지에는 기록 금지(N/J) / not allowed for a compressed primary image" } }
        if (compressedThumbnail(t) == true) t.ifds[ExifIfd.IFD1]?.let { ifd ->
            NOT_ALLOWED_1ST.forEach { (tag, name) -> if (ifd.entry(tag) != null) v += "1st IFD: $name — 압축 썸네일에는 기록 금지(N/J) / not allowed for a compressed thumbnail" }
        }
        return ctx.finding("exif.tags.not_allowed", v, emptyList(), listOf("기록 금지(N/J) 태그 없음 / no tags that are not allowed"))
    }

    private fun unreadableNotes(t: ExifTiff, kinds: Set<ExifIfd>): List<String> =
        t.unreadable.filter { it in kinds }.map { "${it.label}를 읽을 수 없어 그 태그를 판단할 수 없음 (exif.ifd.structure 참고) / ${it.label} could not be read, so its tags are not evaluated (see exif.ifd.structure)" }

    private fun recommended(ctx: JpegContext, t: ExifTiff): ConformanceFinding {
        val ifd0 = t.ifds[ExifIfd.IFD0]
        val exif = t.ifds[ExifIfd.EXIF]
        val missing = (if (ExifIfd.IFD0 in t.unreadable) emptyList() else RECOMMENDED_0TH.filter { ifd0?.entry(it.key) == null }.map { "0th IFD ${it.value}" }) +
            (if (ExifIfd.EXIF in t.unreadable) emptyList() else RECOMMENDED_EXIF.filter { exif?.entry(it.key) == null }.map { "Exif IFD ${it.value}" })
        val cannot = unreadableNotes(t, setOf(ExifIfd.IFD0, ExifIfd.EXIF))
        if (missing.isEmpty() && cannot.isNotEmpty()) return ctx.skip("exif.tags.recommended", NOT_CHECKED, cannot.joinToString("; "))
        // One row listing every missing tag name (user decision 2026-10-25).
        val v = if (missing.isEmpty()) emptyList() else listOf("권장(R) 태그 ${missing.size}개 없음 / ${missing.size} recommended tag(s) missing: " + missing.joinToString(", ")) + cannot
        return ctx.finding("exif.tags.recommended", v, emptyList(), listOf("권장(R) 태그 모두 있음 / all recommended tags present"))
    }

    private fun dimensions(ctx: JpegContext, t: ExifTiff): ConformanceFinding {
        val exif = t.ifds[ExifIfd.EXIF]
        val px = exif?.entry(0xA002)?.let { t.uint(it) }
        val py = exif?.entry(0xA003)?.let { t.uint(it) }
        if (px == null || py == null) return ctx.skip("exif.dimensions", NOT_APPLICABLE, "PixelX/YDimension 없음 또는 읽을 수 없음 (필수 태그 규칙 참고) / missing or unreadable")
        val f = ctx.frame ?: return ctx.skip("exif.dimensions", NOT_CHECKED, "프레임 헤더를 읽을 수 없음 / frame header unreadable")
        val lines = ctx.lines ?: return ctx.skip("exif.dimensions", NOT_CHECKED, "줄 수(Y/DNL)를 알 수 없음 / number of lines unknown")
        val v = ArrayList<String>()
        if (px < 1 || px > f.samplesPerLine) v += "PixelXDimension $px — 1..SOF X(${f.samplesPerLine}) 범위 밖 (§4.8.1 Table 23, p.128) / outside 1..X"
        if (py != lines.toLong()) v += "PixelYDimension $py ≠ SOF Y $lines — SOF는 유효 이미지 높이와 같음 (§4.8.1, p.129) / differs from the SOF height"
        return ctx.finding("exif.dimensions", v, emptyList(), listOf("PixelX/Y ${px}x$py, SOF ${f.samplesPerLine}x$lines"))
    }

    private fun thumbnail(ctx: JpegContext, app1: JpegSegment, t: ExifTiff): ConformanceFinding {
        val id = "exif.thumbnail"
        val ifd1 = t.ifds[ExifIfd.IFD1] ?: return ctx.skip(id, NOT_APPLICABLE, "1st IFD(썸네일) 없음 / no thumbnail IFD")
        if (compressedThumbnail(t) != true) return ctx.skip(id, NOT_CHECKED, "JPEG 압축 썸네일이 아님(Compression≠6) — 1단계 범위 밖 / not a compressed thumbnail")
        val off = ifd1.entry(0x0201)?.let { t.uint(it) }
        val len = ifd1.entry(0x0202)?.let { t.uint(it) }
        if (off == null || len == null) return ctx.skip(id, NOT_CHECKED, "JPEGInterchangeFormat/Length 없음 (필수 태그 규칙 참고) / missing")
        if (!t.inRange(off, len) || len < 4) return ctx.finding(id, listOf("썸네일 오프셋 $off + 길이 $len — APP1 데이터(${t.bytes.size}바이트) 밖 / outside the APP1"), listOf(app1.range), emptyList())
        val abs = t.base + off
        val stream = JpegStreamScanner.scan(ctx.reader, abs, abs + len)
        val v = ArrayList<String>()
        if (stream.segments.firstOrNull()?.marker != 0xD8 || stream.segments.first().offset != abs) v += "썸네일이 SOI로 시작하지 않음 / does not start with SOI"
        if (stream.eoi == null || stream.end != abs + len) v += "썸네일이 SOI..EOI로 정확히 끝나지 않음 (EOI ${stream.eoi?.let { hex(it.offset) } ?: "없음"}) / not exactly SOI..EOI"
        stream.segments.filter { it.marker in 0xE0..0xEF || it.marker == 0xFE }.forEach { v += "썸네일 안의 ${markerName(it.marker)} @${hex(it.offset)} — APPn/COM 기록 금지 / APPn and COM shall not be recorded" }
        val rst = stream.entropy.sumOf { it.restartCount }
        if (rst > 0) v += "썸네일 안의 재시작 마커 ${rst}개 — 기록 금지 / restart markers shall not be recorded"
        stream.segments.firstOrNull { it.marker in JpegFrame.SOF_MARKERS }?.let { if (it.marker != 0xC0) v += "썸네일 ${markerName(it.marker)} — Baseline(SOF0)이 아님 / not Baseline DCT" }
        return ctx.finding(id, v, if (v.isEmpty()) emptyList() else listOf(abs until abs + len), listOf("썸네일 @${hex(abs)} ${len}바이트, SOF0, APPn/COM/RST 없음 / thumbnail @${hex(abs)}, ${len} bytes, SOF0, no APPn/COM/RST"))
    }
}
