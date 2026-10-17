package com.multiviewer.parser.conformance

/** Byte-level observations; these are not normative verdicts until the exact edition/clause is verified. */
object VvcRuleCatalog {
    private const val SOURCE = "unwrapMedia structural observation; VVC header fields (ITU-T H.266, ISO/IEC 14496-15 pending verification)"
    private const val REFERENCE = "https://www.itu.int/rec/T-REC-H.266"
    private const val SCOPE = "First non-fragmented vvc1/vvi1 video track; indexed samples only"
    private val rules = listOf(
        ConformanceRule("vvc.config.vvcc", "vvcC box structure and VvcDecoderConfigurationRecord / vvcC 상자 구조 및 디코더 구성 레코드", SOURCE, REFERENCE, SCOPE),
        ConformanceRule("vvc.nal.framing", "Length-prefixed NAL unit bounds / NAL 길이·범위", SOURCE, REFERENCE, SCOPE),
        ConformanceRule("vvc.nal.forbidden_zero_bit", "forbidden_zero_bit = 0", SOURCE, REFERENCE, SCOPE),
        ConformanceRule("vvc.nal.reserved_zero_bit", "nuh_reserved_zero_bit = 0", SOURCE, REFERENCE, SCOPE),
        ConformanceRule("vvc.nal.temporal_id_plus1", "nuh_temporal_id_plus1 != 0", SOURCE, REFERENCE, SCOPE),
        ConformanceRule("vvc.sample.framing", "Sample byte extents / 샘플 바이트 범위", SOURCE, REFERENCE, SCOPE),
    ).associateBy { it.id }

    fun rule(id: String): ConformanceRule? = rules[id]
}
