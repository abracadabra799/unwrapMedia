package com.multiviewer.parser.conformance.jpeg

import com.multiviewer.parser.conformance.ConformanceFinding
import com.multiviewer.parser.conformance.ConformanceStatus.NOT_APPLICABLE
import com.multiviewer.parser.conformance.ConformanceStatus.NOT_CHECKED
import com.multiviewer.parser.conformance.jpeg.JpegContext.Companion.hex
import com.multiviewer.parser.conformance.jpeg.JpegContext.Companion.markerName

/** ITU-T T.81 Annex B interchange-format rules for the primary image (SOI .. EOI). */
internal object JpegT81Rules {
    private const val HIERARCHICAL = "계층(hierarchical) 모드(DHP/EXP/차분 SOF) — 1단계 검사 범위 밖 / hierarchical mode is outside phase 1"

    fun evaluate(ctx: JpegContext): List<ConformanceFinding> = listOf(
        soi(ctx), marker(ctx), reserved(ctx), segmentLength(ctx), frameStructure(ctx), frameHeader(ctx), scanHeader(ctx),
        dqt(ctx), dht(ctx), tables(ctx), restart(ctx), eoi(ctx),
    )

    private fun soi(ctx: JpegContext): ConformanceFinding {
        val first = ctx.segments.firstOrNull()
        val v = ArrayList<String>()
        val ranges = ArrayList<LongRange>()
        if (first?.marker != 0xD8) {
            v += "파일이 SOI(0xFFD8)로 시작하지 않음 / does not begin with SOI" +
                (first?.let { " — 첫 마커 ${markerName(it.marker)} @${hex(it.offset)}" } ?: "")
            ranges += 0L until minOf(2L, ctx.length)
        }
        // B.2.1: the interchange format begins with one SOI and contains one frame; a later SOI before EOI is not allowed.
        ctx.segments.drop(1).filter { it.marker == 0xD8 }.forEach {
            v += "SOI @${hex(it.offset)}: 이미지 안에 SOI가 또 있음 — SOI는 처음 한 번만 / a second SOI inside the image (B.2.1: begins with an SOI, one frame, ends with an EOI)"
            ranges += it.range
        }
        return ctx.finding("t81.soi", v, ranges, listOf("SOI @${hex(first?.offset ?: 0)}"))
    }

    private fun marker(ctx: JpegContext): ConformanceFinding {
        val p = ctx.stream.markerProblems
        return ctx.finding("t81.marker", p.map { it.message }, p.map { it.offset until minOf(it.offset + 2, ctx.length) },
            listOf("마커 ${ctx.segments.size}개 모두 0xFF + 유효 코드 / all ${ctx.segments.size} markers are 0xFF + a valid code"))
    }

    private val RESERVED = setOf(0x01, 0xC8) + (0x02..0xBF) + (0xF0..0xFD)

    private fun reserved(ctx: JpegContext): ConformanceFinding {
        val r = ctx.segments.filter { it.marker in RESERVED }
        return ctx.finding("t81.marker.reserved", r.map { "${markerName(it.marker)} @${hex(it.offset)}: 예약 마커 / reserved marker (Table B.1)" },
            r.map { it.range }, listOf("예약 마커 없음 / no reserved markers"))
    }

    private fun segmentLength(ctx: JpegContext): ConformanceFinding {
        val p = ctx.stream.lengthProblems
        return ctx.finding("t81.segment.length", p.map { it.message }, p.map { it.offset until minOf(it.offset + 4, ctx.length) },
            listOf("모든 마커 세그먼트 길이가 파일 안에 있음 / every marker segment length fits"))
    }

    private fun stoppedEarly(ctx: JpegContext) = ctx.stream.truncated || ctx.stream.markerProblems.isNotEmpty() ||
        ctx.stream.lengthProblems.isNotEmpty() || ctx.stream.capped

    private fun frameStructure(ctx: JpegContext): ConformanceFinding {
        val id = "t81.frame.structure"
        if (ctx.hierarchical) return ctx.skip(id, NOT_CHECKED, HIERARCHICAL)
        val segs = ctx.segments
        val sofs = ctx.sofSegments
        val sosIdx = segs.indices.filter { segs[it].marker == 0xDA }
        if ((sofs.isEmpty() || sosIdx.isEmpty()) && stoppedEarly(ctx))
            return ctx.skip(id, NOT_CHECKED, "스트림이 SOF/SOS 전에 끝나 판단 불가 / stream ends before SOF/SOS")
        val v = ArrayList<String>()
        val ranges = ArrayList<LongRange>()
        if (sofs.isEmpty()) v += "프레임 헤더(SOFn) 없음 / no frame header"
        if (sofs.size > 1) { v += "프레임 헤더 ${sofs.size}개 — 1개여야 함 / ${sofs.size} frames, the interchange format has one"; ranges += sofs.drop(1).map { it.range } }
        if (sosIdx.isEmpty()) v += "스캔(SOS) 없음 / no scan"
        val sofIdx = segs.indexOfFirst { it.marker in JpegFrame.SOF_MARKERS }
        sosIdx.firstOrNull()?.let { if (sofIdx < 0 || it < sofIdx) { v += "SOS @${hex(segs[it].offset)}가 프레임 헤더보다 앞 / scan before the frame header"; ranges += segs[it].range } }
        segs.withIndex().filter { it.value.marker == 0xDC }.forEach { (i, s) ->
            if (sosIdx.isEmpty() || i != sosIdx.first() + 1) { v += "DNL @${hex(s.offset)}가 첫 스캔 바로 뒤가 아님 / DNL does not immediately follow the first scan"; ranges += s.range }
        }
        if (ctx.frame?.lines == 0 && ctx.lines == null) v += "Y=0인데 첫 스캔 뒤 DNL 없음 / Y = 0 without a DNL after the first scan"
        return ctx.finding(id, v, ranges, listOf("${sofs.firstOrNull()?.let { markerName(it.marker) }} 1개, 스캔 ${sosIdx.size}개 / one frame, ${sosIdx.size} scan(s)"))
    }

    private fun frameHeader(ctx: JpegContext): ConformanceFinding {
        val id = "t81.frame.header"
        if (ctx.hierarchical) return ctx.skip(id, NOT_CHECKED, HIERARCHICAL)
        val sof = ctx.sofSegments.firstOrNull() ?: return ctx.skip(id, NOT_APPLICABLE, "프레임 헤더 없음 / no frame header")
        val f = ctx.frame ?: return ctx.finding(id, listOf("${markerName(sof.marker)} @${hex(sof.offset)}: 길이 ${sof.length} — 고정 필드(6바이트)보다 짧음 / too short"), listOf(sof.range), emptyList())
        val at = "${markerName(f.marker)} @${hex(sof.offset)}"
        val v = ArrayList<String>()
        if (sof.length != 8 + 3 * f.declaredCount) v += "$at: Lf=${sof.length} ≠ 8 + 3×Nf (${8 + 3 * f.declaredCount})"
        val pOk = when (f.process) {
            JpegProcess.BASELINE -> f.precision == 8
            JpegProcess.EXTENDED, JpegProcess.PROGRESSIVE -> f.precision == 8 || f.precision == 12
            else -> f.precision in 2..16
        }
        if (!pOk) v += "$at: P=${f.precision} — ${f.process} 허용값 아님 / not allowed for ${f.process}"
        if (f.samplesPerLine < 1) v += "$at: X=0 — 1 이상이어야 함 / X must be 1-65535"
        val nfMax = if (f.process == JpegProcess.PROGRESSIVE) 4 else 255
        if (f.declaredCount !in 1..nfMax) v += "$at: Nf=${f.declaredCount} — 1-$nfMax"
        f.components.groupBy { it.id }.filter { it.value.size > 1 }.keys.forEach { v += "$at: 성분 ID $it 중복 / duplicate component identifier $it" }
        f.components.forEach { c ->
            if (c.h !in 1..4 || c.v !in 1..4) v += "$at: 성분 ${c.id} H=${c.h} V=${c.v} — 1-4"
            val tqOk = if (f.process == JpegProcess.LOSSLESS) c.tq == 0 else c.tq in 0..3
            if (!tqOk) v += "$at: 성분 ${c.id} Tq=${c.tq} — ${if (f.process == JpegProcess.LOSSLESS) "0" else "0-3"}"
        }
        return ctx.finding(id, v, if (v.isEmpty()) emptyList() else listOf(sof.range),
            listOf("$at: P=${f.precision}, Y=${f.lines}, X=${f.samplesPerLine}, Nf=${f.declaredCount}, " +
                f.components.joinToString { "C${it.id} ${it.h}x${it.v} Tq${it.tq}" }))
    }

    private fun scanHeader(ctx: JpegContext): ConformanceFinding {
        val id = "t81.scan.header"
        if (ctx.hierarchical) return ctx.skip(id, NOT_CHECKED, HIERARCHICAL)
        val sos = ctx.segments.filter { it.marker == 0xDA }
        if (sos.isEmpty()) return ctx.skip(id, NOT_APPLICABLE, "스캔 없음 / no scan")
        val f = ctx.frame ?: return ctx.skip(id, NOT_CHECKED, "프레임 헤더를 읽을 수 없어 판단 불가 / frame header unreadable")
        val v = ArrayList<String>()
        val ranges = ArrayList<LongRange>()
        val order = f.components.map { it.id }
        sos.forEach { s ->
            val at = "SOS @${hex(s.offset)}"
            val before = v.size
            val h = JpegHeaders.scan(ctx.reader, s)
            if (h == null || !h.complete) {
                v += "$at: 길이 ${s.length} — Ns 성분과 Ss/Se/Ah/Al을 담기에 짧음 / too short"
            } else {
                if (s.length != 6 + 2 * h.declaredCount) v += "$at: Ls=${s.length} ≠ 6 + 2×Ns (${6 + 2 * h.declaredCount})"
                if (h.declaredCount !in 1..4) v += "$at: Ns=${h.declaredCount} — 1-4"
                val idx = h.components.map { order.indexOf(it.selector) }
                h.components.filter { it.selector !in order }.forEach { v += "$at: Cs=${it.selector}가 프레임 성분에 없음 / not a frame component" }
                if (h.components.map { it.selector }.toSet().size != h.components.size) v += "$at: 같은 성분 중복 / duplicate component selector"
                if (idx.filter { it >= 0 }.zipWithNext().any { (a, b) -> b <= a }) v += "$at: 성분 순서가 프레임 순서와 다름 / order differs from the frame header"
                if (h.components.size > 1) {
                    val sum = h.components.sumOf { c -> f.components.firstOrNull { it.id == c.selector }?.let { it.h * it.v } ?: 0 }
                    if (sum > 10) v += "$at: ΣH×V=$sum > 10"
                }
                val tMax = if (f.process == JpegProcess.BASELINE) 1 else 3
                h.components.forEach { c ->
                    if (c.td > tMax) v += "$at: 성분 ${c.selector} Td=${c.td} — 0-$tMax"
                    val taOk = if (f.process == JpegProcess.LOSSLESS) c.ta == 0 else c.ta <= tMax
                    if (!taOk) v += "$at: 성분 ${c.selector} Ta=${c.ta} — ${if (f.process == JpegProcess.LOSSLESS) "0" else "0-$tMax"}"
                }
                val spectral = "Ss=${h.ss} Se=${h.se} Ah=${h.ah} Al=${h.al}"
                when (f.process) {
                    JpegProcess.BASELINE, JpegProcess.EXTENDED ->
                        if (h.ss != 0 || h.se != 63 || h.ah != 0 || h.al != 0) v += "$at: $spectral — 순차 DCT는 Ss=0 Se=63 Ah=0 Al=0 / sequential DCT requires 0, 63, 0, 0"
                    JpegProcess.PROGRESSIVE -> {
                        if (h.ss > h.se || h.se > 63) v += "$at: $spectral — Ss ≤ Se ≤ 63"
                        if (h.ss == 0 && h.se != 0) v += "$at: $spectral — Ss=0이면 Se=0 (DC 스캔) / Se must be 0 when Ss is 0"
                        if (h.ah > 13 || h.al > 13) v += "$at: $spectral — Ah, Al 0-13"
                        if (h.declaredCount > 1 && h.ss != 0) v += "$at: 프로그레시브 AC 스캔은 성분 1개만 / progressive AC scans are not interleaved"
                    }
                    JpegProcess.LOSSLESS ->
                        if (h.ss !in 1..7 || h.se != 0 || h.ah != 0 || h.al > 15) v += "$at: $spectral — 무손실은 Ss 1-7, Se=0, Ah=0, Al 0-15 / lossless requires these"
                    JpegProcess.DIFFERENTIAL -> Unit
                }
            }
            if (v.size > before) ranges += s.range
        }
        return ctx.finding(id, v, ranges, listOf("스캔 헤더 ${sos.size}개 / ${sos.size} scan header(s)"))
    }

    private fun dqt(ctx: JpegContext): ConformanceFinding {
        val id = "t81.dqt"
        val segs = ctx.segments.filter { it.marker == 0xDB }
        if (segs.isEmpty()) return ctx.skip(id, NOT_APPLICABLE, "DQT 없음 / no DQT")
        val f = ctx.frame
        val v = ArrayList<String>()
        val ranges = ArrayList<LongRange>()
        var count = 0
        segs.forEach { s ->
            val before = v.size
            val tables = JpegHeaders.quantTables(ctx.reader, s, v)
            count += tables.size
            val at = "DQT @${hex(s.offset)}"
            if (tables.isEmpty() && v.size == before) v += "$at: 테이블 없음 / no table"
            tables.forEach { t ->
                if (t.pq > 1) v += "$at: Pq=${t.pq} — 0 또는 1 / 0 or 1"
                if (t.tq > 3) v += "$at: Tq=${t.tq} — 0-3"
                if (t.pq != 0 && f != null && (f.precision == 8 || f.process == JpegProcess.BASELINE))
                    v += "$at: Tq=${t.tq} Pq=1 — 8비트 정밀도(P=8)에서는 Pq=0 / Pq shall be zero for 8-bit precision"
                if (t.hasZero) v += "$at: Tq=${t.tq}에 0인 양자화 값 / a zero quantization element (Qk 1-255, 1-65535)"
            }
            if (v.size > before) ranges += s.range
        }
        return ctx.finding(id, v, ranges, listOf("DQT ${segs.size}개, 테이블 ${count}개 / ${segs.size} DQT, $count table(s)"))
    }

    private fun dht(ctx: JpegContext): ConformanceFinding {
        val id = "t81.dht"
        val segs = ctx.segments.filter { it.marker == 0xC4 }
        if (segs.isEmpty()) return ctx.skip(id, NOT_APPLICABLE, "DHT 없음 / no DHT")
        val f = ctx.frame
        val v = ArrayList<String>()
        val ranges = ArrayList<LongRange>()
        var count = 0
        segs.forEach { s ->
            val before = v.size
            val tables = JpegHeaders.huffmanTables(ctx.reader, s, v)
            count += tables.size
            val at = "DHT @${hex(s.offset)}"
            if (tables.isEmpty() && v.size == before) v += "$at: 테이블 없음 / no table"
            tables.forEach { t ->
                val tcOk = if (f?.process == JpegProcess.LOSSLESS) t.tc == 0 else t.tc <= 1
                if (!tcOk) v += "$at: Tc=${t.tc} — ${if (f?.process == JpegProcess.LOSSLESS) "0" else "0 또는 1 / 0 or 1"}"
                val thMax = if (f?.process == JpegProcess.BASELINE) 1 else 3
                if (t.th > thMax) v += "$at: Th=${t.th} — 0-$thMax"
            }
            if (v.size > before) ranges += s.range
        }
        return ctx.finding(id, v, ranges, listOf("DHT ${segs.size}개, 테이블 ${count}개 / ${segs.size} DHT, $count table(s)"))
    }

    private fun tables(ctx: JpegContext): ConformanceFinding {
        val id = "t81.tables"
        if (ctx.hierarchical) return ctx.skip(id, NOT_CHECKED, HIERARCHICAL)
        val f = ctx.frame ?: return ctx.skip(id, NOT_CHECKED, "프레임 헤더를 읽을 수 없어 판단 불가 / frame header unreadable")
        if (ctx.segments.none { it.marker == 0xDA }) return ctx.skip(id, NOT_CHECKED, "스캔 없음 / no scan")
        val q = HashSet<Int>()
        val h = HashSet<Pair<Int, Int>>()
        val v = ArrayList<String>()
        val ranges = ArrayList<LongRange>()
        val sink = ArrayList<String>()
        ctx.segments.forEach { s ->
            when (s.marker) {
                0xDB -> JpegHeaders.quantTables(ctx.reader, s, sink).forEach { q += it.tq }
                0xC4 -> JpegHeaders.huffmanTables(ctx.reader, s, sink).forEach { h += it.tc to it.th }
                0xDA -> {
                    val sh = JpegHeaders.scan(ctx.reader, s) ?: return@forEach
                    val before = v.size
                    sh.components.forEach { c ->
                        val fc = f.components.firstOrNull { it.id == c.selector } ?: return@forEach
                        if (f.dct && fc.tq !in q) v += "SOS @${hex(s.offset)}: 성분 ${c.selector}의 양자화 테이블 Tq=${fc.tq} 미정의 / not defined before the scan"
                        if (f.huffman) {
                            val needDc = f.process != JpegProcess.PROGRESSIVE || (sh.ss == 0 && sh.ah == 0)
                            val needAc = f.process != JpegProcess.LOSSLESS && (f.process != JpegProcess.PROGRESSIVE || sh.se > 0)
                            if (needDc && (0 to c.td) !in h) v += "SOS @${hex(s.offset)}: 성분 ${c.selector}의 DC 허프만 테이블 Td=${c.td} 미정의 / not defined before the scan"
                            if (needAc && (1 to c.ta) !in h) v += "SOS @${hex(s.offset)}: 성분 ${c.selector}의 AC 허프만 테이블 Ta=${c.ta} 미정의 / not defined before the scan"
                        }
                    }
                    if (v.size > before) ranges += s.range
                }
            }
        }
        val coding = if (f.huffman) "허프만 / Huffman" else "산술(기본 조건화 허용) / arithmetic (default conditioning allowed)"
        return ctx.finding(id, v, ranges, listOf("모든 스캔의 테이블이 스캔 전에 정의됨 ($coding) / every scan's tables are defined before it"))
    }

    private fun ceilDiv(a: Long, b: Long): Long = (a + b - 1) / b

    /** MCUs in a DCT scan: interleaved → MCU grid of the frame; one component → that component's 8x8 blocks. */
    internal fun mcuCount(f: JpegFrame, scan: JpegScanHeader, lines: Int): Long? {
        val x = f.samplesPerLine.toLong()
        val y = lines.toLong()
        if (scan.components.size > 1) return ceilDiv(x, 8L * f.hMax) * ceilDiv(y, 8L * f.vMax)
        val c = f.components.firstOrNull { it.id == scan.components.firstOrNull()?.selector } ?: return null
        val w = ceilDiv(x * c.h, f.hMax.toLong())
        val h = ceilDiv(y * c.v, f.vMax.toLong())
        return ceilDiv(w, 8) * ceilDiv(h, 8)
    }

    private fun restart(ctx: JpegContext): ConformanceFinding {
        val id = "t81.restart"
        if (ctx.hierarchical) return ctx.skip(id, NOT_CHECKED, HIERARCHICAL)
        val f = ctx.frame
        val v = ArrayList<String>()
        val ranges = ArrayList<LongRange>()
        val notes = ArrayList<String>()
        val unverified = ArrayList<String>()
        var ri: Int? = 0 // SOI disables restart (B.2.4.4); null = unknown after a malformed DRI
        ctx.segments.forEachIndexed { i, s ->
            when (s.marker) {
                0xDD -> {
                    ri = JpegHeaders.restartInterval(ctx.reader, s)
                    if (ri == null) { v += "DRI @${hex(s.offset)}: Lr=${s.length} ≠ 4"; ranges += s.range }
                }
                in 0xD0..0xD7 -> { v += "${markerName(s.marker)} @${hex(s.offset)}: 스캔 데이터 밖의 재시작 마커 / restart marker outside scan data"; ranges += s.range }
                0xDA -> {
                    val e = ctx.stream.entropy.firstOrNull { it.sosIndex == i } ?: return@forEachIndexed
                    val at = "SOS @${hex(s.offset)}"
                    val interval = ri
                    when {
                        interval == null -> unverified += "$at: Ri를 알 수 없어 RST 개수 미검사 / Ri unknown"
                        interval == 0 && e.restartCount > 0 -> {
                            v += "$at: 재시작 비활성(Ri=0)인데 RST ${e.restartCount}개 @${hex(e.firstRestart!!)} / restart markers while restart is disabled"
                            ranges += e.firstRestart until e.firstRestart + 2
                        }
                        interval > 0 -> {
                            e.restartOutOfOrder?.let { v += "$at: RST 번호가 0-7 순환 순서가 아님 @${hex(it)} / RSTm out of modulo-8 sequence"; ranges += it until it + 2 }
                            val sh = JpegHeaders.scan(ctx.reader, s)
                            val lines = ctx.lines
                            val truncatedScan = ctx.stream.truncated && e === ctx.stream.entropy.last()
                            val mcus = if (f != null && f.dct && sh != null && sh.complete && lines != null) mcuCount(f, sh, lines) else null
                            when {
                                truncatedScan -> unverified += "$at: 스캔 데이터가 잘려 RST 개수 미검사 / scan data cut off"
                                mcus == null -> unverified += "$at: MCU 수를 계산할 수 없어(무손실·DNL 미상 등) RST 개수 미검사 / MCU count unknown"
                                else -> {
                                    val expected = ceilDiv(mcus, interval.toLong()) - 1
                                    if (e.restartCount != expected) {
                                        v += "$at: RST ${e.restartCount}개 ≠ 예상 $expected (MCU $mcus, Ri $interval) / restart marker count"
                                        ranges += e.start until e.end
                                    } else notes += "$at: Ri=$interval, MCU $mcus, RST ${e.restartCount}개 (예상 $expected) / as expected"
                                }
                            }
                        }
                        else -> notes += "$at: 재시작 없음 / restart disabled"
                    }
                }
            }
        }
        if (v.isEmpty() && unverified.isNotEmpty()) return ctx.skip(id, NOT_CHECKED, (unverified + notes).joinToString("; "))
        return ctx.finding(id, v, ranges, notes.ifEmpty { listOf("스캔 없음 / no scan") })
    }

    private fun eoi(ctx: JpegContext): ConformanceFinding {
        val id = "t81.eoi"
        if (ctx.stream.capped) return ctx.skip(id, NOT_CHECKED, "세그먼트가 ${JpegStreamScanner.MAX_SEGMENTS}개를 넘어 중단 / too many segments")
        val e = ctx.stream.eoi
        if (e != null) return ctx.finding(id, emptyList(), emptyList(), listOf("EOI @${hex(e.offset)}"))
        val why = when {
            ctx.stream.truncated -> "파일 끝(${ctx.length})까지 EOI 없음 — 잘린 파일 / no EOI before the end of the file (truncated)"
            else -> "마커 구문 오류로 EOI에 도달하지 못함 @${hex(ctx.stream.end)} / marker syntax error before EOI"
        }
        return ctx.finding(id, listOf(why), listOf(ctx.stream.end until ctx.length), emptyList())
    }
}
