package com.multiviewer.parser.conformance

/** Byte-level observations; these are not normative verdicts until the exact edition/clause is verified. */
object Vp9RuleCatalog {
    private const val SOURCE = "unwrapMedia structural observation; VP9 frame and configuration fields (WebM VP9 Bitstream v0.6 clause pending verification)"
    private const val REFERENCE = "https://storage.googleapis.com/downloads.webmproject.org/docs/vp9/vp9-bitstream-specification-v0.6-20160331-draft.pdf"
    private const val SCOPE = "First non-fragmented vp09 video track; indexed samples only"
    private val rules = listOf(
        ConformanceRule("vp9.frame.framing", "Sample and frame framing / 프레임 길이·범위", SOURCE, REFERENCE, SCOPE),
        ConformanceRule("vp9.frame.marker", "frame_marker = 2 (0b10)", SOURCE, REFERENCE, SCOPE),
        ConformanceRule("vp9.frame.sync_code", "sync_code = 0x498342 for KEY_FRAME", SOURCE, REFERENCE, SCOPE),
        ConformanceRule("vp9.frame.profile_reserved_bit", "profile 3 reserved_zero = 0", SOURCE, REFERENCE, SCOPE),
        ConformanceRule("vp9.config.vpcc", "vpcC configuration box / vpcC 설정 상자", SOURCE, REFERENCE, SCOPE),
    ).associateBy { it.id }

    fun rule(id: String): ConformanceRule? = rules[id]
}
