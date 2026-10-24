package com.multiviewer.ui

import com.multiviewer.parser.conformance.ConformanceRule
import com.multiviewer.parser.conformance.ConformanceStatus
import com.multiviewer.parser.conformance.DEFINITIONAL_SUFFIX
import com.multiviewer.parser.conformance.CodecBitstreamReport
import com.multiviewer.parser.integrity.CheckStatus

/** Codec byte observations stay outside normative conformance counts. */
fun codecBitstreamFindings(report: CodecBitstreamReport): List<ContentFinding> = report.findings.map { finding ->
    val range = finding.ranges.firstOrNull()
    ContentFinding(
        id = finding.rule.id,
        stage = FindingStage.CODEC,
        status = when (finding.status) {
            ConformanceStatus.FAIL -> CheckStatus.FAIL
            ConformanceStatus.WARN -> CheckStatus.WARN
            ConformanceStatus.NOT_CHECKED, ConformanceStatus.NOT_APPLICABLE -> CheckStatus.SKIP
            ConformanceStatus.PASS -> CheckStatus.INFO
        },
        title = finding.rule.title,
        location = null,
        offset = range?.first,
        length = range?.let { it.last - it.first + 1 },
        summary = finding.details.firstOrNull().orEmpty(),
        verifiedFacts = finding.details + codecProvenanceFact(finding.rule),
        citation = finding.rule.citation,
        sourceNote = conformanceSourceNote(finding.rule),
    )
}

/** How a codec finding relates to its cited text: verbatim requirement, derived from a definition, or unverified. */
internal fun codecProvenanceFact(rule: ConformanceRule): String {
    val citation = rule.citation
    return when {
        citation == null || !citation.verified ->
            "Structural observation; exact normative clause not verified / 구조 관측값·규격 조항 미확인"
        citation.section.endsWith(DEFINITIONAL_SUFFIX) ->
            "Derived from the cited definition; requirement not stated verbatim / 인용 정의에서 도출한 검사(원문에 규칙 문장 없음)"
        else -> "Checked against the cited spec text / 인용한 규격 원문 대조 완료"
    }
}
