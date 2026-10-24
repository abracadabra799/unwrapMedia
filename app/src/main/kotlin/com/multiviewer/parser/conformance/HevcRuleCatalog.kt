package com.multiviewer.parser.conformance

/** Byte-level NAL header checks cited to ITU-T H.265; the MP4 NAL framing rule is from paid ISO/IEC 14496-15. */
object HevcRuleCatalog {
    private const val DOC = "ITU-T H.265 (V11) 01/2026"
    private const val PDF = "https://www.itu.int/rec/dologin_pub.asp?lang=e&id=T-REC-H.265-202601-I!!PDF-E&type=items"
    private const val SCOPE = "First non-fragmented hvc1/hev1 video track; indexed samples only"
    private val rules = listOf(
        paidRule("hevc.nal.framing", "Length-prefixed NAL extent / NAL 길이·범위", SCOPE,
            ISO_14496_15_DOCUMENT, "HEVC file format, length-prefixed NAL units", ISO_14496_15_URL),
        citedRule("hevc.nal.forbidden_zero_bit", "forbidden_zero_bit = 0", SCOPE,
            DOC, "7.4.2.2 NAL unit header semantics", 83, "$PDF#page=83"),
        citedRule("hevc.nal.temporal_id_plus1", "nuh_temporal_id_plus1 != 0", SCOPE,
            DOC, "7.4.2.2 NAL unit header semantics", 86, "$PDF#page=86"),
    ).associateBy { it.id }

    fun rule(id: String): ConformanceRule? = rules[id]
}
