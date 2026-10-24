package com.multiviewer.parser.conformance

/** Byte-level APV checks cited to RFC 9924 (HTML anchors); apvC cites the openapv ISOBMFF binding (commit-pinned). */
object ApvRuleCatalog {
    private const val RFC = "RFC 9924"
    private const val RFC_URL = "https://www.rfc-editor.org/rfc/rfc9924.html"
    private const val OPENAPV_URL = "https://github.com/openapv/openapv/blob/114280b9202a7eccbf0933f62360f65fabd9c0d8/readme/apv_isobmff.md"
    private const val SCOPE = "First non-fragmented apv1 video track; indexed samples only"
    private val rules = listOf(
        citedRule("apv.au.signature", "Access unit signature = 'aPv1' / 접근 단위 서명", SCOPE,
            RFC, "5.3.1 Access Unit", null, "$RFC_URL#name-access-unit"),
        citedRule("apv.pbu.framing", "Access unit and PBU framing / PBU 구조·범위", SCOPE,
            RFC, "5.3.1 Access Unit$DEFINITIONAL_SUFFIX", null, "$RFC_URL#name-access-unit"),
        citedRule("apv.pbu.reserved_zero", "PBU header reserved_zero_8bits = 0 / PBU 헤더 예약 바이트", SCOPE,
            RFC, "5.3.3 Primitive Bitstream Unit Header", null, "$RFC_URL#name-primitive-bitstream-unit-he"),
        citedRule("apv.frame.profile", "band_idc in 0..3 and reserved_zero_5bits = 0 / 프로필·밴드·예약 비트", SCOPE,
            RFC, "5.3.6 Frame Information", null, "$RFC_URL#name-frame-information"),
        citedRule("apv.frame.reserved_zero", "frame_header reserved_zero_8bits = 0 / 프레임 헤더 예약 바이트", SCOPE,
            RFC, "5.3.5 Frame Header", null, "$RFC_URL#name-frame-header"),
        citedRule("apv.config.apvc", "apvC configuration box / apvC 설정 상자", SCOPE,
            "APV ISOBMFF binding (openapv, commit 114280b, 2025-07-18)", "APV Codec Configuration Box", null,
            "$OPENAPV_URL#user-content-apv-codec-configuration-box"),
    ).associateBy { it.id }

    fun rule(id: String): ConformanceRule? = rules[id]
}
