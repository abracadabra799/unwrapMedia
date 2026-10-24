package com.multiviewer.ui

import com.multiviewer.parser.conformance.SpecCitation
import com.multiviewer.parser.integrity.CheckStatus

enum class FindingStage {
    STRUCTURE,
    CODEC,
    PARSER,
    IMAGE_DECODE,
    MOTION_PHOTO,
    VIDEO_DECODE,
    VIDEO_PACKETS,
    AUDIO_STRUCTURE,
    AUDIO_DECODE,
    GAINMAP,
    CONFORMANCE,
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
    /** Spec text the finding comes from (copied from the rule); null for uncited or non-spec findings. */
    val citation: SpecCitation? = null,
    /**
     * Plain (never a link) source label for findings without a citation, set by the mappers: app structural
     * observation, Samsung SEF, decoder result, or a spec whose link is still pending.
     */
    val sourceNote: String? = null,
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

enum class ContentFindingFilter { ALL, ISSUES, UNVERIFIED }

fun orderedContentFindings(findings: List<ContentFinding>, showAll: Boolean): List<ContentFinding> =
    filteredContentFindings(findings, if (showAll) ContentFindingFilter.ALL else ContentFindingFilter.ISSUES)

fun filteredContentFindings(findings: List<ContentFinding>, filter: ContentFindingFilter): List<ContentFinding> =
    findings.withIndex()
        .filter { (_, finding) -> when (filter) {
            ContentFindingFilter.ALL -> true
            ContentFindingFilter.ISSUES -> finding.status == CheckStatus.FAIL || finding.status == CheckStatus.WARN
            ContentFindingFilter.UNVERIFIED -> finding.status == CheckStatus.SKIP
        } }
        .sortedWith(compareBy<IndexedValue<ContentFinding>>(
            { statusOrder(it.value.status) },
            { it.value.offset ?: Long.MAX_VALUE },
            { it.index },
        ))
        .map { it.value }

fun contentFindingLocationLabel(finding: ContentFinding, ko: Boolean): String {
    val offset = finding.offset
    if (offset != null) {
        val range = finding.length?.takeIf { it > 0 }?.let { " (+$it B)" }.orEmpty()
        val bytes = "0x${offset.toString(16).uppercase()}$range"
        // Conformance rows are status-sorted, so keep their rule group (e.g. "Sample tables") visible next to the bytes.
        val group = if (finding.stage == FindingStage.CONFORMANCE) finding.location?.trim()?.let { cleanLocationLabel(it, ko) } else null
        return if (group.isNullOrEmpty()) bytes else "$group · $bytes"
    }
    val loc = finding.location?.trim()
    if (!loc.isNullOrEmpty()) {
        val cleaned = cleanLocationLabel(loc, ko)
        if (cleaned.isNotEmpty()) return cleaned
    }
    return if (finding.stage == FindingStage.CONFORMANCE) "-"
        else if (ko) "위치 확인 불가" else "Location unavailable"
}

internal fun cleanLocationLabel(loc: String, ko: Boolean): String {
    if (loc.contains("기존 바이트") || loc.contains("Existing byte") ||
        loc.contains("codec syntax") || loc.contains("구조 검사 범위") ||
        loc.contains("추가 바이트") || loc.contains("not validated") ||
        loc.contains("fit in the file")) {
        return ""
    }
    val beforeSemi = loc.substringBefore(';').trim()
    if (beforeSemi.contains('/')) {
        val parts = beforeSemi.split('/').map { it.trim() }
        val chosen = if (ko) parts.firstOrNull() else parts.getOrNull(1) ?: parts.firstOrNull()
        if (chosen != null && chosen.length <= 25) return chosen
    }
    return if (beforeSemi.length <= 25) beforeSemi else ""
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
