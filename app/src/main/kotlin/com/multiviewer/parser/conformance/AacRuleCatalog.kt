package com.multiviewer.parser.conformance

/** Byte-level observations; these are not normative verdicts until the exact edition/clause is verified. */
object AacRuleCatalog {
    private const val SOURCE = "unwrapMedia structural observation; AAC mp4a audio track, esds, and raw sample fields (ISO/IEC 14496-1, 14496-3, 14496-14 pending verification)"
    private const val REFERENCE = "https://www.iso.org/standard/43345.html"
    private const val SCOPE = "First non-fragmented mp4a audio track; indexed samples only"
    private val rules = listOf(
        ConformanceRule("aac.config.esds", "esds box and descriptor structure / esds 상자 및 디스크립터 구조", SOURCE, REFERENCE, SCOPE),
        ConformanceRule("aac.config.asc", "AudioSpecificConfig syntax / AudioSpecificConfig 구문", SOURCE, REFERENCE, SCOPE),
        ConformanceRule("aac.config.match", "Sample entry and ASC parameter match / 샘플 엔트리와 ASC 일치성", SOURCE, REFERENCE, SCOPE),
        ConformanceRule("aac.frame.adts_guard", "Raw data block ADTS guard / ADTS 헤더 누출 방지", SOURCE, REFERENCE, SCOPE),
        ConformanceRule("aac.frame.element", "Raw data block syntactic elements / 원시 데이터 블록 시작 구문", SOURCE, REFERENCE, SCOPE),
        ConformanceRule("aac.frame.ics_reserved", "ICS header reserved bit / 개별 채널 스트림 예약 비트", SOURCE, REFERENCE, SCOPE),
        ConformanceRule("aac.sample.framing", "Sample byte extents / 샘플 바이트 범위", SOURCE, REFERENCE, SCOPE),
    ).associateBy { it.id }

    fun rule(id: String): ConformanceRule? = rules[id]
}
