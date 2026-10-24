package com.multiviewer.parser.conformance.isobmff

import com.multiviewer.parser.ByteReader
import com.multiviewer.parser.conformance.ConformanceFinding
import com.multiviewer.parser.conformance.ConformanceReport
import com.multiviewer.parser.conformance.ConformanceStatus
import com.multiviewer.parser.conformance.ConformanceStatus.FAIL
import com.multiviewer.parser.conformance.ConformanceStatus.NOT_APPLICABLE
import com.multiviewer.parser.conformance.ConformanceStatus.NOT_CHECKED
import com.multiviewer.parser.conformance.ConformanceStatus.PASS
import com.multiviewer.parser.conformance.ConformanceStatus.WARN
import java.util.concurrent.CancellationException

/** Shared state for every rule group: the walked tree, the reader (bounded reads only) and the chosen profile. */
internal class BmffContext(val tree: BmffTree, val reader: ByteReader, val profile: BmffProfile) {
    val length: Long = reader.length
    val isQt: Boolean get() = profile == BmffProfile.QUICKTIME

    /** Every box in document order (depth-first). */
    val allBoxes: List<BmffBox> by lazy { flatten(tree.top) }
    val moovs: List<BmffBox> get() = tree.top.filter { it.type == "moov" }
    val traks: List<BmffBox> get() = moovs.flatMap { m -> m.children.filter { it.type == "trak" } }

    fun finding(id: String, status: ConformanceStatus, details: List<String>, ranges: List<LongRange> = emptyList()) =
        ConformanceFinding(IsoBmffRules.rule(id, profile), status, details, ranges)

    fun range(b: BmffBox): LongRange = b.offset until minOf(b.end, length).coerceAtLeast(b.offset)

    fun fourCC(at: Long): String? = if (at >= 0 && at + 4 <= length) reader.readFourCC(at) else null

    /** hdlr pre_defined / QuickTime component type (payload + 4). */
    fun hdlrComponent(b: BmffBox): String? = if (b.payload + 8 <= b.end) fourCC(b.payload + 4) else null
    /** hdlr handler_type / QuickTime component subtype (payload + 8). */
    fun hdlrHandler(b: BmffBox): String? = if (b.payload + 12 <= b.end) fourCC(b.payload + 8) else null

    companion object {
        fun flatten(boxes: List<BmffBox>): List<BmffBox> = boxes.flatMap { listOf(it) + flatten(it.children) }
    }
}

internal fun at(b: BmffBox): String = "${b.type} @0x%X".format(b.offset)

/** A group of rules evaluated over the shared context; later rule groups live in their own files. */
internal typealias BmffRuleGroup = (BmffContext) -> List<ConformanceFinding>

object IsoBmffConformanceChecker {
    private val ruleGroups: List<BmffRuleGroup> = listOf(
        IsoBmffStructureRules::evaluate,
        IsoBmffFieldRules::evaluate,
        IsoBmffSampleRules::evaluate,
    )

    fun check(reader: ByteReader): ConformanceReport {
        var profile = BmffProfile.ISO
        val findings = ArrayList<ConformanceFinding>()
        val error = try {
            val tree = IsoBmffBoxTree.walk(reader)
            profile = profileOf(tree, reader)
            val ctx = BmffContext(tree, reader, profile)
            for (group in ruleGroups) findings += group(ctx)
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            "검사 중 오류: ${e.javaClass.simpleName}: ${e.message ?: ""}".trim()
        }
        return ConformanceReport(
            format = if (profile == BmffProfile.QUICKTIME) "QuickTime" else "ISO-BMFF",
            profile = if (profile == BmffProfile.QUICKTIME) "QuickTime File Format phase 1" else "ISO/IEC 14496-12 phase 1",
            findings = findings,
            limitations = listOf(
                "코덱 비트스트림 구문 전체는 검사하지 않습니다 / Codec bitstream syntax is not fully checked",
                "규칙은 공개 자료 기반이며 규격 원문과 대조하지 않았습니다 / Rules are based on public material, not checked against the standard text",
            ),
            inspectionError = error,
        )
    }

    /** `ftyp` major brand `qt  ` → QT; no `ftyp` and a top-level `wide` or an `mhlr` hdlr → QT; otherwise ISO. */
    internal fun profileOf(tree: BmffTree, reader: ByteReader): BmffProfile {
        val ftyp = tree.top.firstOrNull { it.type == "ftyp" }
        if (ftyp != null) {
            val major = if (ftyp.payload + 4 <= minOf(ftyp.end, reader.length)) reader.readFourCC(ftyp.payload) else null
            return if (major == "qt  ") BmffProfile.QUICKTIME else BmffProfile.ISO
        }
        if (tree.top.any { it.type == "wide" }) return BmffProfile.QUICKTIME
        val ctx = BmffContext(tree, reader, BmffProfile.ISO)
        val mhlr = ctx.allBoxes.any { it.type == "hdlr" && it.problem == null && ctx.hdlrComponent(it) == "mhlr" }
        return if (mhlr) BmffProfile.QUICKTIME else BmffProfile.ISO
    }
}

/** Box structure, ftyp, required-box and allowed-parent rules. */
internal object IsoBmffStructureRules {
    private const val MAX_BRANDS = 4096
    private const val MAX_UNKNOWN_LISTED = 50
    /** Unparsed bytes inside a container (other than meta) at or above this size fail bmff.box.size. */
    private const val MIN_LEFTOVER_FAIL = 8L

    private val PREFTYP_ALLOWED = setOf("free", "skip", "wide", "uuid")
    private const val MAX_BRANDS_SHOWN = 16
    /** gmhd (QuickTime base media header) counts in both profiles: ffmpeg's default MP4 chapter track uses it. */
    private val MEDIA_HEADERS = setOf("vmhd", "smhd", "hmhd", "nmhd", "sthd", "gmhd")
    private val ISO_BRANDS = setOf(
        "isom", "avc1", "avc2", "avc3", "avc4", "mp41", "mp42", "mp71", "M4A ", "M4V ", "M4P ", "M4B ", "M4VH", "M4VP",
        "f4v ", "f4a ", "f4p ", "f4b ", "dash", "msdh", "msix", "cmfc", "cmf2", "mmp4", "MSNV", "XAVC", "iso2", "iso3",
        "iso4", "iso5", "iso6", "iso7", "iso8", "iso9", "isoa", "isob", "isoc",
    )

    private const val TOP = ""
    private val ANYWHERE = setOf("free", "skip", "uuid")
    private val QT_ANYWHERE = ANYWHERE + "wide"

    /** type → allowed parent types ("" = top level). QuickTime additions are merged in [QT_PARENTS]. */
    private val ISO_PARENTS: Map<String, Set<String>> = buildMap {
        fun put(parent: String, vararg types: String) = types.forEach { t -> put(t, (get(t) ?: emptySet()) + parent) }
        put(TOP, "ftyp", "moov", "mdat", "meta", "moof", "mfra", "sidx", "styp", "pdin")
        put("moov", "mvhd", "trak", "udta", "meta", "mvex", "iods")
        put("trak", "tkhd", "mdia", "edts", "tref", "udta", "meta")
        put("edts", "elst")
        put("mdia", "mdhd", "hdlr", "minf")
        put("minf", "vmhd", "smhd", "hmhd", "nmhd", "sthd", "gmhd", "dinf", "stbl")
        put("dinf", "dref")
        put("stbl", "stsd", "stts", "ctts", "stss", "stsc", "stsz", "stz2", "stco", "co64", "sdtp", "sbgp", "sgpd",
            "subs", "saiz", "saio", "cslg", "stsh")
        // udta>meta is ffmpeg/iTunes practice (not in 14496-12's meta container list); accepted so real files pass.
        put("udta", "meta")
        // 14496-12 meta's own handler/data information, and meta/udta/sample-group boxes inside fragments.
        put("meta", "hdlr", "dinf")
        put("moof", "udta", "meta")
        put("traf", "udta", "meta", "sdtp", "sbgp", "sgpd", "subs", "saiz", "saio")
    }
    private val QT_PARENTS: Map<String, Set<String>> = ISO_PARENTS.toMutableMap().apply {
        put("hdlr", getValue("hdlr") + "minf")
        put("stps", setOf("stbl"))
    }

    fun evaluate(ctx: BmffContext): List<ConformanceFinding> = listOf(
        boxSize(ctx), boxSize0(ctx), boxTrailing(ctx),
        ftypPresent(ctx), ftypPosition(ctx), ftypBrands(ctx),
        moovCount(ctx), mvhdCount(ctx), trakCount(ctx), trakRequired(ctx), mediaHeaderMatch(ctx),
        boxParent(ctx),
    )

    private fun noBoxes(ctx: BmffContext, id: String) = ctx.finding(id, NOT_CHECKED, listOf("유효한 박스가 없음"))

    private fun boxSize(ctx: BmffContext): ConformanceFinding {
        if (ctx.allBoxes.isEmpty()) return noBoxes(ctx, "bmff.box.size")
        val bad = ctx.allBoxes.filter { it.problem != null }
        // Shorter leftovers are tolerated: QuickTime ends some containers (udta) with a 4-byte zero terminator.
        val left = ctx.tree.leftovers.filter { it.bytes >= MIN_LEFTOVER_FAIL }
        if (bad.isEmpty() && left.isEmpty())
            return ctx.finding("bmff.box.size", PASS, listOf("모든 박스 크기가 헤더 이상이며 상위 범위 안에 있음 (${ctx.allBoxes.size}개)"))
        return ctx.finding("bmff.box.size", FAIL,
            bad.map { "${at(it)}${it.parent?.let { p -> " ($p 안)" } ?: ""}: ${it.problem}" } +
                left.map { "${it.bytes}바이트가 ${it.container} 안에서 박스로 해석되지 않습니다 @0x%X".format(it.offset) },
            bad.map(ctx::range) + left.map { it.offset until minOf(it.offset + it.bytes, ctx.length) })
    }

    private fun boxSize0(ctx: BmffContext): ConformanceFinding {
        if (ctx.allBoxes.isEmpty()) return noBoxes(ctx, "bmff.box.size0")
        val nested = ctx.allBoxes.filter { it.rawSize == 0L && it.parent != null }
        if (nested.isNotEmpty()) return ctx.finding("bmff.box.size0", FAIL,
            nested.map { "${it.parent} 안의 ${at(it)}: 크기 0(파일 끝까지)은 최상위 박스에만 허용" }, nested.map(ctx::range))
        val top = ctx.tree.top.filter { it.rawSize == 0L }
        return ctx.finding("bmff.box.size0", PASS,
            if (top.isEmpty()) listOf("크기 0 박스 없음") else top.map { "최상위 ${at(it)}: 크기 0 (파일 끝까지, 마지막 박스)" })
    }

    private fun boxTrailing(ctx: BmffContext): ConformanceFinding {
        val t = ctx.tree
        if (t.trailingBytes > 0 && t.trailingOffset != null) return ctx.finding("bmff.box.trailing", FAIL,
            listOf("마지막 최상위 박스 뒤 ${t.trailingBytes}바이트(오프셋 0x%X)가 박스가 아님".format(t.trailingOffset)),
            listOf(t.trailingOffset until t.trailingOffset + t.trailingBytes))
        if (t.top.isEmpty()) return noBoxes(ctx, "bmff.box.trailing")
        return ctx.finding("bmff.box.trailing", PASS, listOf("마지막 최상위 박스가 파일 끝에서 끝남"))
    }

    private fun ftyps(ctx: BmffContext) = ctx.tree.top.filter { it.type == "ftyp" }

    private fun ftypPresent(ctx: BmffContext): ConformanceFinding {
        val f = ftyps(ctx).firstOrNull()
        return when {
            f != null -> ctx.finding("bmff.ftyp.present", PASS, listOf("${at(f)}"), listOf(ctx.range(f)))
            ctx.isQt -> ctx.finding("bmff.ftyp.present", PASS, listOf("ftyp 없음 — QuickTime: 선택 사항"))
            else -> ctx.finding("bmff.ftyp.present", WARN, listOf("ftyp 없음 (ftyp 이전 형식 파일은 호환성 때문에 허용되나 권장되지 않음)"))
        }
    }

    private fun ftypPosition(ctx: BmffContext): ConformanceFinding {
        val all = ftyps(ctx)
        if (all.isEmpty()) return ctx.finding("bmff.ftyp.position", NOT_APPLICABLE, listOf("ftyp 없음"))
        val idx = ctx.tree.top.indexOf(all.first())
        val before = ctx.tree.top.take(idx).filter { it.type !in PREFTYP_ALLOWED }
        val details = ArrayList<String>()
        val ranges = ArrayList<LongRange>()
        before.forEach { details += "${at(it)}가 ftyp(0x%X)보다 앞에 있음 (free/skip/wide/uuid만 허용)".format(all.first().offset); ranges += ctx.range(it) }
        all.drop(1).forEach { details += "${at(it)}: ftyp 중복"; ranges += ctx.range(it) }
        if (details.isNotEmpty()) return ctx.finding("bmff.ftyp.position", FAIL, details, ranges)
        return ctx.finding("bmff.ftyp.position", PASS, listOf("${at(all.first())}가 moov/mdat보다 앞에 있음"))
    }

    private fun printable(b: String) = b.length == 4 && b.all { it.code in 0x20..0x7E }
    private fun isIsoBrand(b: String) = b in ISO_BRANDS || b.startsWith("3g")
    private fun show(b: String) = b.map { if (it.code in 0x20..0x7E) it else '?' }.joinToString("") +
        " (" + b.map { "%02X".format(it.code) }.joinToString("") + ")"

    private fun ftypBrands(ctx: BmffContext): ConformanceFinding {
        val f = ftyps(ctx).firstOrNull() ?: return ctx.finding("bmff.ftyp.brands", NOT_APPLICABLE, listOf("ftyp 없음"))
        if (f.problem != null) return ctx.finding("bmff.ftyp.brands", NOT_CHECKED, listOf("${at(f)}: 박스 헤더 오류로 브랜드를 읽지 않음"))
        val payload = f.size - f.headerSize
        val fails = ArrayList<String>()
        if (payload < 8 || (payload - 8) % 4 != 0L)
            fails += "${at(f)}: 크기 ${f.size}가 헤더 + 8 + 4·n 형식이 아님 (페이로드 ${payload}바이트)"
        if (payload < 4) return ctx.finding("bmff.ftyp.brands", FAIL, fails, listOf(ctx.range(f)))
        val major = String(ctx.reader.readBytes(f.payload, 4), Charsets.ISO_8859_1)
        val compatCount = if (payload >= 8) ((payload - 8) / 4).coerceAtMost(MAX_BRANDS.toLong()).toInt() else 0
        val compat = (0 until compatCount).map { i -> String(ctx.reader.readBytes(f.payload + 8 + 4L * i, 4), Charsets.ISO_8859_1) }
        if (!printable(major)) fails += "major_brand ${show(major)}: 인쇄 가능한 4문자가 아님"
        compat.forEachIndexed { i, b -> if (!printable(b)) fails += "compatible_brands[$i] ${show(b)}: 인쇄 가능한 4문자가 아님" }
        val shown = compat.take(MAX_BRANDS_SHOWN).joinToString(", ") { "'$it'" } +
            if (compat.size > MAX_BRANDS_SHOWN) ", … 외 ${compat.size - MAX_BRANDS_SHOWN}개" else ""
        val summary = "major '$major', compatible [$shown]"
        val totalCompat = if (payload >= 8) (payload - 8) / 4 else 0L
        val capLine = if (totalCompat > MAX_BRANDS) "compatible_brands ${totalCompat}개 중 처음 ${MAX_BRANDS}개만 검사함 (상한)" else null
        val notes = listOfNotNull(capLine)
        if (fails.isNotEmpty()) return ctx.finding("bmff.ftyp.brands", FAIL, fails + summary + notes, listOf(ctx.range(f)))
        if (!ctx.isQt && (listOf(major) + compat).none(::isIsoBrand))
            return ctx.finding("bmff.ftyp.brands", WARN, listOf("$summary: 알려진 ISO 계열 브랜드 없음") + notes, listOf(ctx.range(f)))
        return ctx.finding("bmff.ftyp.brands", PASS, listOf(summary) + notes)
    }

    private fun moovCount(ctx: BmffContext): ConformanceFinding {
        val m = ctx.moovs
        return when (m.size) {
            1 -> ctx.finding("bmff.moov.count", PASS, listOf("최상위 ${at(m.single())}"))
            0 -> ctx.finding("bmff.moov.count", FAIL, listOf("최상위 moov 없음"))
            else -> ctx.finding("bmff.moov.count", FAIL, listOf("최상위 moov ${m.size}개 (1개여야 함)") + m.map(::at), m.map(ctx::range))
        }
    }

    private fun mvhdCount(ctx: BmffContext): ConformanceFinding {
        if (ctx.moovs.isEmpty()) return ctx.finding("bmff.mvhd.count", NOT_CHECKED, listOf("moov 없음"))
        val bad = ctx.moovs.filter { m -> m.children.count { it.type == "mvhd" } != 1 }
        if (bad.isEmpty()) return ctx.finding("bmff.mvhd.count", PASS, listOf("moov마다 mvhd 1개"))
        return ctx.finding("bmff.mvhd.count", FAIL,
            bad.map { m -> "${at(m)}: mvhd ${m.children.count { it.type == "mvhd" }}개 (1개여야 함)" },
            bad.flatMap { m -> m.children.filter { it.type == "mvhd" }.map(ctx::range).ifEmpty { listOf(ctx.range(m)) } })
    }

    private fun trakCount(ctx: BmffContext): ConformanceFinding {
        if (ctx.moovs.isEmpty()) return ctx.finding("bmff.trak.count", NOT_CHECKED, listOf("moov 없음"))
        val n = ctx.traks.size
        return when {
            n >= 1 -> ctx.finding("bmff.trak.count", PASS, listOf("trak ${n}개"))
            isReferenceMovie(ctx) -> ctx.finding("bmff.trak.count", NOT_APPLICABLE,
                listOf("QuickTime 참조 무비(rmra): trak 없이 다른 무비를 참조함"))
            else -> ctx.finding("bmff.trak.count", FAIL, listOf("moov 안에 trak 없음"), ctx.moovs.map(ctx::range))
        }
    }

    /** QuickTime reference movie: moov holds `rmra` (reference movie atom) instead of tracks. */
    private fun isReferenceMovie(ctx: BmffContext) = ctx.traks.isEmpty() && ctx.moovs.any { m -> m.children.any { it.type == "rmra" } }

    private fun trakRequired(ctx: BmffContext): ConformanceFinding {
        val traks = ctx.traks
        if (isReferenceMovie(ctx)) return ctx.finding("bmff.trak.required", NOT_APPLICABLE, listOf("QuickTime 참조 무비(rmra): trak 없음"))
        if (traks.isEmpty()) return ctx.finding("bmff.trak.required", NOT_CHECKED, listOf("trak 없음"))
        val details = ArrayList<String>()
        val ranges = ArrayList<LongRange>()
        val headers = MEDIA_HEADERS
        traks.forEachIndexed { i, trak ->
            val label = "trak #${i + 1} @0x%X".format(trak.offset)
            fun need(parent: BmffBox, types: Set<String>, name: String, min: Int, max: Int): BmffBox? {
                val found = parent.children.filter { it.type in types }
                if (found.size < min || found.size > max) {
                    val expect = if (min == max) "${min}개여야 함" else "최대 ${max}개"
                    details += if (found.isEmpty()) "$label ${parent.type}: $name 없음"
                    else "$label ${parent.type}: $name ${found.size}개 ($expect)"
                    ranges += if (found.isEmpty()) listOf(ctx.range(parent)) else found.map(ctx::range)
                }
                return found.firstOrNull()
            }
            fun one(parent: BmffBox, type: String) = need(parent, setOf(type), type, 1, 1)
            fun atMostOne(parent: BmffBox, type: String) = need(parent, setOf(type), type, 0, 1)

            one(trak, "tkhd")
            atMostOne(trak, "edts")
            val mdia = one(trak, "mdia") ?: return@forEachIndexed
            one(mdia, "mdhd")
            one(mdia, "hdlr")
            val minf = one(mdia, "minf") ?: return@forEachIndexed
            need(minf, headers, "미디어 헤더(${headers.joinToString("/")})", 1, 1)
            if (ctx.isQt) need(minf, setOf("hdlr"), "데이터 핸들러 hdlr", 0, 1)
            one(minf, "dinf")?.let { one(it, "dref") }
            val stbl = one(minf, "stbl") ?: return@forEachIndexed
            one(stbl, "stsd")
            one(stbl, "stts")
            one(stbl, "stsc")
            need(stbl, setOf("stsz", "stz2"), "stsz/stz2", 1, 1)
            need(stbl, setOf("stco", "co64"), "stco/co64", 1, 1)
            atMostOne(stbl, "ctts")
            atMostOne(stbl, "stss")
        }
        if (details.isNotEmpty()) return ctx.finding("bmff.trak.required", FAIL, details, ranges)
        return ctx.finding("bmff.trak.required", PASS, listOf("trak ${traks.size}개 모두 필수 박스를 갖춤"))
    }

    /** Media header required for [handler]; null = any handler not listed (nmhd, or gmhd). */
    private fun expectedHeader(handler: String): String? = when (handler) {
        "vide", "auxv", "pict" -> "vmhd"
        "soun" -> "smhd"
        "hint" -> "hmhd"
        "subt" -> "sthd"
        else -> null
    }

    private fun mediaHeaderMatch(ctx: BmffContext): ConformanceFinding {
        if (isReferenceMovie(ctx)) return ctx.finding("bmff.mediaheader.match", NOT_APPLICABLE, listOf("QuickTime 참조 무비(rmra): trak 없음"))
        val fails = ArrayList<String>()
        val warns = ArrayList<String>()
        val passes = ArrayList<String>()
        val failRanges = ArrayList<LongRange>()
        val warnRanges = ArrayList<LongRange>()
        ctx.traks.forEachIndexed { i, trak ->
            val mdia = trak.children.singleOrNull { it.type == "mdia" } ?: return@forEachIndexed
            val hdlr = mdia.children.singleOrNull { it.type == "hdlr" } ?: return@forEachIndexed
            val handler = ctx.hdlrHandler(hdlr) ?: return@forEachIndexed
            val minf = mdia.children.singleOrNull { it.type == "minf" } ?: return@forEachIndexed
            val mh = minf.children.filter { it.type in MEDIA_HEADERS }.singleOrNull() ?: return@forEachIndexed
            val label = "trak #${i + 1} @0x%X".format(trak.offset)
            val expected = expectedHeader(handler)
            when {
                expected != null && mh.type != expected -> {
                    fails += "$label: 핸들러 '$handler'에는 ${expected}가 필요하지만 ${at(mh)}"; failRanges += ctx.range(mh)
                }
                expected == null && mh.type !in setOf("nmhd", "gmhd") -> {
                    fails += "$label: 핸들러 '$handler'에는 nmhd${if (ctx.isQt) "/gmhd" else ""}가 필요하지만 ${at(mh)}"; failRanges += ctx.range(mh)
                }
                expected == null && mh.type == "gmhd" && !ctx.isQt -> {
                    warns += "$label: 핸들러 '$handler' → ${at(mh)}: QuickTime 계열 gmhd (ISO/IEC 14496-12는 nmhd)"; warnRanges += ctx.range(mh)
                }
                else -> passes += "$label: '$handler' → ${mh.type}"
            }
        }
        return when {
            fails.isNotEmpty() -> ctx.finding("bmff.mediaheader.match", FAIL, fails + warns, failRanges + warnRanges)
            warns.isNotEmpty() -> ctx.finding("bmff.mediaheader.match", WARN, warns + passes, warnRanges)
            passes.isNotEmpty() -> ctx.finding("bmff.mediaheader.match", PASS, passes)
            else -> ctx.finding("bmff.mediaheader.match", NOT_CHECKED, listOf("핸들러와 미디어 헤더를 모두 갖춘 trak 없음"))
        }
    }

    private fun boxParent(ctx: BmffContext): ConformanceFinding {
        if (ctx.allBoxes.isEmpty()) return noBoxes(ctx, "bmff.box.parent")
        val table = if (ctx.isQt) QT_PARENTS else ISO_PARENTS
        val anywhere = if (ctx.isQt) QT_ANYWHERE else ANYWHERE
        val details = ArrayList<String>()
        val ranges = ArrayList<LongRange>()
        val warns = ArrayList<String>()
        val warnRanges = ArrayList<LongRange>()
        val unknown = LinkedHashSet<String>()
        // ISO profile: one QuickTime-style data handler hdlr per minf is a tolerated deviation (WARN); more FAIL.
        val qtDataHandlers = HashSet<Long>()
        if (!ctx.isQt) for (minf in ctx.allBoxes.filter { it.type == "minf" }) {
            minf.children.filter { it.type == "hdlr" && it.problem == null }.forEachIndexed { i, h ->
                qtDataHandlers += h.offset
                if (i == 0) { warns += "${at(h)}: minf 안의 QuickTime 계열 data handler hdlr (ISO/IEC 14496-12에는 없음)"; warnRanges += ctx.range(h) }
                else { details += "${at(h)}: minf 안의 hdlr ${i + 1}번째 (data handler는 최대 1개)"; ranges += ctx.range(h) }
            }
        }
        for (b in ctx.allBoxes) {
            if (b.problem != null || b.type in anywhere || b.offset in qtDataHandlers && b.type == "hdlr") continue
            val allowed = table[b.type]
            if (allowed == null) { unknown += b.type; continue }
            val parent = b.parent ?: TOP
            if (parent !in allowed) {
                val where = if (parent == TOP) "최상위" else "$parent 안"
                val ok = allowed.joinToString("/") { if (it == TOP) "최상위" else it }
                details += "${at(b)}: $where 에 있음 (허용: $ok)"
                ranges += ctx.range(b)
            }
        }
        val unknownLine = if (unknown.isEmpty()) null else "알 수 없는 박스(무시 허용): " +
            unknown.take(MAX_UNKNOWN_LISTED).joinToString(", ") + if (unknown.size > MAX_UNKNOWN_LISTED) " 외 ${unknown.size - MAX_UNKNOWN_LISTED}종" else ""
        if (details.isNotEmpty()) return ctx.finding("bmff.box.parent", FAIL, details + warns + listOfNotNull(unknownLine), ranges + warnRanges)
        if (warns.isNotEmpty()) return ctx.finding("bmff.box.parent", WARN, warns + listOfNotNull(unknownLine), warnRanges)
        return ctx.finding("bmff.box.parent", PASS, listOf("알려진 박스가 모두 허용된 상위 박스 안에 있음") + listOfNotNull(unknownLine))
    }
}
