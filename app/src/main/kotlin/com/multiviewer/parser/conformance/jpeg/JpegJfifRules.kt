package com.multiviewer.parser.conformance.jpeg

import com.multiviewer.parser.conformance.ConformanceFinding
import com.multiviewer.parser.conformance.ConformanceStatus.NOT_APPLICABLE
import com.multiviewer.parser.conformance.ConformanceStatus.NOT_CHECKED
import com.multiviewer.parser.conformance.jpeg.JpegContext.Companion.JFIF_ID
import com.multiviewer.parser.conformance.jpeg.JpegContext.Companion.JFXX_ID
import com.multiviewer.parser.conformance.jpeg.JpegContext.Companion.hex
import com.multiviewer.parser.conformance.jpeg.JpegContext.Companion.markerName

/** ITU-T T.871 (JFIF) rules; they apply only when the file carries a JFIF APP0 ("JFIF\0"). */
internal object JpegJfifRules {
    private val IDS = listOf("jfif.position", "jfif.fields", "jfif.version", "jfif.frame", "jfif.extension", "jfif.app0")
    private val EXTENSION_CODES = setOf(0x10, 0x11, 0x13)

    fun evaluate(ctx: JpegContext): List<ConformanceFinding> {
        val app0 = ctx.segments.filter { it.marker == 0xE0 }
        val jfif = app0.filter { ctx.startsWith(it, JFIF_ID) }
        val first = jfif.firstOrNull() ?: return IDS.map { ctx.skip(it, NOT_APPLICABLE, "JFIF APP0 없음 / no JFIF APP0") }
        return listOf(position(ctx, first), fields(ctx, first), version(ctx, first), frame(ctx), extension(ctx, first), app0(ctx, first, app0))
    }

    private fun position(ctx: JpegContext, s: JpegSegment): ConformanceFinding {
        val ok = ctx.segments.getOrNull(0)?.marker == 0xD8 && ctx.segments.getOrNull(1) == s
        val prev = ctx.segments.getOrNull(ctx.segments.indexOf(s) - 1)
        return ctx.finding("jfif.position",
            if (ok) emptyList() else listOf("JFIF APP0 @${hex(s.offset)}가 SOI 바로 뒤가 아님 — 앞: ${prev?.let { markerName(it.marker) + " @" + hex(it.offset) }} / does not immediately follow SOI"),
            if (ok) emptyList() else listOf(s.range), listOf("JFIF APP0 @${hex(s.offset)} = SOI 바로 뒤 / immediately after SOI"))
    }

    private fun fields(ctx: JpegContext, s: JpegSegment): ConformanceFinding {
        val p = ctx.payload(s)
        if (p.size < 14) return ctx.finding("jfif.fields", listOf("JFIF APP0 @${hex(s.offset)}: Lp=${s.length} — 필수 필드(16바이트)보다 짧음 / shorter than 16"), listOf(s.range), emptyList())
        val units = p[7].toInt() and 0xFF
        val hd = ((p[8].toInt() and 0xFF) shl 8) or (p[9].toInt() and 0xFF)
        val vd = ((p[10].toInt() and 0xFF) shl 8) or (p[11].toInt() and 0xFF)
        val tw = p[12].toInt() and 0xFF
        val th = p[13].toInt() and 0xFF
        val k = tw * th
        val v = ArrayList<String>()
        if (s.length != 16 + 3 * k) v += "Lp=${s.length} ≠ 16 + 3×k (k = ${tw}×$th, ${16 + 3 * k})"
        if (units > 2) v += "units=$units — 0, 1, 2만 정의 / only 0, 1, 2 are defined"
        if (hd == 0 || vd == 0) v += "Hdensity=$hd, Vdensity=$vd — 0이 아니어야 함 / must be non-zero"
        return ctx.finding("jfif.fields", v.map { "JFIF APP0 @${hex(s.offset)}: $it" }, if (v.isEmpty()) emptyList() else listOf(s.range),
            listOf("Lp=${s.length}, units=$units, density ${hd}x$vd, 썸네일 ${tw}x$th"))
    }

    private fun version(ctx: JpegContext, s: JpegSegment): ConformanceFinding {
        val p = ctx.payload(s)
        if (p.size < 7) return ctx.skip("jfif.version", NOT_CHECKED, "버전 필드가 없음 / no version field")
        val major = p[5].toInt() and 0xFF
        val minor = p[6].toInt() and 0xFF
        val ok = major == 1 && minor == 2
        val legacy = ((major shl 8) or minor) in JpegRules.LEGACY_JFIF_VERSIONS
        val detail = if (legacy) "JFIF APP0 @${hex(s.offset)}: version %d.%02d — 이전 JFIF %d.%02d 판과 일치, T.871은 X'0102'를 요구 / matches the earlier JFIF %d.%02d edition; T.871 requires X'0102'".format(major, minor, major, minor, major, minor)
            else "JFIF APP0 @${hex(s.offset)}: version %d.%02d (0x%02X%02X) ≠ 0x0102 — T.871은 1.02를 요구 / T.871 requires X'0102'".format(major, minor, major, minor)
        val f = ctx.finding("jfif.version", if (ok) emptyList() else listOf(detail),
            if (ok) emptyList() else listOf(s.offset + 9 until s.offset + 11), listOf("version 1.02"))
        return if (legacy) f.copy(status = com.multiviewer.parser.conformance.ConformanceStatus.WARN) else f
    }

    private fun frame(ctx: JpegContext): ConformanceFinding {
        val f = ctx.frame ?: return ctx.skip("jfif.frame", NOT_CHECKED, "프레임 헤더를 읽을 수 없음 / frame header unreadable")
        val v = ArrayList<String>()
        if (f.declaredCount != 1 && f.declaredCount != 3) v += "Nf=${f.declaredCount} — JFIF는 1 또는 3 성분 / JFIF has 1 or 3 colour channels"
        if (f.precision != 8) v += "P=${f.precision} — JFIF는 8비트 / JFIF has 8 bits per channel"
        val ids = f.components.map { it.id }
        val expected = if (f.declaredCount == 1) listOf(1) else listOf(1, 2, 3)
        if ((f.declaredCount == 1 || f.declaredCount == 3) && ids != expected)
            v += "성분 ID ${ids} ≠ $expected (§10.1: C1=1 Y, C2=2 Cb, C3=3 Cr, p.13) / component identifiers"
        return ctx.finding("jfif.frame", v.map { "${markerName(f.marker)} @${hex(f.segment.offset)}: $it" },
            if (v.isEmpty()) emptyList() else listOf(f.segment.range), listOf("Nf=${f.declaredCount}, P=${f.precision}, 성분 ID $ids"))
    }

    private fun extension(ctx: JpegContext, jfif: JpegSegment): ConformanceFinding {
        val jfxx = ctx.segments.filter { it.marker == 0xE0 && ctx.startsWith(it, JFXX_ID) }
        if (jfxx.isEmpty()) return ctx.skip("jfif.extension", NOT_APPLICABLE, "JFXX 확장 없음 / no JFIF extension")
        val start = ctx.segments.indexOf(jfif) + 1
        val run = ctx.segments.drop(start).takeWhile { it in jfxx }
        val v = ArrayList<String>()
        jfxx.filter { it !in run }.forEach { v += "JFXX @${hex(it.offset)}: JFIF APP0 바로 뒤(연속)가 아님 / does not immediately follow the JFIF APP0" }
        jfxx.forEach { s ->
            val p = ctx.payload(s)
            if (p.size < 6) v += "JFXX @${hex(s.offset)}: extension_code 없음 / no extension_code"
            else if ((p[5].toInt() and 0xFF) !in EXTENSION_CODES) v += "JFXX @${hex(s.offset)}: extension_code=0x%02X — 0x10/0x11/0x13만 정의 (§10.2, p.13) / undefined".format(p[5].toInt() and 0xFF)
        }
        return ctx.finding("jfif.extension", v, if (v.isEmpty()) emptyList() else jfxx.map { it.range }, listOf("JFXX ${jfxx.size}개, JFIF 바로 뒤 / immediately after the JFIF APP0"))
    }

    private fun app0(ctx: JpegContext, jfif: JpegSegment, app0: List<JpegSegment>): ConformanceFinding {
        val v = ArrayList<String>()
        val ranges = ArrayList<LongRange>()
        app0.filter { it != jfif && ctx.startsWith(it, JFIF_ID) }.forEach { v += "APP0 @${hex(it.offset)}: 두 번째 \"JFIF\" 식별자 — 응용 APP0는 JFIF/JFXX 식별자를 쓸 수 없음 / application APP0 shall not use \"JFIF\""; ranges += it.range }
        val others = app0.filter { !ctx.startsWith(it, JFIF_ID) && !ctx.startsWith(it, JFXX_ID) }
        others.forEach { s ->
            val p = ctx.payload(s)
            if (p.isEmpty() || p.none { it == 0.toByte() }) { v += "APP0 @${hex(s.offset)}: 0으로 끝나는 식별 문자열이 없음 / no zero-terminated identifier string"; ranges += s.range }
        }
        return ctx.finding("jfif.app0", v, ranges, listOf("응용 APP0 ${others.size}개, 식별자 정상 / ${others.size} application APP0(s) with identifiers"))
    }
}
