package com.multiviewer.parser.conformance.webp

import com.multiviewer.parser.conformance.ConformanceFinding
import com.multiviewer.parser.conformance.ConformanceStatus.NOT_APPLICABLE
import com.multiviewer.parser.conformance.ConformanceStatus.NOT_CHECKED
import com.multiviewer.parser.conformance.ImageRuleBook.Companion.hex
import com.multiviewer.parser.conformance.webp.WebpContext.Companion.BITSTREAMS

/** RFC 9649 §2.3-2.7: RIFF header and size, chunk padding, simple / extended layout, order, VP8X fields and flags. */
internal object WebpContainerRules {
    /** Rank of the chunks "necessary for reconstruction and color correction" (§2.7); image data = 3. */
    private val RANK = mapOf("VP8X" to 0, "ICCP" to 1, "ANIM" to 2, "ANMF" to 3, "ALPH" to 3, "VP8 " to 3, "VP8L" to 3)
    const val MAX_FILE_SIZE = 0xFFFF_FFFFL - 9 // 2^32 - 10

    fun evaluate(ctx: WebpContext): List<ConformanceFinding> =
        listOf(header(ctx), riffSize(ctx), layout(ctx), fileLayout(ctx), order(ctx), vp8x(ctx), flags(ctx), metadataCount(ctx))

    private fun header(ctx: WebpContext): ConformanceFinding =
        if (ctx.headerOk) ctx.finding("rfc9649.header", emptyList(), emptyList(), listOf("'RIFF' + File Size + 'WEBP'"))
        else ctx.finding("rfc9649.header", listOf("'RIFF'…'WEBP' 헤더가 아님 / not a 'RIFF' … 'WEBP' header"), listOf(0L until minOf(12L, ctx.length)), emptyList())

    private fun riffSize(ctx: WebpContext): ConformanceFinding {
        val size = ctx.riffSize ?: return ctx.finding("rfc9649.riff.size", listOf("File Size 필드 없음 / no File Size field"), emptyList(), emptyList())
        if (!ctx.headerOk) return ctx.skip("rfc9649.riff.size", NOT_CHECKED, "RIFF/WEBP 헤더 아님 / not a RIFF/WEBP header")
        val bad = ArrayList<String>()
        if (size > MAX_FILE_SIZE) bad += "File Size $size > 2^32-10"
        if (size % 2 == 1L) bad += "File Size $size 홀수(청크 크기 합은 짝수) / File Size $size is odd (padded chunks always sum to an even size)"
        if (ctx.riffEnd > ctx.length) bad += "File Size가 ${ctx.riffEnd}바이트를 선언하나 파일은 ${ctx.length}바이트(잘림) / File Size declares ${ctx.riffEnd} bytes but the file has ${ctx.length} (truncated)"
        else if (ctx.chunks.any { !it.complete }) bad += "청크가 File Size 범위(${hex(ctx.riffEnd)})를 넘음 / a chunk runs past the File Size range end ${hex(ctx.riffEnd)}"
        else if (ctx.top.end != ctx.riffEnd) bad += "청크가 RIFF 범위를 정확히 채우지 않음(${hex(ctx.top.end)} ≠ ${hex(ctx.riffEnd)}) / chunks do not tile the RIFF payload exactly"
        return ctx.finding("rfc9649.riff.size", bad, if (bad.isEmpty()) emptyList() else listOf(0L until 8L),
            listOf("File Size $size = 'WEBP' 4 + 청크 ${ctx.chunks.size}개 / 4 + ${ctx.chunks.size} padded chunks"))
    }

    private fun layout(ctx: WebpContext): ConformanceFinding {
        if (!ctx.headerOk) return ctx.skip("rfc9649.chunk.layout", NOT_CHECKED, "RIFF/WEBP 헤더 아님 / not a RIFF/WEBP header")
        val problems = ctx.top.problems + ctx.frameProblems
        return ctx.finding("rfc9649.chunk.layout", problems.map { it.first }, problems.map { it.second },
            listOf("청크 ${ctx.allChunks.size}개(ANMF 내부 포함), 경계·패딩 정상 / ${ctx.allChunks.size} chunks (incl. ANMF frame data) inside their parents, pad bytes zero"))
    }

    private fun fileLayout(ctx: WebpContext): ConformanceFinding {
        val first = ctx.chunks.firstOrNull() ?: return ctx.finding("rfc9649.layout", listOf("청크 없음 / no chunk"), emptyList(), emptyList())
        val names = ctx.chunks.joinToString(", ") { it.fourcc.trim() }
        val bad = ArrayList<String>()
        when (first.fourcc) {
            "VP8 ", "VP8L" -> if (ctx.chunks.size > 1)
                bad += "단순 형식(${first.fourcc.trim()})인데 청크가 더 있음 / simple format (${first.fourcc.trim()}) followed by more chunks: $names"
            "VP8X" -> {
                val x = ctx.vp8x
                val top = ctx.chunks.filter { it.fourcc in BITSTREAMS }
                val anmf = ctx.chunks.filter { it.fourcc == "ANMF" }
                if (x != null && x.animation) {
                    if (anmf.isEmpty()) bad += "애니메이션 플래그인데 ANMF 없음 / Animation flag set but no ANMF chunk"
                    (top + ctx.chunks.filter { it.fourcc == "ALPH" }).forEach { bad += "애니메이션 파일의 최상위 ${it.label} / top-level ${it.label} in an animated file" }
                } else {
                    if (top.size != 1) bad += "정지 영상 비트스트림 청크 ${top.size}개(1개여야 함) / still image has ${top.size} bitstream chunks (exactly one required)"
                    if (ctx.chunks.count { it.fourcc == "ALPH" } > 1) bad += "ALPH 2개 이상 / more than one ALPH"
                }
            }
            else -> bad += "첫 청크 ${first.label}: 'VP8 ', 'VP8L', 'VP8X' 중 하나여야 함 / first chunk must be 'VP8 ', 'VP8L' or 'VP8X'"
        }
        return ctx.finding("rfc9649.layout", bad, if (bad.isEmpty()) emptyList() else listOf(12L until minOf(ctx.riffEnd, ctx.length)), listOf(
            when (first.fourcc) { "VP8X" -> "확장 형식 / extended format: $names"; else -> "단순 형식 / simple format: $names" }))
    }

    private fun order(ctx: WebpContext): ConformanceFinding {
        if (ctx.chunks.firstOrNull()?.fourcc != "VP8X") return ctx.skip("rfc9649.order", NOT_APPLICABLE, "확장 형식 아님 / not the extended format")
        val bad = ArrayList<String>()
        val ranges = ArrayList<LongRange>()
        var rank = 0
        var bitstreamSeen = false
        for (c in ctx.chunks) {
            val r = RANK[c.fourcc] ?: continue
            if (c.fourcc == "VP8X" && c != ctx.chunks.first()) { bad += "${c.label}: 두 번째 VP8X / second VP8X"; ranges += ctx.range(c) }
            if (r < rank) { bad += "${c.label}: 이미지 데이터 또는 상위 청크 뒤 / after image data or a later reconstruction chunk"; ranges += ctx.range(c) }
            if (c.fourcc == "ALPH" && bitstreamSeen) { bad += "${c.label}: 비트스트림 뒤의 ALPH / ALPH after the bitstream chunk"; ranges += ctx.range(c) }
            if (c.fourcc in BITSTREAMS) bitstreamSeen = true
            rank = maxOf(rank, r)
        }
        return ctx.finding("rfc9649.order", bad, ranges, listOf("VP8X → ICCP → ANIM → 이미지 데이터 순서 / VP8X, ICCP, ANIM, image data in order"))
    }

    private fun vp8x(ctx: WebpContext): ConformanceFinding {
        val c = ctx.chunks.firstOrNull()?.takeIf { it.fourcc == "VP8X" } ?: return ctx.skip("rfc9649.vp8x", NOT_APPLICABLE, "VP8X 없음 / no VP8X chunk")
        if (c.size < 10) return ctx.finding("rfc9649.vp8x", listOf("${c.label}: 크기 ${c.size} < 10 / size ${c.size} < 10"), listOf(ctx.range(c)), emptyList())
        val x = ctx.vp8x!!
        val bad = ArrayList<String>()
        if (x.flagsByte and 0xC1 != 0) bad += "예약 비트(Rsv, R) ≠ 0: 플래그 바이트 0x%02X / reserved bits set in flags byte 0x%02X".format(x.flagsByte, x.flagsByte)
        if (x.reserved24 != 0) bad += "예약 24비트 ≠ 0 / reserved 24 bits = 0x%06X".format(x.reserved24)
        if (x.canvasWidth * x.canvasHeight > 0xFFFF_FFFFL) bad += "캔버스 ${x.canvasWidth}x${x.canvasHeight} > 2^32-1 픽셀 / canvas exceeds 2^32-1 pixels"
        return ctx.finding("rfc9649.vp8x", bad.map { "${c.label}: $it" }, if (bad.isEmpty()) emptyList() else listOf(ctx.range(c)),
            listOf("캔버스 / canvas ${x.canvasWidth}x${x.canvasHeight}, 플래그 / flags 0x%02X".format(x.flagsByte)))
    }

    private fun flags(ctx: WebpContext): ConformanceFinding {
        val x = ctx.vp8x ?: return ctx.skip("rfc9649.vp8x.flags", NOT_APPLICABLE, "VP8X 없음 / no VP8X chunk")
        val bad = ArrayList<String>()
        fun pair(flag: Boolean, name: String, fourcc: String) {
            val present = ctx.of(fourcc).isNotEmpty()
            if (flag && !present) bad += "$name 플래그인데 '${fourcc.trim()}' 청크 없음 / $name flag set but no '${fourcc.trim()}' chunk"
            if (!flag && present) bad += "'${fourcc.trim()}' 청크가 있는데 $name 플래그 없음 / '${fourcc.trim()}' chunk present but the $name flag is not set"
        }
        pair(x.icc, "ICC profile (I)", "ICCP")
        pair(x.exif, "Exif metadata (E)", "EXIF")
        pair(x.xmp, "XMP metadata (X)", "XMP ")
        if (x.animation && ctx.of("ANIM").isEmpty()) bad += "Animation (A) 플래그인데 ANIM 없음 / Animation flag set but no ANIM chunk"
        val hasAlph = ctx.allChunks.any { it.fourcc == "ALPH" }
        val hasVp8l = ctx.allChunks.any { it.fourcc == "VP8L" }
        if (hasAlph && !x.alpha) bad += "ALPH 청크가 있는데 Alpha (L) 플래그 없음 / ALPH chunk present but the Alpha (L) flag is not set"
        if (x.alpha && !hasAlph && !hasVp8l) bad += "Alpha (L) 플래그인데 투명도 정보가 있는 프레임 없음(ALPH·VP8L 없음) / Alpha flag set but no frame carries transparency (no ALPH, no VP8L)"
        return ctx.finding("rfc9649.vp8x.flags", bad, if (bad.isEmpty()) emptyList() else listOf(ctx.range(ctx.chunks.first())),
            listOf("I=${x.icc.b} L=${x.alpha.b} E=${x.exif.b} X=${x.xmp.b} A=${x.animation.b}, 청크와 일치 / consistent with the chunks present"))
    }

    private val Boolean.b get() = if (this) 1 else 0

    private fun metadataCount(ctx: WebpContext): ConformanceFinding {
        val bad = listOf("ICCP", "EXIF", "XMP ").mapNotNull { f -> ctx.of(f).size.takeIf { it > 1 }?.let { "'${f.trim()}' ${it}개 / $it '${f.trim()}' chunks" } }
        return ctx.finding("rfc9649.metadata.count", bad, ctx.chunks.filter { it.fourcc in setOf("ICCP", "EXIF", "XMP ") }.map { ctx.range(it) }.takeIf { bad.isNotEmpty() } ?: emptyList(),
            listOf("ICCP ${ctx.of("ICCP").size}, EXIF ${ctx.of("EXIF").size}, XMP ${ctx.of("XMP ").size} (각 1개 이하 / at most one each)"))
    }
}
