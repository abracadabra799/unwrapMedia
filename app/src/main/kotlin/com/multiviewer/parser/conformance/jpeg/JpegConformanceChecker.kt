package com.multiviewer.parser.conformance.jpeg

import com.multiviewer.parser.ByteReader
import com.multiviewer.parser.conformance.ConformanceFinding
import com.multiviewer.parser.conformance.ConformanceReport
import java.util.concurrent.CancellationException

object JpegConformanceChecker {
    const val PROFILE = "ITU-T T.81 + T.871 (JFIF) + CIPA DC-008-2026 (Exif 3.1) + DC-007-2026 (MPF) phase 1"

    val LIMITATIONS = listOf(
        "엔트로피 부호화 데이터는 해독하지 않습니다(RSTm 마커만 셉니다) / Entropy-coded data is not decoded (only RSTm markers are counted)",
        "계층(hierarchical) 모드, XMP, ICC, MakerNote 내부, 비압축 Exif 썸네일, MPF 2번째 이후 이미지의 내부는 1단계에서 검사하지 않습니다 / " +
            "Hierarchical mode, XMP, ICC, MakerNote contents, uncompressed Exif thumbnails and the inside of MPF secondary images are not checked in phase 1",
        "Exif·MPF 규칙은 첫(주) 이미지의 APP1/APP2만 검사합니다 / Exif and MPF rules check the first (primary) image's APP1/APP2 only",
    )

    private val ORDER = JpegRules.all.withIndex().associate { it.value.id to it.index }

    fun check(reader: ByteReader): ConformanceReport {
        val findings = ArrayList<ConformanceFinding>()
        val error = try {
            val ctx = JpegContext(reader, JpegStreamScanner.scan(reader))
            findings += JpegT81Rules.evaluate(ctx)
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            "검사 중 오류: ${e.javaClass.simpleName}: ${e.message ?: ""}".trim()
        }
        return ConformanceReport(
            format = "JPEG",
            profile = PROFILE,
            findings = findings.sortedBy { ORDER[it.rule.id] ?: Int.MAX_VALUE },
            limitations = LIMITATIONS,
            inspectionError = error,
        )
    }
}
