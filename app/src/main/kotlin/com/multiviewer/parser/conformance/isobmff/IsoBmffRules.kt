package com.multiviewer.parser.conformance.isobmff

import com.multiviewer.parser.conformance.ConformanceRule

enum class BmffProfile { ISO, QUICKTIME }

/** [reference] is the ISO/IEC 14496-12 section hint; QuickTime rows use the QTFF chapter from [IsoBmffRules]. */
data class BmffRuleDef(val id: String, val titleKo: String, val titleEn: String, val reference: String, val scope: String)

/** ISO-BMFF / QuickTime container rule catalog (public material, not checked against the standard text). */
object IsoBmffRules {
    const val ISO_SOURCE = "ISO/IEC 14496-12 (공개 자료 기반, 원문 미대조)"
    const val QT_SOURCE = "QuickTime File Format (공개 자료 기반, 원문 미대조)"

    const val SCOPE_STRUCTURE = "박스 구조 / Box structure"
    const val SCOPE_FTYP = "ftyp"
    const val SCOPE_REQUIRED = "필수 박스 / Required boxes"
    const val SCOPE_HEADER = "헤더 필드 / Header fields"
    const val SCOPE_SAMPLES = "샘플 테이블 / Sample tables"
    const val SCOPE_EDITS = "편집 목록 / Edit lists"

    private const val QT_OVERVIEW = "QTFF: Overview of QTFF"
    private const val QT_FTYP = "QTFF: File Type Compatibility Atom"
    private const val QT_MOVIE = "QTFF: Movie Atoms"

    private fun iso(section: String) = "ISO/IEC 14496-12 $section, 판에 따라 다를 수 있음"

    private class Entry(val def: BmffRuleDef, val qtReference: String)

    private fun r(id: String, ko: String, en: String, section: String, scope: String, qt: String = QT_MOVIE) =
        Entry(BmffRuleDef(id, ko, en, iso(section), scope), qt)

    private val entries = listOf(
        // Box structure
        r("bmff.box.size", "박스 크기", "Box size", "§4.2 (box)", SCOPE_STRUCTURE, QT_OVERVIEW),
        r("bmff.box.size0", "크기 0 박스 위치", "Size-0 box placement", "§4.2 (box)", SCOPE_STRUCTURE, QT_OVERVIEW),
        r("bmff.box.trailing", "마지막 박스 뒤 잔여 바이트", "Trailing bytes after last box", "§4.2 (box)", SCOPE_STRUCTURE, QT_OVERVIEW),
        r("bmff.box.parent", "허용된 상위 박스", "Allowed parent box", "§8 (box Container 정의)", SCOPE_STRUCTURE),
        // ftyp
        r("bmff.ftyp.present", "ftyp 존재", "ftyp present", "§4.3 (ftyp)", SCOPE_FTYP, QT_FTYP),
        r("bmff.ftyp.position", "ftyp 위치", "ftyp position", "§4.3 (ftyp)", SCOPE_FTYP, QT_FTYP),
        r("bmff.ftyp.brands", "ftyp 브랜드", "ftyp brands", "§4.3 (ftyp)", SCOPE_FTYP, QT_FTYP),
        // Required boxes
        r("bmff.moov.count", "moov 개수", "moov count", "§8.2.1 (moov)", SCOPE_REQUIRED),
        r("bmff.mvhd.count", "mvhd 개수", "mvhd count", "§8.2.2 (mvhd)", SCOPE_REQUIRED),
        r("bmff.trak.count", "trak 개수", "trak count", "§8.3.1 (trak)", SCOPE_REQUIRED),
        r("bmff.trak.required", "트랙 필수 박스", "Track required boxes", "§8.3.1 (trak), §8.4-8.7", SCOPE_REQUIRED),
        r("bmff.mediaheader.match", "미디어 헤더와 핸들러 일치", "Media header matches handler",
            "§8.4.5 (minf 미디어 헤더), §12.1.2/12.2.2/12.4.2/12.6.2, §8.4.5.2 (nmhd)", SCOPE_REQUIRED),
        // Header fields
        r("bmff.fullbox.version", "FullBox 버전", "FullBox version", "§4.2 (FullBox), §8.2.2/8.3.2/8.4.2/8.6.5", SCOPE_HEADER),
        r("bmff.mvhd.timescale", "mvhd timescale", "mvhd timescale", "§8.2.2 (mvhd)", SCOPE_HEADER),
        r("bmff.mdhd.timescale", "mdhd timescale", "mdhd timescale", "§8.4.2 (mdhd)", SCOPE_HEADER),
        r("bmff.tkhd.track_id", "tkhd track_ID", "tkhd track_ID", "§8.3.2 (tkhd)", SCOPE_HEADER),
        r("bmff.mvhd.next_track_id", "mvhd next_track_ID", "mvhd next_track_ID", "§8.2.2 (mvhd)", SCOPE_HEADER),
        r("bmff.vmhd.flags", "vmhd flags", "vmhd flags", "§12.1.2 (vmhd)", SCOPE_HEADER),
        r("bmff.hdlr.predefined", "hdlr pre_defined / 구성요소 유형", "hdlr pre_defined / component type", "§8.4.3 (hdlr)", SCOPE_HEADER),
        r("bmff.mdhd.language", "mdhd 언어 코드", "mdhd language", "§8.4.2 (mdhd)", SCOPE_HEADER),
        r("bmff.dref.entries", "dref 항목", "dref entries", "§8.7.2 (dref)", SCOPE_HEADER),
        r("bmff.stsd.entries", "stsd 샘플 엔트리", "stsd sample entries", "§8.5.2 (stsd)", SCOPE_HEADER),
        // Sample tables
        r("bmff.stbl.sample_count", "샘플 수 일치", "Sample count match", "§8.6.1.2 (stts), §8.7.3 (stsz)", SCOPE_SAMPLES),
        r("bmff.stsc.order", "stsc 항목 순서", "stsc entry order", "§8.7.4 (stsc)", SCOPE_SAMPLES),
        r("bmff.stsc.coverage", "stsc 샘플 범위", "stsc sample coverage", "§8.7.4 (stsc), §8.7.5 (stco)", SCOPE_SAMPLES),
        r("bmff.ctts.count", "ctts 샘플 수", "ctts sample count", "§8.6.1.3 (ctts)", SCOPE_SAMPLES),
        r("bmff.stss.order", "stss 순서", "stss order", "§8.6.2 (stss)", SCOPE_SAMPLES),
        r("bmff.sample.location", "샘플 위치", "Sample location", "§8.7.5 (stco), §8.1.1 (mdat)", SCOPE_SAMPLES),
        r("bmff.mdat.present", "mdat 존재", "mdat present", "§8.1.1 (mdat)", SCOPE_SAMPLES),
        r("bmff.duration.consistency", "트랙 길이 일치", "Duration consistency", "§8.3.2 (tkhd), §8.4.2 (mdhd)", SCOPE_SAMPLES),
        r("bmff.fragmented", "조각화 파일", "Fragmented file", "§8.8.1 (mvex)", SCOPE_SAMPLES),
        // Edit lists
        r("bmff.elst.entries", "elst 항목", "elst entries", "§8.6.5 (edts), §8.6.6 (elst)", SCOPE_EDITS),
    )

    private val byId = entries.associateBy { it.def.id }

    val all: List<BmffRuleDef> = entries.map { it.def }

    fun def(id: String): BmffRuleDef = byId.getValue(id).def

    fun rule(def: BmffRuleDef, profile: BmffProfile): ConformanceRule = when (profile) {
        BmffProfile.ISO -> ConformanceRule(def.id, "${def.titleKo} / ${def.titleEn}", ISO_SOURCE, def.reference, def.scope)
        BmffProfile.QUICKTIME -> ConformanceRule(def.id, "${def.titleKo} / ${def.titleEn}", QT_SOURCE,
            byId[def.id]?.qtReference ?: QT_MOVIE, def.scope)
    }

    fun rule(id: String, profile: BmffProfile): ConformanceRule = rule(def(id), profile)
}
