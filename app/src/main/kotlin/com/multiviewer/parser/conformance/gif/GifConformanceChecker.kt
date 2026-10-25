package com.multiviewer.parser.conformance.gif

import com.multiviewer.parser.ByteReader
import com.multiviewer.parser.conformance.ConformanceFinding
import com.multiviewer.parser.conformance.ConformanceReport
import com.multiviewer.parser.conformance.ConformanceStatus.NOT_CHECKED
import com.multiviewer.parser.conformance.ImageRuleBook
import com.multiviewer.parser.conformance.trailingDataFinding
import java.util.concurrent.CancellationException

object GifConformanceChecker {
    const val PROFILE = "GIF89a (CompuServe 1990) block structure phase 1"

    val LIMITATIONS = listOf(
        "LZW 데이터는 해독하지 않습니다(첫 코드만 읽음) — End of Information 코드, 색상표 범위 안의 인덱스, 픽셀 수는 디코딩 검증(ffmpeg) 몫이며 여기서는 PASS로 보지 않습니다 / " +
            "LZW data is not decoded (first code only): the End of Information code, indices within the active colour table and the pixel count are left to the ffmpeg decode check and never counted as PASS here",
        "NETSCAPE2.0 등 응용 확장 내용, 주석의 7비트 ASCII 권고, 평문 확장 격자, 정의되지 않은 확장 레이블의 내용은 검사하지 않습니다 / " +
            "Not checked: Application Extension contents (e.g. NETSCAPE2.0), the 7-bit ASCII comment recommendation, Plain Text grids, contents of undefined extension labels",
    )

    private const val TRAILING_NOTE = "GIF89a 부록 C: 모든 블록은 Trailer 앞에 있어야 함 / Appendix C: all control and data blocks must precede the Trailer"

    fun check(reader: ByteReader): ConformanceReport {
        val findings = ArrayList<ConformanceFinding>()
        val error = try {
            val ctx = GifContext(reader)
            findings += GifConformanceRules.evaluate(ctx)
            val t = ctx.scan.trailer
            findings += if (t == null) ctx.book.skip("gif89a.trailing", NOT_CHECKED, "Trailer 없음 — 판단 불가 / no Trailer")
            else trailingDataFinding(GifRules.book, "gif89a.trailing", reader, t.end, TRAILING_NOTE)
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ImageRuleBook.inspectionError(e)
        }
        return ConformanceReport("GIF", PROFILE, GifRules.book.sorted(findings), LIMITATIONS, error)
    }
}
