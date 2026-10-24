package com.multiviewer.parser.conformance.isobmff

import com.multiviewer.parser.conformance.ConformanceFinding
import com.multiviewer.parser.conformance.ConformanceStatus.FAIL
import com.multiviewer.parser.conformance.ConformanceStatus.NOT_APPLICABLE
import com.multiviewer.parser.conformance.ConformanceStatus.NOT_CHECKED
import com.multiviewer.parser.conformance.ConformanceStatus.PASS
import com.multiviewer.parser.conformance.ConformanceStatus.WARN

/** Child of [parent] path, e.g. `trak.child("mdia", "minf")`; null if any step is missing or ambiguous. */
internal fun BmffBox.child(vararg path: String): BmffBox? {
    var cur: BmffBox = this
    for (t in path) cur = cur.children.singleOrNull { it.type == t } ?: return null
    return cur
}

/** FullBox version byte, or null when the box is invalid or has no 4-byte FullBox header. */
internal fun BmffContext.fullBoxVersion(b: BmffBox): Int? =
    if (b.problem == null && b.payload + 4 <= b.end) reader.readUInt8(b.payload) else null

internal fun BmffContext.fullBoxFlags(b: BmffBox): Int? =
    if (b.problem == null && b.payload + 4 <= b.end) (reader.readUInt32(b.payload) and 0xFFFFFF).toInt() else null

/**
 * Whether [trak]'s sample data lives in this file, following each `stsd` sample entry's data_reference_index:
 * true when every index points at a self-contained `dref` entry (flags & 1), false when every index points at an
 * external entry, null when mixed or unreadable (invalid index, unreadable dref/stsd). Sample-location rules
 * treat false as NOT_APPLICABLE and null as NOT_CHECKED.
 */
internal fun BmffContext.trackDataSelfContained(trak: BmffBox): Boolean? {
    val dref = trak.child("mdia", "minf", "dinf", "dref") ?: return null
    val stsd = trak.child("mdia", "minf", "stbl", "stsd") ?: return null
    val entries = IsoBmffFieldRules.drefEntries(this, dref).entries ?: return null
    val samples = IsoBmffFieldRules.stsdEntries(this, stsd).entries ?: return null
    if (entries.isEmpty() || samples.isEmpty()) return null
    val refs = samples.map { s -> entries.getOrNull(s.dataReferenceIndex - 1) ?: return null }
    return when {
        refs.all { it.selfContained } -> true
        refs.none { it.selfContained } -> false
        else -> null
    }
}

/** Header-field rules: FullBox versions, timescales, track IDs, vmhd flags, hdlr, language, dref, stsd. */
internal object IsoBmffFieldRules {
    private const val MAX_TABLE_ENTRIES = 2_000_000L
    private const val NEXT_TRACK_NONE = 0xFFFFFFFFL

    private val V01 = setOf("mvhd", "tkhd", "mdhd", "elst", "ctts")
    private val V0 = setOf("hdlr", "stsd", "stts", "stsc", "stsz", "stco", "co64", "stss", "vmhd", "smhd", "dref")

    fun evaluate(ctx: BmffContext): List<ConformanceFinding> = listOf(
        fullBoxVersion(ctx), mvhdTimescale(ctx), mdhdTimescale(ctx), trackId(ctx), nextTrackId(ctx),
        vmhdFlags(ctx), hdlrPreDefined(ctx), mdhdLanguage(ctx), drefRule(ctx), stsdRule(ctx),
    )

    /** Collects per-box outcomes; any unchecked item keeps the rule from reporting PASS. */
    private class Acc(val ctx: BmffContext, val id: String) {
        val fails = ArrayList<String>(); val warns = ArrayList<String>()
        val passes = ArrayList<String>(); val unchecked = ArrayList<String>()
        val ranges = ArrayList<LongRange>()
        fun fail(b: BmffBox, msg: String) { fails += "${at(b)}: $msg"; ranges += ctx.range(b) }
        fun warn(b: BmffBox, msg: String) { warns += "${at(b)}: $msg"; ranges += ctx.range(b) }
        fun pass(b: BmffBox, msg: String) { passes += "${at(b)}: $msg" }
        fun skip(b: BmffBox, msg: String) { unchecked += "${at(b)}: $msg" }
        fun result(none: String, noneStatus: com.multiviewer.parser.conformance.ConformanceStatus = NOT_CHECKED): ConformanceFinding {
            val all = fails + warns + unchecked + passes
            return when {
                fails.isNotEmpty() -> ctx.finding(id, FAIL, all, ranges)
                warns.isNotEmpty() -> ctx.finding(id, WARN, all, ranges)
                unchecked.isNotEmpty() -> ctx.finding(id, NOT_CHECKED, all)
                passes.isNotEmpty() -> ctx.finding(id, PASS, passes)
                else -> ctx.finding(id, noneStatus, listOf(none))
            }
        }
    }

    private fun u32(ctx: BmffContext, at: Long) = ctx.reader.readUInt32(at)

    private fun mvhds(ctx: BmffContext) = ctx.moovs.flatMap { m -> m.children.filter { it.type == "mvhd" } }
    private fun trackBoxes(ctx: BmffContext, vararg path: String): List<BmffBox> =
        ctx.traks.mapNotNull { t -> t.child(*path) }

    private fun fullBoxVersion(ctx: BmffContext): ConformanceFinding {
        val a = Acc(ctx, "bmff.fullbox.version")
        for (b in ctx.allBoxes) {
            val allowed = when (b.type) { in V01 -> setOf(0, 1); in V0 -> setOf(0); else -> continue }
            if (b.problem != null) { a.skip(b, "박스 헤더 오류로 버전을 읽지 않음"); continue }
            val v = ctx.fullBoxVersion(b)
            when {
                v == null -> a.fail(b, "FullBox 헤더(버전/플래그 4바이트)가 없음")
                v !in allowed -> a.fail(b, "version $v (허용: ${allowed.joinToString("/")})")
                else -> {}
            }
        }
        val checked = ctx.allBoxes.count { it.type in V01 || it.type in V0 }
        if (checked > 0 && a.fails.isEmpty() && a.unchecked.isEmpty())
            return ctx.finding(a.id, PASS, listOf("FullBox ${checked}개의 버전이 모두 허용 범위"))
        return a.result("버전을 검사할 FullBox 없음")
    }

    /** timescale offset inside a mvhd/mdhd payload for the given version, or null for an unknown version. */
    private fun timescaleOffset(v: Int): Long? = when (v) { 0 -> 12L; 1 -> 20L; else -> null }

    private fun timescale(ctx: BmffContext, id: String, boxes: List<BmffBox>, missing: String): ConformanceFinding {
        val a = Acc(ctx, id)
        for (b in boxes) {
            if (b.problem != null) { a.skip(b, "박스 헤더 오류"); continue }
            val v = ctx.fullBoxVersion(b) ?: run { a.fail(b, "FullBox 헤더 없음"); null } ?: continue
            val off = timescaleOffset(v) ?: run { a.skip(b, "알 수 없는 version $v — 필드 위치를 알 수 없음"); null } ?: continue
            if (b.payload + off + 4 > b.end) { a.fail(b, "박스가 timescale 필드를 담기에 짧음"); continue }
            val ts = u32(ctx, b.payload + off)
            if (ts == 0L) a.fail(b, "timescale 0 (0보다 커야 함)") else a.pass(b, "timescale $ts")
        }
        return a.result(missing)
    }

    private fun mvhdTimescale(ctx: BmffContext) = timescale(ctx, "bmff.mvhd.timescale", mvhds(ctx), "mvhd 없음")
    private fun mdhdTimescale(ctx: BmffContext) = timescale(ctx, "bmff.mdhd.timescale", trackBoxes(ctx, "mdia", "mdhd"), "mdhd 없음")

    /** track_ID per trak (null = unreadable, reason recorded in [a]). */
    private fun readTrackId(ctx: BmffContext, tkhd: BmffBox, a: Acc?): Long? {
        if (tkhd.problem != null) { a?.skip(tkhd, "박스 헤더 오류"); return null }
        val v = ctx.fullBoxVersion(tkhd) ?: run { a?.fail(tkhd, "FullBox 헤더 없음"); return null }
        val off = when (v) { 0 -> 12L; 1 -> 20L; else -> { a?.skip(tkhd, "알 수 없는 version $v — 필드 위치를 알 수 없음"); return null } }
        if (tkhd.payload + off + 4 > tkhd.end) { a?.fail(tkhd, "박스가 track_ID 필드를 담기에 짧음"); return null }
        return u32(ctx, tkhd.payload + off)
    }

    private fun trackId(ctx: BmffContext): ConformanceFinding {
        val a = Acc(ctx, "bmff.tkhd.track_id")
        val seen = HashMap<Long, BmffBox>()
        for (tkhd in trackBoxes(ctx, "tkhd")) {
            val id = readTrackId(ctx, tkhd, a) ?: continue
            val first = seen[id]
            when {
                id == 0L -> a.fail(tkhd, "track_ID 0 (0이 아니어야 함)")
                first != null -> a.fail(tkhd, "track_ID $id 중복 (${at(first)}와 같음)")
                else -> a.pass(tkhd, "track_ID $id")
            }
            seen.putIfAbsent(id, tkhd)
        }
        return a.result("tkhd 없음")
    }

    private fun nextTrackId(ctx: BmffContext): ConformanceFinding {
        val a = Acc(ctx, "bmff.mvhd.next_track_id")
        val ids = trackBoxes(ctx, "tkhd").mapNotNull { readTrackId(ctx, it, null) }
        val readable = ids.size == ctx.traks.size
        for (mvhd in mvhds(ctx)) {
            if (mvhd.problem != null) { a.skip(mvhd, "박스 헤더 오류"); continue }
            val v = ctx.fullBoxVersion(mvhd) ?: run { a.fail(mvhd, "FullBox 헤더 없음"); null } ?: continue
            val off = when (v) { 0 -> 96L; 1 -> 108L; else -> { a.skip(mvhd, "알 수 없는 version $v — 필드 위치를 알 수 없음"); continue } }
            if (mvhd.payload + off + 4 > mvhd.end) { a.fail(mvhd, "박스가 next_track_ID 필드를 담기에 짧음"); continue }
            val next = u32(ctx, mvhd.payload + off)
            val max = ids.maxOrNull()
            when {
                next == NEXT_TRACK_NONE -> a.pass(mvhd, "next_track_ID 0xFFFFFFFF (다음 ID 없음 — 허용)")
                !readable -> a.skip(mvhd, "next_track_ID $next — 읽을 수 없는 track_ID가 있어 비교하지 않음")
                max == null -> if (next == 0L) a.fail(mvhd, "next_track_ID 0") else a.pass(mvhd, "next_track_ID $next (trak 없음)")
                next <= max -> a.fail(mvhd, "next_track_ID $next ≤ 사용 중인 최대 track_ID $max")
                else -> a.pass(mvhd, "next_track_ID $next > 최대 track_ID $max")
            }
        }
        return a.result("mvhd 없음")
    }

    private fun vmhdFlags(ctx: BmffContext): ConformanceFinding {
        val a = Acc(ctx, "bmff.vmhd.flags")
        for (b in ctx.allBoxes.filter { it.type == "vmhd" }) {
            if (b.problem != null) { a.skip(b, "박스 헤더 오류"); continue }
            val f = ctx.fullBoxFlags(b) ?: run { a.fail(b, "FullBox 헤더 없음"); null } ?: continue
            if (f != 1) a.fail(b, "flags 0x%06X (1이어야 함)".format(f)) else a.pass(b, "flags 1")
        }
        return a.result("vmhd 없음", NOT_APPLICABLE)
    }

    private fun show4(s: String) = if (s.all { it.code in 0x20..0x7E }) "'$s'" else
        "0x" + s.map { "%02X".format(it.code and 0xFF) }.joinToString("")

    private fun hdlrPreDefined(ctx: BmffContext): ConformanceFinding {
        val a = Acc(ctx, "bmff.hdlr.predefined")
        for (b in ctx.allBoxes.filter { it.type == "hdlr" }) {
            if (b.problem != null) { a.skip(b, "박스 헤더 오류"); continue }
            val comp = ctx.hdlrComponent(b) ?: run { a.fail(b, "박스가 pre_defined 필드를 담기에 짧음"); null } ?: continue
            val raw = u32(ctx, b.payload + 4)
            val handler = ctx.hdlrHandler(b)?.let(::show4) ?: "?"
            val expectedQt = when { !ctx.isQt -> null; b.parent == "mdia" -> "mhlr"; b.parent == "minf" -> "dhlr"; else -> null }
            when {
                expectedQt != null && comp == expectedQt -> a.pass(b, "구성요소 유형 '$comp' (핸들러 $handler)")
                // Some QuickTime writers leave the component type 0: tolerated (WARN); any other value FAILs.
                expectedQt != null && raw == 0L -> a.warn(b, "QuickTime ${b.parent} hdlr component type 0 ($expectedQt 기대)")
                expectedQt != null -> a.fail(b, "구성요소 유형 ${show4(comp)} (${b.parent} 안에서는 '$expectedQt'이어야 함)")
                raw == 0L -> a.pass(b, "pre_defined 0 (핸들러 $handler)")
                !ctx.isQt && (b.parent == "mdia" && comp == "mhlr" || b.parent == "minf" && comp == "dhlr") ->
                    a.warn(b, "pre_defined '$comp': QuickTime 계열 component type (ISO/IEC 14496-12는 0)")
                else -> a.fail(b, "pre_defined ${show4(comp)} (0이어야 함)")
            }
        }
        return a.result("hdlr 없음")
    }

    private fun isoLanguage(v: Int): String? {
        if (v and 0x8000 != 0) return null
        val chars = listOf((v shr 10) and 0x1F, (v shr 5) and 0x1F, v and 0x1F)
        if (chars.any { it !in 1..26 }) return null
        return chars.joinToString("") { (it + 0x60).toChar().toString() }
    }

    private fun mdhdLanguage(ctx: BmffContext): ConformanceFinding {
        val a = Acc(ctx, "bmff.mdhd.language")
        for (b in trackBoxes(ctx, "mdia", "mdhd")) {
            if (b.problem != null) { a.skip(b, "박스 헤더 오류"); continue }
            val v = ctx.fullBoxVersion(b) ?: run { a.skip(b, "FullBox 헤더 없음"); null } ?: continue
            val off = when (v) { 0 -> 20L; 1 -> 32L; else -> { a.skip(b, "알 수 없는 version $v — 필드 위치를 알 수 없음"); continue } }
            if (b.payload + off + 2 > b.end) { a.skip(b, "박스가 language 필드를 담기에 짧음"); continue }
            val lang = ctx.reader.readUInt16(b.payload + off)
            val hex = "0x%04X".format(lang)
            val iso = isoLanguage(lang)
            when {
                iso != null -> a.pass(b, "language $hex ('$iso')")
                ctx.isQt && lang < 0x400 -> a.pass(b, "language $hex (Macintosh 언어 코드 $lang)")
                ctx.isQt && lang == 0x7FFF -> a.pass(b, "language $hex (지정 안 됨)")
                lang and 0x8000 != 0 -> a.warn(b, "language $hex: pad 비트(15)가 1 (0이어야 함)")
                else -> a.warn(b, "language $hex: ISO-639-2/T 3문자 패킹이 아님 (각 5비트 값이 1..26이어야 함)" +
                    if (!ctx.isQt && lang == 0) " — 0은 일부 장치가 기록하는 값" else "")
            }
        }
        return a.result("mdhd 없음")
    }

    class DrefEntry(val type: String, val selfContained: Boolean)
    /** [entries] null when the dref could not be read; [problems] are FAIL-level layout violations. */
    class DrefRead(val entries: List<DrefEntry>?, val problems: List<String>, val unchecked: String? = null)

    fun drefEntries(ctx: BmffContext, dref: BmffBox): DrefRead {
        if (dref.problem != null) return DrefRead(null, emptyList(), "박스 헤더 오류")
        if (dref.payload + 8 > dref.end) return DrefRead(null, listOf("박스가 entry_count 필드를 담기에 짧음"))
        val count = u32(ctx, dref.payload + 4)
        if (count == 0L) return DrefRead(emptyList(), listOf("entry_count 0 (1 이상이어야 함)"))
        val room = (dref.end - dref.payload - 8) / 12
        if (count > room) return DrefRead(null, listOf("entry_count $count 항목이 dref 안에 들어가지 않음 (최대 ${room}개)"))
        val entries = ArrayList<DrefEntry>()
        var pos = dref.payload + 8
        for (i in 0 until count) {
            if (pos + 12 > dref.end) return DrefRead(null, listOf("항목 #${i + 1} @0x%X: dref 끝을 넘음".format(pos)))
            val size = u32(ctx, pos)
            if (size < 12 || size > dref.end - pos)
                return DrefRead(null, listOf("항목 #${i + 1} @0x%X: 크기 $size 가 12 미만이거나 dref 끝을 넘음".format(pos)))
            val type = ctx.reader.readFourCC(pos + 4)
            val flags = u32(ctx, pos + 8) and 0xFFFFFF
            entries += DrefEntry(type, flags and 1L != 0L)
            pos += size
        }
        return DrefRead(entries, emptyList())
    }

    private fun drefRule(ctx: BmffContext): ConformanceFinding {
        val a = Acc(ctx, "bmff.dref.entries")
        ctx.traks.forEachIndexed { ti, trak ->
            val dref = trak.child("mdia", "minf", "dinf", "dref") ?: return@forEachIndexed
            val label = "trak #${ti + 1}"
            val r = drefEntries(ctx, dref)
            r.unchecked?.let { a.skip(dref, it) }
            r.problems.forEach { a.fail(dref, it) }
            val e = r.entries ?: return@forEachIndexed
            if (e.isEmpty()) return@forEachIndexed
            val samples = trak.child("mdia", "minf", "stbl", "stsd")?.let { stsdEntries(ctx, it).entries }
            if (samples == null) { a.skip(dref, "$label: stsd를 읽을 수 없어 data_reference_index를 검사하지 않음"); return@forEachIndexed }
            val used = ArrayList<DrefEntry>()
            var badIndex = false
            samples.forEachIndexed { si, se ->
                val ref = e.getOrNull(se.dataReferenceIndex - 1)
                if (ref == null) {
                    badIndex = true
                    a.fail(dref, "$label 샘플 엔트리 #${si + 1} ${show4(se.type)}: data_reference_index ${se.dataReferenceIndex} " +
                        "(1..${e.size} 이어야 함)")
                } else used += ref
            }
            if (badIndex) return@forEachIndexed
            val list = e.joinToString(", ") { "${show4(it.type)}${if (it.selfContained) "(자체 포함)" else "(외부)"}" }
            when {
                used.isEmpty() -> a.pass(dref, "$label 항목 ${e.size}개: $list (샘플 엔트리 없음)")
                used.all { it.selfContained } -> a.pass(dref, "$label 항목 ${e.size}개: $list")
                used.none { it.selfContained } ->
                    a.pass(dref, "$label 외부 데이터 참조 — 샘플 데이터가 이 파일 밖에 있음 (샘플 위치 규칙 제외): $list")
                else -> a.pass(dref, "$label 자체 포함/외부 데이터 참조 혼합: $list")
            }
        }
        return a.result("dref 없음")
    }

    class SampleEntry(val offset: Long, val type: String, val dataReferenceIndex: Int)
    /** [entries] null when the stsd could not be read; [problems] are FAIL-level layout violations. */
    class StsdRead(val entries: List<SampleEntry>?, val problems: List<String>, val unchecked: String? = null)

    private const val MIN_SAMPLE_ENTRY = 16L

    fun stsdEntries(ctx: BmffContext, stsd: BmffBox): StsdRead {
        if (stsd.problem != null) return StsdRead(null, emptyList(), "박스 헤더 오류")
        if (stsd.payload + 8 > stsd.end) return StsdRead(null, listOf("박스가 entry_count 필드를 담기에 짧음"))
        val count = u32(ctx, stsd.payload + 4)
        if (count == 0L) return StsdRead(emptyList(), listOf("entry_count 0 (1 이상이어야 함)"))
        val room = (stsd.end - stsd.payload - 8) / MIN_SAMPLE_ENTRY
        if (count > room) return StsdRead(null,
            listOf("entry_count $count 샘플 엔트리(각 ${MIN_SAMPLE_ENTRY}바이트 이상)가 stsd 안에 들어가지 않음 (최대 ${room}개)"))
        if (count > MAX_TABLE_ENTRIES) return StsdRead(null, emptyList(), "entry_count $count: 상한 ${MAX_TABLE_ENTRIES}개 초과로 펼치지 않음")
        val out = ArrayList<SampleEntry>()
        var pos = stsd.payload + 8
        for (i in 0 until count) {
            if (pos + MIN_SAMPLE_ENTRY > stsd.end) return StsdRead(null, listOf("샘플 엔트리 #${i + 1} @0x%X: stsd 끝을 넘음".format(pos)))
            val size = u32(ctx, pos)
            if (size < MIN_SAMPLE_ENTRY || size > stsd.end - pos) return StsdRead(null,
                listOf("샘플 엔트리 #${i + 1} @0x%X: 크기 $size 가 ${MIN_SAMPLE_ENTRY} 미만이거나 stsd 끝을 넘음".format(pos)))
            out += SampleEntry(pos, ctx.reader.readFourCC(pos + 4), ctx.reader.readUInt16(pos + 14))
            pos += size
        }
        return StsdRead(out, emptyList())
    }

    private fun stsdRule(ctx: BmffContext): ConformanceFinding {
        val a = Acc(ctx, "bmff.stsd.entries")
        for (stsd in trackBoxes(ctx, "mdia", "minf", "stbl", "stsd")) {
            val r = stsdEntries(ctx, stsd)
            r.unchecked?.let { a.skip(stsd, it) }
            r.problems.forEach { a.fail(stsd, it) }
            val e = r.entries ?: continue
            if (e.isEmpty()) continue
            a.pass(stsd, "샘플 엔트리 ${e.size}개: " + e.take(8).joinToString(", ") { show4(it.type) } +
                if (e.size > 8) " … 외 ${e.size - 8}개" else "")
        }
        return a.result("stsd 없음")
    }
}
