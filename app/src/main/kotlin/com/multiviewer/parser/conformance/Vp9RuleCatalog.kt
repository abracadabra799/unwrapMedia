package com.multiviewer.parser.conformance

/** Byte-level VP9 frame and vpcC checks cited to the VP9 bitstream spec v0.7 (PDF pages) and the VP ISOBMFF binding. */
object Vp9RuleCatalog {
    private const val SPEC = "VP9 Bitstream & Decoding Process Specification v0.7 (2017-02-22 draft)"
    private const val PDF = "https://storage.googleapis.com/downloads.webmproject.org/docs/vp9/vp9-bitstream-specification-v0.7-20170222-draft.pdf"
    private const val BINDING = "VP Codec ISO Media File Format Binding v1.0 (2017-03-31)"
    private const val SCOPE = "First non-fragmented vp09 video track; indexed samples only"
    private val rules = listOf(
        citedRule("vp9.frame.framing", "Sample and frame framing / 프레임 길이·범위", SCOPE,
            SPEC, "B.4 Superframe parsing$DEFINITIONAL_SUFFIX", 172, "$PDF#page=172"),
        citedRule("vp9.frame.marker", "frame_marker = 2 (0b10)", SCOPE,
            SPEC, "7.2 Uncompressed header semantics", 68, "$PDF#page=68"),
        citedRule("vp9.frame.sync_code", "sync_code = 0x498342 for KEY_FRAME", SCOPE,
            SPEC, "7.2.1 Frame sync semantics", 70, "$PDF#page=70"),
        citedRule("vp9.frame.profile_reserved_bit", "profile 3 reserved_zero = 0", SCOPE,
            SPEC, "7.2 Uncompressed header semantics (reserved_zero)", 68, "$PDF#page=68"),
        citedRule("vp9.config.vpcc", "vpcC configuration box / vpcC 설정 상자", SCOPE,
            BINDING, "VP Codec Configuration Box semantics", null, "https://www.webmproject.org/vp9/mp4/#semantics_1"),
    ).associateBy { it.id }

    fun rule(id: String): ConformanceRule? = rules[id]
}
