package com.multiviewer.parser.conformance

/** AAC mp4a rules come from paid ISO/IEC 14496-1/-3 and were not checked against the originals: store pages only. */
object AacRuleCatalog {
    private const val ISO_14496_1 = "ISO/IEC 14496-1:2010"
    private const val ISO_14496_1_URL = "https://www.iso.org/standard/55688.html"
    private const val ISO_14496_3 = "ISO/IEC 14496-3:2019"
    private const val ISO_14496_3_URL = "https://www.iso.org/standard/76383.html"
    private const val SCOPE = "First non-fragmented mp4a audio track; indexed samples only"
    private val rules = listOf(
        paidRule("aac.config.esds", "esds box and descriptor structure / esds 상자 및 디스크립터 구조", SCOPE,
            ISO_14496_1, "ES_Descriptor / DecoderConfigDescriptor (esds 상자 자체는 ISO/IEC 14496-14)", ISO_14496_1_URL),
        paidRule("aac.config.asc", "AudioSpecificConfig syntax / AudioSpecificConfig 구문", SCOPE,
            ISO_14496_3, "Subpart 1: AudioSpecificConfig", ISO_14496_3_URL),
        paidRule("aac.config.match", "Sample entry and ASC parameter match / 샘플 엔트리와 ASC 일치성", SCOPE,
            ISO_14496_3, "Subpart 1: AudioSpecificConfig", ISO_14496_3_URL),
        paidRule("aac.frame.adts_guard", "Raw data block ADTS guard / ADTS 헤더 누출 방지", SCOPE,
            ISO_14496_3, "raw_data_block (ADTS는 별도 전송 형식)", ISO_14496_3_URL),
        paidRule("aac.frame.element", "Raw data block syntactic elements / 원시 데이터 블록 시작 구문", SCOPE,
            ISO_14496_3, "raw_data_block, id_syn_ele", ISO_14496_3_URL),
        paidRule("aac.frame.ics_reserved", "ICS header reserved bit / 개별 채널 스트림 예약 비트", SCOPE,
            ISO_14496_3, "individual_channel_stream / ics_info (ics_reserved_bit)", ISO_14496_3_URL),
        paidRule("aac.sample.framing", "Sample byte extents / 샘플 바이트 범위", SCOPE,
            ISO_14496_3, "sample extents (실제 규칙은 ISO/IEC 14496-12 sample table)", ISO_14496_3_URL),
    ).associateBy { it.id }

    fun rule(id: String): ConformanceRule? = rules[id]
}
