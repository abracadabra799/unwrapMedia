package com.multiviewer.parser.conformance

/** Byte-level FLAC checks cited to RFC 9639 (HTML anchors) and FLAC-in-ISOBMFF (commit-pinned line links). */
object FlacRuleCatalog {
    private const val RFC = "RFC 9639"
    private const val RFC_URL = "https://www.rfc-editor.org/rfc/rfc9639.html"
    private const val BINDING = "Encapsulation of FLAC in ISO Base Media File Format v0.0.4"
    private const val BINDING_URL = "https://github.com/xiph/flac/blob/78d85dd4e4e8044d0f6989182cd1a22a461dce37/doc/isoflac.txt"
    private const val SCOPE = "First non-fragmented fLaC audio track; indexed samples only"
    private val rules = listOf(
        citedRule("flac.config.dfla", "dfLa box structure and STREAMINFO block / dfLa 상자 구조 및 STREAMINFO 블록", SCOPE,
            BINDING, "3.3.2 FLAC Specific Box", null, "$BINDING_URL#L227"),
        citedRule("flac.config.match", "Sample entry and STREAMINFO parameter match / 샘플 엔트리와 STREAMINFO 일치성", SCOPE,
            BINDING, "3.3.1 Sample entry format", null, "$BINDING_URL#L135"),
        citedRule("flac.frame.header", "FLAC frame header sync, reserved bits, and CRC-8 / FLAC 프레임 헤더 동기단어, 예약 비트 및 CRC-8", SCOPE,
            RFC, "9.1 Frame Header", null, "$RFC_URL#section-9.1"),
        citedRule("flac.frame.crc", "FLAC frame payload CRC-16 / FLAC 프레임 CRC-16 검증", SCOPE,
            RFC, "9.3 Frame Footer", null, "$RFC_URL#section-9.3"),
        citedRule("flac.sample.framing", "Sample byte extents / 샘플 바이트 범위", SCOPE,
            BINDING, "3.3.3 Sample format$DEFINITIONAL_SUFFIX", null, "$BINDING_URL#L309"),
    ).associateBy { it.id }

    fun rule(id: String): ConformanceRule? = rules[id]
}
