package com.multiviewer.parser.conformance

enum class ConformanceStatus { PASS, FAIL, WARN, NOT_CHECKED, NOT_APPLICABLE }

data class ConformanceRule(
    val id: String,
    val title: String,
    val source: String,
    val reference: String?,
    val scope: String,
    /** Where in the spec text this rule comes from; null for uncited / app-only structural rules. */
    val citation: SpecCitation? = null,
)

/**
 * A pointer to the spec text a rule comes from. [page] and anchors in [url] are only set when checked against the
 * original; [verified] is true only when the clause text (and page) was checked against the original.
 */
data class SpecCitation(
    val document: String,
    val section: String,
    val page: Int?,
    val url: String,
    val verified: Boolean,
)

/** "{document} {section}[, p. N][ (원문 미대조 / not checked against the original)]". */
fun SpecCitation.label(): String =
    "$document $section" + (page?.let { ", p. $it" } ?: "") +
        (if (verified) "" else " (원문 미대조 / not checked against the original)")

data class ConformanceFinding(
    val rule: ConformanceRule,
    val status: ConformanceStatus,
    val details: List<String>,
    val ranges: List<LongRange>,
)

/** Reports only implemented checks, not complete normative conformance. */
data class ConformanceReport(
    val format: String,
    val profile: String,
    val findings: List<ConformanceFinding>,
    // Callers supplying their own limitations must also disclose unsupported codec syntax.
    val limitations: List<String> = listOf(
        "코덱 비트스트림 구문 전체는 검사하지 않습니다 / Codec bitstream syntax is not fully checked",
    ),
    val inspectionError: String? = null,
) {
    val overall: ConformanceStatus
        get() = when {
            findings.any { it.status == ConformanceStatus.FAIL } -> ConformanceStatus.FAIL
            findings.any { it.status == ConformanceStatus.WARN } -> ConformanceStatus.WARN
            inspectionError != null -> ConformanceStatus.NOT_CHECKED
            findings.any { it.status == ConformanceStatus.NOT_CHECKED } -> ConformanceStatus.NOT_CHECKED
            findings.any { it.status == ConformanceStatus.PASS } -> ConformanceStatus.PASS
            else -> ConformanceStatus.NOT_CHECKED
        }
}
