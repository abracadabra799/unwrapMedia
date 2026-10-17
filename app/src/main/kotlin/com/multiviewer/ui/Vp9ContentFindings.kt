package com.multiviewer.ui

import com.multiviewer.parser.conformance.ConformanceStatus
import com.multiviewer.parser.conformance.Vp9BitstreamReport
import com.multiviewer.parser.integrity.CheckStatus

/** VP9 byte observations stay outside normative conformance counts. */
fun vp9BitstreamFindings(report: Vp9BitstreamReport): List<ContentFinding> = report.findings.map { finding ->
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
        verifiedFacts = finding.details + "Structural observation; exact normative clause not verified / 구조 관측값·규격 조항 미확인",
    )
}
