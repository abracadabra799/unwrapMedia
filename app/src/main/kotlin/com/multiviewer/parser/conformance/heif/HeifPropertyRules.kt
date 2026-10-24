package com.multiviewer.parser.conformance.heif

import com.multiviewer.parser.conformance.ConformanceFinding
import com.multiviewer.parser.conformance.ConformanceRule
import com.multiviewer.parser.conformance.ConformanceStatus
import com.multiviewer.parser.conformance.ConformanceStatus.FAIL
import com.multiviewer.parser.conformance.ConformanceStatus.NOT_APPLICABLE
import com.multiviewer.parser.conformance.ConformanceStatus.NOT_CHECKED
import com.multiviewer.parser.conformance.ConformanceStatus.PASS
import com.multiviewer.parser.conformance.ConformanceStatus.WARN
import com.multiviewer.parser.conformance.isobmff.BmffBox

/**
 * Item property, item reference and derived-image rules. Phase 1 reads only the property boxes, the reference
 * lists and the grid descriptor bytes; coded item data is never opened. Whenever a table could not be read completely
 * an absence-based conclusion becomes NOT_CHECKED, never PASS.
 */
internal object HeifPropertyRules {
    private const val MAX_LISTED = 100

    private val TRANSFORMATIVE = setOf("irot", "imir", "clap")
    private val UNIQUE_TYPES = setOf("ispe", "pixi", "irot", "imir", "clap")
    private val CODEC_CONFIG = mapOf("hvc1" to "hvcC", "av01" to "av1C", "vvc1" to "vvcC", "j2k1" to "j2kH", "avc1" to "avcC")

    /** `heif.props.codec_config` for an AVIF file with av01 items: AVIF v1.2.0 §2.1 (verified anchor). */
    private val AVIF_CODEC_CITATION = AvifRules.citation("§2.1 AV1 Image Item", "image-item")

    fun evaluate(ctx: HeifContext, avif: Boolean): List<ConformanceFinding> {
        val p = Props(ctx)
        return listOf(
            ipmaRefs(ctx, p), propsIspe(ctx, p), codecConfig(ctx, p, avif), propsEssential(ctx, p), propsUnique(ctx, p),
            irefTargets(ctx, p), derivedGrid(ctx, p), derivedInputs(ctx, p),
        )
    }

    private fun <T> List<T>.capped(): List<T> = take(MAX_LISTED)
    private fun moreLine(total: Int): List<String> = if (total > MAX_LISTED) listOf("… 외 ${total - MAX_LISTED}건") else emptyList()

    private fun isImage(type: String) = type in CODED_IMAGE_TYPES || type in DERIVED_TYPES

    private val PROBLEM_OFFSET = Regex("""^[^@:]{1,8}@(\d+):""")

    /** Model problems whose box offset (the "type@offset:" prefix) lies inside [box]. */
    internal fun problemsWithin(ctx: HeifContext, box: BmffBox): List<String> = ctx.model.problems.filter { p ->
        val off = PROBLEM_OFFSET.find(p)?.groupValues?.get(1)?.toLongOrNull()
        off != null && off >= box.offset && off < box.end
    }

    /** Derived per-file views shared by the rules (computed once). */
    internal class Props(val ctx: HeifContext) {
        val m = ctx.model
        val meta = m.meta
        val iprp: BmffBox? = meta?.children?.firstOrNull { it.type == "iprp" }
        val iref: BmffBox? = meta?.children?.firstOrNull { it.type == "iref" }
        val iinf: BmffBox? = meta?.children?.firstOrNull { it.type == "iinf" }

        /** Declared items (first infe per id), in declaration order. */
        val items: List<HeifItemInfo> = ctx.itemsById.values.map { it.first() }

        /** Associated properties per item_ID (valid indices only), with the essential flag. */
        val byItem: Map<Long, List<Pair<HeifProperty, Boolean>>> = m.associations.groupBy { it.itemId }.mapValues { (_, list) ->
            list.mapNotNull { a -> m.properties.getOrNull(a.propertyIndex - 1)?.let { it to a.essential } }
        }

        fun props(itemId: Long, type: String): List<HeifProperty> = byItem[itemId].orEmpty().map { it.first }.filter { it.type == type }

        /** Problems that may have dropped items (truncated infe, bad iinf children, count mismatch). */
        val itemsIncomplete: List<String> = iinf?.let { b ->
            problemsWithin(ctx, b).filter { it.startsWith("iinf@") || isHeifUnreadableProblem(it) }
        }.orEmpty()

        /** Problems of the iprp/ipco/ipma part that may have dropped properties or associations. */
        val propsIncomplete: List<String> = iprp?.let { b ->
            problemsWithin(ctx, b).filterNot(::isHeifTrailingBytesProblem)
        }.orEmpty()

        /** Problems of iref itself (unreadable header, bad child box): references may be missing. */
        val refsIncomplete: List<String> = iref?.let { b ->
            problemsWithin(ctx, b).filter { it.startsWith("iref@") }
        }.orEmpty()

        fun dimg(from: Long): List<Long> = m.references.filter { it.type == "dimg" && it.from == from }.flatMap { it.to }

        /** Unique ispe size of [itemId], or null when it has none, several, or an unreadable one. */
        fun ispeSize(itemId: Long): Pair<Long, Long>? {
            val list = props(itemId, "ispe")
            if (list.size != 1) return null
            val b = list.single().box
            val end = minOf(b.end, ctx.length)
            if (b.payload + 12 > end) return null
            return ctx.reader.readUInt32(b.payload + 4) to ctx.reader.readUInt32(b.payload + 8)
        }
    }

    /** True when the item/property tables were read completely (no cap, iinf readable, iprp intact). */
    private fun propsReadable(ctx: HeifContext, p: Props): Boolean =
        p.meta != null && ctx.capped("iinf", "iprp", "ipco", "ipma").isEmpty() && p.iinf != null &&
            p.m.iinfEntryCount != null && p.propsIncomplete.isEmpty()

    /** Non-null = item/property tables cannot be read completely; the NOT_CHECKED finding to return. */
    internal fun propsUnavailable(ctx: HeifContext, p: Props, finding: (ConformanceStatus, List<String>) -> ConformanceFinding): ConformanceFinding? {
        if (p.meta == null) return finding(NOT_CHECKED, listOf("최상위 meta 없음"))
        ctx.capped("iinf", "iprp", "ipco", "ipma").takeIf { it.isNotEmpty() }?.let { return finding(NOT_CHECKED, it) }
        if (p.iinf == null || p.m.iinfEntryCount == null) return finding(NOT_CHECKED, listOf("iinf를 읽지 못함"))
        if (p.propsIncomplete.isNotEmpty())
            return finding(NOT_CHECKED, listOf("iprp/ipco/ipma를 끝까지 읽지 못해 속성 연결을 확인할 수 없음") + p.propsIncomplete.capped())
        return null
    }

    private fun result(
        ctx: HeifContext, id: String, fails: List<String>, warns: List<String>, unchecked: List<String>,
        ranges: List<LongRange>, pass: String, rule: ConformanceRule = HeifRules.rule(id),
    ): ConformanceFinding {
        val r = ranges.distinct().capped()
        return when {
            fails.isNotEmpty() -> ConformanceFinding(rule, FAIL, fails.capped() + moreLine(fails.size) + warns.capped() + unchecked.capped(), r)
            warns.isNotEmpty() -> ConformanceFinding(rule, WARN, warns.capped() + moreLine(warns.size) + unchecked.capped(), r)
            unchecked.isNotEmpty() -> ConformanceFinding(rule, NOT_CHECKED, unchecked.capped() + moreLine(unchecked.size), emptyList())
            else -> ConformanceFinding(rule, PASS, listOf(pass), emptyList())
        }
    }

    // ---- properties ----

    private fun ipmaRefs(ctx: HeifContext, p: Props): ConformanceFinding {
        val id = "heif.ipma.refs"
        val m = p.m
        if (p.meta == null) return ctx.finding(id, NOT_CHECKED, listOf("최상위 meta 없음"))
        ctx.capped("iinf", "iprp", "ipco", "ipma").takeIf { it.isNotEmpty() }?.let { return ctx.finding(id, NOT_CHECKED, it) }
        if (p.iinf == null || m.iinfEntryCount == null) return ctx.finding(id, NOT_CHECKED, listOf("iinf를 읽지 못함"))
        val iprp = p.iprp ?: return ctx.finding(id, NOT_CHECKED, listOf("iprp 없음 — 검사할 속성 연결 없음"))
        if (m.ipmas.isEmpty()) return ctx.finding(id, NOT_CHECKED,
            listOf("${at(iprp)}: ipma 없음 — 검사할 속성 연결 없음") + p.propsIncomplete.capped())
        val fails = ArrayList<String>()
        val warns = ArrayList<String>()
        val unchecked = ArrayList<String>()
        val ranges = ArrayList<LongRange>()
        for (ipma in m.ipmas) problemsWithin(ctx, ipma).forEach {
            if (isHeifTrailingBytesProblem(it)) warns += it else fails += it
            ranges += ctx.range(ipma)
        }
        val ipcoProblems = m.ipco?.let { problemsWithin(ctx, it) }.orEmpty() +
            problemsWithin(ctx, iprp).filter { it.startsWith("iprp@") }
        val ipmaRange = ctx.range(m.ipmas.first())
        val counts = m.ipmaOrder.groupingBy { it }.eachCount()
        counts.filter { it.value > 1 }.keys.sorted().forEach {
            fails += "item_ID $it 이(가) ipma에 ${counts[it]}번 나옴 (최대 1번)"; ranges += ipmaRange
        }
        // item_IDs increase within each ipma box; across boxes only uniqueness (above) applies.
        m.ipmaOrders.forEachIndexed { boxIndex, order ->
            val box = m.ipmas.getOrNull(boxIndex) ?: return@forEachIndexed
            for (i in 1 until order.size) {
                val a = order[i - 1]; val b = order[i]
                if (b < a) { fails += "${at(box)}: item_ID 순서가 증가하지 않음: $a 다음 $b"; ranges += ctx.range(box) }
            }
        }
        val declared = ctx.itemsById.keys
        counts.keys.filter { it !in declared }.sorted().forEach {
            if (p.itemsIncomplete.isEmpty()) { fails += "ipma item_ID $it 이(가) iinf에 선언되지 않음"; ranges += ipmaRange }
            else unchecked += "ipma item_ID $it: iinf를 끝까지 읽지 못해 선언 여부 미확인"
        }
        val count = m.properties.size
        val outOfRange = m.associations.filter { it.propertyIndex > count }
        if (ipcoProblems.isNotEmpty()) {
            if (outOfRange.isNotEmpty()) unchecked += "ipco를 끝까지 읽지 못해 property_index 범위 미검사"
            unchecked += ipcoProblems
        } else {
            outOfRange.forEach {
                fails += "item_ID ${it.itemId}: property_index ${it.propertyIndex} 이(가) ipco 속성 수($count)를 넘음"; ranges += ipmaRange
            }
        }
        return result(ctx, id, fails, warns, unchecked, ranges,
            "ipma 항목 ${m.ipmaOrder.size}개 (item_ID 증가 순·중복 없음·모두 선언됨), 연결 ${m.associations.size}개가 ipco 속성 ${count}개 범위 안")
    }

    private fun propsIspe(ctx: HeifContext, p: Props): ConformanceFinding {
        val id = "heif.props.ispe"
        propsUnavailable(ctx, p) { s, d -> ctx.finding(id, s, d) }?.let { return it }
        val images = p.items.filter { isImage(it.type) }
        if (images.isEmpty()) return ctx.finding(id, NOT_CHECKED, listOf("이미지 항목 없음"))
        val fails = ArrayList<String>()
        val ranges = ArrayList<LongRange>()
        for (item in images) {
            val n = p.props(item.id, "ispe").size
            if (n != 1) {
                fails += "item_ID ${item.id} ('${item.type}'): ispe ${n}개 (정확히 1개여야 함)"
                ranges += ctx.range(item.infe)
            }
        }
        return result(ctx, id, fails, emptyList(), emptyList(), ranges, "이미지 항목 ${images.size}개가 ispe를 1개씩 가짐")
    }

    private fun codecConfig(ctx: HeifContext, p: Props, avif: Boolean): ConformanceFinding {
        val id = "heif.props.codec_config"
        val base = HeifRules.rule(id)
        val rule = if (avif && p.items.any { it.type == "av01" })
            base.copy(source = AvifRules.AVIF_SOURCE, reference = "AVIF v1.2.0 §2.1 (AV1 Image Item)", citation = AVIF_CODEC_CITATION)
        else base
        propsUnavailable(ctx, p) { s, d -> ConformanceFinding(rule, s, d, emptyList()) }?.let { return it }
        val coded = p.items.filter { it.type in CODED_IMAGE_TYPES }
        val fails = ArrayList<String>()
        val ranges = ArrayList<LongRange>()
        val notApplicable = ArrayList<String>()
        var checked = 0
        for (item in coded) {
            val config = CODEC_CONFIG[item.type]
            if (config == null) { notApplicable += "item_ID ${item.id} ('${item.type}'): 디코더 구성 속성 규칙 없음 (2단계)"; continue }
            checked++
            if (p.props(item.id, config).isEmpty()) {
                fails += "item_ID ${item.id} ('${item.type}'): $config 속성 없음"; ranges += ctx.range(item.infe)
            }
        }
        if (fails.isEmpty() && checked == 0) return ConformanceFinding(rule, NOT_APPLICABLE,
            notApplicable.capped().ifEmpty { listOf("부호화 이미지 항목 없음") }, emptyList())
        val summary = coded.filter { it.type in CODEC_CONFIG }.groupingBy { it.type }.eachCount().entries
            .joinToString(", ") { "${it.key}→${CODEC_CONFIG[it.key]} ${it.value}개" }
        val f = result(ctx, id, fails, emptyList(), emptyList(), ranges, "부호화 항목의 디코더 구성 속성 있음 ($summary)", rule)
        return if (f.status == PASS && notApplicable.isNotEmpty()) f.copy(details = f.details + notApplicable.capped()) else f
    }

    private fun propsEssential(ctx: HeifContext, p: Props): ConformanceFinding {
        val id = "heif.props.essential"
        propsUnavailable(ctx, p) { s, d -> ctx.finding(id, s, d) }?.let { return it }
        val warns = ArrayList<String>()
        val ranges = ArrayList<LongRange>()
        var seen = 0
        for ((itemId, list) in p.byItem) for ((prop, essential) in list) {
            if (prop.type !in TRANSFORMATIVE) continue
            seen++
            if (!essential) { warns += "item_ID $itemId: ${prop.type} (속성 ${prop.index}) 이(가) essential이 아님"; ranges += ctx.range(prop.box) }
        }
        if (seen == 0) return ctx.finding(id, NOT_APPLICABLE, listOf("연결된 변환 속성(irot/imir/clap) 없음"))
        return result(ctx, id, emptyList(), warns, emptyList(), ranges, "변환 속성 연결 ${seen}개 모두 essential")
    }

    private fun colourType(ctx: HeifContext, b: BmffBox): String =
        if (b.payload + 4 <= minOf(b.end, ctx.length)) ctx.reader.readFourCC(b.payload) else "?"

    private fun propsUnique(ctx: HeifContext, p: Props): ConformanceFinding {
        val id = "heif.props.unique"
        propsUnavailable(ctx, p) { s, d -> ctx.finding(id, s, d) }?.let { return it }
        if (p.byItem.values.all { it.isEmpty() }) return ctx.finding(id, NOT_APPLICABLE, listOf("속성 연결 없음"))
        val warns = ArrayList<String>()
        val ranges = ArrayList<LongRange>()
        for ((itemId, list) in p.byItem) {
            val keyed = list.map { it.first }.mapNotNull { prop ->
                when (prop.type) {
                    in UNIQUE_TYPES -> prop.type to prop
                    "colr" -> "colr('${colourType(ctx, prop.box)}')" to prop
                    else -> null
                }
            }.groupBy({ it.first }, { it.second })
            keyed.filter { it.value.size > 1 }.forEach { (key, props) ->
                warns += "item_ID $itemId: $key ${props.size}개 (최대 1개)"; ranges += props.map { ctx.range(it.box) }
            }
        }
        return result(ctx, id, emptyList(), warns, emptyList(), ranges,
            "항목 ${p.byItem.size}개: ispe/pixi/irot/imir/clap/colr(유형별) 각 최대 1개")
    }

    // ---- references ----

    private fun irefTargets(ctx: HeifContext, p: Props): ConformanceFinding {
        val id = "heif.iref.targets"
        val m = p.m
        if (p.meta == null) return ctx.finding(id, NOT_CHECKED, listOf("최상위 meta 없음"))
        val iref = p.iref ?: return ctx.finding(id, NOT_APPLICABLE, listOf("iref 없음"))
        ctx.capped("iinf", "iref").takeIf { it.isNotEmpty() }?.let { return ctx.finding(id, NOT_CHECKED, it) }
        if (p.iinf == null || m.iinfEntryCount == null) return ctx.finding(id, NOT_CHECKED, listOf("iinf를 읽지 못함"))
        val fails = ArrayList<String>()
        val warns = ArrayList<String>()
        val unchecked = ArrayList<String>()
        val ranges = ArrayList<LongRange>()
        if (p.refsIncomplete.isNotEmpty()) unchecked += listOf("iref를 끝까지 읽지 못해 일부 참조 미검사") + p.refsIncomplete
        // Problems of individual reference boxes: a box whose content is shorter than its fields is malformed (FAIL);
        // one that could not be read (I/O error) may hide references, so it blocks a PASS.
        problemsWithin(ctx, iref).filterNot { it.startsWith("iref@") }.forEach {
            when {
                isHeifReadErrorProblem(it) -> unchecked += "읽지 못한 참조 박스 — $it"
                isHeifUnreadableProblem(it) -> { fails += "잘린 참조 박스 (내용이 필드보다 짧음) — $it"; ranges += ctx.range(iref) }
                else -> { warns += it; ranges += ctx.range(iref) }
            }
        }
        val propsReadable = propsReadable(ctx, p)
        val declared = ctx.itemsById.keys
        fun typeOf(itemId: Long) = ctx.itemsById[itemId]?.first()?.type
        for (r in m.references) {
            val where = "${r.type} @0x%X".format(r.box.offset)
            (listOf(r.from) + r.to).distinct().filter { it !in declared }.forEach {
                if (p.itemsIncomplete.isEmpty()) { fails += "$where: item_ID $it 이(가) iinf에 선언되지 않음"; ranges += ctx.range(r.box) }
                else unchecked += "$where: item_ID $it — iinf를 끝까지 읽지 못해 선언 여부 미확인"
            }
            if (r.type == "thmb" || r.type == "auxl") {
                val fromType = typeOf(r.from)
                if (fromType != null && fromType in METADATA_TYPES) {
                    fails += "$where: 원본 item_ID ${r.from} ('$fromType') 이(가) 이미지 항목이 아님"; ranges += ctx.range(r.box)
                }
                r.to.forEach { t ->
                    val tt = typeOf(t)
                    if (tt != null && tt in METADATA_TYPES) {
                        fails += "$where: 대상 item_ID $t ('$tt') 이(가) 이미지 항목이 아님"; ranges += ctx.range(r.box)
                    }
                }
            }
            if (r.type == "auxl" && r.from in declared) {
                when {
                    !propsReadable -> unchecked += "$where: item_ID ${r.from} 의 auxC — 속성을 읽지 못해 미검사"
                    p.props(r.from, "auxC").isEmpty() -> {
                        fails += "$where: 보조 이미지 item_ID ${r.from} 에 auxC 속성 없음"; ranges += ctx.range(r.box)
                    }
                }
            }
        }
        val byType = m.references.groupingBy { it.type }.eachCount().entries.joinToString(", ") { "${it.key} ${it.value}개" }
        return result(ctx, id, fails, warns, unchecked, ranges,
            "참조 ${m.references.size}개 ($byType): 모든 item_ID 선언됨, thmb/auxl 대상은 이미지 항목, auxl 원본에 auxC 있음")
    }

    // ---- derived items ----

    /** Non-null = references or items cannot be read completely; the NOT_CHECKED finding to return. */
    private fun refsUnavailable(ctx: HeifContext, p: Props, id: String): ConformanceFinding? {
        if (p.meta == null) return ctx.finding(id, NOT_CHECKED, listOf("최상위 meta 없음"))
        ctx.capped("iinf", "iref").takeIf { it.isNotEmpty() }?.let { return ctx.finding(id, NOT_CHECKED, it) }
        if (p.iinf == null || p.m.iinfEntryCount == null) return ctx.finding(id, NOT_CHECKED, listOf("iinf를 읽지 못함"))
        if (p.refsIncomplete.isNotEmpty()) return ctx.finding(id, NOT_CHECKED,
            listOf("iref를 끝까지 읽지 못해 dimg 입력을 확인할 수 없음") + p.refsIncomplete)
        val brokenRefs = p.iref?.let { b -> problemsWithin(ctx, b).filter(::isHeifUnreadableProblem) }.orEmpty()
        if (brokenRefs.isNotEmpty()) return ctx.finding(id, NOT_CHECKED,
            listOf("읽지 못한 참조 박스가 있어 dimg 입력을 확인할 수 없음 (heif.iref.targets 참조)") + brokenRefs)
        return null
    }

    private sealed class ItemData {
        class Bytes(val bytes: ByteArray, val total: Long) : ItemData()
        class Unavailable(val reason: String) : ItemData()
    }

    /** Up to [max] leading bytes of [itemId]'s data from its iloc entry (method 0 file or method 1 idat). */
    private fun itemData(ctx: HeifContext, itemId: Long, max: Int): ItemData {
        val m = ctx.model
        if (ctx.model.problems.any { heifSkippedItemId(it) == itemId }) return ItemData.Unavailable("iloc 항목을 해석하지 못함")
        val locs = m.locations.filter { it.itemId == itemId }
        if (locs.size != 1) return ItemData.Unavailable("iloc 항목 ${locs.size}개 (heif.iloc.items 참조)")
        val loc = locs.single()
        val base: Long
        val limit: Long
        when {
            loc.method == 0 && loc.dataReferenceIndex == 0 -> { base = 0; limit = ctx.length }
            loc.method == 1 && m.idat != null -> { base = m.idat.payload; limit = minOf(m.idat.end, ctx.length) - m.idat.payload }
            else -> return ItemData.Unavailable("construction_method ${loc.method}/data_reference_index ${loc.dataReferenceIndex}: 데이터를 읽지 않음")
        }
        if (loc.extents.isEmpty()) return ItemData.Unavailable("extent 없음 (heif.iloc.extents 참조)")
        val out = java.io.ByteArrayOutputStream()
        var total = 0L
        for (e in loc.extents) {
            if (e.offset > limit) return ItemData.Unavailable("익스텐트가 범위를 넘음 (heif.iloc.extents 참조)")
            val len = if (e.length == 0L) limit - e.offset else e.length
            if (len > limit - e.offset) return ItemData.Unavailable("익스텐트가 범위를 넘음 (heif.iloc.extents 참조)")
            total += len
            val want = minOf(len, (max - out.size()).toLong()).toInt()
            if (want > 0) out.write(ctx.reader.readBytes(base + e.offset, want))
        }
        return ItemData.Bytes(out.toByteArray(), total)
    }

    private fun derivedGrid(ctx: HeifContext, p: Props): ConformanceFinding {
        val id = "heif.derived.grid"
        if (p.meta == null) return ctx.finding(id, NOT_CHECKED, listOf("최상위 meta 없음"))
        val grids = p.items.filter { it.type == "grid" }
        if (grids.isEmpty() && p.m.iinfEntryCount != null && ctx.capped("iinf").isEmpty() && p.itemsIncomplete.isEmpty())
            return ctx.finding(id, NOT_APPLICABLE, listOf("grid 항목 없음"))
        refsUnavailable(ctx, p, id)?.let { return it }
        if (grids.isEmpty()) return ctx.finding(id, NOT_CHECKED,
            listOf("iinf를 끝까지 읽지 못해 grid 항목 여부를 확인할 수 없음") + p.itemsIncomplete.capped())
        propsUnavailable(ctx, p) { s, d -> ctx.finding(id, s, d) }?.let { return it }
        ctx.capped("iloc").takeIf { it.isNotEmpty() }?.let { return ctx.finding(id, NOT_CHECKED, it) }
        val fails = ArrayList<String>()
        val unchecked = ArrayList<String>()
        val ranges = ArrayList<LongRange>()
        for (g in grids) {
            val tag = "grid item_ID ${g.id}"
            val data = when (val d = itemData(ctx, g.id, 12)) {
                is ItemData.Unavailable -> { unchecked += "$tag: 기술자를 읽지 않음 — ${d.reason}"; continue }
                is ItemData.Bytes -> d
            }
            val b = data.bytes
            if (b.size < 8) { fails += "$tag: grid 기술자 잘림 (${data.total}바이트, 8 이상이어야 함)"; ranges += ctx.range(g.infe); continue }
            val version = b[0].toInt() and 0xFF
            if (version != 0) { fails += "$tag: grid 기술자 version $version (0이어야 함)"; ranges += ctx.range(g.infe); continue }
            val large = b[1].toInt() and 1 != 0
            val need = if (large) 12 else 8
            if (b.size < need) { fails += "$tag: grid 기술자 잘림 (${data.total}바이트, flags=1이면 12 필요)"; ranges += ctx.range(g.infe); continue }
            fun u16(i: Int) = ((b[i].toLong() and 0xFF) shl 8) or (b[i + 1].toLong() and 0xFF)
            fun u32(i: Int) = (u16(i) shl 16) or u16(i + 2)
            val rows = (b[2].toInt() and 0xFF) + 1
            val cols = (b[3].toInt() and 0xFF) + 1
            val outW = if (large) u32(4) else u16(4)
            val outH = if (large) u32(8) else u16(6)
            val tiles = p.dimg(g.id)
            if (tiles.size != rows * cols) {
                fails += "$tag: dimg 입력 ${tiles.size}개 ≠ rows×columns ${rows}×${cols}=${rows * cols}"; ranges += ctx.range(g.infe)
            }
            val sizes = tiles.distinct().associateWith { p.ispeSize(it) }
            val unknown = sizes.filter { it.value == null }.keys
            val known = sizes.values.filterNotNull().distinct()
            if (known.size > 1) {
                fails += "$tag: 타일 ispe가 서로 다름 (" + known.joinToString(", ") { "${it.first}×${it.second}" } + ")"
                ranges += ctx.range(g.infe)
            }
            if (unknown.isNotEmpty()) unchecked += "$tag: 타일 ${unknown.sorted().take(10).joinToString(", ")}의 ispe를 확인할 수 없음 (heif.props.ispe 참조)"
            else if (known.size == 1) {
                val (tw, th) = known.single()
                if (tw * cols < outW || th * rows < outH) {
                    fails += "$tag: 타일 ${tw}×${th} × ${cols}열·${rows}행이 출력 ${outW}×${outH}를 덮지 못함"; ranges += ctx.range(g.infe)
                }
            }
            when (val gs = p.ispeSize(g.id)) {
                null -> unchecked += "$tag: grid의 ispe를 확인할 수 없음 (heif.props.ispe 참조)"
                else -> if (gs.first != outW || gs.second != outH) {
                    fails += "$tag: ispe ${gs.first}×${gs.second} ≠ 출력 크기 ${outW}×${outH}"; ranges += ctx.range(g.infe)
                }
            }
        }
        if (p.itemsIncomplete.isNotEmpty())
            unchecked += listOf("iinf를 끝까지 읽지 못해 다른 grid 항목이 있을 수 있음") + p.itemsIncomplete.capped()
        return result(ctx, id, fails, emptyList(), unchecked, ranges,
            "grid ${grids.size}개: 기술자 version 0, dimg 수 = rows×columns, 타일 ispe 동일·출력 크기 덮음, grid ispe = 출력 크기")
    }

    private fun derivedInputs(ctx: HeifContext, p: Props): ConformanceFinding {
        val id = "heif.derived.inputs"
        if (p.meta == null) return ctx.finding(id, NOT_CHECKED, listOf("최상위 meta 없음"))
        val derived = p.items.filter { it.type in DERIVED_TYPES }
        if (derived.isEmpty() && p.m.iinfEntryCount != null && ctx.capped("iinf").isEmpty() && p.itemsIncomplete.isEmpty())
            return ctx.finding(id, NOT_APPLICABLE, listOf("파생 이미지 항목 없음"))
        refsUnavailable(ctx, p, id)?.let { return it }
        if (derived.isEmpty()) return ctx.finding(id, NOT_CHECKED,
            listOf("iinf를 끝까지 읽지 못해 파생 이미지 항목 여부를 확인할 수 없음") + p.itemsIncomplete.capped())
        val fails = ArrayList<String>()
        val unchecked = ArrayList<String>()
        val ranges = ArrayList<LongRange>()
        for (d in derived) {
            val tag = "${d.type} item_ID ${d.id}"
            val inputs = p.dimg(d.id)
            if (inputs.isEmpty()) { fails += "$tag: dimg 입력 없음"; ranges += ctx.range(d.infe); continue }
            for (input in inputs.distinct()) {
                val t = ctx.itemsById[input]?.first()?.type
                when {
                    input == d.id -> { fails += "$tag: 자기 자신을 입력으로 참조"; ranges += ctx.range(d.infe) }
                    t == null && p.itemsIncomplete.isEmpty() -> { fails += "$tag: 입력 item_ID $input 이(가) 선언되지 않음"; ranges += ctx.range(d.infe) }
                    t == null -> unchecked += "$tag: 입력 item_ID $input — iinf를 끝까지 읽지 못해 선언 여부 미확인"
                    t in METADATA_TYPES -> { fails += "$tag: 입력 item_ID $input ('$t') 이(가) 이미지 항목이 아님"; ranges += ctx.range(d.infe) }
                }
            }
            if (d.type == "tmap" && inputs.size != 2) { fails += "$tag: 입력 ${inputs.size}개 (정확히 2개여야 함)"; ranges += ctx.range(d.infe) }
            if (d.type == "iden" && inputs.size != 1) { fails += "$tag: 입력 ${inputs.size}개 (§6.6.2.2 iden: dimg 입력 1개)"; ranges += ctx.range(d.infe) }
        }
        if (p.itemsIncomplete.isNotEmpty())
            unchecked += listOf("iinf를 끝까지 읽지 못해 다른 파생 이미지 항목이 있을 수 있음") + p.itemsIncomplete.capped()
        val byType = derived.groupingBy { it.type }.eachCount().entries.joinToString(", ") { "${it.key} ${it.value}개" }
        return result(ctx, id, fails, emptyList(), unchecked, ranges, "파생 항목 ${derived.size}개 ($byType): dimg 입력이 모두 선언된 이미지 항목")
    }
}
