package com.multiviewer.parser.conformance.heif

import com.multiviewer.parser.conformance.ConformanceRule
import com.multiviewer.parser.conformance.SpecCitation

/**
 * HEIF (ISO/IEC 23008-12) item-structure rule catalog. The standard is paid and was not checked against the
 * original text: clause numbers are public-material hints (they may differ between editions), citations carry no
 * page or anchor and are marked unverified.
 */
object HeifRules {
    const val HEIF_SOURCE = "ISO/IEC 23008-12 (공개 자료 기반, 원문 미대조)"

    /** Current edition (Edition 3, 2025-07). ISO's store page for it; 83650 is the withdrawn 2022 edition. */
    const val HEIF_DOCUMENT = "ISO/IEC 23008-12:2025"
    const val HEIF_URL = "https://www.iso.org/standard/89035.html"

    const val SCOPE_FTYP = "ftyp"
    const val SCOPE_META = "meta 구조 / meta structure"
    const val SCOPE_ITEMS = "항목 / Items"
    const val SCOPE_ILOC = "항목 위치 / Item locations"
    const val SCOPE_PROPS = "속성 / Properties"
    const val SCOPE_REFS = "참조 / References"
    const val SCOPE_DERIVED = "파생 항목 / Derived items"

    private class Def(val id: String, val ko: String, val en: String, val clause: String, val scope: String)

    private val defs = listOf(
        Def("heif.ftyp.mif1", "mif1/msf1 호환 브랜드", "mif1/msf1 compatible brand",
            "§10.2.1 (mif1 브랜드), §10.3.1 (msf1 브랜드)", SCOPE_FTYP),
        Def("heif.ftyp.codec_brand", "코덱 브랜드", "Codec brand",
            "§B (부속서 B: HEVC 이미지 브랜드 heic/heix/heim/heis)", SCOPE_FTYP),
        Def("heif.meta.present", "최상위 meta 1개", "Exactly one top-level meta",
            "§6 (파일 수준 MetaBox)", SCOPE_META),
        Def("heif.meta.required", "meta 필수·고유 하위 박스", "meta required and unique children",
            "§6 (MetaBox: hdlr 'pict', pitm, iinf, iloc)", SCOPE_META),
        Def("heif.items.ids", "항목 정보와 item_ID", "Item information and item_IDs",
            "§6 (항목 정보 iinf/infe, ISO/IEC 14496-12 §8.11.6)", SCOPE_ITEMS),
        Def("heif.items.primary", "주 항목", "Primary item",
            "§6 (주 항목 pitm, ISO/IEC 14496-12 §8.11.4)", SCOPE_ITEMS),
        Def("heif.iloc.items", "항목별 위치 항목", "Item location entries",
            "§6 (항목 위치 iloc, ISO/IEC 14496-12 §8.11.3)", SCOPE_ILOC),
        Def("heif.iloc.method", "구성 방법", "Construction method",
            "§6 (항목 위치 iloc, ISO/IEC 14496-12 §8.11.3)", SCOPE_ILOC),
        Def("heif.iloc.extents", "익스텐트 범위", "Extent ranges",
            "§6 (항목 위치 iloc, ISO/IEC 14496-12 §8.11.3)", SCOPE_ILOC),
        Def("heif.ipma.refs", "속성 연결 참조", "Property association references",
            "§6 (속성 연결 ipma, ISO/IEC 14496-12 §8.11.14)", SCOPE_PROPS),
        Def("heif.props.ispe", "이미지 크기 속성 ispe", "Image spatial extents (ispe)",
            "§6.5 (이미지 속성: ispe)", SCOPE_PROPS),
        Def("heif.props.codec_config", "디코더 구성 속성", "Decoder configuration property",
            "§6.5 및 코덱별 부속서 (hvcC 등 디코더 구성 속성)", SCOPE_PROPS),
        Def("heif.props.essential", "변환 속성 essential 표시", "Transformative properties marked essential",
            "§6.5 (변환 속성 irot/imir/clap)", SCOPE_PROPS),
        Def("heif.props.unique", "항목별 속성 중복", "At most one property of each kind per item",
            "§6.5 (이미지 속성: ispe/pixi/irot/imir/clap/colr)", SCOPE_PROPS),
        Def("heif.iref.targets", "항목 참조 대상", "Item reference targets",
            "§6 (항목 참조 iref: thmb/auxl/cdsc/dimg, ISO/IEC 14496-12 §8.11.12)", SCOPE_REFS),
        Def("heif.derived.grid", "grid 파생 이미지", "Grid derived image",
            "§6.6 (파생 이미지: grid)", SCOPE_DERIVED),
        Def("heif.derived.inputs", "파생 이미지 입력", "Derived image inputs",
            "§6.6 (파생 이미지: grid/iovl/iden/tmap 입력 dimg)", SCOPE_DERIVED),
    )

    val all: List<ConformanceRule> = defs.map { d ->
        ConformanceRule(
            id = d.id,
            title = "${d.ko} / ${d.en}",
            source = HEIF_SOURCE,
            reference = "ISO/IEC 23008-12 ${d.clause} (판에 따라 다를 수 있음)",
            scope = d.scope,
            citation = SpecCitation(HEIF_DOCUMENT, d.clause, null, HEIF_URL, false),
        )
    }

    private val byId = all.associateBy { it.id }

    fun rule(id: String): ConformanceRule = byId.getValue(id)
}
