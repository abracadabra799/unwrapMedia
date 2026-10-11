package com.multiviewer.ui

import com.multiviewer.parser.integrity.CheckStatus

enum class FindingStage {
    STRUCTURE,
    PARSER,
    IMAGE_DECODE,
    MOTION_PHOTO,
    VIDEO_DECODE,
    VIDEO_PACKETS,
    AUDIO_STRUCTURE,
    AUDIO_DECODE,
}

/** UI-only normalized finding. Source reports remain authoritative and unmodified. */
data class ContentFinding(
    val id: String,
    val stage: FindingStage,
    val status: CheckStatus,
    val title: String,
    val location: String?,
    val offset: Long?,
    val length: Long?,
    val summary: String,
    val verifiedFacts: List<String>,
    val hypothesis: String? = null,
    val nextChecks: List<String> = emptyList(),
)

data class ContentFindingSummary(
    val total: Int,
    val failures: Int,
    val warnings: Int,
    val passed: Int,
    val unverified: Int,
)

fun contentFindingSummary(findings: List<ContentFinding>): ContentFindingSummary = ContentFindingSummary(
    total = findings.size,
    failures = findings.count { it.status == CheckStatus.FAIL },
    warnings = findings.count { it.status == CheckStatus.WARN },
    passed = findings.count { it.status == CheckStatus.PASS || it.status == CheckStatus.INFO },
    unverified = findings.count { it.status == CheckStatus.SKIP },
)

fun orderedContentFindings(findings: List<ContentFinding>, showAll: Boolean): List<ContentFinding> =
    findings.withIndex()
        .filter { (_, finding) -> showAll || finding.status == CheckStatus.FAIL || finding.status == CheckStatus.WARN }
        .sortedWith(compareBy<IndexedValue<ContentFinding>>(
            { statusOrder(it.value.status) },
            { it.value.offset ?: Long.MAX_VALUE },
            { it.index },
        ))
        .map { it.value }

fun contentFindingLocationLabel(finding: ContentFinding, ko: Boolean): String {
    finding.location?.let { return it }
    val offset = finding.offset ?: return if (ko) "위치 확인 불가" else "Location unavailable"
    val range = finding.length?.takeIf { it > 0 }?.let { " (+$it B)" }.orEmpty()
    return "0x${offset.toString(16).uppercase()}$range"
}

fun mapIntegrityStatus(status: IntegrityStatus): CheckStatus = when (status) {
    IntegrityStatus.NOT_RUN -> CheckStatus.SKIP
    IntegrityStatus.CLEAN -> CheckStatus.PASS
    IntegrityStatus.ISSUES -> CheckStatus.WARN
    IntegrityStatus.FAILED -> CheckStatus.FAIL
}

fun mapImageDecodeStatus(status: ImageDecodeStatus): CheckStatus = when (status) {
    ImageDecodeStatus.NOT_RUN -> CheckStatus.SKIP
    ImageDecodeStatus.CLEAN -> CheckStatus.PASS
    ImageDecodeStatus.ISSUES -> CheckStatus.WARN
    ImageDecodeStatus.FAILED -> CheckStatus.FAIL
}

private fun statusOrder(status: CheckStatus): Int = when (status) {
    CheckStatus.FAIL -> 0
    CheckStatus.WARN -> 1
    CheckStatus.INFO, CheckStatus.SKIP -> 2
    CheckStatus.PASS -> 3
}
