package com.multiviewer.parser.integrity

enum class CheckStatus { PASS, INFO, WARN, FAIL, SKIP }

data class IntegrityCheckItem(
    val id: String,
    val title: String,
    val status: CheckStatus,
    val detail: String,
    val offset: Long? = null,
    val length: Long? = null,
)

data class ImageStructureReport(
    val format: String,
    val items: List<IntegrityCheckItem>,
    val declaredWidth: Int? = null,
    val declaredHeight: Int? = null,
) {
    /** Worst non-SKIP status; INFO counts as PASS (recognized, legitimate extra data). */
    val overall: CheckStatus
        get() = when {
            items.any { it.status == CheckStatus.FAIL } -> CheckStatus.FAIL
            items.any { it.status == CheckStatus.WARN } -> CheckStatus.WARN
            else -> CheckStatus.PASS
        }
}

/** A per-format checker's result: its items plus the image size its headers declare (null when unknown). */
data class FormatCheckResult(
    val items: List<IntegrityCheckItem>,
    val declaredWidth: Int? = null,
    val declaredHeight: Int? = null,
)
