package com.multiviewer.parser.conformance.png

import com.multiviewer.parser.conformance.ConformanceRule
import com.multiviewer.parser.conformance.DEFINITIONAL_SUFFIX
import com.multiviewer.parser.conformance.ImageRuleBook
import com.multiviewer.parser.conformance.citedRule

/**
 * PNG / APNG / zlib rule catalog. Every citation was checked against the original HTML (W3C PNG-3 REC of 2025-06-24,
 * RFC 1950): the URL carries the section anchor and page is null (zlib.header: RFC page 5, `#page-5`), and the quoted sentence is listed in
 * docs/superpowers/specs/2026-10-25-png-webp-gif-conformance-design.md.
 */
object PngRules {
    const val PNG_DOCUMENT = "W3C PNG Third Edition (REC 2025-06-24)"
    const val PNG_URL = "https://www.w3.org/TR/2025/REC-png-3-20250624/"
    const val ZLIB_DOCUMENT = "RFC 1950 (ZLIB 3.3)"
    const val ZLIB_URL = "https://www.rfc-editor.org/rfc/rfc1950.html"

    const val SCOPE_STRUCTURE = "PNG 데이터스트림 구조 / PNG datastream structure (PNG-3)"
    const val SCOPE_ANCILLARY = "PNG 보조 청크 / PNG ancillary chunks (PNG-3)"
    const val SCOPE_APNG = "APNG 애니메이션 / APNG animation (PNG-3)"
    const val SCOPE_ZLIB = "zlib 스트림 헤더 / zlib stream headers (PNG-3 §10, RFC 1950)"

    private fun png(id: String, ko: String, en: String, scope: String, section: String, anchor: String) =
        ImageRuleBook.cited(id, ko, en, scope, PNG_DOCUMENT, section, "$PNG_URL#$anchor")

    val all: List<ConformanceRule> = listOf(
        png("png3.signature", "PNG 시그니처", "PNG signature", SCOPE_STRUCTURE, "5.2 PNG signature$DEFINITIONAL_SUFFIX", "5PNG-file-signature"),
        png("png3.chunk.layout", "청크 길이·경계", "Chunk length and extent", SCOPE_STRUCTURE, "5.3 Chunk layout, Table 5 (Length)", "5Chunk-layout"),
        png("png3.chunk.type", "청크 유형 바이트", "Chunk type bytes", SCOPE_STRUCTURE, "5.3 Chunk layout, Table 5 (Chunk Type) / 5.4 Chunk naming conventions", "5Chunk-naming-conventions"),
        png("png3.crc", "청크 CRC", "Chunk CRC", SCOPE_STRUCTURE, "5.3 Chunk layout, Table 5 (CRC) / 5.5 CRC algorithm$DEFINITIONAL_SUFFIX", "5CRC-algorithm"),
        png("png3.ihdr", "IHDR 필드", "IHDR fields", SCOPE_STRUCTURE, "11.2.1 IHDR Image header, Table 12", "11IHDR"),
        png("png3.critical", "필수 청크 구성", "Critical chunk sequence", SCOPE_STRUCTURE, "11.2 Critical chunks / 11.2.3 IDAT", "11Critical-chunks"),
        png("png3.plte", "PLTE 팔레트", "PLTE palette", SCOPE_STRUCTURE, "11.2.2 PLTE Palette", "11PLTE"),
        png("png3.order", "보조 청크 순서", "Ancillary chunk ordering", SCOPE_STRUCTURE, "5.6 Chunk ordering, Table 7", "5ChunkOrdering"),
        png("png3.multiple", "청크 중복", "Chunks that must not repeat", SCOPE_STRUCTURE, "5.6 Chunk ordering, Table 7 (Multiple allowed)$DEFINITIONAL_SUFFIX", "5ChunkOrdering"),
        png("png3.trns", "tRNS 투명도", "tRNS transparency", SCOPE_ANCILLARY, "11.3.1.1 tRNS Transparency", "11tRNS"),
        png("png3.ancillary", "보조 청크 필드", "Ancillary chunk fields", SCOPE_ANCILLARY, "11.3 Ancillary chunks (11.3.2.1-11.3.5.1)", "11Ancillary-chunks"),
        png("png3.keyword", "키워드·이름", "Keywords and names", SCOPE_ANCILLARY, "11.3.3.1 Keywords and text strings", "11keywords"),
        png("png3.text", "텍스트·압축 필드", "Text and compression fields", SCOPE_ANCILLARY, "11.3.3.2-11.3.3.4 tEXt, zTXt, iTXt / 11.3.2.3 iCCP", "11textinfo"),
        png("png3.srgb_iccp", "sRGB·iCCP 동시 사용", "sRGB together with iCCP", SCOPE_ANCILLARY, "5.6 Chunk ordering, Table 7 (iCCP, sRGB)", "5ChunkOrdering"),
        png("png3.unused_bits", "16비트 표본의 미사용 상위 비트", "Unused high bits of 16-bit samples", SCOPE_ANCILLARY, "11.3.1.1 tRNS / 11.3.4.1 bKGD", "11tRNS"),
        png("png3.compression", "PNG 압축 방식 0", "PNG compression method 0", SCOPE_ZLIB, "10.1 Compression method 0", "10CompressionCM0"),
        // rfc1950.html has no #section-2.2 anchor (only top-level sections and pages); both quoted sentences are on page 5.
        citedRule("zlib.header", "zlib CMF·FLG / zlib CMF and FLG", SCOPE_ZLIB, ZLIB_DOCUMENT, "2.2 Data format", 5, "$ZLIB_URL#page-5"),
        png("png3.actl", "acTL 애니메이션 제어", "acTL animation control", SCOPE_APNG, "11.3.6.1 acTL Animation Control Chunk", "acTL-chunk"),
        png("png3.sequence", "APNG 시퀀스 번호", "APNG sequence numbers", SCOPE_APNG, "4.9.2 Sequence numbers", "4Concepts.APNGSequence"),
        png("png3.fctl", "fcTL 프레임 제어", "fcTL frame control", SCOPE_APNG, "11.3.6.2 fcTL Frame Control Chunk", "fcTL-chunk"),
        png("png3.fdat", "fdAT 프레임 데이터", "fdAT frame data", SCOPE_APNG, "11.3.6.3 fdAT Frame Data Chunk", "fdAT-chunk"),
        png("png3.trailing", "IEND 이후 데이터", "Data after IEND", SCOPE_STRUCTURE, "15.3.1 Conformance of PNG datastreams", "15FileConformance"),
    )

    /** Violations of these rules are WARN (should / not recommended / data after IEND, user decision); all others FAIL. */
    val WARN_RULES = setOf("png3.srgb_iccp", "png3.unused_bits")

    internal val book = ImageRuleBook(all, WARN_RULES)

    fun rule(id: String): ConformanceRule = book.rule(id)
}
