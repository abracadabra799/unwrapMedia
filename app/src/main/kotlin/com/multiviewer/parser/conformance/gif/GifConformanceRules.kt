package com.multiviewer.parser.conformance.gif

import com.multiviewer.parser.conformance.ConformanceFinding
import com.multiviewer.parser.conformance.ConformanceStatus.NOT_APPLICABLE
import com.multiviewer.parser.conformance.ConformanceStatus.NOT_CHECKED
import com.multiviewer.parser.conformance.ImageRuleBook.Companion.hex

/** GIF89a header, Logical Screen, block grammar, extensions, image descriptors and the LZW minimum code size. */
internal object GifConformanceRules {
    /** Extension labels whose Required Version is 89a (§23-26). */
    val EXT_89A = mapOf(0xF9 to "Graphic Control", 0xFE to "Comment", 0x01 to "Plain Text", 0xFF to "Application")

    /** Rules whose PASS needs every block up to the Trailer. */
    val BLOCK_RULES = setOf("gif89a.version", "gif89a.version.earliest", "gif89a.gce", "gif89a.extension", "gif89a.reserved",
        "gif89a.image.bounds", "gif89a.lct.size", "gif89a.lzw", "gif89a.lzw.clear")

    fun evaluate(ctx: GifContext): List<ConformanceFinding> {
        val all = listOf(
            header(ctx), version(ctx), earliest(ctx), lsd(ctx), background(ctx), blocks(ctx), gce(ctx), extension(ctx),
            reserved(ctx), bounds(ctx), lctSize(ctx), lzw(ctx), lzwClear(ctx), trailer(ctx),
        )
        return if (!incomplete(ctx)) all
        else ctx.book.partial(all, BLOCK_RULES, "Trailer 전에 멈춤 — 읽은 블록만 검사됨 / walk stopped before the Trailer: only the blocks read were checked")
    }

    private fun f(ctx: GifContext, id: String, bad: List<String>, ranges: List<LongRange>, pass: String) = ctx.book.finding(id, bad, ranges, listOf(pass))

    private fun header(ctx: GifContext): ConformanceFinding {
        val bad = ArrayList<String>()
        if (ctx.signature != "GIF") bad += "Signature \"${ctx.signature}\" ≠ \"GIF\""
        if (ctx.version != "87a" && ctx.version != "89a") bad += "Version \"${ctx.version}\" (87a, 89a)"
        return f(ctx, "gif89a.header", bad, if (bad.isEmpty()) emptyList() else listOf(0L until minOf(6L, ctx.length)), "${ctx.signature}${ctx.version}")
    }

    /** 89a features used: the four 89a extensions, and the sort flags that were reserved under 87a. */
    private fun features89a(ctx: GifContext): List<String> {
        val out = ArrayList<String>()
        ctx.extensions.filter { it.label in EXT_89A }.groupBy { it.label }.forEach { (label, list) -> out += "${EXT_89A[label]} Extension ×${list.size} @${hex(list.first().offset)}" }
        if (ctx.hasLsd && ctx.lsdPacked and 0x08 != 0) out += "Logical Screen Descriptor Sort Flag"
        ctx.images.filter { it.sortFlag }.forEach { out += "Image Descriptor Sort Flag @${hex(it.offset)}" }
        return out
    }

    /** No Trailer was reached (syntax error, truncation): blocks after that point are unknown. */
    fun incomplete(ctx: GifContext) = ctx.scan.trailer == null

    private fun version(ctx: GifContext): ConformanceFinding {
        if (ctx.version != "87a" && ctx.version != "89a") return ctx.book.skip("gif89a.version", NOT_CHECKED, "알 수 없는 버전 / unknown version \"${ctx.version}\"")
        val used = features89a(ctx)
        val bad = if (ctx.version == "87a") used.map { "87a 스트림에 89a 블록 / 89a block in an 87a stream: $it" } else emptyList()
        return f(ctx, "gif89a.version", bad, if (bad.isEmpty()) emptyList() else listOf(3L until 6L), "${ctx.version}: 89a 기능 ${used.size}개 / ${used.size} 89a features used")
    }

    private fun earliest(ctx: GifContext): ConformanceFinding {
        if (ctx.version != "89a") return ctx.book.skip("gif89a.version.earliest", NOT_APPLICABLE, "89a 아님 / not 89a")
        val used = features89a(ctx)
        if (used.isEmpty() && incomplete(ctx)) return ctx.book.skip("gif89a.version.earliest", NOT_CHECKED, "Trailer 전에 멈춤 — 이후 블록 미상 / walk stopped before the Trailer: later blocks unknown")
        return f(ctx, "gif89a.version.earliest", if (used.isEmpty()) listOf("89a 블록 없이 89a 표기(87a로 충분) / labelled 89a but uses no 89a block (87a would do)") else emptyList(),
            if (used.isEmpty()) listOf(3L until 6L) else emptyList(), "89a 기능 / 89a features: ${used.joinToString("; ")}")
    }

    private fun lsd(ctx: GifContext): ConformanceFinding {
        if (!ctx.hasLsd) return f(ctx, "gif89a.lsd", listOf("헤더 뒤 7바이트 논리 화면 설명자 없음 / no 7-byte Logical Screen Descriptor after the Header"), listOf(6L until ctx.length), "")
        if (ctx.dataStart > ctx.length) return f(ctx, "gif89a.lsd", listOf("전역 색상표 ${ctx.gctBytes}바이트가 파일 끝을 넘음 / Global Color Table of ${ctx.gctBytes} bytes runs past EOF"), listOf(13L until ctx.length), "")
        return f(ctx, "gif89a.lsd", emptyList(), emptyList(), "화면 / screen ${ctx.screenWidth}x${ctx.screenHeight}, GCT ${if (ctx.hasGct) "${ctx.gctBytes / 3}색 / colours" else "없음 / none"}")
    }

    private fun background(ctx: GifContext): ConformanceFinding {
        if (!ctx.hasLsd) return ctx.book.skip("gif89a.lsd.background", NOT_CHECKED, "LSD 없음 / no Logical Screen Descriptor")
        val bad = if (!ctx.hasGct && ctx.backgroundIndex != 0) listOf("전역 색상표 없음, Background Color Index ${ctx.backgroundIndex} ≠ 0 / no Global Color Table but Background Color Index ${ctx.backgroundIndex}") else emptyList()
        return f(ctx, "gif89a.lsd.background", bad, if (bad.isEmpty()) emptyList() else listOf(11L..11L), "Background Color Index ${ctx.backgroundIndex} (GCT ${if (ctx.hasGct) "있음 / present" else "없음 / absent"})")
    }

    private fun blocks(ctx: GifContext): ConformanceFinding {
        if (!ctx.hasLsd || ctx.dataStart > ctx.length) return ctx.book.skip("gif89a.blocks", NOT_CHECKED, "논리 화면 손상 / Logical Screen unusable")
        val s = ctx.scan
        if (s.capped) return ctx.book.skip("gif89a.blocks", NOT_CHECKED, "블록 상한 ${GifBlockScanner.MAX_BLOCKS}개에서 멈춤 / stopped at the ${GifBlockScanner.MAX_BLOCKS}-block cap")
        val unknown = ctx.extensions.filter { it.label !in EXT_89A }
        val pass = "블록 ${s.blocks.size}개(이미지 ${ctx.images.size}, 확장 ${ctx.extensions.size}) / ${s.blocks.size} blocks (${ctx.images.size} images, ${ctx.extensions.size} extensions)" +
            (if (unknown.isEmpty()) "" else "; 정의되지 않은 확장 레이블(§12 범위, 건너뜀) / undefined extension labels skipped by §12 ranges: " + unknown.joinToString { "0x%02X @%s".format(it.label, hex(it.offset)) })
        return if (s.trailer != null) f(ctx, "gif89a.blocks", emptyList(), emptyList(), pass)
        else if (s.cleanEof) f(ctx, "gif89a.blocks", emptyList(), emptyList(), "$pass — 파일 끝에서 멈춤(gif89a.trailer 참조) / stopped at EOF (see gif89a.trailer)")
        else f(ctx, "gif89a.blocks", listOfNotNull(s.problem), listOf((s.problemAt ?: 0L) until ctx.length), pass)
    }

    /** A Graphic-Rendering block: Image Descriptor or Plain Text Extension. */
    private fun GifBlock.rendering() = this is GifBlock.Image || (this is GifBlock.Extension && label == 0x01)

    private fun gce(ctx: GifContext): ConformanceFinding {
        val gces = ctx.extensions.filter { it.label == 0xF9 }
        if (gces.isEmpty()) return ctx.book.skip("gif89a.gce", NOT_APPLICABLE, "그래픽 제어 확장 없음 / no Graphic Control Extension")
        val bad = ArrayList<String>()
        val ranges = ArrayList<LongRange>()
        for (g in gces) if (g.firstSize != 4 || g.subBlocks != 1) { bad += "GCE @${hex(g.offset)}: Block Size ${g.firstSize}, 서브블록 ${g.subBlocks}개 (4, 1) / data sub-blocks ${g.subBlocks} (fixed 4, one)"; ranges += ctx.range(g) }
        var pending: GifBlock.Extension? = null
        for (b in ctx.scan.blocks) {
            if (b is GifBlock.Extension && b.label == 0xF9) {
                if (pending != null) { bad += "GCE @${hex(b.offset)}: 그래픽 블록 전 두 번째 GCE(앞 GCE @${hex(pending.offset)}) / second GCE before one graphic rendering block"; ranges += ctx.range(b) }
                pending = b
            } else if (b.rendering()) pending = null
        }
        if (pending != null && ctx.scan.trailer != null) { bad += "GCE @${hex(pending.offset)}: 뒤따르는 그래픽 렌더링 블록 없음 / no graphic rendering block follows"; ranges += ctx.range(pending) }
        return f(ctx, "gif89a.gce", bad, ranges, "GCE ${gces.size}개: Block Size 4, 그래픽 블록마다 최대 1개 / ${gces.size} GCE: size 4, at most one per graphic rendering block")
    }

    private fun extension(ctx: GifContext): ConformanceFinding {
        val list = ctx.extensions.filter { it.label == 0xFF || it.label == 0x01 }
        if (list.isEmpty()) return ctx.book.skip("gif89a.extension", NOT_APPLICABLE, "응용·평문 확장 없음 / no Application or Plain Text Extension")
        val bad = ArrayList<String>()
        val ranges = ArrayList<LongRange>()
        for (e in list) {
            val name = if (e.label == 0xFF) "Application" else "Plain Text"
            val size = if (e.label == 0xFF) 11 else 12
            if (e.firstSize != size) { bad += "$name Extension @${hex(e.offset)}: Block Size ${e.firstSize} ≠ $size"; ranges += ctx.range(e); continue }
            if (e.label == 0xFF && e.first.take(8).any { it.toInt() and 0xFF !in 0x20..0x7E }) {
                bad += "Application Extension @${hex(e.offset)}: Application Identifier가 인쇄 가능 ASCII 8자가 아님 / identifier is not eight printable ASCII characters"; ranges += ctx.range(e)
            }
        }
        val ids = ctx.extensions.filter { it.label == 0xFF && it.firstSize == 11 }.map { String(it.first, 0, 11, Charsets.ISO_8859_1) }.distinct()
        return f(ctx, "gif89a.extension", bad, ranges, "응용·평문 확장 ${list.size}개 / ${list.size} Application / Plain Text extensions" + (if (ids.isEmpty()) "" else ": ${ids.joinToString()}"))
    }

    private fun reserved(ctx: GifContext): ConformanceFinding {
        val bad = ArrayList<String>()
        val ranges = ArrayList<LongRange>()
        for (g in ctx.extensions.filter { it.label == 0xF9 && it.firstSize == 4 && it.first.isNotEmpty() }) {
            val p = g.first[0].toInt() and 0xFF
            if (p shr 5 != 0) { bad += "GCE @${hex(g.offset)}: 예약 3비트 ≠ 0 (0x%02X) / reserved 3 bits set".format(p); ranges += ctx.range(g) }
            val disposal = (p shr 2) and 7
            if (disposal > 3) { bad += "GCE @${hex(g.offset)}: Disposal Method $disposal(4-7 \"To be defined\")"; ranges += ctx.range(g) }
        }
        for (i in ctx.images) if (i.reserved != 0) { bad += "Image Descriptor @${hex(i.offset)}: 예약 2비트 ≠ 0 / reserved 2 bits = ${i.reserved}"; ranges += ctx.range(i) }
        return f(ctx, "gif89a.reserved", bad, ranges, "예약 필드 모두 0, Disposal 0-3 / reserved fields zero, disposal methods 0-3")
    }

    private fun bounds(ctx: GifContext): ConformanceFinding {
        if (ctx.images.isEmpty()) return ctx.book.skip("gif89a.image.bounds", NOT_APPLICABLE, "이미지 없음 / no image")
        val bad = ctx.images.filter { it.left + it.width > ctx.screenWidth || it.top + it.height > ctx.screenHeight }
        return f(ctx, "gif89a.image.bounds",
            bad.map { "Image @${hex(it.offset)}: (${it.left},${it.top}) ${it.width}x${it.height}가 논리 화면 ${ctx.screenWidth}x${ctx.screenHeight} 밖 / outside the ${ctx.screenWidth}x${ctx.screenHeight} Logical Screen" },
            bad.map { ctx.range(it) }, "이미지 ${ctx.images.size}개 모두 논리 화면 ${ctx.screenWidth}x${ctx.screenHeight} 안 / all ${ctx.images.size} images inside the Logical Screen")
    }

    private fun lctSize(ctx: GifContext): ConformanceFinding {
        if (ctx.images.isEmpty()) return ctx.book.skip("gif89a.lct.size", NOT_APPLICABLE, "이미지 없음 / no image")
        val bad = ctx.images.filter { !it.hasLct && it.lctSizeField != 0 }
        return f(ctx, "gif89a.lct.size", bad.map { "Image @${hex(it.offset)}: LCT 없음, Size of Local Color Table ${it.lctSizeField} ≠ 0 / no Local Color Table but size field ${it.lctSizeField}" },
            bad.map { ctx.range(it) }, "LCT 없는 이미지의 크기 필드 0 / size field 0 wherever there is no Local Color Table")
    }

    private fun lzw(ctx: GifContext): ConformanceFinding {
        if (ctx.images.isEmpty()) return ctx.book.skip("gif89a.lzw", NOT_APPLICABLE, "이미지 없음 / no image")
        val bad = ArrayList<String>()
        val ranges = ArrayList<LongRange>()
        for (i in ctx.images) {
            if (i.lzwMinCodeSize !in 2..11) { bad += "Image @${hex(i.offset)}: LZW Minimum Code Size ${i.lzwMinCodeSize} (2..11 → 3..12비트 코드 / 3..12-bit codes)"; ranges += ctx.range(i) }
            if (i.dataBytes == 0L) { bad += "Image @${hex(i.offset)}: 이미지 데이터 서브블록 없음 / no image data sub-block"; ranges += ctx.range(i) }
        }
        return f(ctx, "gif89a.lzw", bad, ranges, "LZW 최소 코드 크기 / LZW Minimum Code Size: ${ctx.images.map { it.lzwMinCodeSize }.distinct().joinToString()}")
    }

    private fun lzwClear(ctx: GifContext): ConformanceFinding {
        val list = ctx.images.filter { it.lzwMinCodeSize in 2..11 && it.head.isNotEmpty() }
        if (list.isEmpty()) return ctx.book.skip("gif89a.lzw.clear", if (ctx.images.isEmpty()) NOT_APPLICABLE else NOT_CHECKED, "판독 가능한 LZW 데이터 없음 / no readable LZW data")
        val bad = ArrayList<String>()
        val ranges = ArrayList<LongRange>()
        val unreadable = ArrayList<GifBlock.Image>()
        for (i in list) {
            val bits = i.lzwMinCodeSize + 1
            val raw = (i.head[0].toInt() and 0xFF) or (if (i.head.size > 1) (i.head[1].toInt() and 0xFF) shl 8 else 0)
            if (bits > 8 && i.head.size < 2) { unreadable += i; continue }
            val code = raw and ((1 shl bits) - 1)
            val clear = 1 shl i.lzwMinCodeSize
            if (code != clear) { bad += "Image @${hex(i.offset)}: 첫 코드 $code ≠ Clear $clear / first code $code is not the Clear code $clear"; ranges += ctx.range(i) }
        }
        if (bad.isEmpty() && unreadable.isNotEmpty())
            return ctx.book.skip("gif89a.lzw.clear", NOT_CHECKED, "이미지 ${unreadable.size}개의 첫 코드(${unreadable.first().lzwMinCodeSize + 1}비트)를 읽을 수 없음(데이터 1바이트) / the first code of ${unreadable.size} image(s) cannot be read (one data byte, ${unreadable.first().lzwMinCodeSize + 1}-bit code)")
        return f(ctx, "gif89a.lzw.clear", bad, ranges, "이미지 ${list.size - unreadable.size}개 첫 코드 = Clear / first code is Clear in all ${list.size - unreadable.size} readable images")
    }

    private fun trailer(ctx: GifContext): ConformanceFinding {
        if (ctx.scan.capped) return ctx.book.skip("gif89a.trailer", NOT_CHECKED, "블록 상한에서 멈춤 / stopped at the block cap")
        val t = ctx.scan.trailer
        return if (t != null) f(ctx, "gif89a.trailer", emptyList(), emptyList(), "Trailer 0x3B @${hex(t.offset)}")
        else f(ctx, "gif89a.trailer", listOf("Trailer(0x3B) 없음 / no Trailer (0x3B)" + (ctx.scan.problem?.let { " — $it" } ?: "")), listOf((ctx.scan.problemAt ?: ctx.length) until ctx.length), "")
    }
}
