package com.multiviewer.parser.conformance.png

import com.multiviewer.parser.conformance.ConformanceFinding
import com.multiviewer.parser.conformance.ConformanceStatus.NOT_APPLICABLE
import com.multiviewer.parser.conformance.ConformanceStatus.NOT_CHECKED
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** PNG-3 §11.3 ancillary chunk fields, keywords, text chunks, and the zlib headers of every compressed stream (§10). */
internal object PngAncillaryRules {
    fun evaluate(ctx: PngContext): List<ConformanceFinding> =
        listOf(trns(ctx), ancillary(ctx), keyword(ctx), text(ctx), srgbIccp(ctx), unusedBits(ctx)) + zlib(ctx)

    private fun u16(b: ByteArray, i: Int) = ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)
    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xFF

    private fun trns(ctx: PngContext): ConformanceFinding {
        val list = ctx.of("tRNS")
        if (list.isEmpty()) return ctx.skip("png3.trns", NOT_APPLICABLE, "tRNS 없음 / no tRNS chunk")
        val h = ctx.validHeader ?: return ctx.skip("png3.trns", NOT_CHECKED, "IHDR 무효 — 색상 타입 미상 / IHDR invalid: colour type unknown")
        val t = list.first()
        val problem = when (h.colorType) {
            4, 6 -> "색상 타입 ${h.colorType}(알파 채널 있음)에 tRNS 금지 / tRNS not allowed for colour type ${h.colorType}"
            0 -> if (t.length != 2L) "색상 타입 0 tRNS 길이 ${t.length} ≠ 2 / colour type 0 tRNS length ${t.length} ≠ 2" else null
            2 -> if (t.length != 6L) "색상 타입 2 tRNS 길이 ${t.length} ≠ 6 / colour type 2 tRNS length ${t.length} ≠ 6" else null
            else -> {
                val entries = ctx.paletteEntries
                when {
                    entries == null -> "색상 타입 3인데 유효한 PLTE 없음 / colour type 3 without a valid PLTE"
                    t.length > entries -> "알파 값 ${t.length}개 > 팔레트 항목 ${entries}개 / ${t.length} alpha values > $entries palette entries"
                    else -> null
                }
            }
        }
        return ctx.finding("png3.trns", listOfNotNull(problem?.let { "${t.label}: $it" }), if (problem == null) emptyList() else listOf(ctx.range(t)),
            listOf("${t.label}: 색상 타입 ${h.colorType}, ${t.length}바이트 / colour type ${h.colorType}, ${t.length} bytes"))
    }

    /** Expected data length of the colour-dependent chunks (sBIT, bKGD) per colour type. */
    private fun sbitLength(ct: Int) = when (ct) { 0 -> 1; 2, 3 -> 3; 4 -> 2; else -> 4 }
    private fun bkgdLength(ct: Int) = when (ct) { 0, 4 -> 2; 2, 6 -> 6; else -> 1 }

    private fun ancillary(ctx: PngContext): ConformanceFinding {
        val h = ctx.validHeader
        val bad = ArrayList<String>()
        val ranges = ArrayList<LongRange>()
        var checked = 0
        var skipped = 0
        fun fail(c: PngChunk, text: String) { bad += "${c.label}: $text"; ranges += ctx.range(c) }
        fun len(c: PngChunk, n: Long) = (c.length == n).also { if (!it) fail(c, "길이 ${c.length} ≠ $n / length ${c.length} ≠ $n") }
        for (c in ctx.complete) {
            when (c.type) {
                "cHRM" -> { checked++; len(c, 32) }
                "gAMA" -> { checked++; len(c, 4) }
                "cLLI" -> { checked++; len(c, 8) }
                "sRGB" -> { checked++; if (len(c, 1)) ctx.data(c, 1)[0].toInt().and(0xFF).let { if (it > 3) fail(c, "Rendering intent $it (0..3)") } }
                "cICP" -> { checked++; if (len(c, 4)) ctx.data(c, 4).let { if (u8(it, 2) != 0) fail(c, "Matrix Coefficients ${u8(it, 2)} ≠ 0"); if (u8(it, 3) !in 0..1) fail(c, "Video Full Range Flag ${u8(it, 3)} (0, 1)") } }
                "mDCV" -> { checked++; len(c, 24); if (ctx.first("cICP") == null) fail(c, "mDCV에는 cICP가 필요 / mDCV requires a cICP chunk") }
                "pHYs" -> { checked++; if (len(c, 9)) u8(ctx.data(c, 9), 8).let { if (it > 1) fail(c, "Unit specifier $it (0, 1)") } }
                "tIME" -> {
                    checked++
                    if (len(c, 7)) {
                        val b = ctx.data(c, 7)
                        val f = listOf("Month" to (u8(b, 2) to 1..12), "Day" to (u8(b, 3) to 1..31), "Hour" to (u8(b, 4) to 0..23),
                            "Minute" to (u8(b, 5) to 0..59), "Second" to (u8(b, 6) to 0..60))
                        f.filter { (_, v) -> v.first !in v.second }.forEach { (n, v) -> fail(c, "$n ${v.first} (${v.second.first}..${v.second.last})") }
                    }
                }
                "sBIT" -> {
                    if (h == null) { skipped++; continue }
                    checked++
                    if (len(c, sbitLength(h.colorType).toLong())) ctx.data(c, 4).forEachIndexed { i, v ->
                        val bits = v.toInt() and 0xFF
                        if (bits !in 1..h.sampleDepth) fail(c, "significant bits[$i] = $bits (1..${h.sampleDepth})")
                    }
                }
                "bKGD" -> {
                    if (h == null) { skipped++; continue }
                    checked++
                    if (len(c, bkgdLength(h.colorType).toLong()) && h.colorType == 3) {
                        val index = u8(ctx.data(c, 1), 0)
                        val entries = ctx.paletteEntries
                        if (entries == null) fail(c, "색상 타입 3인데 유효한 PLTE 없음 / colour type 3 without a valid PLTE")
                        else if (index >= entries) fail(c, "팔레트 인덱스 $index ≥ 항목 수 $entries / palette index $index ≥ $entries entries")
                    }
                }
                "hIST" -> {
                    checked++
                    val entries = ctx.paletteEntries
                    if (entries == null) fail(c, "PLTE 없이 hIST / hIST without a valid PLTE")
                    else len(c, entries * 2L)
                }
                "sPLT" -> { checked++; splt(ctx, c)?.let { fail(c, it) } }
            }
        }
        val names = ctx.of("sPLT").mapNotNull { c -> ctx.data(c, 80).let { b -> b.indexOf(0).takeIf { it > 0 }?.let { String(b, 0, it, Charsets.ISO_8859_1) } } }
        names.groupBy { it }.filter { it.value.size > 1 }.keys.forEach { bad += "sPLT 이름 중복 / duplicate sPLT palette name \"$it\"" }
        if (bad.isEmpty() && skipped > 0) return ctx.skip("png3.ancillary", NOT_CHECKED, "IHDR 무효 — sBIT/bKGD ${skipped}개 미검사 / IHDR invalid: $skipped sBIT/bKGD chunk(s) not checked")
        if (checked == 0 && bad.isEmpty()) return ctx.skip("png3.ancillary", NOT_APPLICABLE, "검사 대상 보조 청크 없음 / no cHRM, gAMA, sRGB, cICP, mDCV, cLLI, sBIT, bKGD, hIST, pHYs, sPLT or tIME chunk")
        return ctx.finding("png3.ancillary", bad, ranges, listOf("보조 청크 ${checked}개 필드 정상 / fields of $checked ancillary chunks valid"))
    }

    /** sPLT after its name: sample depth 8/16, entry bytes divisible by 6/10, frequencies non-increasing. */
    private fun splt(ctx: PngContext, c: PngChunk): String? {
        if (c.length > PngContext.PAYLOAD_CAP) return null
        val b = ctx.data(c)
        val nul = b.indexOf(0)
        if (nul < 0 || nul + 1 >= b.size) return "이름 구분자 또는 sample depth 없음 / missing null separator or sample depth"
        val depth = u8(b, nul + 1)
        if (depth != 8 && depth != 16) return "sample depth $depth (8, 16)"
        val entry = if (depth == 8) 6 else 10
        val rest = b.size - nul - 2
        if (rest % entry != 0) return "항목 바이트 ${rest}가 ${entry}의 배수가 아님 / entry bytes $rest not divisible by $entry"
        var prev = Int.MAX_VALUE
        for (i in 0 until rest / entry) {
            val freq = u16(b, nul + 2 + i * entry + entry - 2)
            if (freq > prev) return "항목 $i 빈도 $freq > 이전 $prev(감소 순서 아님) / entry $i frequency $freq > previous $prev (not in decreasing order)"
            prev = freq
        }
        return null
    }

    private val KEYWORD_CHUNKS = setOf("tEXt", "zTXt", "iTXt", "iCCP", "sPLT")

    /** Keyword rules of 11.3.3.1 (also profile names 11.3.2.3 and palette names 11.3.4.4); null = valid. */
    internal fun keywordProblem(head: ByteArray): String? {
        val nul = head.indexOf(0)
        if (nul < 0) return "79바이트 안에 널 구분자 없음 / no null separator within 80 bytes"
        if (nul == 0) return "빈 키워드 / empty keyword"
        val k = head.copyOf(nul)
        val badChar = k.firstOrNull { val v = it.toInt() and 0xFF; v !in 0x20..0x7E && v !in 0xA1..0xFF }
        if (badChar != null) return "허용되지 않는 문자 0x%02X / character 0x%02X not allowed".format(badChar.toInt() and 0xFF, badChar.toInt() and 0xFF)
        val s = String(k, Charsets.ISO_8859_1)
        if (s.startsWith(" ") || s.endsWith(" ") || s.contains("  ")) return "앞·뒤·연속 공백 / leading, trailing or consecutive spaces: \"$s\""
        return null
    }

    private fun keyword(ctx: PngContext): ConformanceFinding {
        val list = ctx.complete.filter { it.type in KEYWORD_CHUNKS }
        if (list.isEmpty()) return ctx.skip("png3.keyword", NOT_APPLICABLE, "키워드 청크 없음 / no tEXt, zTXt, iTXt, iCCP or sPLT chunk")
        val bad = ArrayList<String>()
        val ranges = ArrayList<LongRange>()
        for (c in list) keywordProblem(ctx.data(c, 80))?.let { bad += "${c.label}: $it"; ranges += ctx.range(c) }
        return ctx.finding("png3.keyword", bad, ranges, listOf("키워드·이름 ${list.size}개 정상 / ${list.size} keywords / names valid"))
    }

    private val UTF8 = Charsets.UTF_8

    private fun validUtf8(b: ByteArray, from: Int, to: Int): Boolean = try {
        UTF8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(b, from, to - from)); true
    } catch (_: CharacterCodingException) { false }

    private fun text(ctx: PngContext): ConformanceFinding {
        val list = ctx.complete.filter { it.type in setOf("tEXt", "zTXt", "iTXt", "iCCP") }
        if (list.isEmpty()) return ctx.skip("png3.text", NOT_APPLICABLE, "tEXt/zTXt/iTXt/iCCP 없음 / no tEXt, zTXt, iTXt or iCCP chunk")
        val bad = ArrayList<String>()
        val ranges = ArrayList<LongRange>()
        var large = 0
        fun fail(c: PngChunk, t: String) { bad += "${c.label}: $t"; ranges += ctx.range(c) }
        for (c in list) {
            if (c.length > PngContext.PAYLOAD_CAP && c.type != "zTXt" && c.type != "iCCP") { large++; continue }
            val b = ctx.data(c, if (c.type == "zTXt" || c.type == "iCCP") 82 else PngContext.PAYLOAD_CAP)
            val nul = b.indexOf(0)
            if (nul <= 0) continue // keyword rule reports it
            when (c.type) {
                "tEXt" -> if (b.indexOf(0, nul + 1) >= 0) fail(c, "텍스트에 널 문자 / text string contains a null character")
                "zTXt", "iCCP" -> when {
                    nul + 1 >= c.length -> fail(c, "압축 방식 바이트 없음 / compression method byte missing")
                    u8(b, nul + 1) != 0 -> fail(c, "압축 방식 ${u8(b, nul + 1)} ≠ 0 / compression method ${u8(b, nul + 1)} ≠ 0")
                }
                "iTXt" -> {
                    if (nul + 2 >= b.size) { fail(c, "압축 플래그·방식 없음 / compression flag and method missing"); continue }
                    val flag = u8(b, nul + 1)
                    val method = u8(b, nul + 2)
                    if (flag > 1) fail(c, "Compression flag $flag (0, 1)")
                    if (method != 0) fail(c, "Compression method $method ≠ 0")
                    val langEnd = b.indexOf(0, nul + 3)
                    val keyEnd = if (langEnd < 0) -1 else b.indexOf(0, langEnd + 1)
                    if (langEnd < 0 || keyEnd < 0) { fail(c, "언어 태그·번역 키워드 널 구분자 없음 / missing null separator after language tag or translated keyword"); continue }
                    if (!validUtf8(b, langEnd + 1, keyEnd)) fail(c, "번역 키워드가 UTF-8이 아님 / translated keyword is not UTF-8")
                    if (flag == 0) {
                        if (b.indexOf(0, keyEnd + 1) >= 0) fail(c, "텍스트에 0 바이트 / text contains a zero byte")
                        if (!validUtf8(b, keyEnd + 1, b.size)) fail(c, "텍스트가 UTF-8이 아님 / text is not UTF-8")
                    }
                }
            }
        }
        if (bad.isEmpty() && large > 0) return ctx.skip("png3.text", NOT_CHECKED, "1 MiB 초과 텍스트 청크 ${large}개 미검사 / $large text chunk(s) over 1 MiB not checked")
        return ctx.finding("png3.text", bad, ranges, listOf("텍스트·프로필 청크 ${list.size}개 필드 정상 / fields of ${list.size} text / profile chunks valid"))
    }

    private fun srgbIccp(ctx: PngContext): ConformanceFinding {
        val srgb = ctx.first("sRGB")
        val iccp = ctx.first("iCCP")
        if (srgb == null || iccp == null) return ctx.finding("png3.srgb_iccp", emptyList(), emptyList(), listOf("sRGB·iCCP 동시 사용 아님 / sRGB and iCCP not both present"))
        return ctx.finding("png3.srgb_iccp", listOf("sRGB(${srgb.label})와 iCCP(${iccp.label})가 함께 있음 / sRGB and iCCP are both present"), listOf(ctx.range(srgb), ctx.range(iccp)), emptyList())
    }

    private fun unusedBits(ctx: PngContext): ConformanceFinding {
        val h = ctx.validHeader ?: return ctx.skip("png3.unused_bits", NOT_CHECKED, "IHDR 무효 / IHDR invalid")
        if (h.colorType == 3 || h.bitDepth == 16) return ctx.skip("png3.unused_bits", NOT_APPLICABLE, "16비트 표본 값이 없거나 비트 깊이 16 / no 16-bit sample fields below bit depth 16")
        val bad = ArrayList<String>()
        val ranges = ArrayList<LongRange>()
        val chunks = listOfNotNull(ctx.first("tRNS")?.takeIf { h.colorType == 0 || h.colorType == 2 }, ctx.first("bKGD"))
        for (c in chunks) {
            if (c.length % 2 != 0L || c.length > 6) continue // length rules report it
            val b = ctx.data(c, 6)
            for (i in 0 until b.size / 2) {
                val v = u16(b, i * 2)
                if (v shr h.bitDepth != 0) { bad += "${c.label}: 표본 $i = $v, 상위 비트가 0이 아님(비트 깊이 ${h.bitDepth}) / sample $i = $v has non-zero bits above bit depth ${h.bitDepth}"; ranges += ctx.range(c) }
            }
        }
        if (chunks.isEmpty()) return ctx.skip("png3.unused_bits", NOT_APPLICABLE, "tRNS/bKGD 없음 / no tRNS or bKGD sample values")
        return ctx.finding("png3.unused_bits", bad, ranges, listOf("미사용 상위 비트 모두 0 / unused high bits are zero"))
    }

    /** A compressed stream: where its first two bytes are (they may span IDAT / fdAT chunk boundaries). */
    internal data class ZStream(val label: String, val head: ByteArray, val at: Long, val range: LongRange)

    internal fun streams(ctx: PngContext): List<ZStream> {
        val out = ArrayList<ZStream>()
        fun concatenated(label: String, parts: List<PngChunk>, skip: Long) {
            val head = java.io.ByteArrayOutputStream()
            for (c in parts) {
                if (head.size() >= 2) break
                val avail = c.length - skip
                if (avail > 0) head.write(ctx.reader.readBytes(c.dataStart + skip, minOf(avail, 2L - head.size()).toInt()))
            }
            val first = parts.first()
            out += ZStream(label, head.toByteArray(), first.dataStart + skip, first.offset until parts.last().end)
        }
        val idats = ctx.of("IDAT")
        if (idats.isNotEmpty()) concatenated("IDAT (${idats.size}개 / ${idats.size} chunks)", idats, 0)
        for (c in ctx.complete) {
            val head = when (c.type) { "iCCP", "zTXt" -> ctx.data(c, 120); "iTXt" -> ctx.data(c); else -> continue }
            val nul = head.indexOf(0)
            if (nul <= 0) continue
            val start = when (c.type) {
                "iTXt" -> {
                    if (nul + 2 >= head.size || u8(head, nul + 1) != 1) continue
                    val lang = head.indexOf(0, nul + 3).takeIf { it >= 0 } ?: continue
                    val key = head.indexOf(0, lang + 1).takeIf { it >= 0 } ?: continue
                    key + 1
                }
                else -> nul + 2
            }
            val bytes = if (start < head.size) head.copyOfRange(start, minOf(head.size, start + 2)) else ctx.reader.readBytes(c.dataStart + start, minOf(2L, maxOf(0L, c.length - start)).toInt())
            out += ZStream(c.label, bytes, c.dataStart + start, ctx.range(c))
        }
        // One stream per APNG frame stored in fdAT (frame_data after the 4-byte sequence number).
        var frame = ArrayList<PngChunk>()
        fun flush() { if (frame.isNotEmpty()) concatenated("fdAT ${hexOf(frame.first())} (${frame.size}개 / ${frame.size} chunks)", frame, 4); frame = ArrayList() }
        for (c in ctx.complete) when (c.type) { "fcTL" -> flush(); "fdAT" -> frame += c }
        flush()
        return out
    }

    private fun hexOf(c: PngChunk) = "@0x%X".format(c.offset)

    private fun zlib(ctx: PngContext): List<ConformanceFinding> {
        val list = streams(ctx)
        if (list.isEmpty()) return listOf(
            ctx.skip("png3.compression", NOT_APPLICABLE, "압축 스트림 없음 / no compressed stream"),
            ctx.skip("zlib.header", NOT_APPLICABLE, "압축 스트림 없음 / no compressed stream"),
        )
        val png = ArrayList<String>(); val pngRanges = ArrayList<LongRange>()
        val rfc = ArrayList<String>(); val rfcRanges = ArrayList<LongRange>()
        for (s in list) {
            if (s.head.size < 2) { rfc += "${s.label}: 2바이트 헤더보다 짧음 / shorter than the 2-byte CMF/FLG header"; rfcRanges += s.range; continue }
            val cmf = u8(s.head, 0)
            val flg = u8(s.head, 1)
            val cm = cmf and 0x0F
            val cinfo = cmf shr 4
            if (cm != 8) { png += "${s.label}: CM $cm ≠ 8 (deflate)"; pngRanges += s.range }
            if (cm == 8 && cinfo > 7) { png += "${s.label}: CINFO $cinfo → 창 > 32768 / window > 32768 bytes"; pngRanges += s.range }
            if (flg and 0x20 != 0) { png += "${s.label}: FDICT = 1 (preset dictionary)"; pngRanges += s.range }
            if ((cmf * 256 + flg) % 31 != 0) { rfc += "${s.label}: CMF*256+FLG = ${cmf * 256 + flg}, 31의 배수 아님 / not a multiple of 31"; rfcRanges += s.range }
            if (cm == 8 && cinfo > 7) { rfc += "${s.label}: CINFO $cinfo > 7"; rfcRanges += s.range }
        }
        val pass = listOf("zlib 스트림 ${list.size}개 / ${list.size} zlib streams: " + list.joinToString { s -> s.label + " " + s.head.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) } })
        return listOf(ctx.finding("png3.compression", png, pngRanges, pass), ctx.finding("zlib.header", rfc, rfcRanges, pass))
    }

    private fun ByteArray.indexOf(v: Int, from: Int = 0): Int {
        for (i in from until size) if (this[i].toInt() == v) return i
        return -1
    }
}
