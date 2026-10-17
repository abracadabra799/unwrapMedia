package com.multiviewer.parser.conformance

/** Byte-level observations; these are not normative verdicts until the exact edition/clause is verified. */
object AvcRuleCatalog {
    private const val SOURCE = "unwrapMedia structural observation; AVC header fields (ITU-T H.264 08/21 clause pending verification)"
    private const val REFERENCE = "https://www.itu.int/rec/T-REC-H.264"
    private const val SCOPE = "First non-fragmented avc1 video track; indexed samples only"
    private val rules = listOf(
        ConformanceRule("avc.nal.framing", "Length-prefixed NAL extent / NAL 길이·범위", SOURCE, REFERENCE, SCOPE),
        ConformanceRule("avc.nal.forbidden_zero_bit", "forbidden_zero_bit = 0", SOURCE, REFERENCE, SCOPE),
        ConformanceRule("avc.nal.idr_ref_idc", "IDR slice nal_ref_idc != 0 / IDR 슬라이스 참조 표시", SOURCE, REFERENCE, SCOPE),
    ).associateBy { it.id }

    fun rule(id: String): ConformanceRule? = rules[id]
}
