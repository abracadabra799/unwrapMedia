package com.multiviewer.parser.conformance.webp

import com.multiviewer.parser.ByteReader
import com.multiviewer.parser.conformance.ConformanceFinding
import com.multiviewer.parser.conformance.ConformanceReport
import com.multiviewer.parser.conformance.ConformanceStatus.NOT_CHECKED
import com.multiviewer.parser.conformance.ImageRuleBook
import com.multiviewer.parser.conformance.trailingDataFinding
import java.util.concurrent.CancellationException

object WebpConformanceChecker {
    const val PROFILE = "RFC 9649 (WebP container, VP8L header) + RFC 6386 §9.1 (VP8 frame tag) phase 1"

    val LIMITATIONS = listOf(
        "VP8·VP8L 엔트로피 데이터와 ALPH 무손실 스트림은 해독하지 않습니다(헤더만) — 픽셀 디코딩은 디코딩 검증(ffmpeg) 몫이며 여기서는 PASS로 보지 않습니다 / " +
            "VP8 / VP8L entropy-coded data and ALPH lossless streams are not decoded (headers only); pixel decoding is left to the ffmpeg decode check and never counted as PASS here",
        "ICCP·EXIF·XMP 내용, 알 수 없는 청크 내용, VP8 show_frame·스케일 비트, ALPH 전처리 비트는 검사하지 않습니다 / " +
            "Not checked: ICCP, EXIF and XMP contents, unknown chunk contents, VP8 show_frame and scaling bits, ALPH preprocessing bits",
    )

    /** Rules whose PASS needs every chunk of the RIFF payload. */
    private val CHUNK_RULES = WebpRules.all.map { it.id }.toSet() - setOf("rfc9649.header", "rfc9649.riff.size", "rfc9649.chunk.layout", "rfc9649.trailing")

    private const val TRAILING_NOTE = "RFC 9649 §2.4: File Size 뒤에 데이터가 없어야 함(SHOULD NOT) / the file SHOULD NOT contain data after the data specified by File Size"

    fun check(reader: ByteReader): ConformanceReport {
        val findings = ArrayList<ConformanceFinding>()
        val error = try {
            val ctx = WebpContext(reader)
            val rules = WebpContainerRules.evaluate(ctx)
            val truncated = ctx.riffEnd > ctx.length || ctx.chunks.any { !it.complete } || ctx.top.capped
            findings += if (!truncated) rules
            else WebpRules.book.partial(rules, CHUNK_RULES, "RIFF 데이터가 잘림 — 읽은 청크만 검사됨 / RIFF data truncated: only the chunks read were checked")
            findings += if (!ctx.headerOk) ctx.skip("rfc9649.trailing", NOT_CHECKED, "RIFF/WEBP 헤더 아님 / not a RIFF/WEBP header")
            else trailingDataFinding(WebpRules.book, "rfc9649.trailing", reader, ctx.riffEnd, TRAILING_NOTE)
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ImageRuleBook.inspectionError(e)
        }
        return ConformanceReport("WebP", PROFILE, WebpRules.book.sorted(findings), LIMITATIONS, error)
    }
}
