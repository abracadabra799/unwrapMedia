package com.multiviewer.parser.conformance.heif

import com.multiviewer.parser.conformance.ConformanceFinding
import com.multiviewer.parser.conformance.ConformanceRule
import com.multiviewer.parser.conformance.ConformanceStatus
import com.multiviewer.parser.conformance.ConformanceStatus.FAIL
import com.multiviewer.parser.conformance.ConformanceStatus.NOT_APPLICABLE
import com.multiviewer.parser.conformance.ConformanceStatus.NOT_CHECKED
import com.multiviewer.parser.conformance.ConformanceStatus.PASS
import com.multiviewer.parser.conformance.ConformanceStatus.WARN
import com.multiviewer.parser.conformance.SpecCitation

/**
 * AVIF v1.2.0 (AOMedia) rule catalog. The specification is public; every section and anchor was checked against
 * https://aomediacodec.github.io/av1-avif/v1.2.0.html, so citations are marked verified.
 */
object AvifRules {
    const val AVIF_SOURCE = "AVIF v1.2.0 (AOMedia)"
    const val AVIF_DOCUMENT = "AVIF v1.2.0 (AOMedia, 2025-10-16)"
    const val AVIF_URL = "https://aomediacodec.github.io/av1-avif/v1.2.0.html"
    const val SCOPE_AVIF = "AVIF"

    fun citation(section: String, anchor: String) = SpecCitation(AVIF_DOCUMENT, section, null, "$AVIF_URL#$anchor", true)

    private class Def(val id: String, val ko: String, val en: String, val ref: String, val section: String, val anchor: String)

    private val defs = listOf(
        Def("avif.ftyp.miaf", "miaf 브랜드", "miaf brand",
            "§7 (General constraints: 'miaf' 나열)", "§7 General constraints", "file-constraints"),
        Def("avif.ftyp.avif", "avif/avis 브랜드", "avif/avis brand",
            "§7 (General constraints: 'avif' 또는 'avis' 나열)", "§7 General constraints", "file-constraints"),
        Def("avif.primary", "avif 브랜드의 주 항목", "Primary item under the avif brand",
            "§6.2 (AVIF image and image collection brand)", "§6.2 AVIF image and image collection brand",
            "image-and-image-collection-brand"),
        Def("avif.props.av1c_essential", "av1C essential 표시", "av1C marked essential",
            "§2.2.1 (AV1 Item Configuration Property)", "§2.2.1 AV1 Item Configuration Property", "av1-item-configuration-property"),
        Def("avif.props.pixi", "AV1 항목의 pixi", "pixi on AV1 image items",
            "§9.1.1 (Minimum set of boxes 표)", "§9.1.1 Minimum set of boxes", "avif-required-boxes"),
        Def("avif.props.pasp", "pasp 1:1", "pasp is 1:1",
            "§9.1.2 (추가 박스 표: pasp)", "§9.1.2 Requirements on additional image item related boxes",
            "avif-required-boxes-additional"),
        Def("avif.props.a1op", "a1op essential 표시", "a1op marked essential",
            "§2.3.2.1.2 (Operating Point Selector Property)", "§2.3.2.1 Operating Point Selector Property",
            "operating-point-selector-property"),
        Def("avif.props.a1lx", "a1lx essential 아님", "a1lx not marked essential",
            "§2.3.2.3.2 (Layered Image Indexing Property)", "§2.3.2.3 Layered Image Indexing Property",
            "layered-image-indexing-property"),
        Def("avif.alpha.auxc", "알파 보조 이미지 auxC", "Alpha auxiliary image auxC",
            "§4.1 (Auxiliary Image Items and Sequences)", "§4.1 Auxiliary Image Items and Sequences", "auxiliary-images"),
    )

    val all: List<ConformanceRule> = defs.map { d ->
        ConformanceRule(
            id = d.id,
            title = "${d.ko} / ${d.en}",
            source = AVIF_SOURCE,
            reference = "AVIF v1.2.0 ${d.ref}",
            scope = SCOPE_AVIF,
            citation = citation(d.section, d.anchor),
        )
    }

    private val byId = all.associateBy { it.id }

    fun rule(id: String): ConformanceRule = byId.getValue(id)
}

/**
 * AVIF v1.2.0 item rules. Applied only when the report format is AVIF; for HEIF files every rule is NOT_APPLICABLE.
 * Levels follow the specification wording: "shall" → FAIL, "should" → WARN.
 */
internal object HeifAvifRules {
    private const val MAX_LISTED = 100
    private const val MAX_URN_BYTES = 1024

    const val ALPHA_URN = "urn:mpeg:mpegB:cicp:systems:auxiliary:alpha"
    const val DEPTH_URN = "urn:mpeg:mpegB:cicp:systems:auxiliary:depth"
    /** HEVC alpha aux type (ISO/IEC 23008-2 SEI auxId 1), accepted as alpha. */
    const val HEVC_ALPHA_URN = "urn:mpeg:hevc:2015:auxid:1"
    private const val CICP_AUX_PREFIX = "urn:mpeg:mpegb:cicp:systems:auxiliary:"

    /** Derived image item types followed through `dimg` for §6.2 (HEIF derived images plus AVIF §4.2.3 'sato'). */
    private val AVIF_DERIVED = DERIVED_TYPES + "sato"

    fun evaluate(ctx: HeifContext, avif: Boolean): List<ConformanceFinding> {
        if (!avif) return AvifRules.all.map {
            ConformanceFinding(it, NOT_APPLICABLE, listOf("AVIF 파일이 아님 (HEIF) — AVIF 규칙 적용 안 함"), emptyList())
        }
        val p = HeifPropertyRules.Props(ctx)
        return listOf(
            ftypMiaf(ctx), ftypAvif(ctx), primary(ctx, p), av1cEssential(ctx, p), pixi(ctx, p), pasp(ctx, p),
            a1op(ctx, p), a1lx(ctx, p), alphaAuxC(ctx, p),
        )
    }

    private fun f(id: String, status: ConformanceStatus, details: List<String>, ranges: List<LongRange> = emptyList()) =
        ConformanceFinding(AvifRules.rule(id), status, details, ranges)

    private fun <T> List<T>.capped(): List<T> = take(MAX_LISTED)
    private fun moreLine(total: Int): List<String> = if (total > MAX_LISTED) listOf("… 외 ${total - MAX_LISTED}건") else emptyList()

    private fun result(
        id: String, fails: List<String>, warns: List<String>, unchecked: List<String>, ranges: List<LongRange>, pass: String,
    ): ConformanceFinding {
        val r = ranges.distinct().capped()
        return when {
            fails.isNotEmpty() -> f(id, FAIL, fails.capped() + moreLine(fails.size) + warns.capped() + unchecked.capped(), r)
            warns.isNotEmpty() -> f(id, WARN, warns.capped() + moreLine(warns.size) + unchecked.capped(), r)
            unchecked.isNotEmpty() -> f(id, NOT_CHECKED, unchecked.capped() + moreLine(unchecked.size))
            else -> f(id, PASS, listOf(pass))
        }
    }

    private fun brandSummary(b: HeifContext.Brands) =
        "major '${b.major}', compatible [${b.compatible.take(16).joinToString(", ") { "'$it'" }}]"

    // ---- ftyp (§7) ----

    private fun ftypMiaf(ctx: HeifContext): ConformanceFinding {
        val id = "avif.ftyp.miaf"
        val b = ctx.brands ?: return f(id, NOT_CHECKED, listOf("읽을 수 있는 ftyp 없음"))
        val ftyp = ctx.tree.top.firstOrNull { it.type == "ftyp" }
        if ("miaf" in b.all) return f(id, PASS, listOf("ftyp에 'miaf' 있음", brandSummary(b)))
        return f(id, FAIL, listOf("ftyp에 'miaf' 없음 (§7: MIAF를 준수하고 'miaf'를 나열해야 함)", brandSummary(b)),
            listOfNotNull(ftyp?.let(ctx::range)))
    }

    private fun ftypAvif(ctx: HeifContext): ConformanceFinding {
        val id = "avif.ftyp.avif"
        val b = ctx.brands ?: return f(id, NOT_CHECKED, listOf("읽을 수 있는 ftyp 없음"))
        val ftyp = ctx.tree.top.firstOrNull { it.type == "ftyp" }
        val hit = b.all.filter { it == "avif" || it == "avis" }
        if (hit.isNotEmpty()) return f(id, PASS, listOf("ftyp에 ${hit.sorted().joinToString("/") { "'$it'" }} 있음", brandSummary(b)))
        return f(id, FAIL, listOf("AV1 주 영상인데 ftyp에 'avif'/'avis' 없음 (§7: 둘 중 하나를 나열해야 함)", brandSummary(b)),
            listOfNotNull(ftyp?.let(ctx::range)))
    }

    // ---- primary item (§6.2) ----

    private fun primary(ctx: HeifContext, p: HeifPropertyRules.Props): ConformanceFinding {
        val id = "avif.primary"
        val b = ctx.brands ?: return f(id, NOT_CHECKED, listOf("읽을 수 있는 ftyp 없음"))
        if ("avif" !in b.all) return f(id, NOT_APPLICABLE, listOf("'avif' 브랜드 없음 — §6.2는 'avif' 브랜드 파일에 적용", brandSummary(b)))
        val primaryId = ctx.model.primaryId ?: return f(id, NOT_CHECKED, listOf("주 항목(pitm)을 읽지 못함"))
        ctx.capped("iinf").takeIf { it.isNotEmpty() }?.let { return f(id, NOT_CHECKED, it) }
        if (p.iinf == null || ctx.model.iinfEntryCount == null) return f(id, NOT_CHECKED, listOf("iinf를 읽지 못함"))
        val brokenRefs = (p.refsIncomplete + (p.iref?.let { box ->
            HeifPropertyRules.problemsWithin(ctx, box).filter(::isHeifUnreadableProblem)
        }.orEmpty())).distinct()

        val fails = ArrayList<String>()
        val unchecked = ArrayList<String>()
        val ranges = ArrayList<LongRange>()
        val seen = HashSet<Long>()
        val queue = ArrayDeque(listOf(primaryId))
        var av1 = 0
        var derivedSeen = false
        while (queue.isNotEmpty()) {
            if (seen.size >= HeifContext.MAX_DERIVATION_WALK) { unchecked += "파생 체인이 너무 깊음 (${HeifContext.MAX_DERIVATION_WALK}개 초과) — 일부 미검사"; break }
            val itemId = queue.removeFirst()
            if (!seen.add(itemId)) continue
            val item = ctx.itemsById[itemId]?.firstOrNull()
            if (item == null) {
                if (p.itemsIncomplete.isEmpty()) fails += "item_ID $itemId 이(가) iinf에 선언되지 않음 (AV1 이미지 항목이 아님)"
                else unchecked += "item_ID $itemId: iinf를 끝까지 읽지 못해 유형 미확인"
                continue
            }
            val where = if (itemId == primaryId) "주 항목 $itemId" else "주 항목 $primaryId 의 입력 item_ID $itemId"
            when (item.type) {
                "av01" -> av1++
                in AVIF_DERIVED -> {
                    derivedSeen = true
                    val inputs = p.dimg(itemId)
                    if (inputs.isEmpty()) {
                        if (brokenRefs.isEmpty()) { fails += "$where ('${item.type}'): dimg 입력 없음 — AV1 이미지 항목을 참조하지 않음"; ranges += ctx.range(item.infe) }
                    } else queue.addAll(inputs)
                }
                in CODED_IMAGE_TYPES, in METADATA_TYPES -> {
                    fails += "$where: 유형 '${item.type}' (AV1 이미지 항목 'av01'이어야 함)"; ranges += ctx.range(item.infe)
                }
                else -> unchecked += "$where: 알 수 없는 유형 '${item.type.ifEmpty { "(없음)" }}' — AV1 여부 미확인"
            }
        }
        if (derivedSeen && brokenRefs.isNotEmpty())
            unchecked += listOf("iref를 끝까지 읽지 못해 파생 입력 일부 미검사") + brokenRefs.capped()
        val pass = if (derivedSeen) "주 항목 $primaryId: 파생 이미지, 참조하는 부호화 항목 ${av1}개가 모두 'av01'"
            else "주 항목 $primaryId: 'av01' AV1 이미지 항목"
        if (fails.isEmpty() && unchecked.isEmpty() && av1 == 0) return f(id, NOT_CHECKED, listOf("주 항목 $primaryId 에서 부호화 항목을 찾지 못함"))
        return result(id, fails, emptyList(), unchecked, ranges, pass)
    }

    // ---- properties ----

    private fun av01Items(p: HeifPropertyRules.Props) = p.items.filter { it.type == "av01" }

    private fun av1cEssential(ctx: HeifContext, p: HeifPropertyRules.Props): ConformanceFinding {
        val id = "avif.props.av1c_essential"
        HeifPropertyRules.propsUnavailable(ctx, p) { s, d -> f(id, s, d) }?.let { return it }
        val items = av01Items(p)
        if (items.isEmpty()) return f(id, NOT_APPLICABLE, listOf("av01 항목 없음"))
        val warns = ArrayList<String>()
        val unchecked = ArrayList<String>()
        val ranges = ArrayList<LongRange>()
        for (item in items) {
            val configs = p.byItem[item.id].orEmpty().filter { it.first.type == "av1C" }
            when {
                configs.isEmpty() -> unchecked += "item_ID ${item.id}: av1C 없음 — essential 여부 미검사 " +
                    "(av1C 누락은 heif.props.codec_config 에서 FAIL로 보고)"
                configs.any { !it.second } -> {
                    warns += "item_ID ${item.id}: av1C (속성 ${configs.first { !it.second }.first.index}) 이(가) essential이 아님 (§2.2.1: essential로 표시해야 함(should))"
                    ranges += configs.filter { !it.second }.map { ctx.range(it.first.box) }
                }
            }
        }
        return result(id, emptyList(), warns, unchecked, ranges, "av01 항목 ${items.size}개의 av1C가 모두 essential")
    }

    private fun pixi(ctx: HeifContext, p: HeifPropertyRules.Props): ConformanceFinding {
        val id = "avif.props.pixi"
        HeifPropertyRules.propsUnavailable(ctx, p) { s, d -> f(id, s, d) }?.let { return it }
        val items = av01Items(p)
        if (items.isEmpty()) return f(id, NOT_APPLICABLE, listOf("av01 항목 없음"))
        val fails = ArrayList<String>()
        val ranges = ArrayList<LongRange>()
        for (item in items) if (p.props(item.id, "pixi").isEmpty()) {
            fails += "item_ID ${item.id} ('av01'): pixi 속성 없음 (§9.1.1 최소 박스 집합)"; ranges += ctx.range(item.infe)
        }
        return result(id, fails, emptyList(), emptyList(), ranges, "av01 항목 ${items.size}개가 모두 pixi를 가짐")
    }

    private fun pasp(ctx: HeifContext, p: HeifPropertyRules.Props): ConformanceFinding {
        val id = "avif.props.pasp"
        HeifPropertyRules.propsUnavailable(ctx, p) { s, d -> f(id, s, d) }?.let { return it }
        val boxes = p.m.properties.filter { it.type == "pasp" }
        if (boxes.isEmpty()) return f(id, NOT_APPLICABLE, listOf("pasp 속성 없음"))
        val fails = ArrayList<String>()
        val ranges = ArrayList<LongRange>()
        for (prop in boxes) {
            val b = prop.box
            if (b.payload + 8 > minOf(b.end, ctx.length)) {
                fails += "${at(b)} (속성 ${prop.index}): pasp 잘림 (hSpacing/vSpacing 8바이트 필요)"; ranges += ctx.range(b); continue
            }
            val h = ctx.reader.readUInt32(b.payload)
            val v = ctx.reader.readUInt32(b.payload + 4)
            if (h != v || h == 0L) { fails += "${at(b)} (속성 ${prop.index}): 화소 종횡비 $h:$v (1:1이어야 함)"; ranges += ctx.range(b) }
        }
        return result(id, fails, emptyList(), emptyList(), ranges, "pasp ${boxes.size}개 모두 1:1")
    }

    private fun essentialRule(
        ctx: HeifContext, p: HeifPropertyRules.Props, id: String, type: String, mustBeEssential: Boolean,
    ): ConformanceFinding {
        HeifPropertyRules.propsUnavailable(ctx, p) { s, d -> f(id, s, d) }?.let { return it }
        val fails = ArrayList<String>()
        val ranges = ArrayList<LongRange>()
        var seen = 0
        for ((itemId, list) in p.byItem) for ((prop, essential) in list) {
            if (prop.type != type) continue
            seen++
            if (essential != mustBeEssential) {
                fails += "item_ID $itemId: $type (속성 ${prop.index}) 이(가) " +
                    if (mustBeEssential) "essential이 아님 (essential이어야 함)" else "essential로 표시됨 (essential이면 안 됨)"
                ranges += ctx.range(prop.box)
            }
        }
        if (seen == 0) return f(id, NOT_APPLICABLE, listOf("연결된 $type 속성 없음"))
        return result(id, fails, emptyList(), emptyList(), ranges,
            "$type 연결 ${seen}개 모두 " + if (mustBeEssential) "essential" else "essential 아님")
    }

    private fun a1op(ctx: HeifContext, p: HeifPropertyRules.Props) = essentialRule(ctx, p, "avif.props.a1op", "a1op", true)
    private fun a1lx(ctx: HeifContext, p: HeifPropertyRules.Props) = essentialRule(ctx, p, "avif.props.a1lx", "a1lx", false)

    // ---- auxiliary images (§4.1) ----

    /** aux_type of an auxC property (null-terminated string after version/flags), or null when unreadable. */
    private fun auxType(ctx: HeifContext, prop: HeifProperty): String? {
        val b = prop.box
        val end = minOf(b.end, ctx.length)
        val start = b.payload + 4
        if (start >= end) return null
        val n = minOf(end - start, MAX_URN_BYTES.toLong()).toInt()
        val bytes = ctx.reader.readBytes(start, n)
        val nul = bytes.indexOf(0)
        return String(bytes, 0, if (nul >= 0) nul else bytes.size, Charsets.UTF_8)
    }

    private fun editDistance(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length) cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            prev = cur
        }
        return prev[b.length]
    }

    /**
     * A URN under the CICP auxiliary prefix that is not exactly the alpha/depth URN but looks like a misspelt one:
     * a case/whitespace variant or within edit distance 3. Any other suffix is another auxiliary type (not alpha).
     */
    private fun nearMissAlpha(urn: String): Boolean {
        val lower = urn.trim().lowercase()
        if (!lower.startsWith(CICP_AUX_PREFIX)) return false
        return listOf(ALPHA_URN, DEPTH_URN).map { it.lowercase() }.any { lower == it || editDistance(lower, it) <= 3 }
    }

    private fun alphaAuxC(ctx: HeifContext, p: HeifPropertyRules.Props): ConformanceFinding {
        val id = "avif.alpha.auxc"
        HeifPropertyRules.propsUnavailable(ctx, p) { s, d -> f(id, s, d) }?.let { return it }
        val brokenRefs = (p.refsIncomplete + (p.iref?.let { box ->
            HeifPropertyRules.problemsWithin(ctx, box).filter(::isHeifUnreadableProblem)
        }.orEmpty())).distinct()
        val auxItems = p.m.references.filter { it.type == "auxl" }.map { it.from }.distinct()
        if (auxItems.isEmpty()) {
            if (brokenRefs.isNotEmpty()) return f(id, NOT_CHECKED, listOf("iref를 끝까지 읽지 못해 auxl 참조 여부 미확인") + brokenRefs.capped())
            return f(id, NOT_APPLICABLE, listOf("auxl 참조 없음 (보조 이미지 없음)"))
        }
        val warns = ArrayList<String>()
        val unchecked = ArrayList<String>()
        val ranges = ArrayList<LongRange>()
        val seen = ArrayList<String>()
        if (brokenRefs.isNotEmpty()) unchecked += listOf("iref를 끝까지 읽지 못해 일부 auxl 참조 미검사") + brokenRefs.capped()
        for (itemId in auxItems) {
            val props = p.props(itemId, "auxC")
            if (props.isEmpty()) { unchecked += "보조 item_ID $itemId: auxC 없음 (heif.iref.targets 참조)"; continue }
            for (prop in props) {
                val urn = auxType(ctx, prop)
                when {
                    urn == null -> unchecked += "보조 item_ID $itemId: ${at(prop.box)} aux_type을 읽지 못함"
                    urn == ALPHA_URN -> seen += "item_ID $itemId: alpha ($ALPHA_URN)"
                    urn == HEVC_ALPHA_URN -> seen += "item_ID $itemId: alpha (HEVC URN $HEVC_ALPHA_URN, 허용)"
                    urn == DEPTH_URN -> seen += "item_ID $itemId: depth ($DEPTH_URN)"
                    nearMissAlpha(urn) -> {
                        warns += "보조 item_ID $itemId: aux_type '$urn' — alpha/depth URN과 비슷하지만 일치하지 않음. " +
                            "§4.1의 알파 보조 이미지는 aux_type이 정확히 '$ALPHA_URN' 이어야 하므로 리더는 알파로 처리하지 않음 (오타 의심)"
                        ranges += ctx.range(prop.box)
                    }
                    else -> seen += "item_ID $itemId: 알파가 아닌 보조 이미지 ('$urn')"
                }
            }
        }
        return result(id, emptyList(), warns, unchecked, ranges, "보조 이미지 ${auxItems.size}개: " + seen.take(10).joinToString("; "))
    }
}
