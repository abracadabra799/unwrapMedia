package com.multiviewer.parser.conformance.webp

import com.multiviewer.parser.conformance.ConformanceFinding
import com.multiviewer.parser.conformance.ConformanceStatus.NOT_APPLICABLE
import com.multiviewer.parser.conformance.ConformanceStatus.NOT_CHECKED
import com.multiviewer.parser.conformance.webp.WebpContext.Companion.BITSTREAMS

/** RFC 9649 §2.7.1 ANIM/ANMF/ALPH, §3.4 VP8L header, RFC 6386 §9.1 VP8 frame tag, canvas vs bitstream size. */
internal object WebpBitstreamRules {
    fun evaluate(ctx: WebpContext): List<ConformanceFinding> =
        listOf(anim(ctx), anmfFlag(ctx), alph(ctx), alphVp8l(ctx), vp8l(ctx), vp8(ctx), canvas(ctx))

    private fun anim(ctx: WebpContext): ConformanceFinding {
        val anim = ctx.of("ANIM")
        val anmf = ctx.of("ANMF")
        if (anim.isEmpty() && anmf.isEmpty()) return ctx.skip("rfc9649.anim", NOT_APPLICABLE, "ANIM·ANMF 없음 / no ANIM or ANMF chunk")
        val bad = ArrayList<String>()
        val ranges = ArrayList<LongRange>()
        fun fail(c: RiffChunk, t: String) { bad += "${c.label}: $t"; ranges += ctx.range(c) }
        anim.forEach { if (it.size != 6L) fail(it, "크기 ${it.size} ≠ 6 / size ${it.size} ≠ 6") }
        anmf.filter { it.size < 16 }.forEach { fail(it, "크기 ${it.size} < 16 / size ${it.size} < 16") }
        val canvas = ctx.vp8x
        for (f in ctx.frames) {
            if (f.flags and 0xFC != 0) fail(f.chunk, "예약 6비트 ≠ 0(0x%02X) / reserved 6 bits set (0x%02X)".format(f.flags, f.flags))
            if (canvas != null && (f.x + f.width > canvas.canvasWidth || f.y + f.height > canvas.canvasHeight))
                fail(f.chunk, "프레임 (${f.x},${f.y}) ${f.width}x${f.height}가 캔버스 ${canvas.canvasWidth}x${canvas.canvasHeight} 밖 / frame outside the canvas")
            val kinds = f.subchunks.filter { it.complete }.map { it.fourcc }
            val bits = kinds.indices.filter { kinds[it] in BITSTREAMS }
            val alphs = kinds.indices.filter { kinds[it] == "ALPH" }
            if (bits.size != 1) fail(f.chunk, "Frame Data의 비트스트림 청크 ${bits.size}개(1개여야 함) / ${bits.size} bitstream subchunks (exactly one required)")
            if (alphs.size > 1) fail(f.chunk, "ALPH ${alphs.size}개 / ${alphs.size} ALPH subchunks")
            if (bits.isNotEmpty() && alphs.any { it > bits.first() }) fail(f.chunk, "비트스트림 뒤의 ALPH / ALPH after the bitstream subchunk")
        }
        return ctx.finding("rfc9649.anim", bad, ranges, listOf("ANIM ${anim.size}, ANMF ${anmf.size}: 필드·영역·Frame Data 정상 / fields, regions and frame data valid"))
    }

    private fun anmfFlag(ctx: WebpContext): ConformanceFinding {
        val anmf = ctx.of("ANMF")
        if (anmf.isEmpty()) return ctx.skip("rfc9649.anmf.flag", NOT_APPLICABLE, "ANMF 없음 / no ANMF chunk")
        val animated = ctx.vp8x?.animation == true
        return ctx.finding("rfc9649.anmf.flag", if (animated) emptyList() else listOf("ANMF ${anmf.size}개, Animation 플래그 없음 / ${anmf.size} ANMF chunk(s) without the Animation flag"),
            if (animated) emptyList() else anmf.map { ctx.range(it) }, listOf("Animation 플래그 설정 / Animation flag set"))
    }

    /** (ALPH chunk, width, height of the frame it belongs to; null when unknown, sibling bitstream fourcc). */
    private fun alphs(ctx: WebpContext): List<Triple<RiffChunk, Pair<Long, Long>?, String?>> {
        val canvas = ctx.vp8x?.let { it.canvasWidth to it.canvasHeight }
        val top = ctx.chunks.filter { it.fourcc == "ALPH" && it.complete }.map { Triple(it, canvas, ctx.chunks.firstOrNull { c -> c.fourcc in BITSTREAMS }?.fourcc) }
        val inFrames = ctx.frames.flatMap { f -> f.subchunks.filter { it.fourcc == "ALPH" && it.complete }.map { Triple(it, f.width to f.height, f.subchunks.firstOrNull { c -> c.fourcc in BITSTREAMS }?.fourcc) } }
        return top + inFrames
    }

    private fun alph(ctx: WebpContext): ConformanceFinding {
        val list = alphs(ctx)
        if (list.isEmpty()) return ctx.skip("rfc9649.alph", NOT_APPLICABLE, "ALPH 없음 / no ALPH chunk")
        val bad = ArrayList<String>()
        val ranges = ArrayList<LongRange>()
        for ((c, size, _) in list) {
            if (c.size < 1) { bad += "${c.label}: 헤더 바이트 없음 / no header byte"; ranges += ctx.range(c); continue }
            val h = ctx.reader.readUInt8(c.payload)
            val rsv = h shr 6
            val compression = h and 0x03
            if (rsv != 0) { bad += "${c.label}: Rsv $rsv ≠ 0"; ranges += ctx.range(c) }
            if (compression > 1) { bad += "${c.label}: Compression method $compression (0, 1)"; ranges += ctx.range(c) }
            if (compression == 0 && size != null && c.size - 1 != size.first * size.second) {
                bad += "${c.label}: 비압축 알파 ${c.size - 1}바이트 ≠ ${size.first}x${size.second} / raw alpha ${c.size - 1} bytes ≠ width x height"; ranges += ctx.range(c)
            }
        }
        return ctx.finding("rfc9649.alph", bad, ranges, listOf("ALPH ${list.size}개 헤더 정상 / ${list.size} ALPH headers valid"))
    }

    private fun alphVp8l(ctx: WebpContext): ConformanceFinding {
        val list = alphs(ctx)
        if (list.isEmpty()) return ctx.skip("rfc9649.alph.vp8l", NOT_APPLICABLE, "ALPH 없음 / no ALPH chunk")
        val bad = list.filter { it.third == "VP8L" }
        return ctx.finding("rfc9649.alph.vp8l", bad.map { "${it.first.label}: VP8L 프레임에 ALPH / ALPH in a frame whose bitstream is VP8L" }, bad.map { ctx.range(it.first) },
            listOf("ALPH는 VP8 프레임에만 / ALPH only in VP8 frames"))
    }

    private fun vp8l(ctx: WebpContext): ConformanceFinding {
        val list = ctx.allChunks.filter { it.fourcc == "VP8L" }
        if (list.isEmpty()) return ctx.skip("rfc9649.vp8l", NOT_APPLICABLE, "VP8L 없음 / no VP8L chunk")
        val bad = ArrayList<String>()
        val ranges = ArrayList<LongRange>()
        for (c in list) {
            if (c.size < 5) { bad += "${c.label}: 크기 ${c.size} < 5 / size ${c.size} < 5"; ranges += ctx.range(c); continue }
            val b = ctx.reader.readBytes(c.payload, 5)
            if (b[0].toInt() and 0xFF != 0x2F) { bad += "${c.label}: 시그니처 0x%02X ≠ 0x2F / signature 0x%02X ≠ 0x2F".format(b[0].toInt() and 0xFF, b[0].toInt() and 0xFF); ranges += ctx.range(c) }
            val version = (b[4].toInt() and 0xFF) shr 5
            if (version != 0) { bad += "${c.label}: version_number $version ≠ 0"; ranges += ctx.range(c) }
        }
        return ctx.finding("rfc9649.vp8l", bad, ranges, listOf("VP8L ${list.size}개: 0x2F, version 0 / ${list.size} VP8L headers: signature 0x2F, version 0"))
    }

    private fun vp8(ctx: WebpContext): ConformanceFinding {
        val list = ctx.allChunks.filter { it.fourcc == "VP8 " }
        if (list.isEmpty()) return ctx.skip("rfc6386.frame", NOT_APPLICABLE, "'VP8 ' 없음 / no 'VP8 ' chunk")
        val bad = ArrayList<String>()
        val ranges = ArrayList<LongRange>()
        for (c in list) {
            if (c.size < 10) { bad += "${c.label}: 크기 ${c.size} < 10(키 프레임 헤더) / size ${c.size} < 10 (key frame header)"; ranges += ctx.range(c); continue }
            val b = ctx.reader.readBytes(c.payload, 10)
            val tag = (b[0].toInt() and 0xFF) or ((b[1].toInt() and 0xFF) shl 8) or ((b[2].toInt() and 0xFF) shl 16)
            val problems = ArrayList<String>()
            if (tag and 1 != 0) problems += "frame type 1(인터 프레임) — 키 프레임이어야 함 / interframe, a key frame is required"
            val version = (tag shr 1) and 7
            if (version > 3) problems += "version $version (0..3)"
            val firstPart = (tag shr 5) and 0x7FFFF
            if (firstPart > c.size - 10) problems += "first_part_size $firstPart > ${c.size - 10}"
            if ((b[3].toInt() and 0xFF) != 0x9D || (b[4].toInt() and 0xFF) != 0x01 || (b[5].toInt() and 0xFF) != 0x2A)
                problems += "start code %02X %02X %02X ≠ 9D 01 2A".format(b[3].toInt() and 0xFF, b[4].toInt() and 0xFF, b[5].toInt() and 0xFF)
            if (problems.isNotEmpty()) { bad += problems.map { "${c.label}: $it" }; ranges += ctx.range(c) }
        }
        return ctx.finding("rfc6386.frame", bad, ranges, listOf("'VP8 ' ${list.size}개: 키 프레임, 9D 01 2A / ${list.size} VP8 key frame headers with start code 9D 01 2A"))
    }

    private fun canvas(ctx: WebpContext): ConformanceFinding {
        val first = ctx.chunks.firstOrNull() ?: return ctx.skip("rfc9649.canvas", NOT_CHECKED, "청크 없음 / no chunk")
        if (first.fourcc in BITSTREAMS) return ctx.finding("rfc9649.canvas", emptyList(), emptyList(),
            listOf("단순 형식: 캔버스 = 비트스트림 크기 / simple format: the bitstream size is the canvas"))
        val x = ctx.vp8x ?: return ctx.skip("rfc9649.canvas", NOT_CHECKED, "VP8X 없음·손상 / no usable VP8X")
        val bad = ArrayList<String>()
        val ranges = ArrayList<LongRange>()
        var unreadable = 0
        fun compare(c: RiffChunk, w: Long, h: Long, ko: String, en: String) {
            val b = ctx.bitstream(c)
            if (b.width == null || b.height == null) { unreadable++; return }
            if (b.width.toLong() != w || b.height.toLong() != h) { bad += "${c.label}: 비트스트림 ${b.width}x${b.height} ≠ $ko ${w}x$h / bitstream ${b.width}x${b.height} ≠ $en ${w}x$h"; ranges += ctx.range(c) }
        }
        if (!x.animation) ctx.chunks.filter { it.fourcc in BITSTREAMS && it.complete }.forEach { compare(it, x.canvasWidth, x.canvasHeight, "VP8X 캔버스", "VP8X canvas") }
        ctx.frames.forEach { f -> f.subchunks.filter { it.fourcc in BITSTREAMS && it.complete }.forEach { compare(it, f.width, f.height, "ANMF 프레임", "ANMF frame") } }
        if (bad.isEmpty() && unreadable > 0) return ctx.skip("rfc9649.canvas", NOT_CHECKED, "비트스트림 헤더 ${unreadable}개 판독 불가 / $unreadable bitstream headers unreadable")
        return ctx.finding("rfc9649.canvas", bad, ranges, listOf("비트스트림 크기 = 캔버스/프레임 크기 / bitstream sizes match the canvas / frame sizes"))
    }
}
