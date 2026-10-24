package com.multiviewer.parser.conformance

/** Byte-level OBU and av1C checks cited to the AV1 specification and the AV1 ISOBMFF binding (HTML anchors). */
object Av1RuleCatalog {
    private const val SPEC = "AV1 Bitstream & Decoding Process Specification (v1.0.0 with Errata 1, HTML 2023-05-25)"
    private const val SPEC_URL = "https://aomediacodec.github.io/av1-spec/"
    private const val BINDING = "AV1 Codec ISO Media File Format Binding v1.3.0"
    private const val BINDING_URL = "https://aomediacodec.github.io/av1-isobmff/"
    private const val SCOPE = "First non-fragmented av01 video track; indexed samples only"
    private val rules = listOf(
        citedRule("av1.obu.framing", "Sample and OBU framing / OBU 길이·범위", SCOPE,
            BINDING, "2.4 AV1 Sample Format$DEFINITIONAL_SUFFIX", null, "$BINDING_URL#sampleformat"),
        citedRule("av1.obu.forbidden_bit", "obu_forbidden_bit = 0", SCOPE,
            SPEC, "6.2.2 OBU header semantics", null, "$SPEC_URL#obu-header-semantics"),
        citedRule("av1.obu.reserved_bit", "obu_reserved_1bit = 0", SCOPE,
            SPEC, "6.2.2 OBU header semantics", null, "$SPEC_URL#obu-header-semantics"),
        citedRule("av1.obu.extension_reserved_bits", "extension_header_reserved_3bits = 0", SCOPE,
            SPEC, "6.2.3 OBU extension header semantics", null, "$SPEC_URL#obu-extension-header-semantics"),
        citedRule("av1.config.sequence_header", "Sequence Header in av1C / av1C 시퀀스 헤더", SCOPE,
            BINDING, "2.3.4 AV1CodecConfigurationBox Semantics (configOBUs)", null, "$BINDING_URL#av1codecconfigurationbox-semantics"),
    ).associateBy { it.id }

    /** Every rule of this catalog, for catalog-wide checks. */
    val all: List<ConformanceRule> get() = rules.values.toList()

    fun rule(id: String): ConformanceRule? = rules[id]
}
