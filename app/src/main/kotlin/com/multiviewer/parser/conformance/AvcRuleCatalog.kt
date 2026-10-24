package com.multiviewer.parser.conformance

/** Byte-level NAL header checks cited to ITU-T H.264; the MP4 NAL framing rule is from paid ISO/IEC 14496-15. */
object AvcRuleCatalog {
    private const val DOC = "ITU-T H.264 (V16) 06/2026"
    private const val PDF = "https://www.itu.int/rec/dologin_pub.asp?lang=e&id=T-REC-H.264-202606-I!!PDF-E&type=items"
    private const val SCOPE = "First non-fragmented avc1 video track; indexed samples only"
    private val rules = listOf(
        paidRule("avc.nal.framing", "Length-prefixed NAL extent / NAL 길이·범위", SCOPE,
            ISO_14496_15_DOCUMENT, "AVC file format, length-prefixed NAL units (lengthSizeMinusOne)", ISO_14496_15_URL),
        citedRule("avc.nal.forbidden_zero_bit", "forbidden_zero_bit = 0", SCOPE,
            DOC, "7.4.1 NAL unit semantics", 89, "$PDF#page=89"),
        citedRule("avc.nal.idr_ref_idc", "IDR slice nal_ref_idc != 0 / IDR 슬라이스 참조 표시", SCOPE,
            DOC, "7.4.1 NAL unit semantics (nal_ref_idc)", 90, "$PDF#page=90"),
    ).associateBy { it.id }

    fun rule(id: String): ConformanceRule? = rules[id]
}
