package com.multiviewer.parser.conformance

/** Byte-level observations; these are not normative verdicts until the exact edition/clause is verified. */
object Av1RuleCatalog {
    private const val SOURCE = "unwrapMedia structural observation; AV1 OBU and configuration fields (AOMedia AV1 v1.0.0 with errata clause pending verification)"
    private const val REFERENCE = "https://aomediacodec.github.io/av1-spec/"
    private const val SCOPE = "First non-fragmented av01 video track; indexed samples only"
    private val rules = listOf(
        ConformanceRule("av1.obu.framing", "Sample and OBU framing / OBU 길이·범위", SOURCE, REFERENCE, SCOPE),
        ConformanceRule("av1.obu.forbidden_bit", "obu_forbidden_bit = 0", SOURCE, REFERENCE, SCOPE),
        ConformanceRule("av1.obu.reserved_bit", "obu_reserved_1bit = 0", SOURCE, REFERENCE, SCOPE),
        ConformanceRule("av1.obu.extension_reserved_bits", "extension_header_reserved_3bits = 0", SOURCE, REFERENCE, SCOPE),
        ConformanceRule("av1.config.sequence_header", "Sequence Header in av1C / av1C 시퀀스 헤더", SOURCE, REFERENCE, SCOPE),
    ).associateBy { it.id }

    fun rule(id: String): ConformanceRule? = rules[id]
}
