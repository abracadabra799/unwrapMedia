package com.multiviewer.parser.conformance.png

import com.multiviewer.parser.ByteReader
import com.multiviewer.parser.conformance.ConformanceFinding
import com.multiviewer.parser.conformance.ConformanceReport
import com.multiviewer.parser.conformance.ConformanceStatus.NOT_CHECKED
import com.multiviewer.parser.conformance.ImageRuleBook
import com.multiviewer.parser.conformance.trailingDataFinding
import java.util.concurrent.CancellationException

object PngConformanceChecker {
    const val PROFILE = "W3C PNG Third Edition (2025) + APNG + RFC 1950 zlib headers phase 1"

    val LIMITATIONS = listOf(
        "zlib/deflate 데이터는 해제하지 않습니다(CMF·FLG만 검사) — 필터 바이트, 압축 해제 길이, Adler-32, 팔레트 인덱스 범위, 압축 텍스트·ICC 내용은 디코딩 검증(ffmpeg) 몫이며 여기서는 PASS로 보지 않습니다 / " +
            "zlib/deflate data is not inflated (CMF/FLG only): filter bytes, decompressed length, Adler-32, palette index range and compressed text / ICC contents are left to the ffmpeg decode check and never counted as PASS here",
        "BCP 47 언어 태그, ICC 프로필·eXIf(Exif) 내용, 등록 확장 청크(oFFs, pCAL, sCAL 등)와 비공개 청크 내용, hIST 0 항목과 실제 픽셀 사용은 검사하지 않습니다 / " +
            "Not checked: BCP 47 language tags, ICC profile and eXIf (Exif) contents, registered extension chunks (oFFs, pCAL, sCAL …) and private chunk contents, hIST zero entries vs actual pixel use",
        "청크 ${PngChunkScanner.MAX_CHUNKS}개, 텍스트·sPLT 1 MiB까지 읽습니다 / Reads at most ${PngChunkScanner.MAX_CHUNKS} chunks and 1 MiB per text / sPLT chunk",
    )

    /** Rules whose PASS needs every chunk up to IEND. */
    private val CHUNK_RULES = PngRules.all.map { it.id }.toSet() - setOf("png3.signature", "png3.chunk.layout", "png3.ihdr", "png3.critical", "png3.trailing")

    private const val TRAILING_NOTE = "PNG-3 §15.3.1: IEND 뒤에는 어떤 청크나 데이터도 없어야 함 / no chunks or other content follow the IEND chunk"

    fun check(reader: ByteReader): ConformanceReport {
        val findings = ArrayList<ConformanceFinding>()
        val error = try {
            val ctx = PngContext(reader, PngChunkScanner.scan(reader))
            val rules = PngStructureRules.evaluate(ctx) + PngAncillaryRules.evaluate(ctx) + PngApngRules.evaluate(ctx)
            findings += if (ctx.scan.problem == null && ctx.scan.iend != null) rules
            else PngRules.book.partial(rules, CHUNK_RULES, "청크 열거가 IEND 전에 멈춤 — 읽은 청크만 검사됨 / chunk walk stopped before IEND: only the chunks read were checked")
            findings += if (ctx.scan.iend == null) ctx.skip("png3.trailing", NOT_CHECKED, "IEND 없음 — 판단 불가 / no IEND")
            else trailingDataFinding(PngRules.book, "png3.trailing", reader, ctx.scan.end, TRAILING_NOTE)
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ImageRuleBook.inspectionError(e)
        }
        return ConformanceReport("PNG", PROFILE, PngRules.book.sorted(findings), LIMITATIONS, error)
    }
}
