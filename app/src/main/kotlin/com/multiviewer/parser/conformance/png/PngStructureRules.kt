package com.multiviewer.parser.conformance.png

import com.multiviewer.parser.conformance.ConformanceFinding
import com.multiviewer.parser.conformance.ConformanceStatus.NOT_CHECKED
import com.multiviewer.parser.conformance.ImageRuleBook.Companion.hex
import com.multiviewer.parser.integrity.crc32Of
import com.multiviewer.parser.integrity.hex32

/** PNG-3 datastream structure: signature, chunk layout/type/CRC, IHDR, critical chunk sequence, PLTE, ordering, uniqueness. */
internal object PngStructureRules {
    /** Table 7 ancillary ordering groups (fcTL / fdAT are checked by the APNG rules). */
    val BEFORE_PLTE_AND_IDAT = setOf("cHRM", "cICP", "gAMA", "iCCP", "mDCV", "cLLI", "sBIT", "sRGB")
    val AFTER_PLTE_BEFORE_IDAT = setOf("bKGD", "hIST", "tRNS")
    val BEFORE_IDAT = setOf("acTL", "eXIf", "pHYs", "sPLT")

    /** Table 7 "Multiple allowed: No" (ancillary); IHDR / IEND / PLTE uniqueness is checked by png3.critical / png3.plte. */
    val SINGLE = listOf("acTL", "cHRM", "cICP", "gAMA", "iCCP", "mDCV", "cLLI", "sBIT", "sRGB", "bKGD", "hIST", "tRNS", "eXIf", "pHYs", "tIME")

    fun evaluate(ctx: PngContext): List<ConformanceFinding> =
        listOf(signature(ctx), layout(ctx), type(ctx), unknownCritical(ctx), crc(ctx), ihdr(ctx), critical(ctx), plte(ctx), order(ctx), multiple(ctx))

    private fun signature(ctx: PngContext): ConformanceFinding {
        val ok = ctx.length >= 8 && ctx.reader.readBytes(0, 8).contentEquals(PngChunkScanner.SIGNATURE)
        return ctx.finding("png3.signature", if (ok) emptyList() else listOf("첫 8바이트가 89 50 4E 47 0D 0A 1A 0A가 아님 / first 8 bytes are not 89 50 4E 47 0D 0A 1A 0A"),
            if (ok) emptyList() else listOf(0L until minOf(8L, ctx.length)), listOf("89 50 4E 47 0D 0A 1A 0A"))
    }

    private fun layout(ctx: PngContext): ConformanceFinding {
        val s = ctx.scan
        if (s.capped) return ctx.skip("png3.chunk.layout", NOT_CHECKED, "청크 상한 ${PngChunkScanner.MAX_CHUNKS}개에서 멈춤 / stopped at the ${PngChunkScanner.MAX_CHUNKS}-chunk cap")
        return if (s.problem == null) ctx.finding("png3.chunk.layout", emptyList(), emptyList(),
            listOf("청크 ${s.chunks.size}개, 모두 파일 안 / ${s.chunks.size} chunks, all inside the file, lengths ≤ 2^31-1"))
        else ctx.finding("png3.chunk.layout", listOf(s.problem), listOf(s.problemAt!! until ctx.length), emptyList())
    }

    private fun type(ctx: PngContext): ConformanceFinding {
        val bad = ArrayList<String>()
        val ranges = ArrayList<LongRange>()
        for (c in ctx.chunks) {
            val bytes = c.type.map { it.code }
            when {
                bytes.any { it !in 0x41..0x5A && it !in 0x61..0x7A } -> "유형 바이트가 41-5A/61-7A 밖 / type bytes outside 41-5A and 61-7A: ${bytes.joinToString(" ") { "%02X".format(it) }}"
                bytes[2] and 0x20 != 0 -> "세 번째 글자 소문자(예약 비트 1) / third letter lowercase (reserved bit set)"
                else -> null
            }?.let { bad += "${c.label}: $it"; ranges += ctx.range(c) }
        }
        return ctx.finding("png3.chunk.type", bad, ranges, listOf("청크 유형 ${ctx.chunks.map { it.type }.distinct().size}종 / ${ctx.chunks.map { it.type }.distinct().size} chunk types: ${ctx.chunks.map { it.type }.distinct().joinToString(", ")}"))
    }

    private val KNOWN_CRITICAL = setOf("IHDR", "PLTE", "IDAT", "IEND")

    /**
     * PNG-3 §15.3.1: no chunks other than those specified or defined by the rules for new chunk types; §13.1: an unknown
     * critical chunk is fatal for a decoder. A public (2nd letter uppercase) critical chunk is not defined by PNG-3 -> FAIL;
     * a private critical chunk is allowed by the naming rules but cannot be verified (and decoders must reject it) -> NOT_CHECKED.
     */
    private fun unknownCritical(ctx: PngContext): ConformanceFinding {
        val bad = ArrayList<String>()
        val ranges = ArrayList<LongRange>()
        val priv = ArrayList<PngChunk>()
        for (c in ctx.chunks) {
            val b = c.type.map { it.code }
            if (b.size != 4 || b.any { it !in 0x41..0x5A && it !in 0x61..0x7A } || b[2] and 0x20 != 0) continue   // png3.chunk.type's business
            if (b[0] and 0x20 != 0 || c.type in KNOWN_CRITICAL) continue
            if (b[1] and 0x20 == 0) { bad += "${c.label}: PNG-3이 정의하지 않은 공개 필수 청크 / public critical chunk not defined by PNG-3 (decoders must reject the file)"; ranges += ctx.range(c) }
            else priv += c
        }
        if (bad.isEmpty() && priv.isNotEmpty())
            return ctx.skip("png3.chunk.unknown", NOT_CHECKED, "비공개 필수 청크 ${priv.joinToString { it.type }}는 검증 불가 / private critical chunk(s) ${priv.joinToString { it.type }} cannot be verified")
        return ctx.finding("png3.chunk.unknown", bad, ranges, listOf("정의되지 않은 필수 청크 없음 / no critical chunk other than IHDR, PLTE, IDAT, IEND"))
    }

    private fun crc(ctx: PngContext): ConformanceFinding {
        val bad = ArrayList<String>()
        val ranges = ArrayList<LongRange>()
        for (c in ctx.complete) {
            val stored = ctx.reader.readUInt32(c.dataStart + c.length)
            val computed = crc32Of(ctx.reader, c.offset + 4, 4 + c.length)
            if (stored != computed) { bad += "${c.label}: 저장 ${hex32(stored)} ≠ 계산 ${hex32(computed)} / stored ${hex32(stored)} ≠ computed ${hex32(computed)}"; ranges += ctx.range(c) }
        }
        if (ctx.complete.isEmpty()) return ctx.skip("png3.crc", NOT_CHECKED, "완전한 청크 없음 / no complete chunk")
        return ctx.finding("png3.crc", bad, ranges, listOf("완전한 청크 ${ctx.complete.size}개의 CRC 일치 / CRC matches for all ${ctx.complete.size} complete chunks"))
    }

    private fun ihdr(ctx: PngContext): ConformanceFinding {
        val first = ctx.chunks.firstOrNull() ?: return ctx.finding("png3.ihdr", listOf("청크 없음 / no chunk"), emptyList(), emptyList())
        if (first.type != "IHDR") return ctx.finding("png3.ihdr", listOf("첫 청크가 ${first.label} / first chunk is ${first.label}"), listOf(ctx.range(first)), emptyList())
        if (!first.complete) return ctx.finding("png3.ihdr", listOf("IHDR가 잘림 / IHDR is cut off"), listOf(ctx.range(first)), emptyList())
        if (first.length != 13L) return ctx.finding("png3.ihdr", listOf("IHDR 길이 ${first.length} ≠ 13 / IHDR length ${first.length} ≠ 13"), listOf(ctx.range(first)), emptyList())
        val h = ctx.header!!
        val bad = ArrayList<String>()
        if (h.width == 0L || h.width > PngChunkScanner.MAX_LENGTH) bad += "Width ${h.width} (1..2^31-1)"
        if (h.height == 0L || h.height > PngChunkScanner.MAX_LENGTH) bad += "Height ${h.height} (1..2^31-1)"
        val allowed = PngHeader.ALLOWED_DEPTHS[h.colorType]
        if (allowed == null) bad += "Color type ${h.colorType} (0, 2, 3, 4, 6)"
        else if (h.bitDepth !in allowed) bad += "Color type ${h.colorType} + bit depth ${h.bitDepth} (허용 / allowed ${allowed.joinToString()})"
        if (h.compression != 0) bad += "Compression method ${h.compression} ≠ 0"
        if (h.filter != 0) bad += "Filter method ${h.filter} ≠ 0"
        if (h.interlace !in 0..1) bad += "Interlace method ${h.interlace} (0, 1)"
        return ctx.finding("png3.ihdr", bad.map { "IHDR: $it" }, if (bad.isEmpty()) emptyList() else listOf(ctx.range(first)),
            listOf("${h.width}x${h.height}, bit depth ${h.bitDepth}, color type ${h.colorType}, compression ${h.compression}, filter ${h.filter}, interlace ${h.interlace}"))
    }

    private fun critical(ctx: PngContext): ConformanceFinding {
        if (ctx.scan.capped) return ctx.skip("png3.critical", NOT_CHECKED, "청크 상한에서 멈춤 — IEND 미확인 / stopped at the chunk cap: IEND not reached")
        val bad = ArrayList<String>()
        val ranges = ArrayList<LongRange>()
        val chunks = ctx.chunks
        chunks.filter { it.type == "IHDR" }.drop(1).forEach { bad += "두 번째 IHDR / second IHDR: ${it.label}"; ranges += ctx.range(it) }
        val idats = chunks.filter { it.type == "IDAT" }
        if (idats.isEmpty()) bad += "IDAT 없음 / no IDAT chunk"
        else {
            val between = chunks.subList(idats.first().index, idats.last().index + 1).filter { it.type != "IDAT" }
            between.forEach { bad += "IDAT 사이의 ${it.label} / ${it.label} between IDAT chunks"; ranges += ctx.range(it) }
        }
        val plte = chunks.firstOrNull { it.type == "PLTE" }
        if (plte != null && idats.isNotEmpty() && plte.index > idats.first().index) { bad += "PLTE가 첫 IDAT 뒤 / PLTE after the first IDAT: ${plte.label}"; ranges += ctx.range(plte) }
        val iend = ctx.scan.iend
        when {
            iend == null -> bad += "IEND 없음(잘림 또는 누락) / no IEND (truncated or missing)"
            iend.length != 0L -> { bad += "IEND 데이터 ${iend.length}바이트(비어 있어야 함) / IEND carries ${iend.length} data bytes (must be empty)"; ranges += ctx.range(iend) }
        }
        return ctx.finding("png3.critical", bad, ranges,
            listOf("IHDR 1개(처음), IDAT ${idats.size}개 연속, IEND 1개(마지막) / one IHDR first, ${idats.size} consecutive IDAT, one empty IEND last"))
    }

    private fun plte(ctx: PngContext): ConformanceFinding {
        val h = ctx.validHeader ?: return ctx.skip("png3.plte", NOT_CHECKED, "IHDR 무효 — 색상 타입 미상 / IHDR invalid: colour type unknown")
        val pltes = ctx.chunks.filter { it.type == "PLTE" }
        val bad = ArrayList<String>()
        val ranges = ArrayList<LongRange>()
        if (h.colorType == 3 && pltes.isEmpty()) bad += "색상 타입 3인데 PLTE 없음 / colour type 3 without PLTE"
        if (h.colorType == 0 || h.colorType == 4) pltes.forEach { bad += "색상 타입 ${h.colorType}에 PLTE 금지 / PLTE not allowed for colour type ${h.colorType}: ${it.label}"; ranges += ctx.range(it) }
        pltes.drop(1).forEach { bad += "두 번째 PLTE / second PLTE: ${it.label}"; ranges += ctx.range(it) }
        pltes.firstOrNull()?.let { p ->
            if (p.length % 3 != 0L) { bad += "PLTE 길이 ${p.length}가 3의 배수가 아님 / PLTE length ${p.length} not divisible by 3"; ranges += ctx.range(p) }
            else if (p.length !in 3L..768L) { bad += "PLTE 항목 ${p.length / 3}개(1..256) / ${p.length / 3} PLTE entries (1..256)"; ranges += ctx.range(p) }
            else if (h.colorType == 3 && p.length / 3 > (1L shl h.bitDepth)) {
                bad += "PLTE 항목 ${p.length / 3}개 > 2^${h.bitDepth} / ${p.length / 3} entries exceed 2^${h.bitDepth} for bit depth ${h.bitDepth}"; ranges += ctx.range(p)
            }
        }
        return ctx.finding("png3.plte", bad, ranges, listOf(
            if (pltes.isEmpty()) "색상 타입 ${h.colorType}, PLTE 없음 / colour type ${h.colorType}, no PLTE"
            else "색상 타입 ${h.colorType}, PLTE 항목 ${pltes.first().length / 3}개 / colour type ${h.colorType}, ${pltes.first().length / 3} PLTE entries"))
    }

    private fun order(ctx: PngContext): ConformanceFinding {
        val plte = ctx.chunks.firstOrNull { it.type == "PLTE" }?.index
        val idat = ctx.chunks.firstOrNull { it.type == "IDAT" }?.index
        val bad = ArrayList<String>()
        val ranges = ArrayList<LongRange>()
        var checked = 0
        for (c in ctx.chunks) {
            val beforePlte = c.type in BEFORE_PLTE_AND_IDAT
            val afterPlte = c.type in AFTER_PLTE_BEFORE_IDAT
            if (!beforePlte && !afterPlte && c.type !in BEFORE_IDAT) continue
            checked++
            val problem = when {
                idat != null && c.index > idat -> "IDAT 뒤 / after IDAT"
                beforePlte && plte != null && c.index > plte -> "PLTE 뒤 / after PLTE"
                afterPlte && plte != null && c.index < plte -> "PLTE 앞 / before PLTE"
                else -> null
            }
            if (problem != null) { bad += "${c.label}: $problem"; ranges += ctx.range(c) }
        }
        return ctx.finding("png3.order", bad, ranges, listOf("순서 제약이 있는 보조 청크 ${checked}개 / $checked ancillary chunks with ordering constraints in place"))
    }

    private fun multiple(ctx: PngContext): ConformanceFinding {
        val bad = ArrayList<String>()
        val ranges = ArrayList<LongRange>()
        for (type in SINGLE) {
            val list = ctx.chunks.filter { it.type == type }
            if (list.size > 1) { bad += "$type ${list.size}개 / $type appears ${list.size} times: ${list.joinToString { hex(it.offset) }}"; list.drop(1).forEach { ranges += ctx.range(it) } }
        }
        return ctx.finding("png3.multiple", bad, ranges, listOf("반복 금지 청크 모두 1개 이하 / every single-instance ancillary chunk appears at most once"))
    }
}
