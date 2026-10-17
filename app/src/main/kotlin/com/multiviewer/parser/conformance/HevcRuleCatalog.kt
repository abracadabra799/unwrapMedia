package com.multiviewer.parser.conformance

/** Byte-level observations; these are not normative verdicts until the exact edition/clause is verified. */
object HevcRuleCatalog {
    private const val SOURCE = "unwrapMedia structural observation; HEVC header fields (ITU-T H.265 01/26 clause pending verification)"
    private const val REFERENCE = "https://www.itu.int/rec/T-REC-H.265"
    private const val SCOPE = "First non-fragmented hvc1/hev1 video track; indexed samples only"
    private val rules = listOf(
        ConformanceRule("hevc.nal.framing", "Length-prefixed NAL extent / NAL 길이·범위", SOURCE, REFERENCE, SCOPE),
        ConformanceRule("hevc.nal.forbidden_zero_bit", "forbidden_zero_bit = 0", SOURCE, REFERENCE, SCOPE),
        ConformanceRule("hevc.nal.temporal_id_plus1", "nuh_temporal_id_plus1 != 0", SOURCE, REFERENCE, SCOPE),
    ).associateBy { it.id }

    fun rule(id: String): ConformanceRule? = rules[id]
}
