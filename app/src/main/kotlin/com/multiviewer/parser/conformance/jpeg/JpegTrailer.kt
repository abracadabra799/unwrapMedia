package com.multiviewer.parser.conformance.jpeg

import com.multiviewer.parser.SefIntegrityAnalyzer
import com.multiviewer.parser.SefIntegritySeverity
import com.multiviewer.parser.conformance.ConformanceFinding
import com.multiviewer.parser.conformance.ConformanceStatus.NOT_CHECKED
import com.multiviewer.parser.conformance.jpeg.JpegContext.Companion.hex
import com.multiviewer.parser.motionphoto.walkBoxes

/** A byte range after the primary EOI and what explains it ([known] false = unexplained). */
data class TrailerRegion(val start: Long, val end: Long, val label: String, val known: Boolean)

/**
 * T.81 defines nothing after EOI. Data there is PASS when every byte is explained by a known structure (MPF secondary
 * images that resolved to SOI..EOI, an ISO-BMFF video = Google Motion Photo, a Samsung SEF trailer ending in "SEFT"),
 * WARN otherwise.
 */
internal object JpegTrailer {
    private const val FTYP_SEARCH = 4L * 1024 * 1024

    fun regions(ctx: JpegContext, mpfImages: List<LongRange>): List<TrailerRegion> {
        val len = ctx.length
        val out = ArrayList<TrailerRegion>()
        var pos = ctx.stream.end
        var guard = 0
        while (pos < len && guard++ < 10_000) {
            val image = mpfImages.firstOrNull { it.first == pos }
            if (image != null) {
                out += TrailerRegion(pos, image.last + 1, "MPF 개별 이미지 / MPF individual image", true); pos = image.last + 1; continue
            }
            val video = videoEnd(ctx, pos)
            if (video != null) {
                out += TrailerRegion(pos, video, "ISO-BMFF 동영상 (모션 포토) / ISO-BMFF video (motion photo)", true); pos = video; continue
            }
            if (sefExplains(ctx, pos)) {
                out += TrailerRegion(pos, len, "Samsung SEF 트레일러 / Samsung SEF trailer", true); pos = len; continue
            }
            val next = nextKnownStart(ctx, pos, mpfImages) ?: len
            out += TrailerRegion(pos, next, "알 수 없는 데이터 / unexplained data", false)
            pos = next
        }
        return out
    }

    private fun videoEnd(ctx: JpegContext, pos: Long): Long? {
        if (pos + 8 > ctx.length || ctx.reader.readFourCC(pos + 4) != "ftyp") return null
        val boxes = walkBoxes(ctx.reader, pos, ctx.length).takeWhile { !it.truncated }
        return boxes.lastOrNull()?.end?.takeIf { it > pos }
    }

    private fun sefExplains(ctx: JpegContext, pos: Long): Boolean {
        val len = ctx.length
        if (len - pos < 12 || ctx.reader.readFourCC(len - 4) != "SEFT") return false
        val report = try { SefIntegrityAnalyzer.analyze(ctx.reader, pos, 0, len - pos, len) } catch (e: java.util.concurrent.CancellationException) { throw e } catch (_: Exception) { return false }
        return report.overallSeverity != SefIntegritySeverity.CRITICAL
    }

    /** The next MPF image start or ISO-BMFF `ftyp` box after [pos] (searched within 4 MiB), or null. */
    private fun nextKnownStart(ctx: JpegContext, pos: Long, mpfImages: List<LongRange>): Long? {
        val image = mpfImages.map { it.first }.filter { it > pos }.minOrNull()
        val limit = minOf(ctx.length, pos + FTYP_SEARCH, image ?: Long.MAX_VALUE)
        var at = pos + 1
        while (at + 8 <= limit) {
            val n = minOf(64L * 1024, limit - at).toInt()
            val buf = ctx.reader.readBytes(at, n)
            for (i in 0..n - 8) {
                if (buf[i + 4] == 'f'.code.toByte() && buf[i + 5] == 't'.code.toByte() && buf[i + 6] == 'y'.code.toByte() && buf[i + 7] == 'p'.code.toByte()) {
                    val size = ((buf[i].toLong() and 0xFF) shl 24) or ((buf[i + 1].toLong() and 0xFF) shl 16) or ((buf[i + 2].toLong() and 0xFF) shl 8) or (buf[i + 3].toLong() and 0xFF)
                    if (size in 16..4096) return at + i
                }
            }
            if (n < 8) break
            at += n - 7
        }
        return image
    }

    fun evaluate(ctx: JpegContext, mpfImages: List<LongRange>): ConformanceFinding {
        val id = "t81.trailing"
        val eoi = ctx.stream.eoi ?: return ctx.skip(id, NOT_CHECKED, "EOI 없음 — 판단 불가 / no EOI")
        if (ctx.stream.end >= ctx.length) return ctx.finding(id, emptyList(), emptyList(), listOf("EOI @${hex(eoi.offset)} 뒤 데이터 없음 / nothing after EOI"))
        val regions = regions(ctx, mpfImages)
        val lines = regions.map { "${it.label}: ${hex(it.start)}..${hex(it.end)} (${it.end - it.start}바이트)" }
        val unknown = regions.filter { !it.known }
        val note = "T.81은 EOI 이후 데이터를 정의하지 않음 / T.81 defines no data after EOI"
        return if (unknown.isEmpty()) ctx.finding(id, emptyList(), emptyList(), listOf("$note — 모두 알려진 구조로 설명됨 / all explained by known structures") + lines)
        else ctx.finding(id, lines.filterIndexed { i, _ -> !regions[i].known } +
            ("$note — 설명된 영역 / explained: " + lines.filterIndexed { i, _ -> regions[i].known }.joinToString("; ").ifEmpty { "없음 / none" }),
            unknown.map { it.start until it.end }, emptyList())
    }
}
