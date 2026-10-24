package com.multiviewer.parser.conformance

/** Byte-level NAL header checks cited to ITU-T H.266; MP4 carriage rules are from paid ISO/IEC 14496-15. */
object VvcRuleCatalog {
    private const val DOC = "ITU-T H.266 (V4) 01/2026"
    private const val PDF = "https://www.itu.int/rec/dologin_pub.asp?lang=e&id=T-REC-H.266-202601-I!!PDF-E&type=items"
    private const val SCOPE = "First non-fragmented vvc1/vvi1 video track; indexed samples only"
    private val rules = listOf(
        paidRule("vvc.config.vvcc", "vvcC box structure and VvcDecoderConfigurationRecord / vvcC 상자 구조 및 디코더 구성 레코드", SCOPE,
            ISO_14496_15_DOCUMENT, "VvcDecoderConfigurationRecord / vvcC", ISO_14496_15_URL),
        paidRule("vvc.nal.framing", "Length-prefixed NAL unit bounds / NAL 길이·범위", SCOPE,
            ISO_14496_15_DOCUMENT, "VVC file format, length-prefixed NAL units", ISO_14496_15_URL),
        citedRule("vvc.nal.forbidden_zero_bit", "forbidden_zero_bit = 0", SCOPE,
            DOC, "7.4.2.2 NAL unit header semantics", 104, "$PDF#page=104"),
        citedRule("vvc.nal.reserved_zero_bit", "nuh_reserved_zero_bit = 0", SCOPE,
            DOC, "7.4.2.2 NAL unit header semantics", 104, "$PDF#page=104"),
        citedRule("vvc.nal.temporal_id_plus1", "nuh_temporal_id_plus1 != 0", SCOPE,
            DOC, "7.4.2.2 NAL unit header semantics", 107, "$PDF#page=107"),
        paidRule("vvc.sample.framing", "Sample byte extents / 샘플 바이트 범위", SCOPE,
            ISO_14496_15_DOCUMENT, "sample table / sample extents (ISO/IEC 14496-12 and 14496-15)", ISO_14496_15_URL),
    ).associateBy { it.id }

    fun rule(id: String): ConformanceRule? = rules[id]
}
