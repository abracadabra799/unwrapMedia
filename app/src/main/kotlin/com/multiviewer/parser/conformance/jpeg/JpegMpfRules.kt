package com.multiviewer.parser.conformance.jpeg

import com.multiviewer.parser.conformance.ConformanceFinding
import com.multiviewer.parser.conformance.ConformanceStatus.NOT_APPLICABLE
import com.multiviewer.parser.conformance.ConformanceStatus.NOT_CHECKED
import com.multiviewer.parser.conformance.jpeg.JpegContext.Companion.MPF_ID
import com.multiviewer.parser.conformance.jpeg.JpegContext.Companion.hex

/** One MP Entry (16 bytes) of the MP Index IFD. */
data class MpEntry(val index: Int, val attribute: Long, val size: Long, val dataOffset: Long, val dependent1: Int, val dependent2: Int) {
    val typeCode: Int get() = (attribute and 0xFFFFFF).toInt()
    val dataFormat: Int get() = ((attribute shr 24) and 7).toInt()
}

/** MPF findings plus the byte ranges of the secondary images that resolved to SOI..EOI (used to explain data after EOI). */
internal class MpfResult(val findings: List<ConformanceFinding>, val images: List<LongRange>)

/** CIPA DC-007-2026 (Multi-Picture Format) rules for the MP Extensions APP2 ("MPF\0") of the first individual image. */
internal object JpegMpfRules {
    private val IDS = listOf("mpf.count", "mpf.header", "mpf.endian", "mpf.index", "mpf.index.order", "mpf.version", "mpf.entries", "mpf.images", "mpf.type")
    private const val BASELINE_PRIMARY = 0x030000
    /** Table 4 codes other than the type-addition range xy000z. */
    private val TABLE4 = setOf(0x030000, 0x010001, 0x010002, 0x010003, 0x010004, 0x010005, 0x040000, 0x050000, 0x020001, 0x020002, 0x020003, 0x000000)
    /** "×" in the Baseline MP file (.JPG) column. */
    private val NOT_IN_BASELINE = setOf(0x020001, 0x020002, 0x020003, 0x000000)
    /** "×" in the Extended MP file (.MPO) column. */
    private val NOT_IN_EXTENDED = setOf(0x030000, 0x040000, 0x050000)
    private val ASCII_0100 = byteArrayOf(0x30, 0x31, 0x30, 0x30)

    fun typeAddition(code: Int): Boolean =
        (code shr 20) and 0xF in 8..15 && (code shr 16) and 0xF in 1..15 && code and 0xFFF0 == 0 && code and 0xF in 1..15

    fun evaluate(ctx: JpegContext): MpfResult {
        val segs = ctx.segments.filter { it.marker == 0xE2 && ctx.startsWith(it, MPF_ID) }
        if (segs.isEmpty()) return MpfResult(IDS.map { ctx.skip(it, NOT_APPLICABLE, "MPF APP2 없음 / no MP Extensions") }, emptyList())
        val s = segs.first()
        val count = ctx.finding("mpf.count",
            if (segs.size == 1) emptyList() else listOf("MP 확장 APP2 ${segs.size}개 (${segs.joinToString { hex(it.offset) }}) — 개별 이미지당 1개 / only one per individual image"),
            segs.drop(1).map { it.range }, listOf("MPF APP2 @${hex(s.offset)}"))
        val p = ctx.payload(s)
        val t = ExifTiff.header(p.copyOfRange(4, p.size), s.payload + 4)
        val header = ctx.finding("mpf.header", t.headerProblems.map { "MPF APP2 @${hex(s.offset)}: $it" },
            if (t.headerOk) emptyList() else listOf(s.range), listOf("MP Endian ${if (t.littleEndian) "II" else "MM"}, Offset to First IFD ${t.firstIfd}"))
        if (!t.headerOk) return MpfResult(listOf(count, header) + IDS.drop(2).map { ctx.skip(it, NOT_CHECKED, "MP 헤더 오류로 판단 불가 / MP header invalid") }, emptyList())

        val exif = ctx.exif?.takeIf { it.headerOk }
        val endian = if (exif == null) ctx.skip("mpf.endian", NOT_APPLICABLE, "Exif TIFF 헤더 없음 / no Exif TIFF header")
        else ctx.finding("mpf.endian",
            if (exif.littleEndian == t.littleEndian) emptyList() else listOf("MP Endian ${if (t.littleEndian) "II" else "MM"} ≠ Exif ${if (exif.littleEndian) "II" else "MM"} / differs from the Exif TIFF header"),
            emptyList(), listOf("MP·Exif 모두 ${if (t.littleEndian) "II" else "MM"} / both ${if (t.littleEndian) "II" else "MM"}"))

        val ifd = t.readIfd(ExifIfd.MP_INDEX, t.firstIfd)
        if (ifd == null) {
            val index = ctx.finding("mpf.index", t.structureProblems.ifEmpty { listOf("MP Index IFD를 읽을 수 없음 / unreadable") }, listOf(s.range), emptyList())
            return MpfResult(listOf(count, header, endian, index) + IDS.drop(4).map { ctx.skip(it, NOT_CHECKED, "MP Index IFD를 읽을 수 없음 / MP Index IFD unreadable") }, emptyList())
        }
        val n = ifd.entry(0xB001)?.let { t.uint(it) }
        val entries = readEntries(t, ifd, n)
        val baseline = entries.firstOrNull()?.typeCode == BASELINE_PRIMARY
        val images = ArrayList<LongRange>()
        return MpfResult(listOf(count, header, endian, index(ctx, t, ifd, n, baseline), order(ctx, ifd), version(ctx, t, ifd),
            entriesRule(ctx, t, ifd, n, entries), imagesRule(ctx, t, entries, images), type(ctx, entries)), images)
    }

    private fun readEntries(t: ExifTiff, ifd: TiffIfd, n: Long?): List<MpEntry> {
        val e = ifd.entry(0xB002) ?: return emptyList()
        if (e.type != 7) return emptyList()
        val count = minOf(e.count / 16, n ?: Long.MAX_VALUE, 10_000)
        if (!t.inRange(e.valueOffset, count * 16)) return emptyList()
        return (0 until count.toInt()).map { i ->
            val o = e.valueOffset + 16L * i
            MpEntry(i, t.u32(o), t.u32(o + 4), t.u32(o + 8), t.u16(o + 12), t.u16(o + 14))
        }
    }

    private fun index(ctx: JpegContext, t: ExifTiff, ifd: TiffIfd, n: Long?, baseline: Boolean): ConformanceFinding {
        val v = ArrayList<String>()
        v += t.structureProblems
        fun need(tag: Int, name: String, type: Int, count: Long?) {
            val e = ifd.entry(tag)
            if (e == null) { v += "$name(0x%04X) 없음 (Table 6: M) / missing".format(tag); return }
            if (e.type != type || (count != null && e.count != count)) v += "$name: Type ${e.type} Count ${e.count} ≠ Table 3 Type $type Count ${count ?: "16×NumberOfImages"} (p.16)"
        }
        need(0xB000, "MPFVersion", 7, 4)
        need(0xB001, "NumberOfImages", 4, 1)
        need(0xB002, "MPEntry", 7, null)
        ifd.entry(0xB003)?.let { if (it.type != 7 || (n != null && it.count != 33 * n)) v += "ImageUIDList: Type ${it.type} Count ${it.count} ≠ UNDEFINED 33×NumberOfImages (p.16)" }
        ifd.entry(0xB004)?.let { if (it.type != 4 || it.count != 1L) v += "TotalFrames: Type ${it.type} Count ${it.count} ≠ LONG 1 (p.16)" }
        if (baseline && ifd.next != 0L) v += "Baseline MP: MP Index IFD의 Offset to Next IFD ${ifd.next} ≠ 0 — MP Attribute IFD 기록 금지 (§6.1.1.1, p.34) / shall be NULL"
        return ctx.finding("mpf.index", v, emptyList(), listOf("MPFVersion, NumberOfImages=${n ?: "?"}, MPEntry 있음 / present"))
    }

    private fun order(ctx: JpegContext, ifd: TiffIfd): ConformanceFinding {
        val bad = ifd.entries.map { it.tag }.zipWithNext().firstOrNull { (a, b) -> b <= a }
        return ctx.finding("mpf.index.order",
            bad?.let { listOf("MP Index IFD: 태그 0x%04X 뒤에 0x%04X — 태그 ID 순서 아님 / not in tag ID order".format(it.first, it.second)) } ?: emptyList(),
            emptyList(), listOf("태그 ID 오름차순 / in tag ID order"))
    }

    private fun version(ctx: JpegContext, t: ExifTiff, ifd: TiffIfd): ConformanceFinding {
        val e = ifd.entry(0xB000) ?: return ctx.skip("mpf.version", NOT_CHECKED, "MPFVersion 없음 (mpf.index 참고) / missing")
        val b = t.valueBytes(e) ?: return ctx.skip("mpf.version", NOT_CHECKED, "MPFVersion 값을 읽을 수 없음 / unreadable")
        val ok = b.contentEquals(ASCII_0100)
        return ctx.finding("mpf.version",
            if (ok) emptyList() else listOf("MPFVersion ${b.joinToString(" ") { "%02X".format(it) }} ≠ \"0100\" (NULL 종료 없음) / shall be ASCII \"0100\""),
            emptyList(), listOf("MPFVersion \"0100\""))
    }

    private fun entriesRule(ctx: JpegContext, t: ExifTiff, ifd: TiffIfd, n: Long?, entries: List<MpEntry>): ConformanceFinding {
        val e = ifd.entry(0xB002)
        if (e == null || n == null) return ctx.skip("mpf.entries", NOT_CHECKED, "NumberOfImages/MPEntry 없음 (mpf.index 참고) / missing")
        val v = ArrayList<String>()
        if (n < 1) v += "NumberOfImages $n — 1 이상 / at least 1"
        if (e.count != 16 * n) v += "MPEntry Count ${e.count} ≠ 16×NumberOfImages (${16 * n}) — MP Entry 수가 이미지 수와 다름 / entries do not match the number of images"
        if (entries.isEmpty() && n >= 1) v += "MPEntry 값이 APP2 데이터 밖 / MP Entry values outside the APP2"
        entries.firstOrNull()?.let { if (it.dataOffset != 0L) v += "MP Entry #1 Data Offset ${it.dataOffset} ≠ 0 — 첫 개별 이미지는 NULL (§5.2.3.3.3, p.20) / shall be NULL" }
        return ctx.finding("mpf.entries", v, emptyList(), listOf("이미지 ${n}개, MP Entry ${entries.size}개 / $n images"))
    }

    private fun imagesRule(ctx: JpegContext, t: ExifTiff, entries: List<MpEntry>, images: MutableList<LongRange>): ConformanceFinding {
        if (entries.isEmpty()) return ctx.skip("mpf.images", NOT_CHECKED, "MP Entry를 읽을 수 없음 / no readable MP Entry")
        val v = ArrayList<String>()
        val ranges = ArrayList<LongRange>()
        val pass = ArrayList<String>()
        entries.forEach { e ->
            val label = "MP Entry #${e.index + 1} (Type 0x%06X)".format(e.typeCode)
            if (e.index == 0) {
                val primary = ctx.stream.end - ctx.stream.start
                when {
                    ctx.stream.eoi == null -> pass += "$label: 주 이미지 EOI 없음 — 크기 미검사 / primary EOI missing, size not checked"
                    e.size != primary -> { v += "$label: Individual Image Size ${e.size} ≠ 주 이미지 SOI..EOI ${primary}바이트 (차이 ${e.size - primary}) / differs from the primary image"; ranges += 0L until minOf(ctx.length, maxOf(e.size, primary)) }
                    else -> pass += "$label: 주 이미지 ${primary}바이트 / primary image size matches"
                }
                return@forEach
            }
            val abs = t.base + e.dataOffset
            val end = abs + e.size
            when {
                e.size < 4 || end > ctx.length -> { v += "$label: @${hex(abs)} + ${e.size}바이트 — 파일(${ctx.length}) 밖 / outside the file"; ranges += abs until minOf(end, ctx.length) }
                ctx.reader.readUInt16(abs) != 0xFFD8 -> { v += "$label: @${hex(abs)}가 SOI가 아님 (0x%04X) / does not point at SOI".format(ctx.reader.readUInt16(abs)); ranges += abs until abs + 2 }
                ctx.reader.readUInt16(end - 2) != 0xFFD9 -> { v += "$label: @${hex(abs)}+${e.size}의 끝이 EOI가 아님 (0x%04X) / size does not end at EOI".format(ctx.reader.readUInt16(end - 2)); ranges += end - 2 until end }
                else -> { images += abs until end; pass += "$label: @${hex(abs)}..${hex(end)} SOI..EOI" }
            }
        }
        return ctx.finding("mpf.images", v, ranges, pass)
    }

    private fun type(ctx: JpegContext, entries: List<MpEntry>): ConformanceFinding {
        if (entries.isEmpty()) return ctx.skip("mpf.type", NOT_CHECKED, "MP Entry를 읽을 수 없음 / no readable MP Entry")
        val baseline = entries.first().typeCode == BASELINE_PRIMARY
        val v = ArrayList<String>()
        entries.forEach { e ->
            val label = "MP Entry #${e.index + 1}: Type 0x%06X".format(e.typeCode)
            if (e.dataFormat != 0) v += "$label — Image Data Format ${e.dataFormat} (0=JPEG 외 예약) / reserved data format"
            val known = e.typeCode in TABLE4 || typeAddition(e.typeCode)
            when {
                !known -> v += "$label — Table 4에 없는 코드는 사용 제한 / not a Table 4 code (restricted)"
                baseline && e.typeCode in NOT_IN_BASELINE -> v += "$label — Baseline MP 파일(.JPG)에서 사용 금지(×) / shall not be used in a Baseline MP file"
                !baseline && (e.typeCode in NOT_IN_EXTENDED || typeAddition(e.typeCode)) -> v += "$label — Extended MP 파일(.MPO)에서 사용 금지(×) / shall not be used in an Extended MP file"
            }
        }
        return ctx.finding("mpf.type", v, emptyList(),
            listOf((if (baseline) "Baseline MP 파일 / Baseline MP file: " else "Extended MP 파일 / Extended MP file: ") + entries.joinToString { "0x%06X".format(it.typeCode) }))
    }
}
