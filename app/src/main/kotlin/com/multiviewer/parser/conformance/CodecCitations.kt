package com.multiviewer.parser.conformance

/** Marks a citation whose quoted sentence is the closest definitional text rather than a rule stated verbatim. */
internal const val DEFINITIONAL_SUFFIX = " (정의 문장 / definitional)"

/**
 * A rule whose clause text (and page, for PDFs) was copied from the original document. [section] becomes the
 * citation section; [url] carries `#page=N` for PDFs or an `#anchor` for HTML.
 */
internal fun citedRule(
    id: String, title: String, scope: String,
    document: String, section: String, page: Int?, url: String,
): ConformanceRule = ConformanceRule(id, title, document, url, scope, SpecCitation(document, section, page, url, true))

/** A rule from a paid ISO standard that was not checked against the original: store page only, never a page. */
internal fun paidRule(
    id: String, title: String, scope: String,
    document: String, section: String, url: String,
): ConformanceRule = ConformanceRule(
    id, title, "$document (공개 자료 기반, 원문 미대조)", url, scope,
    SpecCitation(document, section, null, url, false),
)

internal const val ISO_14496_15_DOCUMENT = "ISO/IEC 14496-15:2024"
/** Current edition (Edition 7, 2024-10). */
internal const val ISO_14496_15_URL = "https://www.iso.org/standard/89118.html"
