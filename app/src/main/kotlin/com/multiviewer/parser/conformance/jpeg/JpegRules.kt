package com.multiviewer.parser.conformance.jpeg

import com.multiviewer.parser.conformance.ConformanceRule
import com.multiviewer.parser.conformance.DEFINITIONAL_SUFFIX
import com.multiviewer.parser.conformance.citedRule

/**
 * JPEG / JFIF / Exif / MPF rule catalog. Every rule was checked against the original PDF: [ConformanceRule.citation]
 * carries the physical PDF page (`#page=N`, 1-based) and the quoted sentence is listed in
 * docs/superpowers/specs/2026-10-24-jpeg-exif-conformance-design.md.
 */
object JpegRules {
    /** CCITT Rec. T.81 (09/1992), the ITU original as hosted by W3C (itu.int sells T.81; it is not a free download). */
    const val T81_DOCUMENT = "ITU-T T.81 (09/92)"
    const val T81_URL = "https://www.w3.org/Graphics/JPEG/itu-t81.pdf"
    const val T871_DOCUMENT = "ITU-T T.871 (05/2011)"
    const val T871_URL = "https://www.itu.int/rec/dologin_pub.asp?lang=e&id=T-REC-T.871-201105-I!!PDF-E&type=items"
    /** CIPA serves the PDF only by POST after accepting its terms: the link opens the download page; the page is of that PDF. */
    const val EXIF_DOCUMENT = "CIPA DC-008-Translation-2026 (Exif 3.1)"
    const val EXIF_URL = "https://www.cipa.jp/std/documents/download_e.html?CIPA_DC-008-2026-E"
    const val MPF_DOCUMENT = "CIPA DC-007-Translation-2026 (MPF)"
    const val MPF_URL = "https://www.cipa.jp/std/documents/download_e.html?CIPA_DC-007-2026_E"

    const val SCOPE_T81 = "JPEG 마커 구문 / JPEG marker syntax (T.81)"
    const val SCOPE_JFIF = "JFIF APP0 (T.871)"
    const val SCOPE_EXIF = "Exif APP1 (DC-008)"
    const val SCOPE_MPF = "MPF APP2 (DC-007)"

    private class Def(val id: String, val ko: String, val en: String, val doc: String, val base: String,
                      val section: String, val page: Int, val scope: String)

    private fun t81(id: String, ko: String, en: String, section: String, page: Int) = Def(id, ko, en, T81_DOCUMENT, T81_URL, section, page, SCOPE_T81)
    private fun jfif(id: String, ko: String, en: String, section: String, page: Int) = Def(id, ko, en, T871_DOCUMENT, T871_URL, section, page, SCOPE_JFIF)
    private fun exif(id: String, ko: String, en: String, section: String, page: Int) = Def(id, ko, en, EXIF_DOCUMENT, EXIF_URL, section, page, SCOPE_EXIF)
    private fun mpf(id: String, ko: String, en: String, section: String, page: Int) = Def(id, ko, en, MPF_DOCUMENT, MPF_URL, section, page, SCOPE_MPF)

    private val defs = listOf(
        t81("t81.soi", "SOI로 시작", "Starts with SOI", "B.2.1 High-level syntax", 38),
        t81("t81.marker", "마커 코드", "Marker codes", "B.1.1.3 Marker assignments", 35),
        t81("t81.marker.reserved", "예약 마커", "Reserved markers", "B.1.1.3 Marker assignments, Table B.1$DEFINITIONAL_SUFFIX", 36),
        t81("t81.segment.length", "세그먼트 길이", "Marker segment length", "B.1.1.4 Marker segments$DEFINITIONAL_SUFFIX", 37),
        t81("t81.frame.structure", "프레임·스캔 구성", "Frame and scan structure", "B.2.1 High-level syntax", 39),
        t81("t81.frame.header", "프레임 헤더", "Frame header (SOFn)", "B.2.2 Frame header syntax, Table B.2", 40),
        t81("t81.scan.header", "스캔 헤더", "Scan header (SOS)", "B.2.3 Scan header syntax, Table B.3", 41),
        t81("t81.dqt", "양자화 테이블 정의", "Quantization table specification (DQT)", "B.2.4.1 Quantization table-specification syntax, Table B.4", 44),
        t81("t81.dht", "허프만 테이블 정의", "Huffman table specification (DHT)", "B.2.4.2 Huffman table-specification syntax, Table B.5$DEFINITIONAL_SUFFIX", 45),
        t81("t81.tables", "테이블 참조", "Table references", "B.2.1 High-level syntax", 39),
        t81("t81.restart", "재시작 간격·마커", "Restart interval and markers", "B.2.1 High-level syntax", 39),
        t81("t81.eoi", "EOI로 끝남", "Ends with EOI", "B.2.1 High-level syntax", 38),
        t81("t81.trailing", "EOI 이후 데이터", "Data after EOI", "B.2.1 High-level syntax$DEFINITIONAL_SUFFIX", 38),

        jfif("jfif.position", "JFIF APP0 위치", "JFIF APP0 position", "6.3 JFIF APP0 marker segment", 9),
        jfif("jfif.fields", "JFIF APP0 필드", "JFIF APP0 fields", "10.1 JFIF file syntax", 12),
        jfif("jfif.version", "JFIF 버전", "JFIF version", "10.1 JFIF file syntax (version)", 12),
        jfif("jfif.frame", "JFIF 프레임 제약", "JFIF frame constraints", "6.1 JPEG compression", 8),
        jfif("jfif.extension", "JFIF 확장 APP0", "JFIF extension APP0 (JFXX)", "6.4 APP0 marker used to specify JFIF extensions", 9),
        jfif("jfif.app0", "응용 APP0 식별자", "Application-specific APP0 identifiers", "6.5 Application marker segments used for application-specific information", 9),

        exif("exif.app1.position", "Exif APP1 위치", "Exif APP1 position", "4.7.2.1 Order of APP1 recording", 116),
        exif("exif.tiff.header", "TIFF 헤더", "TIFF header", "4.5.2 Table 1 TIFF Headers", 31),
        exif("exif.ifd.structure", "IFD 구조·오프셋", "IFD structure and offsets", "4.6.2 IFD Structure$DEFINITIONAL_SUFFIX", 38),
        exif("exif.ifd.order", "태그 오름차순", "Tags in ascending order", "4.6.2 IFD Structure", 39),
        exif("exif.tag.type", "태그 Type·Count", "Tag Type and Count", "4.6.2 IFD Structure (Type) / 4.6.3-4.6.8 tag definitions$DEFINITIONAL_SUFFIX", 38),
        exif("exif.tag.ascii", "ASCII·UTF-8 값", "ASCII and UTF-8 values", "4.6.2 IFD Structure (Type)", 38),
        exif("exif.tags.mandatory", "필수 태그", "Mandatory tags", "4.6.9 Tag Support Levels, Table 17-21 (M)", 104),
        exif("exif.tags.not_allowed", "기록 금지 태그", "Tags not allowed", "4.6.9 Tag Support Levels, Table 17-21 (N, J)", 105),
        exif("exif.tags.recommended", "권장 태그", "Recommended tags", "4.6.9 Tag Support Levels, Table 17-21 (R)", 105),
        exif("exif.dimensions", "유효 이미지 크기", "Valid image dimensions", "4.6.6.3.1 PixelXDimension", 59),
        exif("exif.thumbnail", "압축 썸네일", "Compressed thumbnail", "4.5.8 Basic Structure of Thumbnail Data", 37),

        mpf("mpf.count", "MP 확장 APP2 개수", "One MP Extensions APP2", "5.2.1 The MP Format Identifier$DEFINITIONAL_SUFFIX", 15),
        mpf("mpf.header", "MP 헤더", "MP header", "5.2.1 The MP Format Identifier", 14),
        mpf("mpf.endian", "MP 바이트 순서", "MP byte order", "5.2.2.1 MP Endian", 15),
        mpf("mpf.index", "MP Index IFD 필수 태그", "MP Index IFD mandatory tags", "6.1 Baseline MP File (Table 6)", 34),
        mpf("mpf.index.order", "MP Index IFD 태그 순서", "MP Index IFD tag order", "5.2.3 MP Index IFD", 15),
        mpf("mpf.version", "MP 형식 버전", "MP Format Version", "5.2.3.1 MP Format Version", 16),
        mpf("mpf.entries", "MP Entry 개수·첫 오프셋", "MP Entry count and first offset", "5.2.3.3 MP Entry", 17),
        mpf("mpf.images", "개별 이미지 위치", "Individual image locations", "5.2.3.3.2-5.2.3.3.3 Individual Image Size / Data Offset$DEFINITIONAL_SUFFIX", 20),
        mpf("mpf.type", "MP 유형 코드", "MP Type Code", "5.2.3.3.1 Individual Image Attribute, Table 4 MP Types", 19),
    )

    val all: List<ConformanceRule> = defs.map { d ->
        citedRule(d.id, "${d.ko} / ${d.en}", d.scope, d.doc, d.section, d.page, "${d.base}#page=${d.page}")
    }

    /** jfif.version is FAIL-level but reports the earlier JFIF editions 1.00 / 1.01 as WARN (user decision 2026-10-25). */
    val LEGACY_JFIF_VERSIONS = setOf(0x0100, 0x0101)

    /** Rules whose violation is WARN (recommendation / unexplained data); every other rule FAILs. */
    val WARN_RULES = setOf("t81.marker.reserved", "t81.trailing", "exif.tags.recommended", "mpf.endian")

    private val byId = all.associateBy { it.id }

    fun rule(id: String): ConformanceRule = byId.getValue(id)
}
