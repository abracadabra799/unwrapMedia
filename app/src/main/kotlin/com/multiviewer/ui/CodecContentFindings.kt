package com.multiviewer.ui

import com.multiviewer.parser.conformance.ConformanceStatus
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
        verifiedFacts = finding.details + (
            if (finding.rule.citation?.verified == true) "Checked against the cited spec text / 인용한 규격 원문 대조 완료"
            else "Structural observation; exact normative clause not verified / 구조 관측값·규격 조항 미확인"),
        citation = finding.rule.citation,
        sourceNote = conformanceSourceNote(finding.rule),
    )
}
