package com.multiviewer.parser.conformance

/** Byte-level observations; these are not normative verdicts until the exact edition/clause is verified. */
object OpusRuleCatalog {
    private const val SOURCE = "unwrapMedia structural observation; Opus audio track, dOps, and packet fields (RFC 6716, RFC 7845 pending verification)"
    private const val REFERENCE = "https://datatracker.ietf.org/doc/html/rfc6716"
    private const val SCOPE = "First non-fragmented Opus audio track; indexed samples only"
    private val rules = listOf(
        ConformanceRule("opus.config.dops", "dOps box structure and parameter limits / dOps 상자 구조 및 파라미터 유효성", SOURCE, REFERENCE, SCOPE),
        ConformanceRule("opus.config.match", "Sample entry and dOps parameter match / 샘플 엔트리와 dOps 일치성", SOURCE, REFERENCE, SCOPE),
        ConformanceRule("opus.packet.toc", "Opus packet Table of Contents (TOC) / Opus 패킷 목차(TOC) 구문", SOURCE, REFERENCE, SCOPE),
        ConformanceRule("opus.packet.framing", "Opus packet framing and duration / Opus 패킷 프레이밍 및 길이 제약", SOURCE, REFERENCE, SCOPE),
        ConformanceRule("opus.sample.framing", "Sample byte extents / 샘플 바이트 범위", SOURCE, REFERENCE, SCOPE),
    ).associateBy { it.id }

    fun rule(id: String): ConformanceRule? = rules[id]
}
