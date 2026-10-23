package com.multiviewer.parser.conformance.isobmff

import com.multiviewer.parser.conformance.ConformanceFinding
import com.multiviewer.parser.conformance.ConformanceStatus.FAIL
import com.multiviewer.parser.conformance.ConformanceStatus.NOT_APPLICABLE
import com.multiviewer.parser.conformance.ConformanceStatus.NOT_CHECKED
import com.multiviewer.parser.conformance.ConformanceStatus.PASS
import com.multiviewer.parser.conformance.ConformanceStatus.WARN
import java.math.BigInteger

/**
 * Sample-table, edit-list, duration and fragmented-file rules. Tables are read entry by entry through the
 * [com.multiviewer.parser.ByteReader] (never materialised whole) and never expanded past [MAX_TABLE_ENTRIES].
 */
internal object IsoBmffSampleRules {
    const val MAX_TABLE_ENTRIES = 2_000_000L
    private const val MAX_LISTED = 10
    /** Per-sample fallback budget when a chunk is not inside one mdat (or the file); beyond it, chunk granularity. */
    private const val PER_SAMPLE_BUDGET = 2_000_000L

    private val SAMPLE_RULES = listOf(
        "bmff.stbl.sample_count", "bmff.stsc.order", "bmff.stsc.coverage", "bmff.ctts.count", "bmff.stss.order",
        "bmff.sample.location", "bmff.mdat.present", "bmff.duration.consistency",
    )

    /** A counted table inside a box: Ok when [count] entries of [entryBits] bits fit; Skip = NOT_CHECKED; Bad = FAIL. */
    sealed class Table {
        data class Ok(val box: BmffBox, val count: Long, val start: Long, val entryBits: Int) : Table()
        data class Skip(val reason: String) : Table()
        data class Bad(val reason: String) : Table()
    }

    private fun bitsText(bits: Int) = if (bits % 8 == 0) "${bits / 8}바이트" else "${bits}비트"

    /**
     * Reads the entry_count at payload + [countAt]; entries follow it. The cap is checked before the size fit, so a
     * huge count in a short box is NOT_CHECKED (cap), not a FAIL; truncation itself is covered by `bmff.box.size`.
     * [entryBits] 0 = no table follows (e.g. stsz with a fixed sample_size).
     */
    fun table(ctx: BmffContext, b: BmffBox, countAt: Long, entryBits: Int): Table {
        if (b.problem != null) return Table.Skip("${at(b)}: 박스 헤더 오류로 표를 읽지 않음")
        val end = minOf(b.end, ctx.length)
        if (b.payload + countAt + 4 > end) return Table.Bad("${at(b)}: 박스가 entry_count 필드를 담기에 짧음")
        val count = ctx.reader.readUInt32(b.payload + countAt)
        if (entryBits > 0 && count > MAX_TABLE_ENTRIES) return Table.Skip(
            "${at(b)}: entry_count $count — 표 항목 상한 초과 (상한 ${MAX_TABLE_ENTRIES}개), 펼치지 않음")
        val start = b.payload + countAt + 4
        if (count * entryBits > (end - start) * 8) return Table.Bad(
            "${at(b)}: entry_count $count 항목(각 ${bitsText(entryBits)})이 박스 안에 들어가지 않음 (${end - start}바이트)")
        return Table.Ok(b, count, start, entryBits)
    }

    private fun Table.Ok.u32(ctx: BmffContext, i: Long, field: Int = 0) =
        ctx.reader.readUInt32(start + i * (entryBits / 8) + field * 4L)

    /** Outcome of reading a table-derived value. */
    private sealed class Got<out T> {
        class Ok<T>(val v: T) : Got<T>()
        class Skip(val reason: String) : Got<Nothing>()
        class Bad(val reason: String) : Got<Nothing>()
    }

    private fun Table.got(): Got<Table.Ok> = when (this) {
        is Table.Ok -> Got.Ok(this)
        is Table.Skip -> Got.Skip(reason)
        is Table.Bad -> Got.Bad(reason)
    }

    private class Sizes(val count: Long, val fixed: Long, val table: Table.Ok?) {
        fun size(ctx: BmffContext, i: Long): Long {
            val t = table ?: return fixed
            return when (t.entryBits) {
                32 -> ctx.reader.readUInt32(t.start + 4 * i)
                16 -> ctx.reader.readUInt16(t.start + 2 * i).toLong()
                8 -> ctx.reader.readUInt8(t.start + i).toLong()
                else -> { val v = ctx.reader.readUInt8(t.start + i / 2); (if (i % 2 == 0L) v shr 4 else v and 0xF).toLong() }
            }
        }
    }

    private class StscCheck(val violations: List<String>, val structural: Boolean, val unchecked: List<String>)

    /** Per-track lazily read tables. */
    private class Tr(val ctx: BmffContext, index: Int, val trak: BmffBox) {
        val label = "trak #${index + 1} @0x%X".format(trak.offset)
        val stbl: BmffBox? = trak.child("mdia", "minf", "stbl")
        private fun all(vararg types: String) = stbl?.children?.filter { it.type in types } ?: emptyList()

        private fun exactlyOne(vararg types: String): Got<BmffBox> {
            if (stbl == null) return Got.Skip("stbl 없음")
            val found = all(*types)
            return if (found.size == 1) Got.Ok(found.single())
            else Got.Skip("${types.joinToString("/")} ${found.size}개 (bmff.trak.required 참고)")
        }

        private fun <T, R> Got<T>.then(f: (T) -> Got<R>): Got<R> = when (this) {
            is Got.Ok -> f(v); is Got.Skip -> this; is Got.Bad -> this
        }

        val sizes: Got<Sizes> by lazy {
            exactlyOne("stsz", "stz2").then { b ->
                if (b.problem != null) return@then Got.Skip("${at(b)}: 박스 헤더 오류")
                if (b.payload + 12 > b.end) return@then Got.Bad("${at(b)}: 박스가 sample_count 필드를 담기에 짧음")
                if (b.type == "stsz") {
                    val fixed = ctx.reader.readUInt32(b.payload + 4)
                    if (fixed != 0L) Got.Ok(Sizes(ctx.reader.readUInt32(b.payload + 8), fixed, null))
                    else table(ctx, b, 8, 32).got().then { Got.Ok(Sizes(it.count, 0, it)) }
                } else {
                    val field = ctx.reader.readUInt8(b.payload + 7)
                    if (field !in setOf(4, 8, 16)) Got.Bad("${at(b)}: field_size $field (4/8/16이어야 함)")
                    else table(ctx, b, 8, field).got().then { Got.Ok(Sizes(it.count, 0, it)) }
                }
            }
        }
        val stts: Got<Table.Ok> by lazy { exactlyOne("stts").then { table(ctx, it, 4, 64).got() } }
        val stsc: Got<Table.Ok> by lazy { exactlyOne("stsc").then { table(ctx, it, 4, 96).got() } }
        val chunks: Got<Table.Ok> by lazy {
            exactlyOne("stco", "co64").then { table(ctx, it, 4, if (it.type == "co64") 64 else 32).got() }
        }
        /** null = box absent (optional). */
        fun optional(type: String, bits: Int): Got<Table.Ok>? {
            val found = all(type)
            if (found.isEmpty()) return null
            if (found.size > 1) return Got.Skip("$type ${found.size}개 (bmff.trak.required 참고)")
            return table(ctx, found.single(), 4, bits).got()
        }

        fun chunkOffset(t: Table.Ok, i: Long): Long =
            if (t.entryBits == 64) ctx.reader.readUInt64(t.start + 8 * i) else ctx.reader.readUInt32(t.start + 4 * i)

        val stsdCount: Int? by lazy {
            val stsd = trak.child("mdia", "minf", "stbl", "stsd") ?: return@lazy null
            IsoBmffFieldRules.stsdEntries(ctx, stsd).entries?.size
        }

        val stscCheck: StscCheck? by lazy {
            val t = (stsc as? Got.Ok)?.v ?: return@lazy null
            val chunkCount = (chunks as? Got.Ok)?.v?.count
            val v = ArrayList<String>()
            var total = 0
            var structural = false
            val unchecked = ArrayList<String>()
            fun add(msg: String, struct: Boolean) { total++; if (struct) structural = true; if (v.size < MAX_LISTED) v += msg }
            var prev = 0L
            val sdCount = stsdCount
            for (i in 0 until t.count) {
                val first = t.u32(ctx, i, 0)
                val spc = t.u32(ctx, i, 1)
                val sdi = t.u32(ctx, i, 2)
                val n = "항목 #${i + 1}"
                if (i == 0L && first != 1L) add("$n: first_chunk $first (첫 항목은 1이어야 함)", true)
                else if (i > 0 && first <= prev) add("$n: first_chunk $first ≤ 이전 $prev (엄격히 증가해야 함)", true)
                if (chunkCount != null && first > chunkCount) add("$n: first_chunk $first > 청크 수 $chunkCount", true)
                if (spc < 1) add("$n: samples_per_chunk 0 (1 이상이어야 함)", true)
                if (sdCount != null && (sdi < 1 || sdi > sdCount)) add("$n: sample_description_index $sdi (1..$sdCount 이어야 함)", false)
                prev = first
            }
            if (total > v.size) v += "… 외 ${total - v.size}건"
            if (chunkCount == null && t.count > 0) unchecked += "청크 표를 읽을 수 없어 first_chunk ≤ 청크 수를 검사하지 않음"
            if (sdCount == null && t.count > 0) unchecked += "stsd를 읽을 수 없어 sample_description_index를 검사하지 않음"
            StscCheck(v, structural, unchecked)
        }

        /** Samples mapped by stsc over all chunks, saturating at Long.MAX_VALUE; null when not computable. */
        val mapped: Long? by lazy {
            val t = (stsc as? Got.Ok)?.v ?: return@lazy null
            val c = (chunks as? Got.Ok)?.v ?: return@lazy null
            if (stscCheck?.structural != false) return@lazy null
            var sum = 0L
            for (i in 0 until t.count) {
                val first = t.u32(ctx, i, 0)
                val next = if (i + 1 < t.count) t.u32(ctx, i + 1, 0) else c.count + 1
                sum = satAdd(sum, satMul(next - first, t.u32(ctx, i, 1)))
            }
            sum
        }
    }

    private fun satMul(a: Long, b: Long): Long = try { Math.multiplyExact(a, b) } catch (_: ArithmeticException) { Long.MAX_VALUE }
    private fun satAdd(a: Long, b: Long): Long = try { Math.addExact(a, b) } catch (_: ArithmeticException) { Long.MAX_VALUE }

    /** Per-track outcomes; unchecked or not-applicable items keep the rule from PASS where appropriate. */
    private class Acc(val ctx: BmffContext, val id: String) {
        val fails = ArrayList<String>(); val warns = ArrayList<String>(); val skips = ArrayList<String>()
        val passes = ArrayList<String>(); val nas = ArrayList<String>(); val ranges = ArrayList<LongRange>()
        fun fail(t: Tr, msg: String, b: BmffBox? = null) { fails += "${t.label}: $msg"; ranges += ctx.range(b ?: t.trak) }
        fun warn(t: Tr, msg: String, b: BmffBox? = null) { warns += "${t.label}: $msg"; ranges += ctx.range(b ?: t.trak) }
        fun skip(t: Tr, msg: String) { skips += "${t.label}: $msg" }
        fun pass(t: Tr, msg: String) { passes += "${t.label}: $msg" }
        fun na(t: Tr, msg: String) { nas += "${t.label}: $msg" }

        /** Unwraps [g]; Bad is a FAIL only when this rule [owns] the table, otherwise both Skip and Bad are unchecked. */
        fun <T> need(t: Tr, g: Got<T>, owns: Boolean): T? = when (g) {
            is Got.Ok -> g.v
            is Got.Skip -> { skip(t, g.reason); null }
            is Got.Bad -> { if (owns) fail(t, g.reason) else skip(t, "${g.reason} (다른 규칙에서 FAIL)"); null }
        }

        fun result(): ConformanceFinding {
            val all = fails + warns + skips + passes + nas
            return when {
                fails.isNotEmpty() -> ctx.finding(id, FAIL, all, ranges)
                warns.isNotEmpty() -> ctx.finding(id, WARN, all, ranges)
                skips.isNotEmpty() -> ctx.finding(id, NOT_CHECKED, all)
                passes.isNotEmpty() -> ctx.finding(id, PASS, passes + nas)
                nas.isNotEmpty() -> ctx.finding(id, NOT_APPLICABLE, nas)
                else -> ctx.finding(id, NOT_CHECKED, listOf("trak 없음"))
            }
        }
    }

    private fun fragmentation(ctx: BmffContext): List<String> {
        val mvex = ctx.moovs.flatMap { m -> m.children.filter { it.type == "mvex" } }
        val moofs = ctx.tree.top.filter { it.type == "moof" }
        return mvex.map { "moov 안의 ${at(it)}" } + if (moofs.isEmpty()) emptyList() else listOf("최상위 moof ${moofs.size}개 (첫 ${at(moofs.first())})")
    }

    fun evaluate(ctx: BmffContext): List<ConformanceFinding> {
        val frag = fragmentation(ctx)
        val tracks = ctx.traks.mapIndexed { i, t -> Tr(ctx, i, t) }
        val fragmented = if (ctx.tree.top.isEmpty()) ctx.finding("bmff.fragmented", NOT_CHECKED, listOf("유효한 박스가 없음"))
            else if (frag.isNotEmpty()) ctx.finding("bmff.fragmented", PASS,
                listOf("조각화 파일: " + frag.joinToString(", "), "조각(moof/traf/trun) 샘플 표 검사는 phase 2 — 이 파일의 샘플 표 규칙은 적용하지 않음"))
            else ctx.finding("bmff.fragmented", PASS, listOf("조각화되지 않은 파일 (mvex/moof 없음)"))
        val elst = elstRule(ctx, tracks, frag.isNotEmpty())
        if (frag.isNotEmpty()) {
            val na = SAMPLE_RULES.map { ctx.finding(it, NOT_APPLICABLE, listOf("조각화 파일 — 조각 샘플 표 검사는 phase 2")) }
            return na + elst + fragmented
        }
        return listOf(
            sampleCount(ctx, tracks), stscOrder(ctx, tracks), stscCoverage(ctx, tracks), cttsCount(ctx, tracks),
            stssOrder(ctx, tracks), sampleLocation(ctx, tracks), mdatPresent(ctx, tracks), durationConsistency(ctx, tracks),
            elst, fragmented,
        )
    }

    private fun sampleCount(ctx: BmffContext, tracks: List<Tr>): ConformanceFinding {
        val a = Acc(ctx, "bmff.stbl.sample_count")
        for (t in tracks) {
            val sizes = a.need(t, t.sizes, owns = true)
            val stts = a.need(t, t.stts, owns = true)
            if (sizes == null || stts == null) continue
            var total = 0L
            for (i in 0 until stts.count) total += stts.u32(ctx, i, 0)
            if (total == sizes.count) a.pass(t, "stts 합계 $total = ${sizes.box()} 샘플 수 ${sizes.count}")
            else a.fail(t, "stts sample_count 합계 $total ≠ ${sizes.box()} sample_count ${sizes.count}", stts.box)
        }
        return a.result()
    }

    private fun Sizes.box() = table?.box?.type ?: "stsz"

    private fun stscOrder(ctx: BmffContext, tracks: List<Tr>): ConformanceFinding {
        val a = Acc(ctx, "bmff.stsc.order")
        for (t in tracks) {
            val stsc = a.need(t, t.stsc, owns = true) ?: continue
            val c = t.stscCheck ?: continue
            c.violations.forEach { a.fail(t, "stsc $it", stsc.box) }
            if (c.violations.isEmpty()) {
                if (c.unchecked.isNotEmpty()) c.unchecked.forEach { a.skip(t, it) }
                else a.pass(t, "stsc 항목 ${stsc.count}개 순서/값 정상")
            }
        }
        return a.result()
    }

    private fun stscCoverage(ctx: BmffContext, tracks: List<Tr>): ConformanceFinding {
        val a = Acc(ctx, "bmff.stsc.coverage")
        for (t in tracks) {
            val chunks = a.need(t, t.chunks, owns = true)
            val sizes = a.need(t, t.sizes, owns = false)
            if (a.need(t, t.stsc, owns = false) == null || chunks == null || sizes == null) continue
            val mapped = t.mapped
            if (mapped == null) { a.skip(t, "stsc 순서 오류로 매핑 샘플 수를 계산하지 않음 (bmff.stsc.order 참고)"); continue }
            if (mapped == sizes.count) a.pass(t, "청크 ${chunks.count}개에 매핑된 샘플 $mapped = 샘플 수 ${sizes.count}")
            else a.fail(t, "stsc가 청크 ${chunks.count}개에 매핑한 샘플 ${if (mapped == Long.MAX_VALUE) "(overflow)" else mapped} ≠ 샘플 수 ${sizes.count}",
                (t.stsc as Got.Ok).v.box)
        }
        return a.result()
    }

    private fun cttsCount(ctx: BmffContext, tracks: List<Tr>): ConformanceFinding {
        val a = Acc(ctx, "bmff.ctts.count")
        for (t in tracks) {
            val g = t.optional("ctts", 64)
            if (g == null) { if (t.stbl != null) a.pass(t, "ctts 없음 (CT = DT)") else a.skip(t, "stbl 없음"); continue }
            val ctts = a.need(t, g, owns = true) ?: continue
            val sizes = a.need(t, t.sizes, owns = false) ?: continue
            var total = 0L
            var negatives = 0L
            var firstNeg = -1L
            val v0Signed = !ctx.isQt && ctx.fullBoxVersion(ctts.box) == 0
            for (i in 0 until ctts.count) {
                total += ctts.u32(ctx, i, 0)
                if (v0Signed && ctts.u32(ctx, i, 1) and 0x80000000L != 0L) { negatives++; if (firstNeg < 0) firstNeg = i }
            }
            var ok = true
            if (total != sizes.count) { ok = false; a.fail(t, "ctts sample_count 합계 $total ≠ 샘플 수 ${sizes.count}", ctts.box) }
            if (negatives > 0) {
                ok = false
                val raw = ctts.u32(ctx, firstNeg, 1)
                a.warn(t, "v0 offset ≥ 2^31: 부호 있는 값을 version 0에 기록한 것으로 보임 (version 1 사용 필요) — " +
                    "${negatives}개, 항목 #${firstNeg + 1}: $raw (부호 있는 값 ${raw.toInt()})", ctts.box)
            }
            if (ok) a.pass(t, "ctts 합계 $total = 샘플 수")
        }
        return a.result()
    }

    private fun stssOrder(ctx: BmffContext, tracks: List<Tr>): ConformanceFinding {
        val a = Acc(ctx, "bmff.stss.order")
        for (t in tracks) {
            val g = t.optional("stss", 32)
            if (g == null) { if (t.stbl != null) a.pass(t, "stss 없음 (모든 샘플이 동기 샘플)") else a.skip(t, "stbl 없음"); continue }
            val stss = a.need(t, g, owns = true) ?: continue
            val count = (t.sizes as? Got.Ok)?.v?.count
            val bad = ArrayList<String>()
            var total = 0
            var prev = 0L
            for (i in 0 until stss.count) {
                val n = stss.u32(ctx, i)
                val msg = when {
                    n < 1 -> "항목 #${i + 1}: sample_number 0 (1 이상이어야 함)"
                    n <= prev -> "항목 #${i + 1}: sample_number $n ≤ 이전 $prev (엄격히 증가해야 함)"
                    count != null && n > count -> "항목 #${i + 1}: sample_number $n > 샘플 수 $count"
                    else -> null
                }
                if (msg != null) { total++; if (bad.size < MAX_LISTED) bad += msg }
                prev = n
            }
            if (total > bad.size) bad += "… 외 ${total - bad.size}건"
            bad.forEach { a.fail(t, "stss $it", stss.box) }
            if (bad.isEmpty()) {
                if (count == null) a.skip(t, "샘플 수를 읽을 수 없어 sample_number ≤ 샘플 수를 검사하지 않음")
                else a.pass(t, "stss 항목 ${stss.count}개 증가 순서, 1..$count 범위")
            }
        }
        return a.result()
    }

    /** Top-level mdat payload ranges [start, end), clipped to the file. */
    private fun mdatRanges(ctx: BmffContext): List<LongRange> = ctx.tree.top.filter { it.type == "mdat" }
        .map { it.payload until minOf(it.end, ctx.length) }

    private fun sampleLocation(ctx: BmffContext, tracks: List<Tr>): ConformanceFinding {
        val a = Acc(ctx, "bmff.sample.location")
        val mdats = mdatRanges(ctx)
        val starts = LongArray(mdats.size) { mdats[it].first }
        // [s, e) inside one mdat payload; e is exclusive.
        fun inMdat(s: Long, e: Long): Boolean {
            var idx = java.util.Arrays.binarySearch(starts, s)
            if (idx < 0) idx = -idx - 2
            if (idx < 0) return false
            return e <= mdats[idx].last + 1
        }
        var budget = PER_SAMPLE_BUDGET
        for (t in tracks) {
            when (ctx.trackDataSelfContained(t.trak)) {
                false -> { a.na(t, "외부 데이터 참조 — 샘플 데이터가 이 파일 밖에 있음"); continue }
                null -> { a.skip(t, "dref/stsd에서 샘플 데이터 위치(자체 포함 여부)를 확인할 수 없음"); continue }
                true -> {}
            }
            val sizes = a.need(t, t.sizes, owns = false)
            val chunks = a.need(t, t.chunks, owns = false)
            val stsc = a.need(t, t.stsc, owns = false)
            if (sizes == null || chunks == null || stsc == null) continue
            val mapped = t.mapped
            if (mapped == null || mapped != sizes.count) {
                a.skip(t, "stsc 순서/범위 오류로 샘플 위치를 계산하지 않음 (bmff.stsc.order/coverage 참고)"); continue
            }
            if (sizes.count == 0L) { a.pass(t, "샘플 없음"); continue }
            val outFile = ArrayList<String>(); var outFileN = 0L
            val outMdat = ArrayList<String>(); var outMdatN = 0L
            var e = 0L
            var sample = 0L
            for (c in 0 until chunks.count) {
                while (e + 1 < stsc.count && stsc.u32(ctx, e + 1, 0) <= c + 1) e++
                val spc = stsc.u32(ctx, e, 1)
                val off = t.chunkOffset(chunks, c)
                var extent = 0L
                if (sizes.table == null) extent = satMul(spc, sizes.fixed)
                else for (k in 0 until spc) extent = satAdd(extent, sizes.size(ctx, sample + k))
                // co64 offsets ≥ 2^63 read as negative Longs; they and saturated ends lie outside the file.
                val end = if (off < 0) Long.MAX_VALUE else satAdd(off, extent)
                fun label() = "청크 #${c + 1} @0x%s (샘플 #${sample + 1}–#${sample + spc})".format(java.lang.Long.toHexString(off).uppercase())
                if (extent > 0 && (end > ctx.length || !inMdat(off, end))) {
                    val label = label()
                    if (budget >= spc) {
                        budget -= spc
                        var pos = off
                        var nFile = 0L; var nMdat = 0L
                        for (k in 0 until spc) {
                            val sz = if (sizes.table == null) sizes.fixed else sizes.size(ctx, sample + k)
                            val se = if (pos < 0) Long.MAX_VALUE else satAdd(pos, sz)
                            if (sz > 0) { if (se > ctx.length) nFile++ else if (!inMdat(pos, se)) nMdat++ }
                            pos = se
                        }
                        if (nFile > 0) { outFileN += nFile; if (outFile.size < MAX_LISTED) outFile += "$label: 샘플 ${nFile}개가 파일 끝(${ctx.length})을 넘음" }
                        if (nMdat > 0) { outMdatN += nMdat; if (outMdat.size < MAX_LISTED) outMdat += "$label: 샘플 ${nMdat}개가 최상위 mdat 페이로드 밖" }
                    } else if (end > ctx.length) {
                        outFileN += spc; if (outFile.size < MAX_LISTED) outFile += "$label: 범위 끝 $end 이 파일 끝(${ctx.length})을 넘음"
                    } else {
                        outMdatN += spc; if (outMdat.size < MAX_LISTED) outMdat += "$label: 범위가 하나의 mdat 페이로드 안에 있지 않음"
                    }
                }
                sample += spc
            }
            val chunkBox = chunks.box
            if (outFileN > 0) a.fail(t, "샘플 ${outFileN}개가 파일 밖: " + outFile.joinToString("; ") +
                if (outFile.size >= MAX_LISTED) " …" else "", chunkBox)
            if (mdats.isEmpty()) a.fail(t, "최상위 mdat 없음 — 샘플 ${sizes.count}개가 mdat 밖", chunkBox)
            else if (outMdatN > 0) a.warn(t, "샘플 ${outMdatN}개가 최상위 mdat 페이로드 밖(파일 안): " + outMdat.joinToString("; ") +
                if (outMdat.size >= MAX_LISTED) " …" else "", chunkBox)
            if (outFileN == 0L && outMdatN == 0L && mdats.isNotEmpty())
                a.pass(t, "샘플 ${sizes.count}개(청크 ${chunks.count}개) 모두 파일과 mdat 페이로드 안")
        }
        return a.result()
    }

    private fun mdatPresent(ctx: BmffContext, tracks: List<Tr>): ConformanceFinding {
        val id = "bmff.mdat.present"
        val mdats = ctx.tree.top.filter { it.type == "mdat" }
        if (mdats.isNotEmpty()) return ctx.finding(id, PASS, listOf("최상위 mdat ${mdats.size}개 (첫 ${at(mdats.first())})"))
        if (tracks.isEmpty()) return ctx.finding(id, NOT_CHECKED, listOf("trak 없음"))
        val needs = ArrayList<String>(); val unknown = ArrayList<String>(); val none = ArrayList<String>()
        for (t in tracks) {
            val count = (t.sizes as? Got.Ok)?.v?.count
            val self = ctx.trackDataSelfContained(t.trak)
            when {
                count == 0L -> none += "${t.label}: 샘플 없음"
                self == false -> none += "${t.label}: 외부 데이터 참조"
                self == true && count != null -> needs += "${t.label}: 자체 포함 샘플 ${count}개"
                else -> unknown += "${t.label}: 샘플 수 또는 데이터 위치를 확인할 수 없음"
            }
        }
        return when {
            needs.isNotEmpty() -> ctx.finding(id, FAIL, listOf("최상위 mdat 없음") + needs, tracks.map { ctx.range(it.trak) })
            unknown.isNotEmpty() -> ctx.finding(id, NOT_CHECKED, listOf("최상위 mdat 없음") + unknown + none)
            else -> ctx.finding(id, NOT_APPLICABLE, listOf("최상위 mdat 없음, 이 파일에 둘 샘플 데이터 없음") + none)
        }
    }

    private class Hdr(val timescale: Long, val duration: Long, val allOnes: Boolean)

    /** timescale/duration of mvhd/mdhd, or duration of tkhd (timescale -1); null = unreadable with [why] set. */
    private fun header(ctx: BmffContext, b: BmffBox?, why: (String) -> Unit): Hdr? {
        if (b == null) { why("박스 없음"); return null }
        val v = ctx.fullBoxVersion(b) ?: run { why("${at(b)}: FullBox 헤더 없음 또는 박스 오류"); return null }
        if (v !in 0..1) { why("${at(b)}: 알 수 없는 version $v"); return null }
        val tkhd = b.type == "tkhd"
        val tsOff = if (v == 0) 12L else 20L
        val durOff = if (tkhd) (if (v == 0) 20L else 28L) else (if (v == 0) 16L else 24L)
        val durLen = if (v == 0) 4 else 8
        if (b.payload + durOff + durLen > b.end) { why("${at(b)}: 박스가 duration 필드를 담기에 짧음"); return null }
        val ts = if (tkhd) -1L else ctx.reader.readUInt32(b.payload + tsOff)
        val dur = if (v == 0) ctx.reader.readUInt32(b.payload + durOff) else ctx.reader.readUInt64(b.payload + durOff)
        val allOnes = if (v == 0) dur == 0xFFFFFFFFL else dur == -1L
        return Hdr(ts, dur, allOnes)
    }

    private fun durationConsistency(ctx: BmffContext, tracks: List<Tr>): ConformanceFinding {
        val a = Acc(ctx, "bmff.duration.consistency")
        val mvhd = ctx.moovs.singleOrNull()?.children?.singleOrNull { it.type == "mvhd" }
        for (t in tracks) {
            if (t.trak.children.any { it.type == "edts" }) { a.na(t, "edts 있음 — tkhd 길이는 편집 목록 기준이라 비교하지 않음"); continue }
            if (ctx.moovs.size > 1) { a.skip(t, "moov가 여러 개 — 어느 mvhd timescale을 쓸지 알 수 없어 비교하지 않음 (bmff.moov.count 참고)"); continue }
            var reason: String? = null
            val why: (String) -> Unit = { reason = it }
            val mv = header(ctx, mvhd, why)
            val tk = mv?.let { header(ctx, t.trak.children.singleOrNull { it.type == "tkhd" }, why) }
            val md = tk?.let { header(ctx, t.trak.child("mdia", "mdhd"), why) }
            if (mv == null || tk == null || md == null) { a.skip(t, "길이 필드를 읽을 수 없음: $reason"); continue }
            if (mv.timescale == 0L || md.timescale == 0L) {
                a.skip(t, "timescale 0 (mvhd ${mv.timescale}, mdhd ${md.timescale}) — 비교하지 않음 (bmff.*.timescale 참고)"); continue
            }
            if (tk.allOnes || md.allOnes) { a.skip(t, "duration이 모두 1(알 수 없음) — 비교하지 않음"); continue }
            val big = BigInteger.ONE.shiftLeft(64) // unsigned 64-bit (version 1) durations
            fun u(v: Long) = if (v >= 0) BigInteger.valueOf(v) else BigInteger.valueOf(v).add(big)
            val diff = (u(tk.duration) * BigInteger.valueOf(md.timescale) - u(md.duration) * BigInteger.valueOf(mv.timescale)).abs()
            val detail = "tkhd duration ${u(tk.duration)} (movie timescale ${mv.timescale}) / mdhd duration ${u(md.duration)} (media timescale ${md.timescale})"
            if (diff <= BigInteger.valueOf(md.timescale)) a.pass(t, "$detail — ±1 movie tick 이내")
            else {
                val ticks = diff.toBigDecimal().divide(BigInteger.valueOf(md.timescale).toBigDecimal(), 2, java.math.RoundingMode.HALF_UP)
                a.warn(t, "$detail — 약 $ticks movie tick 차이 (edts 없음, ±1 tick 이내여야 함)")
            }
        }
        return a.result()
    }

    /** 16.16 fixed media rate as a plain decimal, e.g. 1, 2, 0.5. */
    private fun rate(int: Int, frac: Int): String =
        java.math.BigDecimal(int.toLong() * 65536 + frac).divide(java.math.BigDecimal(65536)).stripTrailingZeros().toPlainString()

    private fun elstRule(ctx: BmffContext, tracks: List<Tr>, fragmented: Boolean): ConformanceFinding {
        val a = Acc(ctx, "bmff.elst.entries")
        for (t in tracks) {
            val elsts = t.trak.children.filter { it.type == "edts" }.flatMap { e -> e.children.filter { it.type == "elst" } }
            if (elsts.isEmpty()) { a.pass(t, "편집 목록 없음"); continue }
            for (b in elsts) {
                val v = ctx.fullBoxVersion(b)
                if (b.problem != null || v == null) { a.skip(t, "${at(b)}: 박스 헤더 오류 또는 FullBox 헤더 없음"); continue }
                if (v !in 0..1) { a.skip(t, "${at(b)}: 알 수 없는 version $v — 항목 형식을 알 수 없음"); continue }
                val tbl = a.need(t, table(ctx, b, 4, if (v == 0) 96 else 160).got(), owns = true) ?: continue
                var reason: String? = null
                val md = header(ctx, t.trak.child("mdia", "mdhd")) { reason = it }
                val bound: Long? = when {
                    fragmented -> null
                    md == null || md.allOnes || md.duration == 0L || md.duration < 0 -> null
                    else -> md.duration
                }
                val boundNote = when {
                    fragmented -> "조각화 파일: mdhd 길이는 조각을 포함하지 않으므로 media_time 상한은 검사하지 않음"
                    bound == null -> "mdhd duration을 알 수 없어 media_time 상한을 검사하지 않음" + (reason?.let { " ($it)" } ?: "")
                    else -> null
                }
                val bad = ArrayList<String>()
                var total = 0
                val rates = LinkedHashSet<String>()
                val step = if (v == 0) 12L else 20L
                for (i in 0 until tbl.count) {
                    val p = tbl.start + i * step
                    val mediaTime = if (v == 0) ctx.reader.readUInt32(p + 4).toInt().toLong() else ctx.reader.readUInt64(p + 8)
                    val rp = p + if (v == 0) 8 else 16
                    val rateInt = ctx.reader.readUInt16(rp).toShort().toInt()
                    val rateFrac = ctx.reader.readUInt16(rp + 2)
                    val n = "항목 #${i + 1}"
                    val msgs = listOfNotNull(
                        if (mediaTime < -1) "$n: media_time $mediaTime (−1 또는 0 이상이어야 함)" else null,
                        if (bound != null && mediaTime >= 0 && mediaTime >= bound) "$n: media_time $mediaTime ≥ 미디어 길이 $bound (mdhd)" else null,
                        // QuickTime: media_rate is one signed 16.16 fixed value; any non-negative rate is allowed.
                        if (ctx.isQt && rateInt < 0) "$n: media_rate ${rate(rateInt, rateFrac)} (음수 불가)" else null,
                        if (!ctx.isQt && rateInt !in 0..1) "$n: media_rate_integer $rateInt (0 또는 1이어야 함)" else null,
                        if (!ctx.isQt && rateFrac != 0) "$n: media_rate_fraction $rateFrac (0이어야 함)" else null,
                    )
                    if (ctx.isQt && rates.size < MAX_LISTED) rates += rate(rateInt, rateFrac)
                    msgs.forEach { total++; if (bad.size < MAX_LISTED) bad += it }
                }
                if (total > bad.size) bad += "… 외 ${total - bad.size}건"
                bad.forEach { a.fail(t, "${at(b)} $it", b) }
                if (bad.isEmpty()) {
                    if (boundNote != null && !fragmented) a.skip(t, "${at(b)} 항목 ${tbl.count}개: $boundNote")
                    else a.pass(t, "${at(b)} 항목 ${tbl.count}개 정상" +
                        (if (rates.isNotEmpty()) " (media_rate ${rates.joinToString(", ")})" else "") + (boundNote?.let { " ($it)" } ?: ""))
                }
            }
        }
        return a.result()
    }
}
