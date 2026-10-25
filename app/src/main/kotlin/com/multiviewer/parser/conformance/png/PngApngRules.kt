package com.multiviewer.parser.conformance.png

import com.multiviewer.parser.conformance.ConformanceFinding
import com.multiviewer.parser.conformance.ConformanceStatus.NOT_APPLICABLE
import com.multiviewer.parser.conformance.ConformanceStatus.NOT_CHECKED

/** APNG (PNG-3 §4.9, §11.3.6): acTL, sequence numbers, fcTL fields and regions, fdAT placement. */
internal object PngApngRules {
    private val IDS = listOf("png3.actl", "png3.sequence", "png3.fctl", "png3.fdat")

    private fun u32(b: ByteArray, i: Int): Long =
        ((b[i].toLong() and 0xFF) shl 24) or ((b[i + 1].toLong() and 0xFF) shl 16) or ((b[i + 2].toLong() and 0xFF) shl 8) or (b[i + 3].toLong() and 0xFF)

    fun evaluate(ctx: PngContext): List<ConformanceFinding> {
        val actl = ctx.first("acTL")
        val animation = ctx.complete.filter { it.type == "fcTL" || it.type == "fdAT" }
        if (actl == null) {
            return if (animation.isEmpty()) IDS.map { ctx.skip(it, NOT_APPLICABLE, "acTL 없음 — APNG 아님 / no acTL: not an animated PNG") }
            else IDS.map { ctx.skip(it, NOT_CHECKED, "acTL 없이 fcTL/fdAT ${animation.size}개 — APNG로 선언되지 않아 검사하지 않음 / ${animation.size} fcTL/fdAT without acTL: not declared animated, not checked") }
        }
        return listOf(actl(ctx, actl), sequence(ctx, animation), fctl(ctx), fdat(ctx))
    }

    private fun actl(ctx: PngContext, c: PngChunk): ConformanceFinding {
        if (c.length != 8L) return ctx.finding("png3.actl", listOf("${c.label}: 길이 ${c.length} ≠ 8 / length ${c.length} ≠ 8"), listOf(ctx.range(c)), emptyList())
        val b = ctx.data(c, 8)
        val frames = u32(b, 0)
        val plays = u32(b, 4)
        val fctls = ctx.of("fcTL").size
        val bad = ArrayList<String>()
        if (frames == 0L) bad += "num_frames = 0"
        else if (frames != fctls.toLong()) bad += "num_frames $frames ≠ fcTL ${fctls}개 / num_frames $frames ≠ $fctls fcTL chunks"
        return ctx.finding("png3.actl", bad.map { "${c.label}: $it" }, if (bad.isEmpty()) emptyList() else listOf(ctx.range(c)),
            listOf("num_frames $frames = fcTL ${fctls}개 / $fctls fcTL chunks, num_plays $plays"))
    }

    private fun sequence(ctx: PngContext, chunks: List<PngChunk>): ConformanceFinding {
        val bad = ArrayList<String>()
        val ranges = ArrayList<LongRange>()
        var expected = 0L
        for (c in chunks) {
            if (c.length < 4) continue // png3.fctl / png3.fdat report it
            val seq = u32(ctx.data(c, 4), 0)
            if (seq != expected) { bad += "${c.label}: sequence_number $seq ≠ 기대값 $expected / expected $expected"; ranges += ctx.range(c) }
            expected = seq + 1
        }
        if (chunks.firstOrNull()?.type == "fdAT") bad += "첫 애니메이션 청크가 fcTL이 아님 / the first animation chunk is fdAT, not fcTL"
        return ctx.finding("png3.sequence", bad, ranges, listOf("fcTL+fdAT ${chunks.size}개, 0부터 빈틈없이 증가 / ${chunks.size} fcTL+fdAT numbered 0.. without gaps"))
    }

    private fun fctl(ctx: PngContext): ConformanceFinding {
        val h = ctx.header ?: return ctx.skip("png3.fctl", NOT_CHECKED, "IHDR 무효 / IHDR invalid")
        val list = ctx.of("fcTL")
        if (list.isEmpty()) return ctx.finding("png3.fctl", listOf("acTL은 있으나 fcTL 없음 / acTL present but no fcTL"), emptyList(), emptyList())
        val bad = ArrayList<String>()
        val ranges = ArrayList<LongRange>()
        fun fail(c: PngChunk, t: String) { bad += "${c.label}: $t"; ranges += ctx.range(c) }
        val first = ctx.firstIdat
        val last = ctx.lastIdat
        val before = list.filter { first != null && it.index < first }
        if (before.size > 1) before.drop(1).forEach { fail(it, "IDAT 앞의 두 번째 fcTL / second fcTL before IDAT") }
        list.filter { first != null && last != null && it.index in first..last }.forEach { fail(it, "IDAT 사이의 fcTL / fcTL between IDAT chunks") }
        for (c in list) {
            if (c.length != 26L) { fail(c, "길이 ${c.length} ≠ 26 / length ${c.length} ≠ 26"); continue }
            val b = ctx.data(c, 26)
            val w = u32(b, 4); val hh = u32(b, 8); val x = u32(b, 12); val y = u32(b, 16)
            val dispose = b[24].toInt() and 0xFF
            val blend = b[25].toInt() and 0xFF
            if (w == 0L || hh == 0L) fail(c, "width/height ${w}x$hh (> 0)")
            if (x + w > h.width || y + hh > h.height) fail(c, "프레임 영역 ($x,$y) ${w}x${hh}가 IHDR ${h.width}x${h.height} 밖 / frame region ($x,$y) ${w}x$hh outside IHDR ${h.width}x${h.height}")
            if (dispose > 2) fail(c, "dispose_op $dispose (0..2)")
            if (blend > 1) fail(c, "blend_op $blend (0, 1)")
            if (c == before.firstOrNull() && (x != 0L || y != 0L || w != h.width || hh != h.height))
                fail(c, "기본 이미지 fcTL은 오프셋 0, IHDR 크기여야 함 / default-image fcTL must have offsets 0 and the IHDR size (got ($x,$y) ${w}x$hh)")
        }
        return ctx.finding("png3.fctl", bad, ranges, listOf("fcTL ${list.size}개(기본 이미지 포함 ${before.size}) / ${list.size} fcTL (${before.size} for the default image), all inside ${h.width}x${h.height}"))
    }

    private fun fdat(ctx: PngContext): ConformanceFinding {
        val bad = ArrayList<String>()
        val ranges = ArrayList<LongRange>()
        fun fail(c: PngChunk, t: String) { bad += "${c.label}: $t"; ranges += ctx.range(c) }
        val last = ctx.lastIdat
        val first = ctx.firstIdat
        ctx.of("fdAT").forEach { c ->
            if (c.length < 4) fail(c, "길이 ${c.length} < 4(시퀀스 번호) / length ${c.length} < 4 (sequence number)")
            if (last == null || c.index < last) fail(c, "IDAT 앞 / before IDAT")
        }
        // Frames: each fcTL up to the next fcTL; frames whose fcTL is after IDAT need at least one fdAT.
        val fctls = ctx.of("fcTL")
        fctls.forEachIndexed { i, f ->
            if (first != null && f.index < first) return@forEachIndexed
            val next = fctls.getOrNull(i + 1)?.index ?: Int.MAX_VALUE
            if (ctx.complete.none { it.type == "fdAT" && it.index > f.index && it.index < next }) fail(f, "이 프레임에 fdAT 없음 / no fdAT for this frame")
        }
        // Default-image frame (fcTL before IDAT): its data is the IDAT stream, so an fdAT before the next fcTL is a second source.
        if (first != null && last != null) {
            val nextAfterIdat = fctls.firstOrNull { it.index > last }?.index ?: Int.MAX_VALUE
            if (fctls.any { it.index < first })
                ctx.of("fdAT").filter { it.index > last && it.index < nextAfterIdat }
                    .forEach { fail(it, "기본 이미지 프레임에 fdAT(데이터는 IDAT) / fdAT in the default-image frame (its data is the IDAT stream)") }
        }
        val orphan = ctx.of("fdAT").filter { d -> fctls.none { it.index < d.index } }
        orphan.forEach { fail(it, "앞선 fcTL 없음 / no preceding fcTL") }
        return ctx.finding("png3.fdat", bad, ranges, listOf("fdAT ${ctx.of("fdAT").size}개, 모두 IDAT 뒤, 프레임마다 1개 이상 / ${ctx.of("fdAT").size} fdAT, all after IDAT, at least one per non-default frame"))
    }
}
