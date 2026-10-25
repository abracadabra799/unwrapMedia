package com.multiviewer.parser.conformance.gif

import com.multiviewer.parser.conformance.ConformanceRule
import com.multiviewer.parser.conformance.DEFINITIONAL_SUFFIX
import com.multiviewer.parser.conformance.ImageRuleBook

/**
 * GIF89a rule catalog. The source is the W3C-hosted plain-text copy of the CompuServe specification (31 July 1990):
 * it has no anchors, so every citation URL is the document itself and the section number is the locator; page is
 * null. Quotes are in docs/superpowers/specs/2026-10-25-png-webp-gif-conformance-design.md.
 */
object GifRules {
    const val GIF_DOCUMENT = "GIF89a Specification (CompuServe 1990-07-31, 평문·앵커 없음 / plain text, no anchors)"
    const val GIF_URL = "https://www.w3.org/Graphics/GIF/spec-gif89a.txt"
    const val SCOPE = "GIF 블록 구조 / GIF block structure (GIF89a)"

    private fun gif(id: String, ko: String, en: String, section: String) =
        ImageRuleBook.cited(id, ko, en, SCOPE, GIF_DOCUMENT, section, GIF_URL)

    val all: List<ConformanceRule> = listOf(
        gif("gif89a.header", "헤더", "Header", "17. Header"),
        gif("gif89a.version", "버전과 사용 블록", "Version vs blocks used", "17. Header / Required Version of 23-26$DEFINITIONAL_SUFFIX"),
        gif("gif89a.version.earliest", "가능한 가장 이른 버전", "Earliest possible version", "17. Header, d. Recommendations ii"),
        gif("gif89a.lsd", "논리 화면 설명자", "Logical Screen Descriptor", "18. Logical Screen Descriptor"),
        gif("gif89a.lsd.background", "배경색 인덱스", "Background Color Index", "18. Logical Screen Descriptor vii"),
        gif("gif89a.blocks", "블록 문법", "Block grammar", "Appendix B. GIF Grammar / 15. Data Sub-blocks$DEFINITIONAL_SUFFIX"),
        gif("gif89a.gce", "그래픽 제어 확장", "Graphic Control Extension", "23. Graphic Control Extension"),
        gif("gif89a.extension", "응용·평문 확장 크기", "Application / Plain Text Extension size", "25. Plain Text Extension / 26. Application Extension"),
        gif("gif89a.reserved", "예약 필드·값", "Reserved fields and values", "Appendix D. Conventions (Reserved Fields) / 23. iv Disposal Method"),
        gif("gif89a.image.bounds", "이미지와 논리 화면 경계", "Image within the Logical Screen", "20. Image Descriptor"),
        gif("gif89a.lct.size", "로컬 색상표 크기 필드", "Size of Local Color Table field", "20. Image Descriptor ix"),
        gif("gif89a.lzw", "LZW 최소 코드 크기", "LZW Minimum Code Size", "22. Table Based Image Data / Appendix F$DEFINITIONAL_SUFFIX"),
        gif("gif89a.lzw.clear", "첫 코드 = Clear", "First code is Clear", "Appendix F. Variable-Length-Code LZW Compression"),
        gif("gif89a.trailer", "트레일러", "Trailer", "27. Trailer / Appendix B. GIF Grammar$DEFINITIONAL_SUFFIX"),
        gif("gif89a.trailing", "트레일러 이후 데이터", "Data after the Trailer", "Appendix C. Glossary (Data Stream)$DEFINITIONAL_SUFFIX"),
    )

    /** Violations of these rules are WARN (should / expected / data after the Trailer); all others FAIL. */
    val WARN_RULES = setOf(
        "gif89a.version.earliest", "gif89a.lsd.background", "gif89a.reserved", "gif89a.lct.size", "gif89a.lzw.clear", "gif89a.trailing",
    )

    internal val book = ImageRuleBook(all, WARN_RULES)

    fun rule(id: String): ConformanceRule = book.rule(id)
}
