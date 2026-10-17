package com.multiviewer.parser.conformance

/** Byte-level observations; these are not normative verdicts until the exact edition/clause is verified. */
object FlacRuleCatalog {
    private const val SOURCE = "unwrapMedia structural observation; FLAC audio track, dfLa, and frame fields (FLAC-in-ISOBMFF, FLAC format spec pending verification)"
    private const val REFERENCE = "https://xiph.org/flac/format.html"
    private const val SCOPE = "First non-fragmented fLaC audio track; indexed samples only"
    private val rules = listOf(
        ConformanceRule("flac.config.dfla", "dfLa box structure and STREAMINFO block / dfLa 상자 구조 및 STREAMINFO 블록", SOURCE, REFERENCE, SCOPE),
        ConformanceRule("flac.config.match", "Sample entry and STREAMINFO parameter match / 샘플 엔트리와 STREAMINFO 일치성", SOURCE, REFERENCE, SCOPE),
        ConformanceRule("flac.frame.header", "FLAC frame header sync, reserved bits, and CRC-8 / FLAC 프레임 헤더 동기단어, 예약 비트 및 CRC-8", SOURCE, REFERENCE, SCOPE),
        ConformanceRule("flac.frame.crc", "FLAC frame payload CRC-16 / FLAC 프레임 CRC-16 검증", SOURCE, REFERENCE, SCOPE),
        ConformanceRule("flac.sample.framing", "Sample byte extents / 샘플 바이트 범위", SOURCE, REFERENCE, SCOPE),
    ).associateBy { it.id }

    fun rule(id: String): ConformanceRule? = rules[id]
}
