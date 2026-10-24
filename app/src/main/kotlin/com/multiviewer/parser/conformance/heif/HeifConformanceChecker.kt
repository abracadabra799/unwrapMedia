package com.multiviewer.parser.conformance.heif

import com.multiviewer.parser.ByteReader
import com.multiviewer.parser.ContainerFormat
import com.multiviewer.parser.conformance.ConformanceFinding
import com.multiviewer.parser.conformance.ConformanceReport
import com.multiviewer.parser.conformance.ConformanceStatus
import com.multiviewer.parser.conformance.ConformanceStatus.FAIL
import com.multiviewer.parser.conformance.ConformanceStatus.NOT_APPLICABLE
import com.multiviewer.parser.conformance.ConformanceStatus.NOT_CHECKED
import com.multiviewer.parser.conformance.ConformanceStatus.PASS
import com.multiviewer.parser.conformance.ConformanceStatus.WARN
import com.multiviewer.parser.conformance.isobmff.BmffBox
import com.multiviewer.parser.conformance.isobmff.BmffContext
import com.multiviewer.parser.conformance.isobmff.BmffProfile
import com.multiviewer.parser.conformance.isobmff.BmffTree
import com.multiviewer.parser.conformance.isobmff.IsoBmffBoxTree
import com.multiviewer.parser.conformance.isobmff.IsoBmffStructureRules
import com.multiviewer.parser.sniffFormat
import java.util.concurrent.CancellationException

/** Shared, read-only state for the HEIF rule groups. */
internal class HeifContext(val tree: BmffTree, val reader: ByteReader, val model: HeifModel, val trailer: BmffBox? = null) {
    val length: Long = reader.length
    /** Top-level boxes of the HEIF part (before an appended motion-photo video, see [appendedVideoFtyp]). */
    val heifTop: List<BmffBox> = if (trailer == null) tree.top else tree.top.takeWhile { it !== trailer }
    val itemsById: Map<Long, List<HeifItemInfo>> = model.items.groupBy { it.id }

    /** Brands of the first top-level ftyp (null when there is no readable ftyp). */
    val brands: Brands? by lazy { readBrands() }

    data class Brands(val major: String, val compatible: List<String>) {
        val all: Set<String> get() = (compatible + major).toSet()
    }

    private fun readBrands(): Brands? {
        val f = tree.top.firstOrNull { it.type == "ftyp" } ?: return null
        if (f.problem != null) return null
        val end = minOf(f.end, length)
        if (f.payload + 4 > end) return null
        val major = reader.readFourCC(f.payload)
        val n = ((end - f.payload - 8) / 4).coerceIn(0, MAX_BRANDS.toLong()).toInt()
        val compat = (0 until n).map { reader.readFourCC(f.payload + 8 + 4L * it) }
        return Brands(major, compat)
    }

    fun finding(id: String, status: ConformanceStatus, details: List<String>, ranges: List<LongRange> = emptyList()) =
        ConformanceFinding(HeifRules.rule(id), status, details, ranges)

    fun range(b: BmffBox): LongRange = b.offset until minOf(b.end, length).coerceAtLeast(b.offset)

    /** Model problems of [box] (prefix "box@") that hit the count cap. */
    fun capped(vararg boxes: String): List<String> = model.problems.filter { p ->
        isHeifCapProblem(p) && boxes.any { p.startsWith("$it@") }
    }

    fun problemsOf(box: String): List<String> = model.problems.filter { it.startsWith("$box@") }

    /** Coded image item types each [itemId] resolves to through `dimg` references (bounded, cycle-safe). */
    fun codedTypes(itemId: Long): Set<String> {
        val out = HashSet<String>()
        val seen = HashSet<Long>()
        val queue = ArrayDeque(listOf(itemId))
        while (queue.isNotEmpty() && seen.size < MAX_DERIVATION_WALK) {
            val id = queue.removeFirst()
            if (!seen.add(id)) continue
            val type = itemsById[id]?.firstOrNull()?.type ?: continue
            if (type in DERIVED_TYPES) {
                model.references.filter { it.type == "dimg" && it.from == id }.forEach { queue.addAll(it.to) }
            } else out += type
        }
        return out
    }

    companion object {
        const val MAX_BRANDS = 4096
        const val MAX_DERIVATION_WALK = 100_000
    }
}

/** Brands that mark a file (or an appended part) as an image file. */
private val IMAGE_BRANDS = setOf("mif1", "mif2", "msf1", "miaf", "heic", "heix", "heim", "heis", "hevc", "hevx", "avif", "avis",
    "avci", "avcs", "jpeg", "jpgs", "vvic", "vvis", "evbi", "evbs", "j2ki", "j2is")

/**
 * The first top-level `ftyp` after the first one that comes after a top-level `meta` and lists no image brand
 * (e.g. `mp42`/`isom`): the start of an appended video file such as a motion-photo trailer. Null when absent.
 */
internal fun appendedVideoFtyp(tree: BmffTree, reader: ByteReader): BmffBox? {
    val top = tree.top
    val firstMeta = top.indexOfFirst { it.type == "meta" }
    if (firstMeta < 0) return null
    val firstFtyp = top.indexOfFirst { it.type == "ftyp" }
    if (firstFtyp < 0 || firstFtyp > firstMeta) return null
    return top.drop(firstMeta + 1).firstOrNull { b ->
        if (b.type != "ftyp" || b.problem != null) return@firstOrNull false
        val end = minOf(b.end, reader.length)
        if (b.payload + 4 > end) return@firstOrNull false
        val n = ((end - b.payload - 8) / 4).coerceIn(0, HeifContext.MAX_BRANDS.toLong()).toInt()
        val brands = listOf(reader.readFourCC(b.payload)) + (0 until n).map { reader.readFourCC(b.payload + 8 + 4L * it) }
        brands.none { it in IMAGE_BRANDS }
    }
}

/** [tree] cut before [trailer] (an appended video ftyp); [tree] itself when there is none. */
internal fun heifHead(tree: BmffTree, trailer: BmffBox?): BmffTree =
    if (trailer == null) tree else BmffTree(tree.top.takeWhile { it !== trailer }, 0, null)

internal val CODED_IMAGE_TYPES = setOf("hvc1", "av01", "avc1", "jpeg", "j2k1", "vvc1", "evc1", "unci", "lhv1", "mski")
internal val DERIVED_TYPES = setOf("grid", "iovl", "iden", "tmap")
internal val METADATA_TYPES = setOf("Exif", "mime", "uri ")
private val HEVC_BRANDS = setOf("heic", "heix", "heim", "heis")

internal fun at(b: BmffBox): String = "${b.type} @0x%X".format(b.offset)

object HeifConformanceChecker {
    const val HEIF_PROFILE = "ISO/IEC 23008-12 phase 1"
    const val AVIF_PROFILE = "ISO/IEC 23008-12 + AVIF v1.2.0 phase 1"

    fun check(reader: ByteReader): ConformanceReport {
        val findings = ArrayList<ConformanceFinding>()
        var avif = false
        val error = try {
            val tree = IsoBmffBoxTree.walk(reader)
            val bmff = BmffContext(tree, reader, BmffProfile.ISO)
            findings += IsoBmffStructureRules.boxSize(bmff)
            findings += IsoBmffStructureRules.boxSize0(bmff)
            findings += IsoBmffStructureRules.boxTrailing(bmff)
            val trailer = appendedVideoFtyp(tree, reader)
            findings += ftypPosition(bmff, tree, reader, trailer)
            val ctx = HeifContext(tree, reader, heifModel(tree, reader, trailer), trailer)
            avif = isAvif(ctx)
            findings += HeifStructureRules.evaluate(ctx)
            findings += HeifPropertyRules.evaluate(ctx, avif)
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            "검사 중 오류: ${e.javaClass.simpleName}: ${e.message ?: ""}".trim()
        }
        return ConformanceReport(
            format = if (avif) "AVIF" else "HEIF",
            profile = if (avif) AVIF_PROFILE else HEIF_PROFILE,
            findings = findings,
            limitations = listOf(
                "코덱 비트스트림 구문 전체는 검사하지 않습니다 / Codec bitstream syntax is not fully checked",
                "부호화된 항목 데이터는 해독하지 않습니다 / Coded item data is not decoded",
                "HEIF 규칙은 공개 자료 기반이며 ISO/IEC 23008-12 원문과 대조하지 않았습니다 / " +
                    "HEIF rules are based on public material, not checked against the standard text",
            ),
            inspectionError = error,
        )
    }

    /**
     * `bmff.ftyp.position` over the HEIF part only. An appended motion-photo video (a later `ftyp` whose brands are
     * not image brands, e.g. Samsung S26 `mp42` after the HEIF `meta`) is a second file, not HEIF structure: it is
     * reported as WARN (it breaks the single-ftyp layout) and left to the Google Motion Photo checker.
     */
    private fun ftypPosition(bmff: BmffContext, tree: BmffTree, reader: ByteReader, trailer: BmffBox?): ConformanceFinding {
        if (trailer == null) return IsoBmffStructureRules.ftypPosition(bmff)
        val head = heifHead(tree, trailer)
        val f = IsoBmffStructureRules.ftypPosition(BmffContext(head, reader, BmffProfile.ISO))
        val note = "${at(trailer)}: 이미지 브랜드가 없는 두 번째 ftyp — 이어 붙은 동영상(모션 포토)의 시작으로 보고 HEIF 구조에서 제외 " +
            "(단일 ftyp 배치 위반, Google Motion Photo 검사에서 판단)"
        val range = trailer.offset until minOf(trailer.end, reader.length)
        return when (f.status) {
            PASS -> f.copy(status = WARN, details = f.details + note, ranges = f.ranges + listOf(range))
            else -> f.copy(details = f.details + note, ranges = f.ranges + listOf(range))
        }
    }

    /** The HEIF item model of [reader], read over the HEIF part only (never an appended video's boxes). */
    internal fun heifModel(reader: ByteReader): HeifModel = IsoBmffBoxTree.walk(reader).let { heifModel(it, reader) }

    /** The single model-building path shared by [check] and [heifModel]: [tree] cut before [trailer], then read. */
    internal fun heifModel(tree: BmffTree, reader: ByteReader, trailer: BmffBox? = appendedVideoFtyp(tree, reader)): HeifModel =
        HeifItemModel.read(heifHead(tree, trailer), reader)

    private fun isAvif(ctx: HeifContext): Boolean {
        val sniffed = try { sniffFormat(ctx.reader) } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
        if (sniffed == ContainerFormat.AVIF) return true
        val brands = ctx.brands ?: return false
        val primaryType = ctx.model.primaryId?.let { ctx.itemsById[it]?.firstOrNull()?.type }
        return brands.all.any { it == "avif" || it == "avis" } && primaryType == "av01"
    }
}

/** ftyp brand, meta structure, item information and item location rules. */
internal object HeifStructureRules {
    private const val MAX_LISTED = 100

    fun evaluate(ctx: HeifContext): List<ConformanceFinding> = listOf(
        ftypMif1(ctx), ftypCodecBrand(ctx), metaPresent(ctx), metaRequired(ctx), itemIds(ctx), itemsPrimary(ctx),
        ilocItems(ctx), ilocMethod(ctx), ilocExtents(ctx),
    )

    private fun <T> List<T>.capped(): List<T> = take(MAX_LISTED)
    private fun moreLine(total: Int): List<String> = if (total > MAX_LISTED) listOf("… 외 ${total - MAX_LISTED}건") else emptyList()

    // ---- ftyp ----

    private fun ftypMif1(ctx: HeifContext): ConformanceFinding {
        val id = "heif.ftyp.mif1"
        val b = ctx.brands ?: return ctx.finding(id, NOT_CHECKED, listOf("읽을 수 있는 ftyp 없음"))
        val sequence = ctx.heifTop.any { it.type == "moov" }
        val wanted = if (sequence) setOf("mif1", "msf1") else setOf("mif1")
        val summary = "major '${b.major}', compatible [${b.compatible.take(16).joinToString(", ") { "'$it'" }}]"
        val found = b.compatible.filter { it in wanted }
        if (found.isNotEmpty()) return ctx.finding(id, PASS, listOf("호환 브랜드에 ${found.distinct().joinToString("/")} 있음", summary))
        val wantText = wanted.joinToString(" 또는 ")
        val note = if (b.major in wanted) " (major_brand에만 있음)" else ""
        return ctx.finding(id, WARN, listOf("호환 브랜드에 $wantText 없음$note", summary))
    }

    private fun ftypCodecBrand(ctx: HeifContext): ConformanceFinding {
        val id = "heif.ftyp.codec_brand"
        val b = ctx.brands ?: return ctx.finding(id, NOT_CHECKED, listOf("읽을 수 있는 ftyp 없음"))
        val primary = ctx.model.primaryId ?: return ctx.finding(id, NOT_CHECKED, listOf("주 항목(pitm)을 읽지 못함"))
        if (ctx.itemsById[primary] == null) return ctx.finding(id, NOT_CHECKED, listOf("주 항목 $primary 이(가) 선언되지 않음"))
        val coded = ctx.codedTypes(primary)
        if (coded.isEmpty()) return ctx.finding(id, NOT_CHECKED, listOf("주 항목 $primary 의 부호화 항목 유형을 확인하지 못함"))
        val fails = ArrayList<String>()
        val passes = ArrayList<String>()
        if ("hvc1" in coded) {
            val hit = b.all.filter { it in HEVC_BRANDS }
            if (hit.isEmpty()) fails += "HEVC(hvc1) 주 영상인데 heic/heix/heim/heis 브랜드 없음"
            else passes += "HEVC(hvc1) 주 영상 → '${hit.first()}' 브랜드 있음"
        }
        if ("av01" in coded) {
            if ("avif" !in b.all) fails += "AV1(av01) 주 영상인데 avif 브랜드 없음"
            else passes += "AV1(av01) 주 영상 → 'avif' 브랜드 있음"
        }
        val summary = "major '${b.major}', compatible [${b.compatible.take(16).joinToString(", ") { "'$it'" }}]"
        return when {
            fails.isNotEmpty() -> ctx.finding(id, WARN, fails + passes + summary)
            passes.isNotEmpty() -> ctx.finding(id, PASS, passes + summary)
            else -> ctx.finding(id, NOT_APPLICABLE,
                listOf("주 영상 유형 ${coded.joinToString(", ")}: HEVC/AV1 브랜드 규칙만 검사함"))
        }
    }

    // ---- meta structure ----

    private fun metaPresent(ctx: HeifContext): ConformanceFinding {
        val id = "heif.meta.present"
        val metas = ctx.heifTop.filter { it.type == "meta" }
        if (metas.isEmpty()) return ctx.finding(id, FAIL, listOf("최상위 meta 박스 없음"))
        if (metas.size > 1) return ctx.finding(id, FAIL,
            listOf("최상위 meta ${metas.size}개 (1개여야 함)") + metas.map(::at), metas.map(ctx::range))
        val m = metas.single()
        if (m.problem != null) return ctx.finding(id, FAIL, listOf("${at(m)}: ${m.problem}"), listOf(ctx.range(m)))
        val end = minOf(m.end, ctx.length)
        if (m.payload + 4 > end) return ctx.finding(id, FAIL, listOf("${at(m)}: FullBox version/flags 없음"), listOf(ctx.range(m)))
        if (m.children.firstOrNull()?.offset == m.payload)
            return ctx.finding(id, FAIL, listOf("${at(m)}: FullBox가 아님 (version/flags 없이 하위 박스가 바로 시작)"), listOf(ctx.range(m)))
        val version = ctx.reader.readUInt8(m.payload)
        if (version != 0) return ctx.finding(id, FAIL, listOf("${at(m)}: meta version $version (0이어야 함)"), listOf(ctx.range(m)))
        return ctx.finding(id, PASS, listOf("최상위 ${at(m)} 1개 (FullBox version 0)"))
    }

    private fun metaRequired(ctx: HeifContext): ConformanceFinding {
        val id = "heif.meta.required"
        val meta = ctx.model.meta ?: return ctx.finding(id, NOT_CHECKED, listOf("최상위 meta 없음"))
        val details = ArrayList<String>()
        val ranges = ArrayList<LongRange>()
        for (type in listOf("hdlr", "pitm", "iinf", "iloc")) {
            val found = meta.children.filter { it.type == type }
            when {
                found.isEmpty() -> { details += "${at(meta)}: $type 없음 (1개여야 함)"; ranges += ctx.range(meta) }
                found.size > 1 -> { details += "${at(meta)}: $type ${found.size}개 (1개여야 함)"; ranges += found.map(ctx::range) }
            }
        }
        for (type in listOf("iprp", "iref", "idat", "dinf")) {
            val found = meta.children.filter { it.type == type }
            if (found.size > 1) { details += "${at(meta)}: $type ${found.size}개 (최대 1개)"; ranges += found.map(ctx::range) }
        }
        val hdlr = meta.children.firstOrNull { it.type == "hdlr" }
        if (hdlr != null) when (val h = ctx.model.handler) {
            null -> { details += "${at(hdlr)}: handler_type을 읽지 못함"; ranges += ctx.range(hdlr) }
            "pict" -> Unit
            else -> { details += "${at(hdlr)}: handler_type '$h' ('pict'여야 함)"; ranges += ctx.range(hdlr) }
        }
        if (details.isNotEmpty()) return ctx.finding(id, FAIL, details, ranges)
        val optional = listOf("iprp", "iref", "idat", "dinf").filter { t -> meta.children.any { it.type == t } }
        return ctx.finding(id, PASS, listOf("hdlr('pict')/pitm/iinf/iloc 각 1개" +
            if (optional.isEmpty()) "" else ", ${optional.joinToString("/")} 각 1개"))
    }

    // ---- items ----

    private fun itemIds(ctx: HeifContext): ConformanceFinding {
        val id = "heif.items.ids"
        val m = ctx.model
        val iinf = m.meta?.children?.firstOrNull { it.type == "iinf" }
            ?: return ctx.finding(id, NOT_CHECKED, listOf("iinf 없음"))
        ctx.capped("iinf").takeIf { it.isNotEmpty() }?.let { return ctx.finding(id, NOT_CHECKED, it) }
        if (m.iinfEntryCount == null) return ctx.finding(id, FAIL,
            listOf("${at(iinf)}: entry_count를 읽지 못함") + ctx.problemsOf("iinf"), listOf(ctx.range(iinf)))
        val fails = ArrayList<String>()
        val ranges = ArrayList<LongRange>()
        val warns = ArrayList<String>()
        fails += ctx.problemsOf("iinf")
        // A truncated infe is dropped by the model (guarded read); string issues keep the item.
        val infeProblems = ctx.problemsOf("infe")
        val dropped = infeProblems.filter(::isHeifUnreadableProblem)
        fails += dropped
        warns += infeProblems - dropped.toSet()
        for (item in m.items) {
            if (item.infeVersion !in 2..3) {
                fails += "${at(item.infe)}: infe version ${item.infeVersion} (2 또는 3이어야 함, item_ID ${item.id})"
                ranges += ctx.range(item.infe)
            }
            if (item.id == 0L) { fails += "${at(item.infe)}: item_ID 0"; ranges += ctx.range(item.infe) }
        }
        ctx.itemsById.filter { it.value.size > 1 }.forEach { (itemId, list) ->
            fails += "item_ID $itemId 가 ${list.size}번 선언됨 (" + list.joinToString(", ") { at(it.infe) } + ")"
            ranges += list.map { ctx.range(it.infe) }
        }
        if (fails.isNotEmpty()) return ctx.finding(id, FAIL, fails.capped() + moreLine(fails.size) + warns.capped(), ranges.capped())
        if (warns.isNotEmpty()) return ctx.finding(id, WARN, warns.capped() + moreLine(warns.size), listOf(ctx.range(iinf)))
        return ctx.finding(id, PASS, listOf("항목 ${m.items.size}개: infe version 2/3, item_ID 고유·0 아님, entry_count 일치"))
    }

    private fun itemsPrimary(ctx: HeifContext): ConformanceFinding {
        val id = "heif.items.primary"
        val m = ctx.model
        val meta = m.meta ?: return ctx.finding(id, NOT_CHECKED, listOf("최상위 meta 없음"))
        val pitm = meta.children.firstOrNull { it.type == "pitm" } ?: return ctx.finding(id, NOT_CHECKED, listOf("pitm 없음"))
        val primary = m.primaryId ?: return ctx.finding(id, FAIL,
            listOf("${at(pitm)}: item_ID를 읽지 못함") + ctx.problemsOf("pitm"), listOf(ctx.range(pitm)))
        ctx.capped("iinf").takeIf { it.isNotEmpty() }?.let { return ctx.finding(id, NOT_CHECKED, it) }
        if (meta.children.none { it.type == "iinf" } || m.iinfEntryCount == null)
            return ctx.finding(id, NOT_CHECKED, listOf("iinf를 읽지 못해 주 항목 $primary 을(를) 확인할 수 없음"))
        val item = ctx.itemsById[primary]?.firstOrNull() ?: return ctx.finding(id, FAIL,
            listOf("pitm item_ID $primary 이(가) iinf에 선언되지 않음"), listOf(ctx.range(pitm)))
        val fails = ArrayList<String>()
        if (item.hidden) fails += "주 항목 $primary (${at(item.infe)}) 이(가) 숨김(flags & 1) 항목"
        val type = item.type
        when {
            type in METADATA_TYPES -> fails += "주 항목 $primary 의 유형 '$type' 은(는) 메타데이터 항목 (이미지 항목이어야 함)"
            type in CODED_IMAGE_TYPES || type in DERIVED_TYPES -> Unit
            fails.isEmpty() -> return ctx.finding(id, NOT_CHECKED,
                listOf("주 항목 $primary 의 유형 '${type.ifEmpty { "(없음)" }}': 이미지 항목인지 확인할 수 없음"))
        }
        if (fails.isNotEmpty()) return ctx.finding(id, FAIL, fails, listOf(ctx.range(item.infe)))
        return ctx.finding(id, PASS, listOf("주 항목 $primary: '$type' 이미지 항목, 숨김 아님"))
    }

    // ---- item locations ----

    private fun ilocBox(ctx: HeifContext): BmffBox? = ctx.model.meta?.children?.firstOrNull { it.type == "iloc" }

    private fun ilocProblems(ctx: HeifContext) = ctx.problemsOf("iloc")
    /** iloc problems that may have hidden entries (truncation, bad field sizes): not skipped entries, not trailing bytes. */
    private fun ilocIncomplete(ctx: HeifContext) =
        ilocProblems(ctx).filter { heifSkippedItemId(it) == null && !isHeifTrailingBytesProblem(it) && !isHeifCapProblem(it) }

    /** Non-null = the iloc rules cannot be evaluated; the finding to return. */
    private fun ilocUnavailable(ctx: HeifContext, id: String, needItems: Boolean): ConformanceFinding? {
        if (ctx.model.meta == null) return ctx.finding(id, NOT_CHECKED, listOf("최상위 meta 없음"))
        if (ilocBox(ctx) == null) return ctx.finding(id, NOT_CHECKED, listOf("iloc 없음"))
        val capped = ctx.capped("iloc") + if (needItems) ctx.capped("iinf") else emptyList()
        if (capped.isNotEmpty()) return ctx.finding(id, NOT_CHECKED, capped)
        val v = ctx.model.ilocVersion
        if (v == null) return ctx.finding(id, NOT_CHECKED, listOf("iloc 버전을 읽지 못함") + ilocProblems(ctx))
        if (v > 2) return ctx.finding(id, NOT_CHECKED, listOf("iloc 버전 $v: 해석할 수 없음"))
        if (needItems && ctx.model.iinfEntryCount == null) return ctx.finding(id, NOT_CHECKED, listOf("iinf를 읽지 못함"))
        return null
    }

    private fun ilocItems(ctx: HeifContext): ConformanceFinding {
        val id = "heif.iloc.items"
        ilocUnavailable(ctx, id, needItems = true)?.let { return it }
        val iloc = ilocBox(ctx) ?: return ctx.finding(id, NOT_CHECKED, listOf("iloc 없음"))
        val m = ctx.model
        val skipped = ilocProblems(ctx).mapNotNull(::heifSkippedItemId)
        val counts = HashMap<Long, Int>()
        (m.locations.map { it.itemId } + skipped).forEach { counts[it] = (counts[it] ?: 0) + 1 }
        val fails = ArrayList<String>()
        val warns = ArrayList<String>()
        val unchecked = ArrayList<String>()
        val ranges = ArrayList<LongRange>()
        // Truncation / bad field sizes mean entries may be missing; wrapped offsets are reported by heif.iloc.extents.
        ilocIncomplete(ctx).forEach { fails += it; ranges += ctx.range(iloc) }
        ilocProblems(ctx).filter(::isHeifTrailingBytesProblem).forEach { warns += it }
        for (item in m.items) {
            val n = counts[item.id] ?: 0
            when {
                n > 1 -> { fails += "item_ID ${item.id} ('${item.type}'): iloc 항목 ${n}개 (1개여야 함)"; ranges += ctx.range(iloc) }
                n == 1 || item.type == "iden" -> Unit
                item.type in CODED_IMAGE_TYPES || item.type in DERIVED_TYPES || item.type in METADATA_TYPES -> {
                    fails += "item_ID ${item.id} ('${item.type}'): iloc 항목 없음 (데이터가 필요한 항목)"; ranges += ctx.range(item.infe)
                }
                else -> unchecked += "item_ID ${item.id} ('${item.type.ifEmpty { "(없음)" }}'): iloc 항목 없음 — 알 수 없는 유형이라 데이터 필요 여부 미검사"
            }
        }
        val declared = ctx.itemsById.keys
        counts.keys.filter { it !in declared }.sorted().forEach {
            fails += "iloc item_ID $it 이(가) iinf에 선언되지 않음"; ranges += ctx.range(iloc)
        }
        if (fails.isNotEmpty()) return ctx.finding(id, FAIL,
            fails.capped() + moreLine(fails.size) + warns + unchecked.capped(), ranges.distinct().capped())
        if (warns.isNotEmpty()) return ctx.finding(id, WARN, warns + unchecked.capped(), listOf(ctx.range(iloc)))
        if (unchecked.isNotEmpty()) return ctx.finding(id, NOT_CHECKED, unchecked.capped() + moreLine(unchecked.size))
        val withoutData = m.items.count { (counts[it.id] ?: 0) == 0 }
        return ctx.finding(id, PASS, listOf("항목 ${m.items.size}개 중 ${m.items.size - withoutData}개가 iloc 항목 1개씩 가짐" +
            if (withoutData > 0) ", iden ${withoutData}개는 데이터 없음 (허용)" else ""))
    }

    private fun ilocMethod(ctx: HeifContext): ConformanceFinding {
        val id = "heif.iloc.method"
        val m = ctx.model
        val maybeIloc = ilocBox(ctx)
        val maybeVersion = m.ilocVersion
        if (maybeIloc != null && maybeVersion != null && maybeVersion > 2) return ctx.finding(id, FAIL,
            listOf("${at(maybeIloc)}: 정의되지 않은 iloc 버전 $maybeVersion (0~2)"), listOf(ctx.range(maybeIloc)))
        ilocUnavailable(ctx, id, needItems = false)?.let { return it }
        val iloc = maybeIloc ?: return ctx.finding(id, NOT_CHECKED, listOf("iloc 없음"))
        val v = maybeVersion ?: return ctx.finding(id, NOT_CHECKED, listOf("iloc 버전을 읽지 못함"))
        val fails = ArrayList<String>()
        val warns = ArrayList<String>()
        val ranges = ArrayList<LongRange>()
        val ilocRefs = m.references.filter { it.type == "iloc" }.map { it.from }.toSet()
        for (loc in m.locations) {
            when (loc.method) {
                0 -> Unit
                1 -> if (m.idat == null) { fails += "item_ID ${loc.itemId}: construction_method 1 (idat)인데 idat 없음"; ranges += ctx.range(iloc) }
                2 -> if (loc.itemId !in ilocRefs) {
                    fails += "item_ID ${loc.itemId}: construction_method 2 (항목)인데 iref 'iloc' 참조 없음"; ranges += ctx.range(iloc)
                }
                else -> { fails += "item_ID ${loc.itemId}: 정의되지 않은 construction_method ${loc.method}"; ranges += ctx.range(iloc) }
            }
        }
        // v0 entries are implicitly method 0; an idat nobody references is allowed but suspicious.
        if (v == 0 && m.idat != null) warns += "${at(m.idat)}: idat가 있지만 iloc v0은 idat를 가리킬 수 없음 (사용되지 않는 idat)"
        if (fails.isNotEmpty()) return ctx.finding(id, FAIL, fails.capped() + moreLine(fails.size) + warns, ranges.distinct())
        val incomplete = ilocIncomplete(ctx)
        if (incomplete.isNotEmpty()) return ctx.finding(id, NOT_CHECKED,
            listOf("iloc을 끝까지 읽지 못해 일부 항목의 construction_method를 확인할 수 없음") + incomplete + warns)
        if (warns.isNotEmpty()) return ctx.finding(id, WARN, warns, listOfNotNull(m.idat?.let(ctx::range)))
        if (m.locations.isEmpty()) return ctx.finding(id, NOT_CHECKED, listOf("읽은 iloc 항목 없음"))
        val byMethod = m.locations.groupingBy { it.method }.eachCount().toSortedMap()
        return ctx.finding(id, PASS, listOf("iloc 버전 $v, " + byMethod.entries.joinToString(", ") { "방법 ${it.key}: ${it.value}개" }))
    }

    private fun ilocExtents(ctx: HeifContext): ConformanceFinding {
        val id = "heif.iloc.extents"
        ilocUnavailable(ctx, id, needItems = false)?.let { return it }
        val m = ctx.model
        val iloc = ilocBox(ctx) ?: return ctx.finding(id, NOT_CHECKED, listOf("iloc 없음"))
        val fails = ArrayList<String>()
        val ranges = ArrayList<LongRange>()
        val unchecked = ArrayList<String>()
        ilocProblems(ctx).filter { heifSkippedItemId(it) != null }.forEach { fails += it; ranges += ctx.range(iloc) }
        val idat = m.idat
        val idatSize = idat?.let { minOf(it.end, ctx.length) - it.payload }
        var checked = 0
        for (loc in m.locations) {
            if (loc.extents.isEmpty()) {
                fails += "item_ID ${loc.itemId}: extent_count 0 (1 이상이어야 함)"; ranges += ctx.range(iloc); continue
            }
            val limit: Long
            val base: Long
            val where: String
            when {
                loc.method == 0 && loc.dataReferenceIndex != 0 -> {
                    unchecked += "item_ID ${loc.itemId}: data_reference_index ${loc.dataReferenceIndex} (외부 데이터) — 범위 미검사"; continue
                }
                loc.method == 0 -> { limit = ctx.length; base = 0; where = "파일 길이 ${ctx.length}" }
                loc.method == 1 && idat != null && idatSize != null -> { limit = idatSize; base = idat.payload; where = "idat 페이로드 $idatSize 바이트" }
                loc.method == 1 -> { unchecked += "item_ID ${loc.itemId}: idat 없음 — 범위 미검사 (heif.iloc.method 참조)"; continue }
                else -> { unchecked += "item_ID ${loc.itemId}: construction_method ${loc.method} — 범위 미검사"; continue }
            }
            loc.extents.forEachIndexed { i, e ->
                // extent_length 0 = the rest of the referenced data.
                val ok = e.offset <= limit && (e.length == 0L || e.length <= limit - e.offset)
                if (!ok) {
                    fails += "item_ID ${loc.itemId} extent ${i + 1}: offset ${e.offset} + length ${e.length} 이(가) $where 를 넘음"
                    val start = (base + e.offset).coerceIn(0, ctx.length)
                    ranges += if (start < ctx.length) start until ctx.length else ctx.range(iloc)
                }
            }
            checked++
        }
        if (fails.isNotEmpty()) return ctx.finding(id, FAIL, fails.capped() + moreLine(fails.size) + unchecked.capped(), ranges.distinct().capped())
        val incomplete = ilocIncomplete(ctx)
        if (incomplete.isNotEmpty()) return ctx.finding(id, NOT_CHECKED,
            listOf("iloc을 끝까지 읽지 못해 일부 항목의 익스텐트를 확인할 수 없음") + incomplete + unchecked.capped())
        if (unchecked.isNotEmpty()) return ctx.finding(id, NOT_CHECKED, unchecked.capped() + moreLine(unchecked.size) +
            "나머지 ${checked}개 항목의 익스텐트는 범위 안")
        if (m.locations.isEmpty()) return ctx.finding(id, NOT_CHECKED, listOf("읽은 iloc 항목 없음"))
        return ctx.finding(id, PASS, listOf("iloc 항목 ${checked}개의 익스텐트가 모두 파일/idat 범위 안"))
    }
}
