package com.multiviewer.parser.conformance

/** Byte-level Opus checks cited to RFC 6716 (HTML anchors) and Opus-in-ISOBMFF (a page without anchors). */
object OpusRuleCatalog {
    private const val RFC = "RFC 6716"
    private const val RFC_URL = "https://www.rfc-editor.org/rfc/rfc6716.html"
    private const val BINDING = "Encapsulation of Opus in ISO Base Media File Format v0.6.8"
    private const val BINDING_URL = "https://opus-codec.org/docs/opus_in_isobmff.html"
    private const val SCOPE = "First non-fragmented Opus audio track; indexed samples only"
    private val rules = listOf(
        citedRule("opus.config.dops", "dOps box structure and parameter limits / dOps 상자 구조 및 파라미터 유효성", SCOPE,
            BINDING, "4.3.2 Opus Specific Box", null, BINDING_URL),
        citedRule("opus.config.match", "Sample entry and dOps parameter match / 샘플 엔트리와 dOps 일치성", SCOPE,
            BINDING, "4.3.1 Sample entry format", null, BINDING_URL),
        citedRule("opus.packet.toc", "Opus packet Table of Contents (TOC) / Opus 패킷 목차(TOC) 구문", SCOPE,
            RFC, "3.1 The TOC Byte$DEFINITIONAL_SUFFIX", null, "$RFC_URL#section-3.1"),
        citedRule("opus.packet.framing", "Opus packet framing and duration / Opus 패킷 프레이밍 및 길이 제약", SCOPE,
            RFC, "3.2.5 Code 3: A Signaled Number of Frames in the Packet", null, "$RFC_URL#section-3.2.5"),
        citedRule("opus.sample.framing", "Sample byte extents / 샘플 바이트 범위", SCOPE,
            BINDING, "4.3.3 Sample format$DEFINITIONAL_SUFFIX", null, BINDING_URL),
    ).associateBy { it.id }

    fun rule(id: String): ConformanceRule? = rules[id]
}
