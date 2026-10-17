package com.multiviewer.parser.conformance

/** Byte-level observations; these are not normative verdicts until the exact edition/clause is verified. */
object ApvRuleCatalog {
    private const val SOURCE = "unwrapMedia structural observation; APV access unit, PBU, and frame fields (RFC 9924 clause pending verification)"
    private const val REFERENCE = "https://datatracker.ietf.org/doc/rfc9924/"
    private const val SCOPE = "First non-fragmented apv1 video track; indexed samples only"
    private val rules = listOf(
        ConformanceRule("apv.au.signature", "Access unit signature = 'aPv1' / 접근 단위 서명", SOURCE, REFERENCE, SCOPE),
        ConformanceRule("apv.pbu.framing", "Access unit and PBU framing / PBU 구조·범위", SOURCE, REFERENCE, SCOPE),
        ConformanceRule("apv.pbu.reserved_zero", "PBU header reserved_zero_8bits = 0 / PBU 헤더 예약 바이트", SOURCE, REFERENCE, SCOPE),
        ConformanceRule("apv.frame.profile", "band_idc in 0..3 and reserved_zero_5bits = 0 / 프로필·밴드·예약 비트", SOURCE, REFERENCE, SCOPE),
        ConformanceRule("apv.frame.reserved_zero", "frame_header reserved_zero_8bits = 0 / 프레임 헤더 예약 바이트", SOURCE, REFERENCE, SCOPE),
        ConformanceRule("apv.config.apvc", "apvC configuration box / apvC 설정 상자", SOURCE, REFERENCE, SCOPE),
    ).associateBy { it.id }

    fun rule(id: String): ConformanceRule? = rules[id]
}
