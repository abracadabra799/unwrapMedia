package com.multiviewer.parser.conformance

import com.multiviewer.parser.integrity.IntegrityCheckItem
import com.multiviewer.parser.integrity.CheckStatus

/** Normalize checker observations without treating missing/conditional findings as passes. */
fun analyzeConformance(format: String, items: List<IntegrityCheckItem>, inspectionError: String? = null): ConformanceReport {
    val inventory = rulesFor(format)
    val observations = items.filterNot { it.id.startsWith("parser.") }.groupBy { it.id }
    val knownIds = inventory.map { it.id }.toSet()
    val rules = inventory + observations.filterKeys { it !in knownIds }.map { (id, values) ->
        ConformanceRule(
            id, values.first().title, "unwrapMedia structural check", null,
            "추가 바이트·구조 관찰 / Additional byte/structure observation; not full codec syntax conformance",
        )
    }
    val findings = rules.map { rule ->
        val values = observations[rule.id].orEmpty()
        ConformanceFinding(
            rule, observationStatus(values),
            if (values.isEmpty()) listOf("검사 결과가 없습니다 / No check result was emitted") else values.map { it.detail },
            values.mapNotNull { byteRange(it) },
        )
    }
    val unsupported = if (inventory.isEmpty()) "지원되는 검사 프로필이 없습니다 / No supported check profile is available" else null
    val limitations = buildList {
        add("코덱 비트스트림 구문 전체는 검사하지 않습니다 / Codec bitstream syntax is not fully checked")
        unsupported?.let { add(it) }
        inspectionError?.let { add("검사 오류 / Inspection error: $it") }
        items.filter { it.id.startsWith("parser.") }.forEach { item ->
            val location = byteRange(item)?.let { " (바이트 / bytes ${it.first}–${it.last})" }.orEmpty()
            add("파서 근거 / Parser evidence [${item.status}]: ${item.detail}$location")
        }
    }
    return ConformanceReport(
        format, "기존 바이트·구조 검사 / Existing byte/structure checks", findings, limitations,
        inspectionError ?: unsupported,
    )
}

private fun observationStatus(values: List<IntegrityCheckItem>): ConformanceStatus = when {
    values.any { it.status == CheckStatus.FAIL } -> ConformanceStatus.FAIL
    values.any { it.status == CheckStatus.WARN } -> ConformanceStatus.WARN
    values.isEmpty() || values.any { it.status == CheckStatus.SKIP } -> ConformanceStatus.NOT_CHECKED
    else -> ConformanceStatus.PASS // INFO describes recognized, legitimate extra data.
}

private fun byteRange(item: IntegrityCheckItem): LongRange? {
    val offset = item.offset ?: return null
    val length = item.length ?: return null
    if (offset < 0 || length <= 0 || length - 1 > Long.MAX_VALUE - offset) return null
    return offset..(offset + (length - 1))
}
