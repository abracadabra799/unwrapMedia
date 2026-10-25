package com.multiviewer.parser.conformance.webp

import com.multiviewer.parser.conformance.ConformanceRule
import com.multiviewer.parser.conformance.DEFINITIONAL_SUFFIX
import com.multiviewer.parser.conformance.ImageRuleBook

/**
 * WebP rule catalog (RFC 9649, VP8 frame tag from RFC 6386). Citations were checked against the rfc-editor HTML: the
 * URL carries the `#section-N` anchor, page is null; quotes are in
 * docs/superpowers/specs/2026-10-25-png-webp-gif-conformance-design.md.
 */
object WebpRules {
    const val WEBP_DOCUMENT = "RFC 9649 (WebP, 2024-11)"
    const val WEBP_URL = "https://www.rfc-editor.org/rfc/rfc9649.html"
    const val VP8_DOCUMENT = "RFC 6386 (VP8, 2011-11)"
    const val VP8_URL = "https://www.rfc-editor.org/rfc/rfc6386.html"

    const val SCOPE_CONTAINER = "WebP RIFF 컨테이너 / WebP RIFF container (RFC 9649 §2)"
    const val SCOPE_BITSTREAM = "WebP 비트스트림 헤더 / WebP bitstream headers (RFC 9649 §2.7.1, §3.4; RFC 6386 §9.1)"

    private fun webp(id: String, ko: String, en: String, scope: String, section: String, anchor: String) =
        ImageRuleBook.cited(id, ko, en, scope, WEBP_DOCUMENT, section, "$WEBP_URL#$anchor")

    val all: List<ConformanceRule> = listOf(
        webp("rfc9649.header", "RIFF/WEBP 헤더", "RIFF/WEBP header", SCOPE_CONTAINER, "2.4 WebP File Header", "section-2.4"),
        webp("rfc9649.riff.size", "RIFF File Size", "RIFF File Size", SCOPE_CONTAINER, "2.4 WebP File Header (File Size)$DEFINITIONAL_SUFFIX", "section-2.4"),
        webp("rfc9649.chunk.layout", "청크 경계·패딩", "Chunk extent and padding", SCOPE_CONTAINER, "2.3 RIFF File Format", "section-2.3"),
        webp("rfc9649.layout", "파일 구성", "File layout", SCOPE_CONTAINER, "2.5-2.7 Simple / Extended File Format$DEFINITIONAL_SUFFIX", "section-2.7"),
        webp("rfc9649.order", "청크 순서", "Chunk order", SCOPE_CONTAINER, "2.7 Extended File Format", "section-2.7"),
        webp("rfc9649.vp8x", "VP8X 필드", "VP8X fields", SCOPE_CONTAINER, "2.7 Extended File Format (Figure 7)", "section-2.7"),
        webp("rfc9649.vp8x.flags", "VP8X 플래그와 청크", "VP8X flags vs chunks", SCOPE_CONTAINER, "2.7 Extended File Format (Figure 7) / 2.7.1.1 ANIM$DEFINITIONAL_SUFFIX", "section-2.7"),
        webp("rfc9649.anim", "ANIM·ANMF 필드", "ANIM and ANMF fields", SCOPE_BITSTREAM, "2.7.1.1 Animation / 2.7.2 Canvas Assembly from Frames", "section-2.7.1.1"),
        webp("rfc9649.anmf.flag", "애니메이션 플래그 없는 ANMF", "ANMF without the Animation flag", SCOPE_BITSTREAM, "2.7.1.1 Animation (ANMF)", "section-2.7.1.1"),
        webp("rfc9649.alph", "ALPH 헤더", "ALPH header", SCOPE_BITSTREAM, "2.7.1.2 Alpha", "section-2.7.1.2"),
        webp("rfc9649.alph.vp8l", "VP8L 프레임의 ALPH", "ALPH in a VP8L frame", SCOPE_BITSTREAM, "2.7.1.2 Alpha", "section-2.7.1.2"),
        webp("rfc9649.metadata.count", "ICCP·EXIF·XMP 개수", "ICCP, EXIF and XMP count", SCOPE_CONTAINER, "2.7.1.4 Color Profile / 2.7.1.5 Metadata", "section-2.7.1.4"),
        webp("rfc9649.vp8l", "VP8L 헤더", "VP8L header", SCOPE_BITSTREAM, "3.4 RIFF Header (VP8L signature, version)", "section-3.4"),
        ImageRuleBook.cited("rfc6386.frame", "VP8 키 프레임 헤더", "VP8 key frame header", SCOPE_BITSTREAM, VP8_DOCUMENT,
            "9.1 Uncompressed Data Chunk (+ RFC 9649 §2.1 \"VP8 key frame\")$DEFINITIONAL_SUFFIX", "$VP8_URL#section-9.1"),
        webp("rfc9649.canvas", "캔버스·프레임 크기와 비트스트림 크기", "Canvas / frame size vs bitstream size", SCOPE_BITSTREAM,
            "2.5-2.6 (VP8/VP8L size = canvas) / 2.7.1.1 (ANMF frame size)$DEFINITIONAL_SUFFIX", "section-2.5"),
        webp("rfc9649.trailing", "RIFF 이후 데이터", "Data after the RIFF File Size", SCOPE_CONTAINER, "2.4 WebP File Header", "section-2.4"),
    )

    /** Violations of these rules are WARN (SHOULD / SHOULD NOT); all others FAIL. */
    val WARN_RULES = setOf("rfc9649.anmf.flag", "rfc9649.alph.vp8l", "rfc9649.metadata.count", "rfc9649.trailing")

    internal val book = ImageRuleBook(all, WARN_RULES)

    fun rule(id: String): ConformanceRule = book.rule(id)
}
