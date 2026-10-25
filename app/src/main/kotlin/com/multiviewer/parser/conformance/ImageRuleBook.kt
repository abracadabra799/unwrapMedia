package com.multiviewer.parser.conformance

import com.multiviewer.parser.ByteReader
import com.multiviewer.parser.SefIntegrityAnalyzer
import com.multiviewer.parser.SefIntegritySeverity
import java.util.concurrent.CancellationException

/**
 * Shared catalog + finding builder for the PNG / WebP / GIF conformance checkers (same status mapping as the JPEG
 * checker): a violated rule FAILs unless its id is in [warnRules]; no violation → PASS with the evaluated facts.
 */
internal class ImageRuleBook(val all: List<ConformanceRule>, val warnRules: Set<String>) {
    private val byId = all.associateBy { it.id }
    private val order = all.withIndex().associate { it.value.id to it.index }

    fun rule(id: String): ConformanceRule = byId.getValue(id)

    fun finding(id: String, violations: List<String>, ranges: List<LongRange>, pass: List<String>): ConformanceFinding {
        val status = when {
            violations.isEmpty() -> ConformanceStatus.PASS
            id in warnRules -> ConformanceStatus.WARN
            else -> ConformanceStatus.FAIL
        }
        return ConformanceFinding(rule(id), status, if (violations.isEmpty()) pass else capped(violations), ranges.take(MAX_LISTED))
    }

    fun skip(id: String, status: ConformanceStatus, reason: String): ConformanceFinding =
        ConformanceFinding(rule(id), status, listOf(reason), emptyList())

    /**
     * The walk stopped early (truncation / syntax error): a PASS or NOT_APPLICABLE of a rule in [dependent] only covers
     * the part that was read, so it becomes NOT_CHECKED with [reason] (never a false PASS). FAIL / WARN stay.
     */
    fun partial(findings: List<ConformanceFinding>, dependent: Set<String>, reason: String): List<ConformanceFinding> = findings.map {
        if (it.rule.id in dependent && (it.status == ConformanceStatus.PASS || it.status == ConformanceStatus.NOT_APPLICABLE))
            it.copy(status = ConformanceStatus.NOT_CHECKED, details = listOf(reason) + it.details, ranges = emptyList())
        else it
    }

    /** Findings in catalog order. */
    fun sorted(findings: List<ConformanceFinding>): List<ConformanceFinding> = findings.sortedBy { order[it.rule.id] ?: Int.MAX_VALUE }

    companion object {
        const val MAX_LISTED = 50

        fun capped(lines: List<String>): List<String> =
            if (lines.size <= MAX_LISTED) lines else lines.take(MAX_LISTED) + "… 외 ${lines.size - MAX_LISTED}건 / ${lines.size - MAX_LISTED} more"

        fun hex(offset: Long): String = "0x%X".format(offset)

        /** Rule with a verified citation whose [url] carries an HTML anchor (page null) or no fragment (plain text). */
        fun cited(id: String, ko: String, en: String, scope: String, document: String, section: String, url: String): ConformanceRule =
            citedRule(id, "$ko / $en", scope, document, section, null, url)

        /** "검사 중 오류" text used by every checker for an unexpected exception. */
        fun inspectionError(e: Exception): String = "검사 중 오류 / inspection error: ${e.javaClass.simpleName}: ${e.message ?: ""}".trim()
    }
}

/**
 * Explains bytes after the end of a PNG / WebP / GIF datastream. The only structure recognised there is a Samsung SEF
 * trailer that runs to EOF, ends in "SEFT" and that [SefIntegrityAnalyzer] does not mark CRITICAL (the JPEG checker
 * uses the same test). Returns the finding for [id]: PASS with "nothing after" / "explained", else violations.
 */
internal fun trailingDataFinding(book: ImageRuleBook, id: String, reader: ByteReader, end: Long, note: String): ConformanceFinding {
    val len = reader.length
    if (end >= len) return book.finding(id, emptyList(), emptyList(), listOf("${ImageRuleBook.hex(end)} 뒤 데이터 없음 / nothing after ${ImageRuleBook.hex(end)}"))
    val size = len - end
    val sef = sefTrailerGaps(reader, end)
    val range = "${ImageRuleBook.hex(end)}..${ImageRuleBook.hex(len)} (${size}바이트 / $size bytes)"
    return when {
        sef == "" -> book.finding(id, emptyList(), emptyList(), listOf("$note — Samsung SEF 트레일러로 설명됨 / explained by a Samsung SEF trailer: $range"))
        sef != null -> book.finding(id, listOf("Samsung SEF 트레일러 안의 설명되지 않는 간극 / unexplained gap in the Samsung SEF trailer: $range — $sef", note), listOf(end until len), emptyList())
        else -> book.finding(id, listOf("알 수 없는 데이터 / unexplained data: $range", note), listOf(end until len), emptyList())
    }
}

/**
 * null = [pos]..EOF is not a usable SEF trailer (no SEFT, or CRITICAL); "" = fully explained; otherwise the analyzer's
 * gap/overlap text. Shared with the JPEG trailer check.
 */
internal fun sefTrailerGaps(reader: ByteReader, pos: Long): String? {
    val len = reader.length
    if (len - pos < 12 || reader.readFourCC(len - 4) != "SEFT") return null
    val report = try {
        SefIntegrityAnalyzer.analyze(reader, pos, 0, len - pos, len)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        return null
    }
    if (report.overallSeverity == SefIntegritySeverity.CRITICAL) return null
    // The analyzer only checks gaps when the directory has entries (count 0 would "explain" anything): require
    // >= 1 entry and entries that tile [pos, SEFH) exactly (the analyzer's own gap/overlap text is kept too).
    val count = report.declaredEntryCount ?: return null
    val sefh = len - 8 - (12 + 12 * count)
    val problems = ArrayList<String>()
    val entries = report.directoryEntries.sortedBy { it.computedDataStart }
    if (entries.isEmpty()) {
        problems += "SEF 디렉터리 항목 0개 — 앞 ${sefh - pos}바이트는 설명되지 않음 / SEF directory has no entries: ${sefh - pos} byte(s) in [$pos, $sefh) are not covered"
    } else {
        if (entries.first().computedDataStart != pos) problems += "SEF 첫 항목 앞 ${entries.first().computedDataStart - pos}바이트 설명되지 않음 / ${entries.first().computedDataStart - pos} byte(s) before the first SEF entry [$pos, ${entries.first().computedDataStart})"
        for (i in 1 until entries.size) {
            val prev = entries[i - 1]; val cur = entries[i]
            if (cur.computedDataStart != prev.computedDataEnd) problems += "SEF 항목 #${prev.entryIndex}/#${cur.entryIndex} 사이 간극 또는 겹침 / gap or overlap between SEF entries #${prev.entryIndex} and #${cur.entryIndex}"
        }
        if (entries.last().computedDataEnd != sefh) problems += "SEF 마지막 항목과 SEFH 사이 간극 / gap between the last SEF entry and SEFH [${entries.last().computedDataEnd}, $sefh)"
    }
    if (problems.isEmpty()) return ""
    val analyzer = report.structuralChecks
        .filter { (it.label == "Field block gaps" || it.label == "Field block overlap") && it.severity != SefIntegritySeverity.PASS }
        .map { it.detail }
    return (problems + analyzer).distinct().joinToString("; ")
}
