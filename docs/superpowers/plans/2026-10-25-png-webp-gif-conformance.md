# PNG / WebP / GIF Conformance Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Content Check's Container Conformance row checks PNG (incl. APNG), WebP and GIF files rule by rule against
W3C PNG Third Edition (+ RFC 1950 zlib headers), RFC 9649 (+ RFC 6386 §9.1 VP8 frame tag) and GIF89a; every rule
carries a verified citation (HTML anchor, or section number for the plain-text GIF89a).

**Architecture:** Three packages `com.multiviewer.parser.conformance.{png,webp,gif}` (catalog, bounded scanner,
rule groups, checker) on a shared `ImageRuleBook`. `containerConformanceFor` dispatches `ContainerFormat.PNG / WEBP /
GIF`; the existing report/CLI/JSON/prompt plumbing renders the reports unchanged.

**Tech Stack:** Kotlin, JDK 21, kotlin.test; ffmpeg 8.1.2, libwebp 1.6.0 (`cwebp`, `img2webp`, `gif2webp`), macOS
`sips`, python3 for fixtures.

Spec: `docs/superpowers/specs/2026-10-25-png-webp-gif-conformance-design.md` (rule tables, quotes and anchors =
authoritative).

**Pre-validated:** every code block below was compiled and run on this branch before the plan was written; the task
sequence was then replayed from a clean `git archive HEAD` copy, task by task (Task 1 → 9, each task's test command
GREEN, the regenerated fixtures byte-identical, full `./gradlew :app:test` → 2,890 tests green), and removed so the
branch holds documents only. Implement it as written; if a step's RED fails for a different reason than "unresolved
reference / assertion", stop and report.

## Global Constraints

- Work in `.worktrees/png-webp-gif-conformance` on `feat/png-webp-gif-conformance`; run `git branch --show-current`
  before every commit (subagents have committed into the main repo before). Commit trailer
  `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- Status mapping: violated rule → FAIL, except the catalogs' `WARN_RULES` (PNG `png3.srgb_iccp`, `png3.unused_bits`;
  WebP `rfc9649.anmf.flag`, `rfc9649.alph.vp8l`, `rfc9649.metadata.count`, `rfc9649.trailing`; GIF
  `gif89a.version.earliest`, `gif89a.lsd.background`, `gif89a.reserved`, `gif89a.lct.size`, `gif89a.lzw.clear`,
  `gif89a.trailing`) → WARN; satisfied → PASS; not evaluable → NOT_CHECKED with the reason; not applicable →
  NOT_APPLICABLE. Never INFO. A walk that stops before IEND / the RIFF end / the Trailer turns the PASS and
  NOT_APPLICABLE findings of the rules that need the whole walk into NOT_CHECKED (`ImageRuleBook.partial`).
- Never throw out of a checker except `CancellationException`; all reads bounded (PNG ≤ 200,000 chunks and ≤ 1 MiB per
  text / sPLT payload, CRCs streamed in 64 KiB blocks; WebP ≤ 100,000 chunks per list; GIF ≤ 1,000,000 blocks). No
  zlib inflate, LZW decode or VP8/VP8L decode.
- Kotlin string templates: a `$name` directly followed by Hangul is parsed as one identifier — always write `${name}개`.
- Never modify or commit real files from `~/Downloads`; copy to a temp dir for manual checks.
- Titles `"한국어 / English"`, details bilingual; no UI code changes (Material3 tables already render findings).

## File map

| File | Task |
|---|---|
| `parser/conformance/ImageRuleBook.kt`, `parser/conformance/{png/PngRules,webp/WebpRules,gif/GifRules}.kt`, `parser/conformance/jpeg/JpegTrailer.kt` (delegates to the shared SEF test), tests `ImageConformanceTestSupport.kt`, `ImageConformanceRulesTest.kt` | 1 |
| `docs/validation/png-webp-gif-conformance-fixtures/{generate.sh,build.py}`, `app/src/test/resources/png-webp-gif-conformance/*` (63 files) | 2 |
| `parser/conformance/png/{PngChunks,PngStructureRules,PngConformanceChecker}.kt`, tests `png/{SyntheticPng,PngStructureRulesTest}.kt` | 3 |
| `parser/conformance/png/PngAncillaryRules.kt`, test `png/PngAncillaryRulesTest.kt` | 4 |
| `parser/conformance/png/PngApngRules.kt`, tests `png/{PngApngRulesTest,PngFixtureTest}.kt` | 5 |
| `parser/conformance/webp/{WebpChunks,WebpContainerRules,WebpConformanceChecker}.kt`, tests `webp/{SyntheticWebp,WebpContainerRulesTest}.kt` | 6 |
| `parser/conformance/webp/WebpBitstreamRules.kt`, tests `webp/{WebpBitstreamRulesTest,WebpFixtureTest}.kt` | 7 |
| `parser/conformance/gif/{GifBlocks,GifConformanceRules,GifConformanceChecker}.kt`, tests `gif/{SyntheticGif,GifRulesTest,GifFixtureTest}.kt` | 8 |
| `ui/ContentCheckPlan.kt`, tests `cli/CheckFileTest.kt`, `ui/ContentCheckPlanTest.kt`, `docs/validation/spec-citations/README.md`, spec sync | 9 |

(`parser/…` = `app/src/main/kotlin/com/multiviewer/parser/…`; tests under `app/src/test/kotlin/com/multiviewer/…`,
conformance tests under `app/src/test/kotlin/com/multiviewer/parser/conformance/`.)

---

### Task 1: Shared rule book and the three catalogs

**Files:** Create `parser/conformance/ImageRuleBook.kt`, `parser/conformance/png/PngRules.kt`,
`parser/conformance/webp/WebpRules.kt`, `parser/conformance/gif/GifRules.kt`; modify
`parser/conformance/jpeg/JpegTrailer.kt`; tests `ImageConformanceTestSupport.kt`, `ImageConformanceRulesTest.kt`
(package `com.multiviewer.parser.conformance`).

- [ ] **Step 1: Failing tests.** Shared test helpers (used by every later task):

```kotlin
package com.multiviewer.parser.conformance

import java.io.File

/** Shared helpers for the PNG / WebP / GIF conformance tests. */
internal fun ConformanceReport.statusOf(id: String): ConformanceStatus = findings.single { it.rule.id == id }.status
internal fun ConformanceReport.idsWith(vararg s: ConformanceStatus): Set<String> = findings.filter { it.status in s }.map { it.rule.id }.toSet()
internal fun ConformanceReport.notPassing(): List<String> =
    findings.filter { it.status != ConformanceStatus.PASS && it.status != ConformanceStatus.NOT_APPLICABLE }.map { "${it.status} ${it.rule.id}: ${it.details}" }

internal fun conformanceFixture(name: String): File =
    File(ConformanceReport::class.java.getResource("/png-webp-gif-conformance/$name")!!.toURI())
```

Catalog test:

```kotlin
package com.multiviewer.parser.conformance

import com.multiviewer.parser.conformance.gif.GifRules
import com.multiviewer.parser.conformance.png.PngRules
import com.multiviewer.parser.conformance.webp.WebpRules
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ImageConformanceRulesTest {
    private fun common(rules: List<ConformanceRule>) {
        assertEquals(rules.size, rules.map { it.id }.toSet().size)
        rules.forEach { r ->
            val c = r.citation!!
            assertTrue(c.verified, r.id)
            assertEquals(c.url, r.reference, r.id)
            assertTrue(r.title.contains(" / "), r.id)
            // Never collide with the Structure row's observation ids (png.*, webp.*, gif.*).
            assertTrue(!r.id.startsWith("png.") && !r.id.startsWith("webp.") && !r.id.startsWith("gif."), r.id)
        }
    }

    private fun definitional(rules: List<ConformanceRule>) = rules.filter { it.citation!!.section.endsWith(DEFINITIONAL_SUFFIX) }.map { it.id }.toSet()

    @Test fun `PNG catalog cites PNG-3 anchors and RFC 1950 page 5`() {
        val rules = PngRules.all
        common(rules)
        assertEquals(22, rules.size)
        assertEquals(setOf("png3.srgb_iccp", "png3.unused_bits"), PngRules.WARN_RULES)
        rules.filter { it.id != "zlib.header" }.forEach { r ->
            val c = r.citation!!
            assertNull(c.page, r.id)
            assertTrue(c.url.startsWith(PngRules.PNG_URL + "#") && c.url.length > PngRules.PNG_URL.length + 1, r.id)
        }
        val z = PngRules.rule("zlib.header").citation!!
        assertEquals(5, z.page)
        assertEquals("https://www.rfc-editor.org/rfc/rfc1950.html#page-5", z.url)
        assertEquals(setOf("png3.signature", "png3.crc", "png3.multiple"), definitional(rules))
    }

    @Test fun `WebP catalog cites RFC 9649 and RFC 6386 section anchors`() {
        val rules = WebpRules.all
        common(rules)
        assertEquals(16, rules.size)
        assertEquals(setOf("rfc9649.anmf.flag", "rfc9649.alph.vp8l", "rfc9649.metadata.count", "rfc9649.trailing"), WebpRules.WARN_RULES)
        rules.forEach { r ->
            val c = r.citation!!
            assertNull(c.page, r.id)
            assertTrue(c.url.startsWith(WebpRules.WEBP_URL + "#section-") || c.url == WebpRules.VP8_URL + "#section-9.1", r.id)
        }
        assertEquals(setOf("rfc9649.riff.size", "rfc9649.layout", "rfc9649.vp8x.flags", "rfc6386.frame", "rfc9649.canvas"), definitional(rules))
    }

    @Test fun `GIF catalog cites section numbers of the plain-text original`() {
        val rules = GifRules.all
        common(rules)
        assertEquals(15, rules.size)
        assertEquals(6, GifRules.WARN_RULES.size)
        rules.forEach { r ->
            val c = r.citation!!
            assertNull(c.page, r.id)
            assertEquals(GifRules.GIF_URL, c.url, r.id)
            assertTrue(c.document.contains("no anchors"), r.id)
        }
        assertEquals(setOf("gif89a.version", "gif89a.blocks", "gif89a.lzw", "gif89a.trailer", "gif89a.trailing"), definitional(rules))
    }
}
```

- [ ] **Step 2:** `./gradlew :app:test --tests 'com.multiviewer.parser.conformance.ImageConformanceRulesTest'` → RED
  (unresolved `PngRules`, `WebpRules`, `GifRules`).
- [ ] **Step 3: Implement.** Shared rule book; `sefTrailerGaps` is the JPEG trailer's SEF test moved verbatim, and
  `ImageRuleBook.partial` is the "never a partial PASS" guard:

```kotlin
package com.multiviewer.parser.conformance

import com.multiviewer.parser.ByteReader
import com.multiviewer.parser.SefIntegrityAnalyzer
import com.multiviewer.parser.SefIntegritySeverity
import java.util.concurrent.CancellationException

/**
 * Shared catalog + finding builder for the PNG / WebP / GIF conformance checkers (same status mapping as the JPEG
 * checker): a violated rule FAILs unless its id is in [warnRules]; no violation → PASS with the evaluated facts.
 */
internal class ImageRuleBook(val all: List<ConformanceRule>, val warnRules: Set<String>) {
    private val byId = all.associateBy { it.id }
    private val order = all.withIndex().associate { it.value.id to it.index }

    fun rule(id: String): ConformanceRule = byId.getValue(id)

    fun finding(id: String, violations: List<String>, ranges: List<LongRange>, pass: List<String>): ConformanceFinding {
        val status = when {
            violations.isEmpty() -> ConformanceStatus.PASS
            id in warnRules -> ConformanceStatus.WARN
            else -> ConformanceStatus.FAIL
        }
        return ConformanceFinding(rule(id), status, if (violations.isEmpty()) pass else capped(violations), ranges.take(MAX_LISTED))
    }

    fun skip(id: String, status: ConformanceStatus, reason: String): ConformanceFinding =
        ConformanceFinding(rule(id), status, listOf(reason), emptyList())

    /**
     * The walk stopped early (truncation / syntax error): a PASS or NOT_APPLICABLE of a rule in [dependent] only covers
     * the part that was read, so it becomes NOT_CHECKED with [reason] (never a false PASS). FAIL / WARN stay.
     */
    fun partial(findings: List<ConformanceFinding>, dependent: Set<String>, reason: String): List<ConformanceFinding> = findings.map {
        if (it.rule.id in dependent && (it.status == ConformanceStatus.PASS || it.status == ConformanceStatus.NOT_APPLICABLE))
            it.copy(status = ConformanceStatus.NOT_CHECKED, details = listOf(reason) + it.details, ranges = emptyList())
        else it
    }

    /** Findings in catalog order. */
    fun sorted(findings: List<ConformanceFinding>): List<ConformanceFinding> = findings.sortedBy { order[it.rule.id] ?: Int.MAX_VALUE }

    companion object {
        const val MAX_LISTED = 50

        fun capped(lines: List<String>): List<String> =
            if (lines.size <= MAX_LISTED) lines else lines.take(MAX_LISTED) + "… 외 ${lines.size - MAX_LISTED}건 / ${lines.size - MAX_LISTED} more"

        fun hex(offset: Long): String = "0x%X".format(offset)

        /** Rule with a verified citation whose [url] carries an HTML anchor (page null) or no fragment (plain text). */
        fun cited(id: String, ko: String, en: String, scope: String, document: String, section: String, url: String): ConformanceRule =
            citedRule(id, "$ko / $en", scope, document, section, null, url)

        /** "검사 중 오류" text used by every checker for an unexpected exception. */
        fun inspectionError(e: Exception): String = "검사 중 오류 / inspection error: ${e.javaClass.simpleName}: ${e.message ?: ""}".trim()
    }
}

/**
 * Explains bytes after the end of a PNG / WebP / GIF datastream. The only structure recognised there is a Samsung SEF
 * trailer that runs to EOF, ends in "SEFT" and that [SefIntegrityAnalyzer] does not mark CRITICAL (the JPEG checker
 * uses the same test). Returns the finding for [id]: PASS with "nothing after" / "explained", else violations.
 */
internal fun trailingDataFinding(book: ImageRuleBook, id: String, reader: ByteReader, end: Long, note: String): ConformanceFinding {
    val len = reader.length
    if (end >= len) return book.finding(id, emptyList(), emptyList(), listOf("${ImageRuleBook.hex(end)} 뒤 데이터 없음 / nothing after ${ImageRuleBook.hex(end)}"))
    val size = len - end
    val sef = sefTrailerGaps(reader, end)
    val range = "${ImageRuleBook.hex(end)}..${ImageRuleBook.hex(len)} (${size}바이트 / $size bytes)"
    return when {
        sef == "" -> book.finding(id, emptyList(), emptyList(), listOf("$note — Samsung SEF 트레일러로 설명됨 / explained by a Samsung SEF trailer: $range"))
        sef != null -> book.finding(id, listOf("Samsung SEF 트레일러 안의 설명되지 않는 간극 / unexplained gap in the Samsung SEF trailer: $range — $sef", note), listOf(end until len), emptyList())
        else -> book.finding(id, listOf("알 수 없는 데이터 / unexplained data: $range", note), listOf(end until len), emptyList())
    }
}

/**
 * null = [pos]..EOF is not a usable SEF trailer (no SEFT, or CRITICAL); "" = fully explained; otherwise the analyzer's
 * gap/overlap text. Shared with the JPEG trailer check.
 */
internal fun sefTrailerGaps(reader: ByteReader, pos: Long): String? {
    val len = reader.length
    if (len - pos < 12 || reader.readFourCC(len - 4) != "SEFT") return null
    val report = try {
        SefIntegrityAnalyzer.analyze(reader, pos, 0, len - pos, len)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        return null
    }
    if (report.overallSeverity == SefIntegritySeverity.CRITICAL) return null
    return report.structuralChecks
        .filter { (it.label == "Field block gaps" || it.label == "Field block overlap") && it.severity != SefIntegritySeverity.PASS }
        .joinToString("; ") { it.detail }
}
```

`JpegTrailer` delegates to it (no behaviour change):

```diff
diff --git a/app/src/main/kotlin/com/multiviewer/parser/conformance/jpeg/JpegTrailer.kt b/app/src/main/kotlin/com/multiviewer/parser/conformance/jpeg/JpegTrailer.kt
index 553a98bd..a622c099 100644
--- a/app/src/main/kotlin/com/multiviewer/parser/conformance/jpeg/JpegTrailer.kt
+++ b/app/src/main/kotlin/com/multiviewer/parser/conformance/jpeg/JpegTrailer.kt
@@ -1,9 +1,8 @@
 package com.multiviewer.parser.conformance.jpeg
 
-import com.multiviewer.parser.SefIntegrityAnalyzer
-import com.multiviewer.parser.SefIntegritySeverity
 import com.multiviewer.parser.conformance.ConformanceFinding
 import com.multiviewer.parser.conformance.ConformanceStatus.NOT_CHECKED
+import com.multiviewer.parser.conformance.sefTrailerGaps
 import com.multiviewer.parser.conformance.jpeg.JpegContext.Companion.hex
 import com.multiviewer.parser.motionphoto.walkBoxes
 
@@ -55,18 +54,8 @@ internal object JpegTrailer {
         return boxes.last().end.takeIf { it > pos }
     }
 
-    /**
-     * null = [pos]..EOF is not a usable SEF trailer (no SEFT, or CRITICAL); "" = explained; otherwise the analyzer's
-     * gap/overlap text (some bytes are not accounted for).
-     */
-    private fun sefAssess(ctx: JpegContext, pos: Long): String? {
-        val len = ctx.length
-        if (len - pos < 12 || ctx.reader.readFourCC(len - 4) != "SEFT") return null
-        val report = try { SefIntegrityAnalyzer.analyze(ctx.reader, pos, 0, len - pos, len) } catch (e: java.util.concurrent.CancellationException) { throw e } catch (_: Exception) { return null }
-        if (report.overallSeverity == SefIntegritySeverity.CRITICAL) return null
-        return report.structuralChecks.filter { (it.label == "Field block gaps" || it.label == "Field block overlap") && it.severity != SefIntegritySeverity.PASS }
-            .joinToString("; ") { it.detail }
-    }
+    /** See [sefTrailerGaps] (shared with the PNG / WebP / GIF trailing-data rules). */
+    private fun sefAssess(ctx: JpegContext, pos: Long): String? = sefTrailerGaps(ctx.reader, pos)
 
     /** The next MPF image start or ISO-BMFF `ftyp` box after [pos] (searched within 4 MiB), or null. */
     private fun nextKnownStart(ctx: JpegContext, pos: Long, mpfImages: List<LongRange>): Long? {
```

Catalogs (sections / anchors are the spec's; do not change one without re-reading the original):

```kotlin
package com.multiviewer.parser.conformance.png

import com.multiviewer.parser.conformance.ConformanceRule
import com.multiviewer.parser.conformance.DEFINITIONAL_SUFFIX
import com.multiviewer.parser.conformance.ImageRuleBook
import com.multiviewer.parser.conformance.citedRule

/**
 * PNG / APNG / zlib rule catalog. Every citation was checked against the original HTML (W3C PNG-3 REC of 2025-06-24,
 * RFC 1950): the URL carries the section anchor and page is null (zlib.header: RFC page 5, `#page-5`), and the quoted sentence is listed in
 * docs/superpowers/specs/2026-10-25-png-webp-gif-conformance-design.md.
 */
object PngRules {
    const val PNG_DOCUMENT = "W3C PNG Third Edition (REC 2025-06-24)"
    const val PNG_URL = "https://www.w3.org/TR/2025/REC-png-3-20250624/"
    const val ZLIB_DOCUMENT = "RFC 1950 (ZLIB 3.3)"
    const val ZLIB_URL = "https://www.rfc-editor.org/rfc/rfc1950.html"

    const val SCOPE_STRUCTURE = "PNG 데이터스트림 구조 / PNG datastream structure (PNG-3)"
    const val SCOPE_ANCILLARY = "PNG 보조 청크 / PNG ancillary chunks (PNG-3)"
    const val SCOPE_APNG = "APNG 애니메이션 / APNG animation (PNG-3)"
    const val SCOPE_ZLIB = "zlib 스트림 헤더 / zlib stream headers (PNG-3 §10, RFC 1950)"

    private fun png(id: String, ko: String, en: String, scope: String, section: String, anchor: String) =
        ImageRuleBook.cited(id, ko, en, scope, PNG_DOCUMENT, section, "$PNG_URL#$anchor")

    val all: List<ConformanceRule> = listOf(
        png("png3.signature", "PNG 시그니처", "PNG signature", SCOPE_STRUCTURE, "5.2 PNG signature$DEFINITIONAL_SUFFIX", "5PNG-file-signature"),
        png("png3.chunk.layout", "청크 길이·경계", "Chunk length and extent", SCOPE_STRUCTURE, "5.3 Chunk layout, Table 5 (Length)", "5Chunk-layout"),
        png("png3.chunk.type", "청크 유형 바이트", "Chunk type bytes", SCOPE_STRUCTURE, "5.3 Chunk layout, Table 5 (Chunk Type) / 5.4 Chunk naming conventions", "5Chunk-naming-conventions"),
        png("png3.crc", "청크 CRC", "Chunk CRC", SCOPE_STRUCTURE, "5.3 Chunk layout, Table 5 (CRC) / 5.5 CRC algorithm$DEFINITIONAL_SUFFIX", "5CRC-algorithm"),
        png("png3.ihdr", "IHDR 필드", "IHDR fields", SCOPE_STRUCTURE, "11.2.1 IHDR Image header, Table 12", "11IHDR"),
        png("png3.critical", "필수 청크 구성", "Critical chunk sequence", SCOPE_STRUCTURE, "11.2 Critical chunks / 11.2.3 IDAT", "11Critical-chunks"),
        png("png3.plte", "PLTE 팔레트", "PLTE palette", SCOPE_STRUCTURE, "11.2.2 PLTE Palette", "11PLTE"),
        png("png3.order", "보조 청크 순서", "Ancillary chunk ordering", SCOPE_STRUCTURE, "5.6 Chunk ordering, Table 7", "5ChunkOrdering"),
        png("png3.multiple", "청크 중복", "Chunks that must not repeat", SCOPE_STRUCTURE, "5.6 Chunk ordering, Table 7 (Multiple allowed)$DEFINITIONAL_SUFFIX", "5ChunkOrdering"),
        png("png3.trns", "tRNS 투명도", "tRNS transparency", SCOPE_ANCILLARY, "11.3.1.1 tRNS Transparency", "11tRNS"),
        png("png3.ancillary", "보조 청크 필드", "Ancillary chunk fields", SCOPE_ANCILLARY, "11.3 Ancillary chunks (11.3.2.1-11.3.5.1)", "11Ancillary-chunks"),
        png("png3.keyword", "키워드·이름", "Keywords and names", SCOPE_ANCILLARY, "11.3.3.1 Keywords and text strings", "11keywords"),
        png("png3.text", "텍스트·압축 필드", "Text and compression fields", SCOPE_ANCILLARY, "11.3.3.2-11.3.3.4 tEXt, zTXt, iTXt / 11.3.2.3 iCCP", "11textinfo"),
        png("png3.srgb_iccp", "sRGB·iCCP 동시 사용", "sRGB together with iCCP", SCOPE_ANCILLARY, "5.6 Chunk ordering, Table 7 (iCCP, sRGB)", "5ChunkOrdering"),
        png("png3.unused_bits", "16비트 표본의 미사용 상위 비트", "Unused high bits of 16-bit samples", SCOPE_ANCILLARY, "11.3.1.1 tRNS / 11.3.4.1 bKGD", "11tRNS"),
        png("png3.compression", "PNG 압축 방식 0", "PNG compression method 0", SCOPE_ZLIB, "10.1 Compression method 0", "10CompressionCM0"),
        // rfc1950.html has no #section-2.2 anchor (only top-level sections and pages); both quoted sentences are on page 5.
        citedRule("zlib.header", "zlib CMF·FLG / zlib CMF and FLG", SCOPE_ZLIB, ZLIB_DOCUMENT, "2.2 Data format", 5, "$ZLIB_URL#page-5"),
        png("png3.actl", "acTL 애니메이션 제어", "acTL animation control", SCOPE_APNG, "11.3.6.1 acTL Animation Control Chunk", "acTL-chunk"),
        png("png3.sequence", "APNG 시퀀스 번호", "APNG sequence numbers", SCOPE_APNG, "4.9.2 Sequence numbers", "4Concepts.APNGSequence"),
        png("png3.fctl", "fcTL 프레임 제어", "fcTL frame control", SCOPE_APNG, "11.3.6.2 fcTL Frame Control Chunk", "fcTL-chunk"),
        png("png3.fdat", "fdAT 프레임 데이터", "fdAT frame data", SCOPE_APNG, "11.3.6.3 fdAT Frame Data Chunk", "fdAT-chunk"),
        png("png3.trailing", "IEND 이후 데이터", "Data after IEND", SCOPE_STRUCTURE, "15.3.1 Conformance of PNG datastreams", "15FileConformance"),
    )

    /** Violations of these rules are WARN (should / not recommended / data after IEND, user decision); all others FAIL. */
    val WARN_RULES = setOf("png3.srgb_iccp", "png3.unused_bits")

    internal val book = ImageRuleBook(all, WARN_RULES)

    fun rule(id: String): ConformanceRule = book.rule(id)
}
```

```kotlin
package com.multiviewer.parser.conformance.webp

import com.multiviewer.parser.conformance.ConformanceRule
import com.multiviewer.parser.conformance.DEFINITIONAL_SUFFIX
import com.multiviewer.parser.conformance.ImageRuleBook

/**
 * WebP rule catalog (RFC 9649, VP8 frame tag from RFC 6386). Citations were checked against the rfc-editor HTML: the
 * URL carries the `#section-N` anchor, page is null; quotes are in
 * docs/superpowers/specs/2026-10-25-png-webp-gif-conformance-design.md.
 */
object WebpRules {
    const val WEBP_DOCUMENT = "RFC 9649 (WebP, 2024-11)"
    const val WEBP_URL = "https://www.rfc-editor.org/rfc/rfc9649.html"
    const val VP8_DOCUMENT = "RFC 6386 (VP8, 2011-11)"
    const val VP8_URL = "https://www.rfc-editor.org/rfc/rfc6386.html"

    const val SCOPE_CONTAINER = "WebP RIFF 컨테이너 / WebP RIFF container (RFC 9649 §2)"
    const val SCOPE_BITSTREAM = "WebP 비트스트림 헤더 / WebP bitstream headers (RFC 9649 §2.7.1, §3.4; RFC 6386 §9.1)"

    private fun webp(id: String, ko: String, en: String, scope: String, section: String, anchor: String) =
        ImageRuleBook.cited(id, ko, en, scope, WEBP_DOCUMENT, section, "$WEBP_URL#$anchor")

    val all: List<ConformanceRule> = listOf(
        webp("rfc9649.header", "RIFF/WEBP 헤더", "RIFF/WEBP header", SCOPE_CONTAINER, "2.4 WebP File Header", "section-2.4"),
        webp("rfc9649.riff.size", "RIFF File Size", "RIFF File Size", SCOPE_CONTAINER, "2.4 WebP File Header (File Size)$DEFINITIONAL_SUFFIX", "section-2.4"),
        webp("rfc9649.chunk.layout", "청크 경계·패딩", "Chunk extent and padding", SCOPE_CONTAINER, "2.3 RIFF File Format", "section-2.3"),
        webp("rfc9649.layout", "파일 구성", "File layout", SCOPE_CONTAINER, "2.5-2.7 Simple / Extended File Format$DEFINITIONAL_SUFFIX", "section-2.7"),
        webp("rfc9649.order", "청크 순서", "Chunk order", SCOPE_CONTAINER, "2.7 Extended File Format", "section-2.7"),
        webp("rfc9649.vp8x", "VP8X 필드", "VP8X fields", SCOPE_CONTAINER, "2.7 Extended File Format (Figure 7)", "section-2.7"),
        webp("rfc9649.vp8x.flags", "VP8X 플래그와 청크", "VP8X flags vs chunks", SCOPE_CONTAINER, "2.7 Extended File Format (Figure 7) / 2.7.1.1 ANIM$DEFINITIONAL_SUFFIX", "section-2.7"),
        webp("rfc9649.anim", "ANIM·ANMF 필드", "ANIM and ANMF fields", SCOPE_BITSTREAM, "2.7.1.1 Animation / 2.7.2 Canvas Assembly from Frames", "section-2.7.1.1"),
        webp("rfc9649.anmf.flag", "애니메이션 플래그 없는 ANMF", "ANMF without the Animation flag", SCOPE_BITSTREAM, "2.7.1.1 Animation (ANMF)", "section-2.7.1.1"),
        webp("rfc9649.alph", "ALPH 헤더", "ALPH header", SCOPE_BITSTREAM, "2.7.1.2 Alpha", "section-2.7.1.2"),
        webp("rfc9649.alph.vp8l", "VP8L 프레임의 ALPH", "ALPH in a VP8L frame", SCOPE_BITSTREAM, "2.7.1.2 Alpha", "section-2.7.1.2"),
        webp("rfc9649.metadata.count", "ICCP·EXIF·XMP 개수", "ICCP, EXIF and XMP count", SCOPE_CONTAINER, "2.7.1.4 Color Profile / 2.7.1.5 Metadata", "section-2.7.1.4"),
        webp("rfc9649.vp8l", "VP8L 헤더", "VP8L header", SCOPE_BITSTREAM, "3.4 RIFF Header (VP8L signature, version)", "section-3.4"),
        ImageRuleBook.cited("rfc6386.frame", "VP8 키 프레임 헤더", "VP8 key frame header", SCOPE_BITSTREAM, VP8_DOCUMENT,
            "9.1 Uncompressed Data Chunk (+ RFC 9649 §2.1 \"VP8 key frame\")$DEFINITIONAL_SUFFIX", "$VP8_URL#section-9.1"),
        webp("rfc9649.canvas", "캔버스·프레임 크기와 비트스트림 크기", "Canvas / frame size vs bitstream size", SCOPE_BITSTREAM,
            "2.5-2.6 (VP8/VP8L size = canvas) / 2.7.1.1 (ANMF frame size)$DEFINITIONAL_SUFFIX", "section-2.5"),
        webp("rfc9649.trailing", "RIFF 이후 데이터", "Data after the RIFF File Size", SCOPE_CONTAINER, "2.4 WebP File Header", "section-2.4"),
    )

    /** Violations of these rules are WARN (SHOULD / SHOULD NOT); all others FAIL. */
    val WARN_RULES = setOf("rfc9649.anmf.flag", "rfc9649.alph.vp8l", "rfc9649.metadata.count", "rfc9649.trailing")

    internal val book = ImageRuleBook(all, WARN_RULES)

    fun rule(id: String): ConformanceRule = book.rule(id)
}
```

```kotlin
package com.multiviewer.parser.conformance.gif

import com.multiviewer.parser.conformance.ConformanceRule
import com.multiviewer.parser.conformance.DEFINITIONAL_SUFFIX
import com.multiviewer.parser.conformance.ImageRuleBook

/**
 * GIF89a rule catalog. The source is the W3C-hosted plain-text copy of the CompuServe specification (31 July 1990):
 * it has no anchors, so every citation URL is the document itself and the section number is the locator; page is
 * null. Quotes are in docs/superpowers/specs/2026-10-25-png-webp-gif-conformance-design.md.
 */
object GifRules {
    const val GIF_DOCUMENT = "GIF89a Specification (CompuServe 1990-07-31, 평문·앵커 없음 / plain text, no anchors)"
    const val GIF_URL = "https://www.w3.org/Graphics/GIF/spec-gif89a.txt"
    const val SCOPE = "GIF 블록 구조 / GIF block structure (GIF89a)"

    private fun gif(id: String, ko: String, en: String, section: String) =
        ImageRuleBook.cited(id, ko, en, SCOPE, GIF_DOCUMENT, section, GIF_URL)

    val all: List<ConformanceRule> = listOf(
        gif("gif89a.header", "헤더", "Header", "17. Header"),
        gif("gif89a.version", "버전과 사용 블록", "Version vs blocks used", "17. Header / Required Version of 23-26$DEFINITIONAL_SUFFIX"),
        gif("gif89a.version.earliest", "가능한 가장 이른 버전", "Earliest possible version", "17. Header, d. Recommendations ii"),
        gif("gif89a.lsd", "논리 화면 설명자", "Logical Screen Descriptor", "18. Logical Screen Descriptor"),
        gif("gif89a.lsd.background", "배경색 인덱스", "Background Color Index", "18. Logical Screen Descriptor vii"),
        gif("gif89a.blocks", "블록 문법", "Block grammar", "Appendix B. GIF Grammar / 15. Data Sub-blocks$DEFINITIONAL_SUFFIX"),
        gif("gif89a.gce", "그래픽 제어 확장", "Graphic Control Extension", "23. Graphic Control Extension"),
        gif("gif89a.extension", "응용·평문 확장 크기", "Application / Plain Text Extension size", "25. Plain Text Extension / 26. Application Extension"),
        gif("gif89a.reserved", "예약 필드·값", "Reserved fields and values", "Appendix D. Conventions (Reserved Fields) / 23. iv Disposal Method"),
        gif("gif89a.image.bounds", "이미지와 논리 화면 경계", "Image within the Logical Screen", "20. Image Descriptor"),
        gif("gif89a.lct.size", "로컬 색상표 크기 필드", "Size of Local Color Table field", "20. Image Descriptor ix"),
        gif("gif89a.lzw", "LZW 최소 코드 크기", "LZW Minimum Code Size", "22. Table Based Image Data / Appendix F$DEFINITIONAL_SUFFIX"),
        gif("gif89a.lzw.clear", "첫 코드 = Clear", "First code is Clear", "Appendix F. Variable-Length-Code LZW Compression"),
        gif("gif89a.trailer", "트레일러", "Trailer", "27. Trailer / Appendix B. GIF Grammar$DEFINITIONAL_SUFFIX"),
        gif("gif89a.trailing", "트레일러 이후 데이터", "Data after the Trailer", "Appendix C. Glossary (Data Stream)$DEFINITIONAL_SUFFIX"),
    )

    /** Violations of these rules are WARN (should / expected / data after the Trailer); all others FAIL. */
    val WARN_RULES = setOf(
        "gif89a.version.earliest", "gif89a.lsd.background", "gif89a.reserved", "gif89a.lct.size", "gif89a.lzw.clear", "gif89a.trailing",
    )

    internal val book = ImageRuleBook(all, WARN_RULES)

    fun rule(id: String): ConformanceRule = book.rule(id)
}
```

- [ ] **Step 4:** `./gradlew :app:test --tests 'com.multiviewer.parser.conformance.ImageConformanceRulesTest' --tests 'com.multiviewer.parser.conformance.jpeg.*'`
  → GREEN (the JPEG tests prove the `JpegTrailer` refactor). **Step 5:** commit
  `feat: add PNG/WebP/GIF conformance rule catalogs`.

---

### Task 2: Real-file fixtures (before any rule code — AGENTS.md rules 1-2)

**Files:** Create `docs/validation/png-webp-gif-conformance-fixtures/generate.sh` (executable), `.../build.py`; run it to
create `app/src/test/resources/png-webp-gif-conformance/` (63 files, commit them all).

- [ ] **Step 1:** write `generate.sh`:

```bash
#!/usr/bin/env bash
# Regenerates the PNG / WebP / GIF conformance fixtures used by Png/Webp/GifFixtureTest. Default encoder settings
# (AGENTS.md rule 2); -fflags +bitexact keeps the ffmpeg output byte-stable. ffmpeg 8.1.2 has no WebP encoder here, so
# the default-settings WebP fixture is libwebp's cwebp. macOS sips writes PNG and GIF (not WebP).
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
out="${1:-$here/../../../app/src/test/resources/png-webp-gif-conformance}"
mkdir -p "$out"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
ff() { ffmpeg -hide_banner -loglevel error -y "$@"; }

ff -f lavfi -i testsrc2=s=64x48 -frames:v 1 -fflags +bitexact "$out/ffmpeg-default.png"
ff -f lavfi -i testsrc2=s=64x48 -frames:v 1 -fflags +bitexact -pix_fmt pal8 "$out/ffmpeg-pal8.png"
ff -f lavfi -i testsrc2=s=64x48 -frames:v 1 -fflags +bitexact -pix_fmt gray16be "$out/ffmpeg-gray16.png"
ff -f lavfi -i testsrc2=s=64x48 -frames:v 1 -fflags +bitexact -pix_fmt rgba "$out/ffmpeg-rgba.png"
ff -f lavfi -i testsrc2=s=64x48:r=10 -frames:v 3 -fflags +bitexact -f apng "$out/ffmpeg.apng"
ff -f lavfi -i testsrc2=s=64x48 -frames:v 1 -fflags +bitexact "$out/ffmpeg-default.gif"
ff -f lavfi -i testsrc2=s=64x48:r=10 -frames:v 3 -fflags +bitexact "$out/ffmpeg-anim.gif"
ff -f lavfi -i testsrc2=s=64x48 -frames:v 1 -pix_fmt rgba "$tmp/rgba.png"
ff -f lavfi -i "testsrc2=s=64x48,format=rgba,geq=r='r(X,Y)':g='g(X,Y)':b='b(X,Y)':a='if(lt(X,32),255,128)'" -frames:v 1 "$tmp/alpha.png"
cwebp -quiet "$tmp/rgba.png" -o "$out/cwebp-default.webp"
cwebp -quiet -lossless "$tmp/rgba.png" -o "$out/cwebp-lossless.webp"
cwebp -quiet "$tmp/alpha.png" -o "$out/cwebp-alpha.webp"
img2webp -loop 0 -d 100 "$out/ffmpeg-default.png" -d 100 "$tmp/alpha.png" -o "$out/img2webp-anim.webp" >/dev/null 2>&1
gif2webp -quiet "$out/ffmpeg-anim.gif" -o "$out/gif2webp-anim.webp"
if command -v sips >/dev/null 2>&1; then
  sips -s format png "$tmp/rgba.png" --out "$out/sips.png" >/dev/null
  sips -s format gif "$tmp/rgba.png" --out "$out/sips.gif" >/dev/null
else
  echo "sips not found (macOS only): keeping the committed sips.png / sips.gif" >&2
fi
python3 -I "$here/build.py" "$out"
ls -l "$out"
```

- [ ] **Step 2:** write `build.py` (byte patches; offsets found by walking the structure, PNG CRCs recomputed):

```python
"""Builds the byte-patched PNG / WebP / GIF variants for Png/Webp/GifFixtureTest.

Usage: python3 -I build.py <out dir>   (needs the tool outputs written by generate.sh in <out dir>)

Every variant starts from a real tool output and changes the fewest bytes needed to break exactly one rule (PNG chunk
CRCs are recomputed so only the targeted rule fails). Offsets are found by walking the structure, never hard-coded.
"""
import struct
import sys
import zlib
from pathlib import Path

out = Path(sys.argv[1])


def read(name):
    return (out / name).read_bytes()


def write(name, data):
    (out / name).write_bytes(bytes(data))


# ---- PNG ---------------------------------------------------------------------------------------------------------
def png_chunks(b):
    """[(type, data)] of a PNG after the 8-byte signature, up to and including IEND."""
    pos, out_ = 8, []
    while pos < len(b):
        n = struct.unpack('>I', b[pos:pos + 4])[0]
        t = b[pos + 4:pos + 8].decode('latin1')
        out_.append((t, b[pos + 8:pos + 8 + n]))
        pos += 12 + n
        if t == 'IEND':
            break
    return out_


def png_chunk(t, data):
    tb = t.encode('latin1')
    return struct.pack('>I', len(data)) + tb + data + struct.pack('>I', zlib.crc32(tb + data))


def png_build(chunks):
    return b'\x89PNG\r\n\x1a\n' + b''.join(png_chunk(t, d) for t, d in chunks)


def png_variant(src, name, edit):
    chunks = png_chunks(read(src))
    write(name, png_build(edit(chunks)))


def insert_before(chunks, t, new):
    i = next(i for i, c in enumerate(chunks) if c[0] == t)
    return chunks[:i] + new + chunks[i:]


def replace_data(chunks, t, fn, nth=0):
    out_, seen = [], 0
    for ct, d in chunks:
        if ct == t:
            if seen == nth:
                d = fn(bytearray(d))
            seen += 1
        out_.append((ct, bytes(d)))
    return out_


def set_bytes(d, off, data):
    d[off:off + len(data)] = data
    return d


def fdict_flg():
    """FLG with FDICT=1, FLEVEL=2 and a valid FCHECK for CMF 0x78."""
    base = 0x80 | 0x20
    return base + (31 - (0x78 * 256 + base) % 31) % 31


ff = read('ffmpeg-default.png')
b = bytearray(ff)
idat = ff.index(b'IDAT') - 4
b[idat + 8 + 100] ^= 0xFF  # one IDAT data byte, CRC left stale
write('png-crc.png', b)
write('png-truncated.png', ff[:-6])
write('png-trailing.png', ff + bytes(16))
png_variant('ffmpeg-default.png', 'png-ihdr-depth.png', lambda c: replace_data(c, 'IHDR', lambda d: set_bytes(d, 8, b'\x04')))
png_variant('ffmpeg-gray16.png', 'png-plte-gray.png', lambda c: insert_before(c, 'IDAT', [('PLTE', bytes(9))]))
png_variant('ffmpeg-pal8.png', 'png-pal-noplte.png', lambda c: [x for x in c if x[0] != 'PLTE'])
png_variant('ffmpeg-default.png', 'png-order.png', lambda c: [x for x in c if x[0] != 'pHYs'][:-1] + [x for x in c if x[0] == 'pHYs'] + [c[-1]])
png_variant('ffmpeg-default.png', 'png-gama-dup.png', lambda c: insert_before(c, 'IDAT', [('gAMA', struct.pack('>I', 45455))] * 2))
png_variant('ffmpeg-rgba.png', 'png-trns-rgba.png', lambda c: insert_before(c, 'IDAT', [('tRNS', bytes(6))]))
png_variant('ffmpeg-default.png', 'png-srgb-iccp.png',
            lambda c: insert_before(c, 'IDAT', [('sRGB', b'\x00'), ('iCCP', b'ICC Profile\x00\x00' + zlib.compress(b'not a real profile'))]))
png_variant('ffmpeg-default.png', 'png-keyword.png', lambda c: insert_before(c, 'IDAT', [('tEXt', b' Title\x00unwrapMedia')]))
png_variant('ffmpeg-default.png', 'png-itxt.png', lambda c: insert_before(c, 'IDAT', [('iTXt', b'Title\x00\x02\x00\x00\x00unwrapMedia')]))
png_variant('ffmpeg-default.png', 'png-text-ok.png', lambda c: insert_before(c, 'IDAT', [
    ('tEXt', b'Title\x00unwrapMedia'), ('zTXt', b'Comment\x00\x00' + zlib.compress(b'zTXt text')),
    ('iTXt', 'Description\x00\x00\x00ko\x00설명\x00유니코드 텍스트'.encode('utf-8')),
    ('iTXt', b'Software\x00\x01\x00\x00\x00' + zlib.compress(b'compressed iTXt'))]))
png_variant('ffmpeg-default.png', 'png-zlib-fcheck.png', lambda c: replace_data(c, 'IDAT', lambda d: set_bytes(d, 1, bytes([d[1] ^ 0x01]))))
png_variant('ffmpeg-default.png', 'png-fdict.png', lambda c: replace_data(c, 'IDAT', lambda d: set_bytes(d, 1, bytes([fdict_flg()]))))
png_variant('ffmpeg-default.png', 'png-bkgd-bits.png', lambda c: insert_before(c, 'IDAT', [('bKGD', struct.pack('>HHH', 0x0100, 0, 0))]))
png_variant('ffmpeg.apng', 'apng-seq.apng', lambda c: replace_data(c, 'fcTL', lambda d: set_bytes(d, 0, struct.pack('>I', 5)), nth=1))
png_variant('ffmpeg.apng', 'apng-actl.apng', lambda c: replace_data(c, 'acTL', lambda d: set_bytes(d, 0, struct.pack('>I', 2))))
png_variant('ffmpeg.apng', 'apng-region.apng', lambda c: replace_data(c, 'fcTL', lambda d: set_bytes(d, 12, struct.pack('>I', 60)), nth=1))
png_variant('ffmpeg.apng', 'apng-nofdat.apng', lambda c: [x for i, x in enumerate(c) if not (x[0] == 'fdAT' and i == max(j for j, y in enumerate(c) if y[0] == 'fdAT'))])


# ---- WebP --------------------------------------------------------------------------------------------------------
def riff_chunks(b, start, end):
    pos, out_ = start, []
    while pos + 8 <= end:
        t = b[pos:pos + 4]
        n = struct.unpack('<I', b[pos + 4:pos + 8])[0]
        out_.append((t, b[pos + 8:pos + 8 + n]))
        pos += 8 + n + (n & 1)
    return out_


def riff_chunk(t, data):
    return t + struct.pack('<I', len(data)) + data + (b'\x00' if len(data) & 1 else b'')


def webp_build(chunks):
    body = b'WEBP' + b''.join(riff_chunk(t, d) for t, d in chunks)
    return b'RIFF' + struct.pack('<I', len(body)) + body


def webp(name):
    b = read(name)
    return riff_chunks(b, 12, 8 + struct.unpack('<I', b[4:8])[0])


def webp_variant(src, name, edit):
    write(name, webp_build(edit(webp(src))))


def patch_chunk(chunks, t, fn, nth=0):
    return replace_data([(x.decode('latin1'), d) for x, d in chunks], t, fn, nth)


def enc(chunks):
    return [(t.encode('latin1') if isinstance(t, str) else t, d) for t, d in chunks]


dflt = read('cwebp-default.webp')
b = bytearray(dflt)
b[4:8] = struct.pack('<I', struct.unpack('<I', dflt[4:8])[0] + 2)
write('webp-riff-size.webp', b)
write('webp-trailing.webp', dflt + bytes(4))
alpha = read('cwebp-alpha.webp')
alph = alpha.index(b'ALPH')
alph_size = struct.unpack('<I', alpha[alph + 4:alph + 8])[0]
assert alph_size % 2 == 1
b = bytearray(alpha)
b[alph + 8 + alph_size] = 0xFF  # the ALPH pad byte
write('webp-pad.webp', b)
webp_variant('cwebp-default.webp', 'webp-simple-extra.webp', lambda c: c + [(b'EXIF', b'II*\x00\x08\x00\x00\x00\x00\x00\x00\x00\x00\x00')])
webp_variant('cwebp-alpha.webp', 'webp-flags-icc.webp', lambda c: enc(patch_chunk(c, 'VP8X', lambda d: set_bytes(d, 0, bytes([d[0] | 0x20])))))
webp_variant('cwebp-alpha.webp', 'webp-flags-alpha.webp', lambda c: enc(patch_chunk(c, 'VP8X', lambda d: set_bytes(d, 0, bytes([d[0] & ~0x10])))))
webp_variant('cwebp-alpha.webp', 'webp-vp8x-reserved.webp', lambda c: enc(patch_chunk(c, 'VP8X', lambda d: set_bytes(d, 0, bytes([d[0] | 0x01])))))
webp_variant('cwebp-alpha.webp', 'webp-order.webp', lambda c: [c[0], c[2], c[1]])
webp_variant('cwebp-alpha.webp', 'webp-alph-rsv.webp', lambda c: enc(patch_chunk(c, 'ALPH', lambda d: set_bytes(d, 0, bytes([d[0] | 0xC0])))))
webp_variant('cwebp-lossless.webp', 'webp-vp8l-version.webp', lambda c: enc(patch_chunk(c, 'VP8L', lambda d: set_bytes(d, 4, bytes([d[4] | 0x20])))))
webp_variant('cwebp-default.webp', 'webp-vp8-start.webp', lambda c: enc(patch_chunk(c, 'VP8 ', lambda d: set_bytes(d, 3, b'\x9e'))))
webp_variant('cwebp-alpha.webp', 'webp-canvas.webp', lambda c: enc(patch_chunk(c, 'VP8X', lambda d: set_bytes(d, 4, struct.pack('<I', 62)[:3]))))
webp_variant('img2webp-anim.webp', 'webp-anmf-noflag.webp', lambda c: enc(patch_chunk(c, 'VP8X', lambda d: set_bytes(d, 0, bytes([d[0] & ~0x02])))))
webp_variant('cwebp-alpha.webp', 'webp-iccp-dup.webp',
             lambda c: enc(patch_chunk([c[0], (b'ICCP', b'icc1'), (b'ICCP', b'icc2')] + c[1:], 'VP8X', lambda d: set_bytes(d, 0, bytes([d[0] | 0x20])))))
lossless = webp('cwebp-lossless.webp')
webp_variant('cwebp-alpha.webp', 'webp-alph-vp8l.webp', lambda c: [c[0], c[1], lossless[0]])


def anmf_patch(d, frame_x):
    d = bytearray(d)
    d[0:3] = struct.pack('<I', frame_x)[:3]
    return d


webp_variant('gif2webp-anim.webp', 'webp-anmf-region.webp', lambda c: enc(patch_chunk(c, 'ANMF', lambda d: anmf_patch(d, 30), nth=1)))


# ---- GIF ---------------------------------------------------------------------------------------------------------
def gif_blocks(b):
    """(kind, start, end) of every block after the Logical Screen; kind = 'ext:XX', 'img' or 'trailer'."""
    f = b[10]
    pos = 13 + (3 * 2 ** ((f & 7) + 1) if f & 0x80 else 0)
    out_ = []
    while pos < len(b):
        c = b[pos]
        if c == 0x3B:
            out_.append(('trailer', pos, pos + 1))
            break
        start = pos
        if c == 0x21:
            kind = 'ext:%02X' % b[pos + 1]
            pos += 2
        else:
            pf = b[pos + 9]
            pos += 10 + (3 * 2 ** ((pf & 7) + 1) if pf & 0x80 else 0) + 1
            kind = 'img'
        while b[pos] != 0:
            pos += 1 + b[pos]
        pos += 1
        out_.append((kind, start, pos))
    return out_


def first(blocks, kind, nth=0):
    return [x for x in blocks if x[0] == kind][nth]


g = read('ffmpeg-default.gif')
gb = gif_blocks(g)
gce = first(gb, 'ext:F9')
img = first(gb, 'img')
app = first(gb, 'ext:FF')
write('gif-gce-size.gif', g[:gce[1] + 2] + b'\x05' + g[gce[1] + 3:gce[1] + 7] + b'\x00' + g[gce[1] + 7:])
write('gif-two-gce.gif', g[:gce[2]] + g[gce[1]:gce[2]] + g[gce[2]:])
b = bytearray(g); b[img[1] + 10] = 12; write('gif-lzw.gif', b)                 # LZW Minimum Code Size 8 -> 12
b = bytearray(g); b[img[1] + 12] |= 0x01; write('gif-clear.gif', b)            # first code 256 -> 257
write('gif-no-trailer.gif', g[:-1])
write('gif-trailing.gif', g + bytes(8))
b = bytearray(g); b[gce[1] + 3] |= 0xE0; write('gif-reserved.gif', b)          # GCE reserved 3 bits
b = bytearray(g); b[img[1] + 9] |= 0x03; write('gif-lct-size.gif', b)          # no LCT but size field 3
gct = 3 * 2 ** ((g[10] & 7) + 1)
write('gif-bgindex.gif', g[:10] + bytes([g[10] & 0x7F]) + g[11:13] + g[13 + gct:])  # drop GCT, keep index 31
write('gif-app-size.gif', g[:app[1] + 2] + b'\x0a' + g[app[1] + 3:app[1] + 13] + g[app[1] + 14:])
write('gif-badblock.gif', g[:gb[0][1]] + b'\x00' + g[gb[0][1]:])
a = read('ffmpeg-anim.gif')
ab = gif_blocks(a)
img2 = first(ab, 'img', 1)
b = bytearray(a); b[img2[1] + 1:img2[1] + 3] = struct.pack('<H', 60); write('gif-bounds.gif', b)  # frame 2 left 0 -> 60
s = read('sips.gif')
sb = gif_blocks(s)
sg = first(sb, 'ext:F9')
write('gif-89a-plain.gif', b'GIF89a' + s[6:sg[1]] + s[sg[2]:])
```

- [ ] **Step 3:** run `docs/validation/png-webp-gif-conformance-fixtures/generate.sh`, then dump the bytes independently
  (`xxd`, `python3 -c` chunk walk, `dwebp file -o /dev/null`, `ffmpeg -v error -i file -f null -`) and compare with the
  verified layouts below (measured 2026-10-25, macOS 15.1.1, ffmpeg 8.1.2, libwebp 1.6.0; a second run produced
  byte-identical files). If sizes or structures differ, record the new values in this plan and the spec before
  continuing; the fixture tests (Tasks 5, 7, 8) depend only on the structure, not on the sizes.

| File | Bytes | Structure (offsets decimal) | Notes |
|---|---|---|---|
| `ffmpeg-default.png` | 997 | IHDR@8 (64x48, depth 8, type 2, 0/0/0), pHYs@33, IDAT@54 (919, zlib `78 9C`), IEND@985 | default-settings PNG |
| `ffmpeg-pal8.png` | 1680 | IHDR (type 3, depth 8), pHYs, PLTE@54 (768 = 256 entries), IDAT@834, IEND | |
| `ffmpeg-gray16.png` | 509 | IHDR (type 0, depth 16), pHYs, IDAT@54 (431), IEND | |
| `ffmpeg-rgba.png` | 1090 | IHDR (type 6, depth 8), pHYs, IDAT@54 (1012), IEND | |
| `ffmpeg.apng` | 1295 | IHDR, pHYs, acTL@54 (`00000003 00000001`), fcTL@74 seq 0 64x48, IDAT@112, fcTL@1043 seq 1 8x10 @(0,0), fdAT@1081 seq 2, fcTL@1163 seq 3 10x8 @(0,2), fdAT@1201 seq 4, IEND@1283 | default image = frame 1 |
| `sips.png` | 1108 | IHDR (type 6), sRGB@33 (1), eXIf@46 (68), IDAT@126 (958, zlib `68 05` = 16 KiB window), IEND | macOS ImageIO |
| `cwebp-default.webp` | 968 | RIFF 960, `VP8 `@12 (948; tag `30 17 00`, `9D 01 2A`, 64x48) | default-settings WebP (ffmpeg has no WebP encoder here) |
| `cwebp-lossless.webp` | 418 | RIFF 410, `VP8L`@12 (398, `2F`, 64x48) | |
| `cwebp-alpha.webp` | 1014 | RIFF 1006, VP8X@12 (flags `10` = L, canvas 64x48), ALPH@30 (21, header `01`, pad byte @59), `VP8 `@60 (946) | |
| `img2webp-anim.webp` | 762 | VP8X (flags `12` = A+L), ANIM@30 (bg `FFFFFFFF`, loop 0), ANMF@44 (64x48, VP8L), ANMF@474 (64x48, VP8L) | |
| `gif2webp-anim.webp` | 906 | VP8X (`12`), ANIM@30, ANMF@44 (64x48), ANMF@730 (8x10 @0,0), ANMF@820 (10x8 @0,2), all VP8L | |
| `ffmpeg-default.gif` | 1965 | `GIF89a`, LSD 64x48 packed `F7` (GCT 256), bg 31, aspect 49; App ext@781 `NETSCAPE2.0`; GCE@800 (`04 04 00 1F`); image@808 (0,0,64x48, packed 00, LZW 8, first code 256 = Clear); Trailer@1964 | default-settings GIF |
| `ffmpeg-anim.gif` | 2080 | as above + GCE@1964 / image@1972 (0,0,8x10) + GCE@2019 / image@2027 (0,2,10x8); Trailer@2079 | |
| `sips.gif` | 969 | **`GIF87a`**, LSD packed `D5` (GCT 64); GCE@205 (packed `09`: disposal 2 + transparent); image@213 (LZW 6); Trailer@968 | GCE is an 89a block |

Variants (each fails exactly the listed rule(s); byte changes measured against the source file):

| Variant | Source → change | Expected FAIL / WARN |
|---|---|---|
| `png-crc.png` | ffmpeg-default, 0xA2: 9E→61 (IDAT data, CRC stale) | `png3.crc` |
| `png-truncated.png` | ffmpeg-default minus 6 bytes | `png3.chunk.layout`, `png3.critical` |
| `png-trailing.png` | ffmpeg-default + 16 zero bytes | `png3.trailing` (FAIL, user decision) |
| `png-ihdr-depth.png` | ffmpeg-default, 0x18: 08→04 (type 2 + depth 4) | `png3.ihdr` |
| `png-plte-gray.png` | ffmpeg-gray16 + 9-byte PLTE before IDAT | `png3.plte` |
| `png-pal-noplte.png` | ffmpeg-pal8 without PLTE | `png3.plte` |
| `png-order.png` | ffmpeg-default with pHYs moved after IDAT | `png3.order` |
| `png-gama-dup.png` | ffmpeg-default + two gAMA | `png3.multiple` |
| `png-trns-rgba.png` | ffmpeg-rgba + 6-byte tRNS | `png3.trns` |
| `png-srgb-iccp.png` | ffmpeg-default + sRGB + iCCP | `png3.srgb_iccp` (WARN) |
| `png-keyword.png` | ffmpeg-default + tEXt keyword `" Title"` | `png3.keyword` |
| `png-itxt.png` | ffmpeg-default + iTXt compression flag 2 | `png3.text` |
| `png-text-ok.png` | ffmpeg-default + valid tEXt, zTXt, UTF-8 iTXt, compressed iTXt | none (PASS) |
| `png-zlib-fcheck.png` | ffmpeg-default, 0x3F: 9C→9D (IDAT FLG; CRC recomputed) | `zlib.header` |
| `png-fdict.png` | ffmpeg-default, 0x3F: 9C→BB (FDICT=1 with a valid FCHECK) | `png3.compression` |
| `png-bkgd-bits.png` | ffmpeg-default + bKGD R = 0x0100 (depth 8) | `png3.unused_bits` (WARN) |
| `apng-seq.apng` | ffmpeg.apng, 0x41E: 01→05 (2nd fcTL seq) | `png3.sequence` |
| `apng-actl.apng` | ffmpeg.apng, 0x41: 03→02 (num_frames) | `png3.actl` |
| `apng-region.apng` | ffmpeg.apng, 0x42A: 00→3C (2nd fcTL x_offset 60) | `png3.fctl` |
| `apng-nofdat.apng` | ffmpeg.apng without the last fdAT | `png3.fdat` |
| `webp-riff-size.webp` | cwebp-default, 0x4: C0→C2 (File Size +2) | `rfc9649.riff.size` |
| `webp-trailing.webp` | cwebp-default + 4 zero bytes | `rfc9649.trailing` (WARN) |
| `webp-pad.webp` | cwebp-alpha, 0x3B: 00→FF (ALPH pad byte) | `rfc9649.chunk.layout` |
| `webp-simple-extra.webp` | cwebp-default + EXIF chunk, no VP8X | `rfc9649.layout` |
| `webp-flags-icc.webp` | cwebp-alpha, 0x14: 10→30 (I without ICCP) | `rfc9649.vp8x.flags` |
| `webp-flags-alpha.webp` | cwebp-alpha, 0x14: 10→00 (ALPH without L) | `rfc9649.vp8x.flags` |
| `webp-vp8x-reserved.webp` | cwebp-alpha, 0x14: 10→11 (R bit) | `rfc9649.vp8x` |
| `webp-order.webp` | cwebp-alpha with ALPH after `VP8 ` | `rfc9649.order` |
| `webp-alph-rsv.webp` | cwebp-alpha, 0x26: 01→C1 | `rfc9649.alph` |
| `webp-vp8l-version.webp` | cwebp-lossless, 0x18: 00→20 (version 1) | `rfc9649.vp8l` |
| `webp-vp8-start.webp` | cwebp-default, 0x17: 9D→9E | `rfc6386.frame` |
| `webp-canvas.webp` | cwebp-alpha, 0x18: 3F→3E (canvas 63x48) | `rfc9649.canvas` |
| `webp-anmf-region.webp` | gif2webp-anim, 0x2E2: 00→1E (frame 2 X = 60) | `rfc9649.anim` |
| `webp-anmf-noflag.webp` | img2webp-anim, 0x14: 12→10 (A cleared) | `rfc9649.layout`, `rfc9649.anmf.flag` (WARN) |
| `webp-alph-vp8l.webp` | VP8X + ALPH (cwebp-alpha) + VP8L (cwebp-lossless) | `rfc9649.alph.vp8l` (WARN) |
| `webp-iccp-dup.webp` | cwebp-alpha + two ICCP, I set | `rfc9649.metadata.count` (WARN) |
| `gif-89a-plain.gif` | sips.gif without its GCE, header `GIF89a` | `gif89a.version.earliest` (WARN) |
| `gif-gce-size.gif` | ffmpeg-default, GCE Block Size 4→5 + one data byte | `gif89a.gce` |
| `gif-two-gce.gif` | ffmpeg-default with the GCE duplicated | `gif89a.gce` |
| `gif-bounds.gif` | ffmpeg-anim, 0x7B5: 00→3C (image 2 left 60) | `gif89a.image.bounds` |
| `gif-lzw.gif` | ffmpeg-default, 0x332: 08→0C (LZW min 12) | `gif89a.lzw` |
| `gif-clear.gif` | ffmpeg-default, 0x334: 00→01 (first code 257) | `gif89a.lzw.clear` (WARN) |
| `gif-no-trailer.gif` | ffmpeg-default minus the Trailer | `gif89a.trailer` |
| `gif-trailing.gif` | ffmpeg-default + 8 zero bytes | `gif89a.trailing` (WARN) |
| `gif-reserved.gif` | ffmpeg-default, 0x323: 04→E4 (GCE reserved bits) | `gif89a.reserved` (WARN) |
| `gif-lct-size.gif` | ffmpeg-default, 0x331: 00→03 (no LCT, size 3) | `gif89a.lct.size` (WARN) |
| `gif-bgindex.gif` | ffmpeg-default without GCT, Background Color Index 31 kept | `gif89a.lsd.background` (WARN) |
| `gif-app-size.gif` | ffmpeg-default, NETSCAPE Block Size 11→10 (one byte dropped) | `gif89a.extension` |
| `gif-badblock.gif` | ffmpeg-default + 0x00 before the first block | `gif89a.blocks`, `gif89a.trailer` |

- [ ] **Step 4:** commit `test: add PNG/WebP/GIF conformance fixtures` (scripts + 63 files).

---

### Task 3: PNG chunk scanner, structure rules and the checker

**Files:** Create `parser/conformance/png/PngChunks.kt`, `PngStructureRules.kt`, `PngConformanceChecker.kt`; tests
`png/SyntheticPng.kt` (shared builder for Tasks 3-5), `png/PngStructureRulesTest.kt`.

- [ ] **Step 1: Failing tests.**

```kotlin
package com.multiviewer.parser.conformance.png

import com.multiviewer.parser.byteReaderOf
import com.multiviewer.parser.conformance.ConformanceReport
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream

/** Minimal PNG writer for rule tests: chunks get correct lengths and CRCs unless stated otherwise. */
internal object SyntheticPng {
    val SIGNATURE = PngChunkScanner.SIGNATURE

    fun be32(v: Long) = byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())
    fun be16(v: Int) = byteArrayOf((v shr 8).toByte(), v.toByte())

    fun chunk(type: String, data: ByteArray = ByteArray(0)): ByteArray {
        val t = type.toByteArray(Charsets.ISO_8859_1)
        val crc = CRC32().apply { update(t); update(data) }.value
        return be32(data.size.toLong()) + t + data + be32(crc)
    }

    fun ihdr(w: Long = 2, h: Long = 2, depth: Int = 8, colorType: Int = 2, compression: Int = 0, filter: Int = 0, interlace: Int = 0) =
        chunk("IHDR", be32(w) + be32(h) + byteArrayOf(depth.toByte(), colorType.toByte(), compression.toByte(), filter.toByte(), interlace.toByte()))

    /** zlib stream of [rows] filter-0 scanlines of [rowBytes] zero samples. */
    fun zlib(rows: Int = 2, rowBytes: Int = 6): ByteArray {
        val out = ByteArrayOutputStream()
        DeflaterOutputStream(out).use { z -> repeat(rows) { z.write(0); z.write(ByteArray(rowBytes)) } }
        return out.toByteArray()
    }

    fun idat(data: ByteArray = zlib()) = chunk("IDAT", data)
    fun iend() = chunk("IEND")

    fun png(vararg chunks: ByteArray): ByteArray = chunks.fold(SIGNATURE) { acc, c -> acc + c }

    /** IHDR (2x2 RGB 8-bit) + [middle] + IDAT + IEND. */
    fun rgb(vararg beforeIdat: ByteArray, after: List<ByteArray> = emptyList()): ByteArray =
        png(ihdr(), *beforeIdat, idat(), *after.toTypedArray(), iend())

    fun fcTL(seq: Long, w: Long = 2, h: Long = 2, x: Long = 0, y: Long = 0, dispose: Int = 0, blend: Int = 0) =
        chunk("fcTL", be32(seq) + be32(w) + be32(h) + be32(x) + be32(y) + be16(1) + be16(10) + byteArrayOf(dispose.toByte(), blend.toByte()))

    fun fdAT(seq: Long, data: ByteArray = zlib()) = chunk("fdAT", be32(seq) + data)
    fun acTL(frames: Long, plays: Long = 0) = chunk("acTL", be32(frames) + be32(plays))

    fun check(bytes: ByteArray): ConformanceReport = byteReaderOf(bytes).use { PngConformanceChecker.check(it) }
}
```

```kotlin
package com.multiviewer.parser.conformance.png

import com.multiviewer.parser.conformance.ConformanceStatus.FAIL
import com.multiviewer.parser.conformance.ConformanceStatus.NOT_CHECKED
import com.multiviewer.parser.conformance.ConformanceStatus.PASS
import com.multiviewer.parser.conformance.ConformanceStatus.WARN
import com.multiviewer.parser.conformance.idsWith
import com.multiviewer.parser.conformance.notPassing
import com.multiviewer.parser.conformance.png.SyntheticPng.be16
import com.multiviewer.parser.conformance.png.SyntheticPng.be32
import com.multiviewer.parser.conformance.png.SyntheticPng.check
import com.multiviewer.parser.conformance.png.SyntheticPng.chunk
import com.multiviewer.parser.conformance.png.SyntheticPng.idat
import com.multiviewer.parser.conformance.png.SyntheticPng.iend
import com.multiviewer.parser.conformance.png.SyntheticPng.ihdr
import com.multiviewer.parser.conformance.png.SyntheticPng.png
import com.multiviewer.parser.conformance.png.SyntheticPng.rgb
import com.multiviewer.parser.conformance.statusOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PngStructureRulesTest {
    private fun failsOnly(bytes: ByteArray, vararg ids: String) {
        val r = check(bytes)
        assertNull(r.inspectionError)
        assertEquals(ids.toSet(), r.idsWith(FAIL, WARN), r.notPassing().toString())
    }

    @Test fun `a minimal valid PNG passes every applicable rule`() {
        val r = check(rgb())
        assertEquals(emptySet(), r.idsWith(FAIL, WARN, NOT_CHECKED), r.notPassing().toString())
        assertEquals(PASS, r.overall)
    }

    @Test fun `chunk type bytes and the reserved bit`() {
        failsOnly(rgb(chunk("abcd")), "png3.chunk.type")          // third letter lowercase
        failsOnly(rgb(chunk("ab1d")), "png3.chunk.type")          // '1' outside 41-5A / 61-7A
        assertEquals(PASS, check(rgb(chunk("prVt"))).statusOf("png3.chunk.type")) // private ancillary chunk is fine
    }

    @Test fun `IHDR values and the critical chunk sequence`() {
        failsOnly(png(ihdr(w = 0), idat(), iend()), "png3.ihdr")
        failsOnly(png(ihdr(interlace = 2), idat(), iend()), "png3.ihdr")
        failsOnly(png(ihdr(filter = 1), idat(), iend()), "png3.ihdr")
        failsOnly(png(ihdr(), iend()), "png3.critical")                                     // no IDAT
        failsOnly(png(ihdr(), idat(), chunk("tEXt", "a\u0000b".toByteArray()), idat(), iend()), "png3.critical")
        failsOnly(png(ihdr(), ihdr(), idat(), iend()), "png3.critical")
        failsOnly(png(ihdr(), idat(), chunk("IEND", byteArrayOf(1))), "png3.critical")
        failsOnly(png(chunk("tEXt", "a\u0000b".toByteArray()), ihdr(), idat(), iend()), "png3.ihdr")       // IHDR not first
    }

    @Test fun `PLTE presence, size and entry count`() {
        fun pal(depth: Int, entries: Int) = png(ihdr(depth = depth, colorType = 3), chunk("PLTE", ByteArray(entries * 3)), idat(), iend())
        assertEquals(PASS, check(pal(1, 2)).statusOf("png3.plte"))
        failsOnly(pal(1, 3), "png3.plte")                                                   // 3 entries > 2^1
        failsOnly(png(ihdr(colorType = 3), chunk("PLTE", ByteArray(4)), idat(), iend()), "png3.plte")
        failsOnly(png(ihdr(), idat(), chunk("PLTE", ByteArray(3)), iend()), "png3.critical") // PLTE after IDAT
        assertEquals(PASS, check(png(ihdr(), chunk("PLTE", ByteArray(3)), idat(), iend())).statusOf("png3.plte")) // suggested palette, colour type 2
    }

    @Test fun `ancillary ordering and uniqueness`() {
        failsOnly(png(ihdr(colorType = 3, depth = 8), chunk("PLTE", ByteArray(3)), chunk("gAMA", be32(45455)), idat(), iend()), "png3.order")
        failsOnly(png(ihdr(colorType = 3, depth = 8), chunk("tRNS", byteArrayOf(0)), chunk("PLTE", ByteArray(3)), idat(), iend()), "png3.order")
        failsOnly(rgb(chunk("tIME", be16(2026) + byteArrayOf(10, 25, 12, 0, 0)), chunk("tIME", be16(2026) + byteArrayOf(10, 25, 12, 0, 0))), "png3.multiple")
        assertEquals(PASS, check(rgb(after = listOf(chunk("tIME", be16(2026) + byteArrayOf(10, 25, 12, 0, 0))))).statusOf("png3.order")) // tIME: no constraint
    }

    @Test fun `a truncated file never reports PASS for rules that need every chunk`() {
        val full = rgb(chunk("gAMA", be32(45455)))
        val r = check(full.copyOf(full.size - 20))
        assertEquals(setOf("png3.chunk.layout", "png3.critical"), r.idsWith(FAIL, WARN), r.notPassing().toString())
        assertEquals(NOT_CHECKED, r.statusOf("png3.crc"))
        assertEquals(NOT_CHECKED, r.statusOf("png3.order"))
        assertEquals(NOT_CHECKED, r.statusOf("png3.trailing"))
        assertTrue(r.findings.none { it.status == PASS && it.rule.id !in setOf("png3.signature", "png3.ihdr") }, r.notPassing().toString())
    }
}
```

- [ ] **Step 2:** `./gradlew :app:test --tests 'com.multiviewer.parser.conformance.png.*'` → RED (unresolved
  `PngConformanceChecker`).
- [ ] **Step 3: Implement.** Scanner and context:

```kotlin
package com.multiviewer.parser.conformance.png

import com.multiviewer.parser.ByteReader
import com.multiviewer.parser.conformance.ConformanceFinding
import com.multiviewer.parser.conformance.ConformanceStatus
import com.multiviewer.parser.conformance.ImageRuleBook.Companion.hex

/** One chunk as laid out in the file; [complete] = header, data and CRC are all inside the file. */
internal data class PngChunk(val index: Int, val offset: Long, val length: Long, val type: String, val complete: Boolean) {
    val dataStart: Long get() = offset + 8
    val end: Long get() = offset + 12 + length
    val label: String get() = "$type @${hex(offset)}"
}

/**
 * Bounded chunk walk from offset 8 up to and including the first IEND. [problem] = why the walk stopped early (a chunk
 * cut off by EOF, a length above 2^31-1); [capped] = stopped at [PngChunkScanner.MAX_CHUNKS] (not a violation);
 * [end] = first byte after the last complete chunk.
 */
internal class PngScan(val chunks: List<PngChunk>, val end: Long, val iend: PngChunk?, val problem: String?, val problemAt: Long?, val capped: Boolean = false)

internal object PngChunkScanner {
    const val MAX_CHUNKS = 200_000
    const val MAX_LENGTH = 0x7FFF_FFFFL
    val SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    fun scan(reader: ByteReader): PngScan {
        val len = reader.length
        val chunks = ArrayList<PngChunk>()
        var pos = 8L
        while (pos < len) {
            if (chunks.size >= MAX_CHUNKS) return PngScan(chunks, pos, null, null, null, capped = true)
            if (len - pos < 12) return PngScan(chunks, pos, null,
                "${hex(pos)}의 ${len - pos}바이트는 청크(최소 12바이트)가 될 수 없음 / ${len - pos} byte(s) at ${hex(pos)} are too short for a chunk (12 bytes minimum)", pos)
            val length = reader.readUInt32(pos)
            val type = reader.readFourCC(pos + 4)
            if (length > MAX_LENGTH) return PngScan(chunks, pos, null,
                "'$type' @${hex(pos)} 길이 $length > 2^31-1 / length $length exceeds 2^31-1", pos)
            if (length > len - pos - 12) {
                chunks += PngChunk(chunks.size, pos, length, type, complete = false)
                return PngScan(chunks, pos, null,
                    "'$type' @${hex(pos)} 데이터 ${length}바이트+CRC가 파일 끝(${hex(len)})을 넘음 / declares $length data bytes + CRC past EOF ${hex(len)}", pos)
            }
            val chunk = PngChunk(chunks.size, pos, length, type, complete = true)
            chunks += chunk
            pos = chunk.end
            if (type == "IEND") return PngScan(chunks, pos, chunk, null, null)
        }
        return PngScan(chunks, pos, null, null, null)
    }
}

/** IHDR fields (only when the first chunk is a complete 13-byte IHDR). */
internal data class PngHeader(val width: Long, val height: Long, val bitDepth: Int, val colorType: Int, val compression: Int, val filter: Int, val interlace: Int) {
    /** Sample depth: 8 for indexed colour, the bit depth otherwise (11.3.2.4). */
    val sampleDepth: Int get() = if (colorType == 3) 8 else bitDepth

    companion object {
        /** Table 12. */
        val ALLOWED_DEPTHS = mapOf(0 to setOf(1, 2, 4, 8, 16), 2 to setOf(8, 16), 3 to setOf(1, 2, 4, 8), 4 to setOf(8, 16), 6 to setOf(8, 16))
    }
}

/** Shared, read-only state for the PNG rule groups. */
internal class PngContext(val reader: ByteReader, val scan: PngScan) {
    val length: Long = reader.length
    val chunks: List<PngChunk> get() = scan.chunks
    val complete: List<PngChunk> = scan.chunks.filter { it.complete }
    val book = PngRules.book

    fun of(type: String): List<PngChunk> = complete.filter { it.type == type }
    fun first(type: String): PngChunk? = complete.firstOrNull { it.type == type }

    val header: PngHeader? by lazy {
        val c = chunks.firstOrNull()?.takeIf { it.type == "IHDR" && it.complete && it.length == 13L } ?: return@lazy null
        val b = reader.readBytes(c.dataStart, 13)
        fun u32(i: Int) = ((b[i].toLong() and 0xFF) shl 24) or ((b[i + 1].toLong() and 0xFF) shl 16) or ((b[i + 2].toLong() and 0xFF) shl 8) or (b[i + 3].toLong() and 0xFF)
        PngHeader(u32(0), u32(4), b[8].toInt() and 0xFF, b[9].toInt() and 0xFF, b[10].toInt() and 0xFF, b[11].toInt() and 0xFF, b[12].toInt() and 0xFF)
    }

    /** IHDR whose colour type / bit depth combination is valid (needed by every colour-dependent rule). */
    val validHeader: PngHeader? by lazy { header?.takeIf { PngHeader.ALLOWED_DEPTHS[it.colorType]?.contains(it.bitDepth) == true } }

    /** Entries of the first complete PLTE whose length is a multiple of 3 (null when absent or malformed). */
    val paletteEntries: Int? by lazy { first("PLTE")?.takeIf { it.length % 3 == 0L && it.length in 3L..768L }?.let { (it.length / 3).toInt() } }

    val firstIdat: Int? by lazy { complete.firstOrNull { it.type == "IDAT" }?.index }
    val lastIdat: Int? by lazy { complete.lastOrNull { it.type == "IDAT" }?.index }

    /** Up to [max] bytes of the chunk's data (the caller decides what a short read means). */
    fun data(c: PngChunk, max: Int = PAYLOAD_CAP): ByteArray = reader.readBytes(c.dataStart, minOf(c.length, max.toLong()).toInt())

    fun finding(id: String, violations: List<String>, ranges: List<LongRange>, pass: List<String>): ConformanceFinding =
        book.finding(id, violations, ranges, pass)

    fun skip(id: String, status: ConformanceStatus, reason: String): ConformanceFinding = book.skip(id, status, reason)

    fun range(c: PngChunk): LongRange = c.offset until minOf(c.end, length)

    companion object {
        /** Largest chunk payload read into memory (text, sPLT); larger chunks are reported NOT_CHECKED where it matters. */
        const val PAYLOAD_CAP = 1 shl 20
    }
}
```

Structure rules:

```kotlin
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
        listOf(signature(ctx), layout(ctx), type(ctx), crc(ctx), ihdr(ctx), critical(ctx), plte(ctx), order(ctx), multiple(ctx))

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
```

Checker (Tasks 4 and 5 extend the `val rules = …` line):

```kotlin
package com.multiviewer.parser.conformance.png

import com.multiviewer.parser.ByteReader
import com.multiviewer.parser.conformance.ConformanceFinding
import com.multiviewer.parser.conformance.ConformanceReport
import com.multiviewer.parser.conformance.ConformanceStatus.NOT_CHECKED
import com.multiviewer.parser.conformance.ImageRuleBook
import com.multiviewer.parser.conformance.trailingDataFinding
import java.util.concurrent.CancellationException

object PngConformanceChecker {
    const val PROFILE = "W3C PNG Third Edition (2025) + APNG + RFC 1950 zlib headers phase 1"

    val LIMITATIONS = listOf(
        "zlib/deflate 데이터는 해제하지 않습니다(CMF·FLG만 검사) — 필터 바이트, 압축 해제 길이, Adler-32, 팔레트 인덱스 범위, 압축 텍스트·ICC 내용은 디코딩 검증(ffmpeg) 몫이며 여기서는 PASS로 보지 않습니다 / " +
            "zlib/deflate data is not inflated (CMF/FLG only): filter bytes, decompressed length, Adler-32, palette index range and compressed text / ICC contents are left to the ffmpeg decode check and never counted as PASS here",
        "BCP 47 언어 태그, ICC 프로필·eXIf(Exif) 내용, 등록 확장 청크(oFFs, pCAL, sCAL 등)와 비공개 청크 내용, hIST 0 항목과 실제 픽셀 사용은 검사하지 않습니다 / " +
            "Not checked: BCP 47 language tags, ICC profile and eXIf (Exif) contents, registered extension chunks (oFFs, pCAL, sCAL …) and private chunk contents, hIST zero entries vs actual pixel use",
        "청크 ${PngChunkScanner.MAX_CHUNKS}개, 텍스트·sPLT 1 MiB까지 읽습니다 / Reads at most ${PngChunkScanner.MAX_CHUNKS} chunks and 1 MiB per text / sPLT chunk",
    )

    /** Rules whose PASS needs every chunk up to IEND. */
    private val CHUNK_RULES = PngRules.all.map { it.id }.toSet() - setOf("png3.signature", "png3.chunk.layout", "png3.ihdr", "png3.critical", "png3.trailing")

    private const val TRAILING_NOTE = "PNG-3 §15.3.1: IEND 뒤에는 어떤 청크나 데이터도 없어야 함 / no chunks or other content follow the IEND chunk"

    fun check(reader: ByteReader): ConformanceReport {
        val findings = ArrayList<ConformanceFinding>()
        val error = try {
            val ctx = PngContext(reader, PngChunkScanner.scan(reader))
            val rules = PngStructureRules.evaluate(ctx)
            findings += if (ctx.scan.problem == null && ctx.scan.iend != null) rules
            else PngRules.book.partial(rules, CHUNK_RULES, "청크 열거가 IEND 전에 멈춤 — 읽은 청크만 검사됨 / chunk walk stopped before IEND: only the chunks read were checked")
            findings += if (ctx.scan.iend == null) ctx.skip("png3.trailing", NOT_CHECKED, "IEND 없음 — 판단 불가 / no IEND")
            else trailingDataFinding(PngRules.book, "png3.trailing", reader, ctx.scan.end, TRAILING_NOTE)
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ImageRuleBook.inspectionError(e)
        }
        return ConformanceReport("PNG", PROFILE, PngRules.book.sorted(findings), LIMITATIONS, error)
    }
}
```

- [ ] **Step 4:** `./gradlew :app:test --tests 'com.multiviewer.parser.conformance.png.*'` → GREEN. **Step 5:** commit
  `feat: check PNG chunk structure, IHDR, PLTE and ordering`.

---

### Task 4: PNG ancillary chunks, text and zlib headers

**Files:** Create `parser/conformance/png/PngAncillaryRules.kt`; modify `PngConformanceChecker.kt`; test
`png/PngAncillaryRulesTest.kt`.

- [ ] **Step 1: Failing test.**

```kotlin
package com.multiviewer.parser.conformance.png

import com.multiviewer.parser.conformance.ConformanceStatus.FAIL
import com.multiviewer.parser.conformance.ConformanceStatus.NOT_APPLICABLE
import com.multiviewer.parser.conformance.ConformanceStatus.PASS
import com.multiviewer.parser.conformance.ConformanceStatus.WARN
import com.multiviewer.parser.conformance.idsWith
import com.multiviewer.parser.conformance.notPassing
import com.multiviewer.parser.conformance.png.SyntheticPng.be16
import com.multiviewer.parser.conformance.png.SyntheticPng.be32
import com.multiviewer.parser.conformance.png.SyntheticPng.check
import com.multiviewer.parser.conformance.png.SyntheticPng.chunk
import com.multiviewer.parser.conformance.png.SyntheticPng.idat
import com.multiviewer.parser.conformance.png.SyntheticPng.iend
import com.multiviewer.parser.conformance.png.SyntheticPng.ihdr
import com.multiviewer.parser.conformance.png.SyntheticPng.png
import com.multiviewer.parser.conformance.png.SyntheticPng.rgb
import com.multiviewer.parser.conformance.statusOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PngAncillaryRulesTest {
    private fun failsOnly(bytes: ByteArray, vararg ids: String) {
        val r = check(bytes)
        assertNull(r.inspectionError)
        assertEquals(ids.toSet(), r.idsWith(FAIL, WARN), r.notPassing().toString())
    }

    @Test fun `ancillary field values`() {
        failsOnly(rgb(chunk("sRGB", byteArrayOf(4))), "png3.ancillary")
        failsOnly(rgb(chunk("cICP", byteArrayOf(1, 13, 1, 1))), "png3.ancillary")              // Matrix Coefficients must be 0
        failsOnly(rgb(chunk("mDCV", ByteArray(24))), "png3.ancillary")                          // without cICP
        assertEquals(PASS, check(rgb(chunk("cICP", byteArrayOf(9, 16, 0, 1)), chunk("mDCV", ByteArray(24)), chunk("cLLI", ByteArray(8)))).statusOf("png3.ancillary"))
        failsOnly(rgb(chunk("sBIT", byteArrayOf(8, 0, 8))), "png3.ancillary")
        failsOnly(rgb(chunk("sBIT", byteArrayOf(8, 9, 8))), "png3.ancillary")
        failsOnly(rgb(chunk("pHYs", be32(1) + be32(1) + byteArrayOf(2))), "png3.ancillary")
        failsOnly(rgb(after = listOf(chunk("tIME", be16(2026) + byteArrayOf(13, 1, 0, 0, 0)))), "png3.ancillary")
        failsOnly(png(ihdr(colorType = 3), chunk("PLTE", ByteArray(6)), chunk("hIST", ByteArray(2)), idat(), iend()), "png3.ancillary")
        failsOnly(png(ihdr(colorType = 3), chunk("PLTE", ByteArray(6)), chunk("bKGD", byteArrayOf(2)), idat(), iend()), "png3.ancillary")
        fun splt(name: String, depth: Int, vararg freq: Int) =
            chunk("sPLT", name.toByteArray() + byteArrayOf(0, depth.toByte()) + freq.fold(ByteArray(0)) { a, f -> a + ByteArray(4) + be16(f) })
        assertEquals(PASS, check(rgb(splt("p", 8, 9, 3))).statusOf("png3.ancillary"))
        failsOnly(rgb(splt("p", 8, 3, 9)), "png3.ancillary")                                    // not decreasing
        failsOnly(rgb(splt("p", 4, 3)), "png3.ancillary")                                       // depth 4
        failsOnly(rgb(splt("p", 8, 3), splt("p", 8, 3)), "png3.ancillary")                      // duplicate names
    }

    @Test fun `tRNS and the unused high bits of 16-bit sample fields`() {
        failsOnly(rgb(chunk("tRNS", ByteArray(2))), "png3.trns")                                // colour type 2 needs 6 bytes
        failsOnly(png(ihdr(colorType = 3), chunk("PLTE", ByteArray(3)), chunk("tRNS", ByteArray(2)), idat(), iend()), "png3.trns")
        failsOnly(rgb(chunk("tRNS", be16(0x100) + be16(0) + be16(0))), "png3.unused_bits")
        assertEquals(NOT_APPLICABLE, check(png(ihdr(depth = 16), chunk("bKGD", be16(0xFFFF) + be16(0) + be16(0)), idat(), iend())).statusOf("png3.unused_bits"))
    }

    @Test fun `keywords, text fields and zlib headers`() {
        failsOnly(rgb(chunk("tEXt", ByteArray(80) { 0x41 })), "png3.keyword")                   // no separator within 79 bytes
        failsOnly(rgb(chunk("tEXt", "a\u007Fb\u0000x".toByteArray())), "png3.keyword")
        failsOnly(rgb(chunk("tEXt", "Title\u0000a\u0000b".toByteArray())), "png3.text")
        failsOnly(rgb(chunk("zTXt", "Title\u0000".toByteArray() + byteArrayOf(1) + SyntheticPng.zlib())), "png3.text")
        failsOnly(rgb(chunk("iTXt", "Title\u0000".toByteArray() + byteArrayOf(0, 0, 0, 0) + byteArrayOf(0xC3.toByte(), 0x28))), "png3.text") // invalid UTF-8
        failsOnly(rgb(chunk("iTXt", "Title\u0000".toByteArray() + byteArrayOf(0, 1, 0, 0) + "x".toByteArray())), "png3.text")         // uncompressed, method 1
        val cinfo8 = byteArrayOf(0x88.toByte(), (31 - (0x88 * 256) % 31).toByte()) + SyntheticPng.zlib().copyOfRange(2, SyntheticPng.zlib().size)
        failsOnly(png(ihdr(), idat(cinfo8), iend()), "png3.compression", "zlib.header")
        failsOnly(png(ihdr(), idat(byteArrayOf(0x78)), iend()), "zlib.header")                  // shorter than CMF+FLG
        // The 2-byte header may span IDAT chunks.
        val z = SyntheticPng.zlib()
        assertEquals(PASS, check(png(ihdr(), idat(z.copyOf(1)), idat(z.copyOfRange(1, z.size)), iend())).statusOf("zlib.header"))
    }
}
```

- [ ] **Step 2:** `./gradlew :app:test --tests 'com.multiviewer.parser.conformance.png.PngAncillaryRulesTest'` → RED
  (no `png3.ancillary` … findings: `NoSuchElementException` / assertion).
- [ ] **Step 3: Implement.**

```kotlin
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
                "cICP" -> { checked++; if (len(c, 4)) ctx.data(c, 4).let { if (u8(it, 2) != 0) fail(c, "Matrix Coefficients ${u8(it, 2)} ≠ 0") } }
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
```

In `PngConformanceChecker.check` replace

```kotlin
            val rules = PngStructureRules.evaluate(ctx)
```

with

```kotlin
            val rules = PngStructureRules.evaluate(ctx) + PngAncillaryRules.evaluate(ctx)
```

- [ ] **Step 4:** `./gradlew :app:test --tests 'com.multiviewer.parser.conformance.png.*'` → GREEN. **Step 5:** commit
  `feat: check PNG ancillary chunks, keywords, text and zlib headers`.

---

### Task 5: APNG rules and the PNG fixture test

**Files:** Create `parser/conformance/png/PngApngRules.kt`; modify `PngConformanceChecker.kt`; tests
`png/PngApngRulesTest.kt`, `png/PngFixtureTest.kt`.

- [ ] **Step 1: Failing tests.**

```kotlin
package com.multiviewer.parser.conformance.png

import com.multiviewer.parser.conformance.ConformanceStatus.FAIL
import com.multiviewer.parser.conformance.ConformanceStatus.NOT_APPLICABLE
import com.multiviewer.parser.conformance.ConformanceStatus.NOT_CHECKED
import com.multiviewer.parser.conformance.ConformanceStatus.WARN
import com.multiviewer.parser.conformance.idsWith
import com.multiviewer.parser.conformance.notPassing
import com.multiviewer.parser.conformance.png.SyntheticPng.acTL
import com.multiviewer.parser.conformance.png.SyntheticPng.check
import com.multiviewer.parser.conformance.png.SyntheticPng.chunk
import com.multiviewer.parser.conformance.png.SyntheticPng.fcTL
import com.multiviewer.parser.conformance.png.SyntheticPng.fdAT
import com.multiviewer.parser.conformance.png.SyntheticPng.idat
import com.multiviewer.parser.conformance.png.SyntheticPng.iend
import com.multiviewer.parser.conformance.png.SyntheticPng.ihdr
import com.multiviewer.parser.conformance.png.SyntheticPng.png
import com.multiviewer.parser.conformance.png.SyntheticPng.rgb
import com.multiviewer.parser.conformance.statusOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PngApngRulesTest {
    private fun failsOnly(bytes: ByteArray, vararg ids: String) {
        val r = check(bytes)
        assertNull(r.inspectionError)
        assertEquals(ids.toSet(), r.idsWith(FAIL, WARN), r.notPassing().toString())
    }

    @Test fun `APNG acTL, sequence numbers, frame regions and fdAT`() {
        fun apng(vararg c: ByteArray) = png(ihdr(), *c, iend())
        val ok = apng(acTL(2), fcTL(0), idat(), fcTL(1, w = 1, h = 1, x = 1, y = 1), fdAT(2))
        val r = check(ok)
        assertEquals(emptySet(), r.idsWith(FAIL, WARN, NOT_CHECKED), r.notPassing().toString())
        failsOnly(apng(acTL(0), fcTL(0), idat()), "png3.actl")
        failsOnly(apng(acTL(2), fcTL(0), idat(), fcTL(2), fdAT(3)), "png3.sequence")
        failsOnly(apng(acTL(1), fcTL(0, w = 1), idat()), "png3.fctl")                            // default image must use the IHDR size
        failsOnly(apng(acTL(2), fcTL(0), idat(), fcTL(1, dispose = 3), fdAT(2)), "png3.fctl")
        failsOnly(apng(acTL(2), fcTL(0), fdAT(1), idat(), fcTL(2)), "png3.fdat")                // fdAT before IDAT; last frame without fdAT
        // fdAT shorter than its sequence number: no frame_data, so the frame has no zlib header either
        failsOnly(apng(acTL(1), idat(), fcTL(0), chunk("fdAT", byteArrayOf(0, 0))), "png3.fdat", "zlib.header")
        assertEquals(NOT_APPLICABLE, check(SyntheticPng.rgb()).statusOf("png3.actl"))
        val noActl = check(apng(idat(), fcTL(0), fdAT(1)))
        assertEquals(NOT_CHECKED, noActl.statusOf("png3.actl"))
        assertEquals(emptySet(), noActl.idsWith(FAIL, WARN))
    }
}
```

```kotlin
package com.multiviewer.parser.conformance.png

import com.multiviewer.parser.ByteReader
import com.multiviewer.parser.conformance.ConformanceReport
import com.multiviewer.parser.conformance.ConformanceStatus.FAIL
import com.multiviewer.parser.conformance.ConformanceStatus.NOT_CHECKED
import com.multiviewer.parser.conformance.ConformanceStatus.PASS
import com.multiviewer.parser.conformance.ConformanceStatus.WARN
import com.multiviewer.parser.conformance.conformanceFixture
import com.multiviewer.parser.conformance.idsWith
import com.multiviewer.parser.conformance.notPassing
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Real tool output (ffmpeg, macOS sips) and byte-level variants — docs/validation/png-webp-gif-conformance-fixtures/. */
class PngFixtureTest {
    private fun run(name: String): ConformanceReport = ByteReader.open(conformanceFixture(name)).use { PngConformanceChecker.check(it) }

    @Test fun `ffmpeg default, palette, 16-bit grey, RGBA, APNG and sips output have no FAIL, WARN or NOT_CHECKED`() {
        listOf("ffmpeg-default.png", "ffmpeg-pal8.png", "ffmpeg-gray16.png", "ffmpeg-rgba.png", "ffmpeg.apng", "sips.png", "png-text-ok.png").forEach { name ->
            val r = run(name)
            assertNull(r.inspectionError, name)
            assertEquals(emptySet(), r.idsWith(FAIL, WARN, NOT_CHECKED), "$name: ${r.notPassing()}")
            assertEquals(PASS, r.overall, name)
        }
        val apng = run("ffmpeg.apng")
        assertTrue(apng.findings.single { it.rule.id == "png3.actl" }.details.single().contains("num_frames 3"))
    }

    @Test fun `each variant fails exactly its targeted rule`() {
        val expected = mapOf(
            "png-crc.png" to setOf("png3.crc"),
            "png-truncated.png" to setOf("png3.chunk.layout", "png3.critical"),
            "png-trailing.png" to setOf("png3.trailing"),
            "png-ihdr-depth.png" to setOf("png3.ihdr"),
            "png-plte-gray.png" to setOf("png3.plte"),
            "png-pal-noplte.png" to setOf("png3.plte"),
            "png-order.png" to setOf("png3.order"),
            "png-gama-dup.png" to setOf("png3.multiple"),
            "png-trns-rgba.png" to setOf("png3.trns"),
            "png-srgb-iccp.png" to setOf("png3.srgb_iccp"),
            "png-keyword.png" to setOf("png3.keyword"),
            "png-itxt.png" to setOf("png3.text"),
            "png-zlib-fcheck.png" to setOf("zlib.header"),
            "png-fdict.png" to setOf("png3.compression"),
            "png-bkgd-bits.png" to setOf("png3.unused_bits"),
            "apng-seq.apng" to setOf("png3.sequence"),
            "apng-actl.apng" to setOf("png3.actl"),
            "apng-region.apng" to setOf("png3.fctl"),
            "apng-nofdat.apng" to setOf("png3.fdat"),
        )
        expected.forEach { (name, ids) ->
            val r = run(name)
            assertNull(r.inspectionError, name)
            assertEquals(ids, r.idsWith(FAIL, WARN), "$name: ${r.notPassing()}")
        }
        listOf("png-srgb-iccp.png", "png-bkgd-bits.png").forEach { assertEquals(WARN, run(it).overall, it) }
        assertEquals(FAIL, run("png-trailing.png").overall)   // PNG-3 §15.3.1: data after IEND is a conformance FAIL
    }

    @Test fun `findings carry the verified W3C anchor`() {
        val c = run("png-crc.png").findings.single { it.rule.id == "png3.crc" }.rule.citation!!
        assertTrue(c.verified)
        assertNull(c.page)
        assertEquals("https://www.w3.org/TR/2025/REC-png-3-20250624/#5CRC-algorithm", c.url)
    }
}
```

- [ ] **Step 2:** `./gradlew :app:test --tests 'com.multiviewer.parser.conformance.png.*'` → RED (APNG findings
  missing; `apng-*` variants pass).
- [ ] **Step 3: Implement.**

```kotlin
package com.multiviewer.parser.conformance.png

import com.multiviewer.parser.conformance.ConformanceFinding
import com.multiviewer.parser.conformance.ConformanceStatus.NOT_APPLICABLE
import com.multiviewer.parser.conformance.ConformanceStatus.NOT_CHECKED

/** APNG (PNG-3 §4.9, §11.3.6): acTL, sequence numbers, fcTL fields and regions, fdAT placement. */
internal object PngApngRules {
    private val IDS = listOf("png3.actl", "png3.sequence", "png3.fctl", "png3.fdat")

    private fun u32(b: ByteArray, i: Int): Long =
        ((b[i].toLong() and 0xFF) shl 24) or ((b[i + 1].toLong() and 0xFF) shl 16) or ((b[i + 2].toLong() and 0xFF) shl 8) or (b[i + 3].toLong() and 0xFF)

    fun evaluate(ctx: PngContext): List<ConformanceFinding> {
        val actl = ctx.first("acTL")
        val animation = ctx.complete.filter { it.type == "fcTL" || it.type == "fdAT" }
        if (actl == null) {
            return if (animation.isEmpty()) IDS.map { ctx.skip(it, NOT_APPLICABLE, "acTL 없음 — APNG 아님 / no acTL: not an animated PNG") }
            else IDS.map { ctx.skip(it, NOT_CHECKED, "acTL 없이 fcTL/fdAT ${animation.size}개 — APNG로 선언되지 않아 검사하지 않음 / ${animation.size} fcTL/fdAT without acTL: not declared animated, not checked") }
        }
        return listOf(actl(ctx, actl), sequence(ctx, animation), fctl(ctx), fdat(ctx))
    }

    private fun actl(ctx: PngContext, c: PngChunk): ConformanceFinding {
        if (c.length != 8L) return ctx.finding("png3.actl", listOf("${c.label}: 길이 ${c.length} ≠ 8 / length ${c.length} ≠ 8"), listOf(ctx.range(c)), emptyList())
        val b = ctx.data(c, 8)
        val frames = u32(b, 0)
        val plays = u32(b, 4)
        val fctls = ctx.of("fcTL").size
        val bad = ArrayList<String>()
        if (frames == 0L) bad += "num_frames = 0"
        else if (frames != fctls.toLong()) bad += "num_frames $frames ≠ fcTL ${fctls}개 / num_frames $frames ≠ $fctls fcTL chunks"
        return ctx.finding("png3.actl", bad.map { "${c.label}: $it" }, if (bad.isEmpty()) emptyList() else listOf(ctx.range(c)),
            listOf("num_frames $frames = fcTL ${fctls}개 / $fctls fcTL chunks, num_plays $plays"))
    }

    private fun sequence(ctx: PngContext, chunks: List<PngChunk>): ConformanceFinding {
        val bad = ArrayList<String>()
        val ranges = ArrayList<LongRange>()
        var expected = 0L
        for (c in chunks) {
            if (c.length < 4) continue // png3.fctl / png3.fdat report it
            val seq = u32(ctx.data(c, 4), 0)
            if (seq != expected) { bad += "${c.label}: sequence_number $seq ≠ 기대값 $expected / expected $expected"; ranges += ctx.range(c) }
            expected = seq + 1
        }
        if (chunks.firstOrNull()?.type == "fdAT") bad += "첫 애니메이션 청크가 fcTL이 아님 / the first animation chunk is fdAT, not fcTL"
        return ctx.finding("png3.sequence", bad, ranges, listOf("fcTL+fdAT ${chunks.size}개, 0부터 빈틈없이 증가 / ${chunks.size} fcTL+fdAT numbered 0.. without gaps"))
    }

    private fun fctl(ctx: PngContext): ConformanceFinding {
        val h = ctx.header ?: return ctx.skip("png3.fctl", NOT_CHECKED, "IHDR 무효 / IHDR invalid")
        val list = ctx.of("fcTL")
        if (list.isEmpty()) return ctx.finding("png3.fctl", listOf("acTL은 있으나 fcTL 없음 / acTL present but no fcTL"), emptyList(), emptyList())
        val bad = ArrayList<String>()
        val ranges = ArrayList<LongRange>()
        fun fail(c: PngChunk, t: String) { bad += "${c.label}: $t"; ranges += ctx.range(c) }
        val first = ctx.firstIdat
        val last = ctx.lastIdat
        val before = list.filter { first != null && it.index < first }
        if (before.size > 1) before.drop(1).forEach { fail(it, "IDAT 앞의 두 번째 fcTL / second fcTL before IDAT") }
        list.filter { first != null && last != null && it.index in first..last }.forEach { fail(it, "IDAT 사이의 fcTL / fcTL between IDAT chunks") }
        for (c in list) {
            if (c.length != 26L) { fail(c, "길이 ${c.length} ≠ 26 / length ${c.length} ≠ 26"); continue }
            val b = ctx.data(c, 26)
            val w = u32(b, 4); val hh = u32(b, 8); val x = u32(b, 12); val y = u32(b, 16)
            val dispose = b[24].toInt() and 0xFF
            val blend = b[25].toInt() and 0xFF
            if (w == 0L || hh == 0L) fail(c, "width/height ${w}x$hh (> 0)")
            if (x + w > h.width || y + hh > h.height) fail(c, "프레임 영역 ($x,$y) ${w}x${hh}가 IHDR ${h.width}x${h.height} 밖 / frame region ($x,$y) ${w}x$hh outside IHDR ${h.width}x${h.height}")
            if (dispose > 2) fail(c, "dispose_op $dispose (0..2)")
            if (blend > 1) fail(c, "blend_op $blend (0, 1)")
            if (c == before.firstOrNull() && (x != 0L || y != 0L || w != h.width || hh != h.height))
                fail(c, "기본 이미지 fcTL은 오프셋 0, IHDR 크기여야 함 / default-image fcTL must have offsets 0 and the IHDR size (got ($x,$y) ${w}x$hh)")
        }
        return ctx.finding("png3.fctl", bad, ranges, listOf("fcTL ${list.size}개(기본 이미지 포함 ${before.size}) / ${list.size} fcTL (${before.size} for the default image), all inside ${h.width}x${h.height}"))
    }

    private fun fdat(ctx: PngContext): ConformanceFinding {
        val bad = ArrayList<String>()
        val ranges = ArrayList<LongRange>()
        fun fail(c: PngChunk, t: String) { bad += "${c.label}: $t"; ranges += ctx.range(c) }
        val last = ctx.lastIdat
        val first = ctx.firstIdat
        ctx.of("fdAT").forEach { c ->
            if (c.length < 4) fail(c, "길이 ${c.length} < 4(시퀀스 번호) / length ${c.length} < 4 (sequence number)")
            if (last == null || c.index < last) fail(c, "IDAT 앞 / before IDAT")
        }
        // Frames: each fcTL up to the next fcTL; frames whose fcTL is after IDAT need at least one fdAT.
        val fctls = ctx.of("fcTL")
        fctls.forEachIndexed { i, f ->
            if (first != null && f.index < first) return@forEachIndexed
            val next = fctls.getOrNull(i + 1)?.index ?: Int.MAX_VALUE
            if (ctx.complete.none { it.type == "fdAT" && it.index > f.index && it.index < next }) fail(f, "이 프레임에 fdAT 없음 / no fdAT for this frame")
        }
        val orphan = ctx.of("fdAT").filter { d -> fctls.none { it.index < d.index } }
        orphan.forEach { fail(it, "앞선 fcTL 없음 / no preceding fcTL") }
        return ctx.finding("png3.fdat", bad, ranges, listOf("fdAT ${ctx.of("fdAT").size}개, 모두 IDAT 뒤, 프레임마다 1개 이상 / ${ctx.of("fdAT").size} fdAT, all after IDAT, at least one per non-default frame"))
    }
}
```

The final checker (the `val rules = …` line now has all three groups):

```kotlin
package com.multiviewer.parser.conformance.png

import com.multiviewer.parser.ByteReader
import com.multiviewer.parser.conformance.ConformanceFinding
import com.multiviewer.parser.conformance.ConformanceReport
import com.multiviewer.parser.conformance.ConformanceStatus.NOT_CHECKED
import com.multiviewer.parser.conformance.ImageRuleBook
import com.multiviewer.parser.conformance.trailingDataFinding
import java.util.concurrent.CancellationException

object PngConformanceChecker {
    const val PROFILE = "W3C PNG Third Edition (2025) + APNG + RFC 1950 zlib headers phase 1"

    val LIMITATIONS = listOf(
        "zlib/deflate 데이터는 해제하지 않습니다(CMF·FLG만 검사) — 필터 바이트, 압축 해제 길이, Adler-32, 팔레트 인덱스 범위, 압축 텍스트·ICC 내용은 디코딩 검증(ffmpeg) 몫이며 여기서는 PASS로 보지 않습니다 / " +
            "zlib/deflate data is not inflated (CMF/FLG only): filter bytes, decompressed length, Adler-32, palette index range and compressed text / ICC contents are left to the ffmpeg decode check and never counted as PASS here",
        "BCP 47 언어 태그, ICC 프로필·eXIf(Exif) 내용, 등록 확장 청크(oFFs, pCAL, sCAL 등)와 비공개 청크 내용, hIST 0 항목과 실제 픽셀 사용은 검사하지 않습니다 / " +
            "Not checked: BCP 47 language tags, ICC profile and eXIf (Exif) contents, registered extension chunks (oFFs, pCAL, sCAL …) and private chunk contents, hIST zero entries vs actual pixel use",
        "청크 ${PngChunkScanner.MAX_CHUNKS}개, 텍스트·sPLT 1 MiB까지 읽습니다 / Reads at most ${PngChunkScanner.MAX_CHUNKS} chunks and 1 MiB per text / sPLT chunk",
    )

    /** Rules whose PASS needs every chunk up to IEND. */
    private val CHUNK_RULES = PngRules.all.map { it.id }.toSet() - setOf("png3.signature", "png3.chunk.layout", "png3.ihdr", "png3.critical", "png3.trailing")

    private const val TRAILING_NOTE = "PNG-3 §15.3.1: IEND 뒤에는 어떤 청크나 데이터도 없어야 함 / no chunks or other content follow the IEND chunk"

    fun check(reader: ByteReader): ConformanceReport {
        val findings = ArrayList<ConformanceFinding>()
        val error = try {
            val ctx = PngContext(reader, PngChunkScanner.scan(reader))
            val rules = PngStructureRules.evaluate(ctx) + PngAncillaryRules.evaluate(ctx) + PngApngRules.evaluate(ctx)
            findings += if (ctx.scan.problem == null && ctx.scan.iend != null) rules
            else PngRules.book.partial(rules, CHUNK_RULES, "청크 열거가 IEND 전에 멈춤 — 읽은 청크만 검사됨 / chunk walk stopped before IEND: only the chunks read were checked")
            findings += if (ctx.scan.iend == null) ctx.skip("png3.trailing", NOT_CHECKED, "IEND 없음 — 판단 불가 / no IEND")
            else trailingDataFinding(PngRules.book, "png3.trailing", reader, ctx.scan.end, TRAILING_NOTE)
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ImageRuleBook.inspectionError(e)
        }
        return ConformanceReport("PNG", PROFILE, PngRules.book.sorted(findings), LIMITATIONS, error)
    }
}
```

- [ ] **Step 4:** `./gradlew :app:test --tests 'com.multiviewer.parser.conformance.png.*'` → GREEN. **Step 5:** commit
  `feat: check APNG acTL, sequence numbers, fcTL and fdAT`.

---

### Task 6: WebP RIFF container rules and the checker

**Files:** Create `parser/conformance/webp/WebpChunks.kt`, `WebpContainerRules.kt`, `WebpConformanceChecker.kt`; tests
`webp/SyntheticWebp.kt` (shared with Task 7), `webp/WebpContainerRulesTest.kt`.

- [ ] **Step 1: Failing tests.**

```kotlin
package com.multiviewer.parser.conformance.webp

import com.multiviewer.parser.byteReaderOf
import com.multiviewer.parser.conformance.ConformanceReport

/** Minimal WebP writer for rule tests (headers only; the bitstreams are not decodable). */
internal object SyntheticWebp {
    fun le16(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte())
    fun le24(v: Long) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte())
    fun le32(v: Long) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())

    fun chunk(fourcc: String, data: ByteArray, pad: Int = 0): ByteArray =
        fourcc.toByteArray(Charsets.ISO_8859_1) + le32(data.size.toLong()) + data + (if (data.size % 2 == 1) byteArrayOf(pad.toByte()) else ByteArray(0))

    fun riff(vararg chunks: ByteArray, sizeDelta: Long = 0): ByteArray {
        val body = "WEBP".toByteArray() + chunks.fold(ByteArray(0)) { a, c -> a + c }
        return "RIFF".toByteArray() + le32(body.size + sizeDelta) + body
    }

    /** 'VP8 ' key frame header for [w]x[h] + 2 filler bytes (first_part_size 2). */
    fun vp8(w: Int = 4, h: Int = 4, tag: Int = (2 shl 5) or (1 shl 4)): ByteArray =
        chunk("VP8 ", le24(tag.toLong()) + byteArrayOf(0x9D.toByte(), 0x01, 0x2A) + le16(w) + le16(h) + ByteArray(2))

    /** 'VP8L' header for [w]x[h] (version [version]). */
    fun vp8l(w: Int = 4, h: Int = 4, alpha: Int = 0, version: Int = 0): ByteArray {
        val bits = (w - 1).toLong() or ((h - 1).toLong() shl 14) or (alpha.toLong() shl 28) or (version.toLong() shl 29)
        return chunk("VP8L", byteArrayOf(0x2F) + le32(bits) + ByteArray(1))
    }

    fun vp8x(flags: Int, w: Long = 4, h: Long = 4, reserved: Long = 0) = chunk("VP8X", byteArrayOf(flags.toByte()) + le24(reserved) + le24(w - 1) + le24(h - 1))
    fun alph(header: Int = 0x01, data: ByteArray = ByteArray(4)) = chunk("ALPH", byteArrayOf(header.toByte()) + data)
    fun anim() = chunk("ANIM", le32(0) + le16(0))
    fun anmf(x: Long = 0, y: Long = 0, w: Long = 4, h: Long = 4, flags: Int = 0, vararg sub: ByteArray) =
        chunk("ANMF", le24(x / 2) + le24(y / 2) + le24(w - 1) + le24(h - 1) + le24(100) + byteArrayOf(flags.toByte()) + sub.fold(ByteArray(0)) { a, c -> a + c })

    const val I = 0x20; const val L = 0x10; const val E = 0x08; const val X = 0x04; const val A = 0x02

    fun check(bytes: ByteArray): ConformanceReport = byteReaderOf(bytes).use { WebpConformanceChecker.check(it) }
}
```

```kotlin
package com.multiviewer.parser.conformance.webp

import com.multiviewer.parser.conformance.ConformanceStatus.FAIL
import com.multiviewer.parser.conformance.ConformanceStatus.NOT_APPLICABLE
import com.multiviewer.parser.conformance.ConformanceStatus.NOT_CHECKED
import com.multiviewer.parser.conformance.ConformanceStatus.PASS
import com.multiviewer.parser.conformance.ConformanceStatus.WARN
import com.multiviewer.parser.conformance.idsWith
import com.multiviewer.parser.conformance.notPassing
import com.multiviewer.parser.conformance.statusOf
import com.multiviewer.parser.conformance.webp.SyntheticWebp.A
import com.multiviewer.parser.conformance.webp.SyntheticWebp.E
import com.multiviewer.parser.conformance.webp.SyntheticWebp.L
import com.multiviewer.parser.conformance.webp.SyntheticWebp.X
import com.multiviewer.parser.conformance.webp.SyntheticWebp.alph
import com.multiviewer.parser.conformance.webp.SyntheticWebp.anim
import com.multiviewer.parser.conformance.webp.SyntheticWebp.anmf
import com.multiviewer.parser.conformance.webp.SyntheticWebp.check
import com.multiviewer.parser.conformance.webp.SyntheticWebp.chunk
import com.multiviewer.parser.conformance.webp.SyntheticWebp.riff
import com.multiviewer.parser.conformance.webp.SyntheticWebp.vp8
import com.multiviewer.parser.conformance.webp.SyntheticWebp.vp8l
import com.multiviewer.parser.conformance.webp.SyntheticWebp.vp8x
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WebpContainerRulesTest {
    private fun failsOnly(bytes: ByteArray, vararg ids: String) {
        val r = check(bytes)
        assertNull(r.inspectionError)
        assertEquals(ids.toSet(), r.idsWith(FAIL, WARN), r.notPassing().toString())
    }

    @Test fun `simple lossy, simple lossless, extended still and animated files pass`() {
        listOf(riff(vp8()), riff(vp8l()), riff(vp8x(L), alph(), vp8()),
            riff(vp8x(E or X), vp8l(), chunk("EXIF", ByteArray(8)), chunk("XMP ", ByteArray(3))),
            riff(vp8x(A or L), anim(), anmf(0, 0, 4, 4, 0, vp8l()), anmf(2, 2, 2, 2, 0, alph(data = ByteArray(2)), vp8(2, 2)))).forEach { b ->
            val r = check(b)
            assertEquals(emptySet(), r.idsWith(FAIL, WARN, NOT_CHECKED), r.notPassing().toString())
            assertEquals(PASS, r.overall)
        }
        assertEquals(NOT_APPLICABLE, check(riff(vp8())).statusOf("rfc9649.vp8x"))
    }

    @Test fun `RIFF size, chunk extent and padding`() {
        // File Size 2 too small: the chunk runs past it, and its last 2 bytes are "after the RIFF data"
        failsOnly(riff(vp8(), sizeDelta = -2), "rfc9649.riff.size", "rfc9649.chunk.layout", "rfc9649.trailing")
        failsOnly(riff(vp8(), sizeDelta = 1), "rfc9649.riff.size")                          // odd and past EOF
        // ALPH payload 3 bytes (odd): its pad byte at 12 (header) + 18 (VP8X) + 8 + 3 must be 0
        failsOnly(riff(vp8x(L), alph(data = ByteArray(2)), vp8()).also { it[12 + 18 + 8 + 3] = 7 }, "rfc9649.chunk.layout")
        val trailing = check(riff(vp8()) + ByteArray(3))
        assertEquals(setOf("rfc9649.trailing"), trailing.idsWith(FAIL, WARN))
        assertEquals(WARN, trailing.overall)
    }

    @Test fun `layout, order and VP8X fields`() {
        failsOnly(riff(chunk("ICCP", ByteArray(2)), vp8()), "rfc9649.layout")
        failsOnly(riff(vp8x(0), vp8(), vp8()), "rfc9649.layout")
        failsOnly(riff(vp8x(A), anim(), anmf(0, 0, 4, 4, 0, vp8()), vp8()), "rfc9649.layout")
        failsOnly(riff(vp8x(SyntheticWebp.I), vp8(), chunk("ICCP", ByteArray(2))), "rfc9649.order")
        failsOnly(riff(vp8x(A), anmf(0, 0, 4, 4, 0, vp8()), anim()), "rfc9649.order")
        failsOnly(riff(vp8x(0x40), vp8()), "rfc9649.vp8x")                                  // Rsv bit
        failsOnly(riff(vp8x(0, reserved = 1), vp8()), "rfc9649.vp8x")
    }

    @Test fun `VP8X flags must match the chunks`() {
        failsOnly(riff(vp8x(E), vp8()), "rfc9649.vp8x.flags")
        failsOnly(riff(vp8x(0), vp8(), chunk("XMP ", ByteArray(2))), "rfc9649.vp8x.flags")
        failsOnly(riff(vp8x(L), vp8()), "rfc9649.vp8x.flags")                               // alpha flag, no transparency source
        failsOnly(riff(vp8x(A), anmf(0, 0, 4, 4, 0, vp8())), "rfc9649.vp8x.flags")           // animation flag without ANIM
        assertEquals(PASS, check(riff(vp8x(L), vp8l())).statusOf("rfc9649.vp8x.flags"))     // VP8L may carry alpha
    }

    @Test fun `truncated data never reports PASS for rules that need every chunk`() {
        val full = riff(vp8x(L), alph(), vp8())
        val r = check(full.copyOf(full.size - 4))
        assertEquals(setOf("rfc9649.riff.size", "rfc9649.chunk.layout"), r.idsWith(FAIL, WARN), r.notPassing().toString())
        assertEquals(NOT_CHECKED, r.statusOf("rfc9649.vp8x.flags"))
    }
}
```

- [ ] **Step 2:** `./gradlew :app:test --tests 'com.multiviewer.parser.conformance.webp.*'` → RED (unresolved
  `WebpConformanceChecker`).
- [ ] **Step 3: Implement.**

```kotlin
package com.multiviewer.parser.conformance.webp

import com.multiviewer.parser.ByteReader
import com.multiviewer.parser.conformance.ConformanceFinding
import com.multiviewer.parser.conformance.ConformanceStatus
import com.multiviewer.parser.conformance.ImageRuleBook.Companion.hex

/** A RIFF chunk; [paddedEnd] includes the pad byte of an odd [size]; [complete] = payload inside the parent. */
internal data class RiffChunk(val fourcc: String, val offset: Long, val size: Long, val complete: Boolean) {
    val payload: Long get() = offset + 8
    val paddedEnd: Long get() = offset + 8 + size + (size and 1)
    val label: String get() = "'$fourcc' @${hex(offset)}"
}

/** One 'ANMF' chunk with its 16-byte header fields and its Frame Data sub-chunks. */
internal data class AnmfFrame(val chunk: RiffChunk, val x: Long, val y: Long, val width: Long, val height: Long, val flags: Int, val subchunks: List<RiffChunk>)

/** Problems of a chunk list: chunk past its parent, a non-zero or missing pad byte, bytes too short for a chunk header. */
internal class RiffWalk(val chunks: List<RiffChunk>, val problems: List<Pair<String, LongRange>>, val end: Long, val capped: Boolean = false)

internal object WebpChunkScanner {
    const val MAX_CHUNKS = 100_000

    fun walk(reader: ByteReader, start: Long, limit: Long): RiffWalk {
        val chunks = ArrayList<RiffChunk>()
        val problems = ArrayList<Pair<String, LongRange>>()
        var pos = start
        while (pos < limit && chunks.size < MAX_CHUNKS) {
            if (limit - pos < 8) { problems += "${hex(pos)}의 ${limit - pos}바이트는 청크 헤더(8바이트)가 될 수 없음 / ${limit - pos} byte(s) at ${hex(pos)} are too short for a chunk header" to (pos until limit); pos = limit; break }
            val fourcc = reader.readFourCC(pos)
            val size = reader.readBytes(pos + 4, 4).let { b -> (0..3).fold(0L) { acc, i -> acc or ((b[i].toLong() and 0xFF) shl (8 * i)) } }
            if (size > limit - pos - 8) {
                chunks += RiffChunk(fourcc, pos, size, complete = false)
                problems += "'$fourcc' @${hex(pos)} 크기 $size 이(가) 상위 범위(${hex(limit)})를 넘음 / size $size runs past the enclosing range end ${hex(limit)}" to (pos until limit)
                pos = limit; break
            }
            val c = RiffChunk(fourcc, pos, size, complete = true)
            chunks += c
            if (size % 2 == 1L) {
                val padAt = pos + 8 + size
                if (padAt >= limit) problems += "${c.label}: 홀수 크기 패딩 바이트 없음 / odd size without its padding byte" to (pos until limit)
                else if (reader.readUInt8(padAt) != 0) problems += "${c.label}: 패딩 바이트 0x%02X ≠ 0 / padding byte 0x%02X ≠ 0".format(reader.readUInt8(padAt), reader.readUInt8(padAt)) to (padAt..padAt)
            }
            pos = minOf(c.paddedEnd, limit)
        }
        return RiffWalk(chunks, problems, pos, capped = pos < limit && chunks.size >= MAX_CHUNKS)
    }
}

/** VP8X fields (payload ≥ 10). */
internal data class Vp8x(val flagsByte: Int, val reserved24: Int, val canvasWidth: Long, val canvasHeight: Long) {
    val icc get() = flagsByte and 0x20 != 0
    val alpha get() = flagsByte and 0x10 != 0
    val exif get() = flagsByte and 0x08 != 0
    val xmp get() = flagsByte and 0x04 != 0
    val animation get() = flagsByte and 0x02 != 0
}

/** Bitstream header facts of one 'VP8 ' or 'VP8L' chunk; null fields = not readable. */
internal data class Bitstream(val chunk: RiffChunk, val width: Int?, val height: Int?)

internal class WebpContext(val reader: ByteReader) {
    val length: Long = reader.length
    val book = WebpRules.book
    val headerOk: Boolean = length >= 12 && reader.readFourCC(0) == "RIFF" && reader.readFourCC(8) == "WEBP"
    val riffSize: Long? = if (length >= 8) reader.readBytes(4, 4).let { b -> (0..3).fold(0L) { acc, i -> acc or ((b[i].toLong() and 0xFF) shl (8 * i)) } } else null
    val riffEnd: Long = riffSize?.let { 8 + it } ?: length
    val top: RiffWalk = if (headerOk) WebpChunkScanner.walk(reader, 12, minOf(riffEnd, length)) else RiffWalk(emptyList(), emptyList(), 12)
    val chunks: List<RiffChunk> get() = top.chunks

    fun of(fourcc: String): List<RiffChunk> = chunks.filter { it.fourcc == fourcc && it.complete }

    val vp8x: Vp8x? by lazy {
        val c = chunks.firstOrNull()?.takeIf { it.fourcc == "VP8X" && it.complete && it.size >= 10 } ?: return@lazy null
        val b = reader.readBytes(c.payload, 10)
        fun u24(i: Int) = (b[i].toLong() and 0xFF) or ((b[i + 1].toLong() and 0xFF) shl 8) or ((b[i + 2].toLong() and 0xFF) shl 16)
        Vp8x(b[0].toInt() and 0xFF, u24(1).toInt(), u24(4) + 1, u24(7) + 1)
    }

    val frames: List<AnmfFrame> by lazy {
        of("ANMF").filter { it.size >= 16 }.map { c ->
            val b = reader.readBytes(c.payload, 16)
            fun u24(i: Int) = (b[i].toLong() and 0xFF) or ((b[i + 1].toLong() and 0xFF) shl 8) or ((b[i + 2].toLong() and 0xFF) shl 16)
            val sub = WebpChunkScanner.walk(reader, c.payload + 16, c.payload + c.size)
            AnmfFrame(c, u24(0) * 2, u24(3) * 2, u24(6) + 1, u24(9) + 1, b[15].toInt() and 0xFF, sub.chunks)
        }
    }

    /** Sub-chunk problems inside every ANMF Frame Data. */
    val frameProblems: List<Pair<String, LongRange>> by lazy {
        of("ANMF").filter { it.size >= 16 }.flatMap { WebpChunkScanner.walk(reader, it.payload + 16, it.payload + it.size).problems }
    }

    /** Every complete chunk, top-level and inside ANMF frames. */
    val allChunks: List<RiffChunk> by lazy { chunks.filter { it.complete } + frames.flatMap { f -> f.subchunks.filter { it.complete } } }

    fun bitstream(c: RiffChunk): Bitstream = when (c.fourcc) {
        "VP8 " -> if (c.size < 10) Bitstream(c, null, null) else reader.readBytes(c.payload, 10).let { b ->
            Bitstream(c, ((b[6].toInt() and 0xFF) or ((b[7].toInt() and 0xFF) shl 8)) and 0x3FFF, ((b[8].toInt() and 0xFF) or ((b[9].toInt() and 0xFF) shl 8)) and 0x3FFF)
        }
        else -> if (c.size < 5) Bitstream(c, null, null) else reader.readBytes(c.payload, 5).let { b ->
            val bits = (b[1].toLong() and 0xFF) or ((b[2].toLong() and 0xFF) shl 8) or ((b[3].toLong() and 0xFF) shl 16) or ((b[4].toLong() and 0xFF) shl 24)
            Bitstream(c, (bits and 0x3FFF).toInt() + 1, ((bits shr 14) and 0x3FFF).toInt() + 1)
        }
    }

    fun finding(id: String, violations: List<String>, ranges: List<LongRange>, pass: List<String>): ConformanceFinding = book.finding(id, violations, ranges, pass)
    fun skip(id: String, status: ConformanceStatus, reason: String): ConformanceFinding = book.skip(id, status, reason)
    fun range(c: RiffChunk): LongRange = c.offset until minOf(c.paddedEnd, length)

    companion object {
        val BITSTREAMS = setOf("VP8 ", "VP8L")
    }
}
```

```kotlin
package com.multiviewer.parser.conformance.webp

import com.multiviewer.parser.conformance.ConformanceFinding
import com.multiviewer.parser.conformance.ConformanceStatus.NOT_APPLICABLE
import com.multiviewer.parser.conformance.ConformanceStatus.NOT_CHECKED
import com.multiviewer.parser.conformance.ImageRuleBook.Companion.hex
import com.multiviewer.parser.conformance.webp.WebpContext.Companion.BITSTREAMS

/** RFC 9649 §2.3-2.7: RIFF header and size, chunk padding, simple / extended layout, order, VP8X fields and flags. */
internal object WebpContainerRules {
    /** Rank of the chunks "necessary for reconstruction and color correction" (§2.7); image data = 3. */
    private val RANK = mapOf("VP8X" to 0, "ICCP" to 1, "ANIM" to 2, "ANMF" to 3, "ALPH" to 3, "VP8 " to 3, "VP8L" to 3)
    const val MAX_FILE_SIZE = 0xFFFF_FFFFL - 9 // 2^32 - 10

    fun evaluate(ctx: WebpContext): List<ConformanceFinding> =
        listOf(header(ctx), riffSize(ctx), layout(ctx), fileLayout(ctx), order(ctx), vp8x(ctx), flags(ctx), metadataCount(ctx))

    private fun header(ctx: WebpContext): ConformanceFinding =
        if (ctx.headerOk) ctx.finding("rfc9649.header", emptyList(), emptyList(), listOf("'RIFF' + File Size + 'WEBP'"))
        else ctx.finding("rfc9649.header", listOf("'RIFF'…'WEBP' 헤더가 아님 / not a 'RIFF' … 'WEBP' header"), listOf(0L until minOf(12L, ctx.length)), emptyList())

    private fun riffSize(ctx: WebpContext): ConformanceFinding {
        val size = ctx.riffSize ?: return ctx.finding("rfc9649.riff.size", listOf("File Size 필드 없음 / no File Size field"), emptyList(), emptyList())
        if (!ctx.headerOk) return ctx.skip("rfc9649.riff.size", NOT_CHECKED, "RIFF/WEBP 헤더 아님 / not a RIFF/WEBP header")
        val bad = ArrayList<String>()
        if (size > MAX_FILE_SIZE) bad += "File Size $size > 2^32-10"
        if (size % 2 == 1L) bad += "File Size $size 홀수(청크 크기 합은 짝수) / File Size $size is odd (padded chunks always sum to an even size)"
        if (ctx.riffEnd > ctx.length) bad += "File Size가 ${ctx.riffEnd}바이트를 선언하나 파일은 ${ctx.length}바이트(잘림) / File Size declares ${ctx.riffEnd} bytes but the file has ${ctx.length} (truncated)"
        else if (ctx.chunks.any { !it.complete }) bad += "청크가 File Size 범위(${hex(ctx.riffEnd)})를 넘음 / a chunk runs past the File Size range end ${hex(ctx.riffEnd)}"
        else if (ctx.top.end != ctx.riffEnd) bad += "청크가 RIFF 범위를 정확히 채우지 않음(${hex(ctx.top.end)} ≠ ${hex(ctx.riffEnd)}) / chunks do not tile the RIFF payload exactly"
        return ctx.finding("rfc9649.riff.size", bad, if (bad.isEmpty()) emptyList() else listOf(0L until 8L),
            listOf("File Size $size = 'WEBP' 4 + 청크 ${ctx.chunks.size}개 / 4 + ${ctx.chunks.size} padded chunks"))
    }

    private fun layout(ctx: WebpContext): ConformanceFinding {
        if (!ctx.headerOk) return ctx.skip("rfc9649.chunk.layout", NOT_CHECKED, "RIFF/WEBP 헤더 아님 / not a RIFF/WEBP header")
        val problems = ctx.top.problems + ctx.frameProblems
        return ctx.finding("rfc9649.chunk.layout", problems.map { it.first }, problems.map { it.second },
            listOf("청크 ${ctx.allChunks.size}개(ANMF 내부 포함), 경계·패딩 정상 / ${ctx.allChunks.size} chunks (incl. ANMF frame data) inside their parents, pad bytes zero"))
    }

    private fun fileLayout(ctx: WebpContext): ConformanceFinding {
        val first = ctx.chunks.firstOrNull() ?: return ctx.finding("rfc9649.layout", listOf("청크 없음 / no chunk"), emptyList(), emptyList())
        val names = ctx.chunks.joinToString(", ") { it.fourcc.trim() }
        val bad = ArrayList<String>()
        when (first.fourcc) {
            "VP8 ", "VP8L" -> if (ctx.chunks.size > 1)
                bad += "단순 형식(${first.fourcc.trim()})인데 청크가 더 있음 / simple format (${first.fourcc.trim()}) followed by more chunks: $names"
            "VP8X" -> {
                val x = ctx.vp8x
                val top = ctx.chunks.filter { it.fourcc in BITSTREAMS }
                val anmf = ctx.chunks.filter { it.fourcc == "ANMF" }
                if (x != null && x.animation) {
                    if (anmf.isEmpty()) bad += "애니메이션 플래그인데 ANMF 없음 / Animation flag set but no ANMF chunk"
                    (top + ctx.chunks.filter { it.fourcc == "ALPH" }).forEach { bad += "애니메이션 파일의 최상위 ${it.label} / top-level ${it.label} in an animated file" }
                } else {
                    if (top.size != 1) bad += "정지 영상 비트스트림 청크 ${top.size}개(1개여야 함) / still image has ${top.size} bitstream chunks (exactly one required)"
                    if (ctx.chunks.count { it.fourcc == "ALPH" } > 1) bad += "ALPH 2개 이상 / more than one ALPH"
                }
            }
            else -> bad += "첫 청크 ${first.label}: 'VP8 ', 'VP8L', 'VP8X' 중 하나여야 함 / first chunk must be 'VP8 ', 'VP8L' or 'VP8X'"
        }
        return ctx.finding("rfc9649.layout", bad, if (bad.isEmpty()) emptyList() else listOf(12L until minOf(ctx.riffEnd, ctx.length)), listOf(
            when (first.fourcc) { "VP8X" -> "확장 형식 / extended format: $names"; else -> "단순 형식 / simple format: $names" }))
    }

    private fun order(ctx: WebpContext): ConformanceFinding {
        if (ctx.chunks.firstOrNull()?.fourcc != "VP8X") return ctx.skip("rfc9649.order", NOT_APPLICABLE, "확장 형식 아님 / not the extended format")
        val bad = ArrayList<String>()
        val ranges = ArrayList<LongRange>()
        var rank = 0
        var bitstreamSeen = false
        for (c in ctx.chunks) {
            val r = RANK[c.fourcc] ?: continue
            if (c.fourcc == "VP8X" && c != ctx.chunks.first()) { bad += "${c.label}: 두 번째 VP8X / second VP8X"; ranges += ctx.range(c) }
            if (r < rank) { bad += "${c.label}: 이미지 데이터 또는 상위 청크 뒤 / after image data or a later reconstruction chunk"; ranges += ctx.range(c) }
            if (c.fourcc == "ALPH" && bitstreamSeen) { bad += "${c.label}: 비트스트림 뒤의 ALPH / ALPH after the bitstream chunk"; ranges += ctx.range(c) }
            if (c.fourcc in BITSTREAMS) bitstreamSeen = true
            rank = maxOf(rank, r)
        }
        return ctx.finding("rfc9649.order", bad, ranges, listOf("VP8X → ICCP → ANIM → 이미지 데이터 순서 / VP8X, ICCP, ANIM, image data in order"))
    }

    private fun vp8x(ctx: WebpContext): ConformanceFinding {
        val c = ctx.chunks.firstOrNull()?.takeIf { it.fourcc == "VP8X" } ?: return ctx.skip("rfc9649.vp8x", NOT_APPLICABLE, "VP8X 없음 / no VP8X chunk")
        if (c.size < 10) return ctx.finding("rfc9649.vp8x", listOf("${c.label}: 크기 ${c.size} < 10 / size ${c.size} < 10"), listOf(ctx.range(c)), emptyList())
        val x = ctx.vp8x!!
        val bad = ArrayList<String>()
        if (x.flagsByte and 0xC1 != 0) bad += "예약 비트(Rsv, R) ≠ 0: 플래그 바이트 0x%02X / reserved bits set in flags byte 0x%02X".format(x.flagsByte, x.flagsByte)
        if (x.reserved24 != 0) bad += "예약 24비트 ≠ 0 / reserved 24 bits = 0x%06X".format(x.reserved24)
        if (x.canvasWidth * x.canvasHeight > 0xFFFF_FFFFL) bad += "캔버스 ${x.canvasWidth}x${x.canvasHeight} > 2^32-1 픽셀 / canvas exceeds 2^32-1 pixels"
        return ctx.finding("rfc9649.vp8x", bad.map { "${c.label}: $it" }, if (bad.isEmpty()) emptyList() else listOf(ctx.range(c)),
            listOf("캔버스 / canvas ${x.canvasWidth}x${x.canvasHeight}, 플래그 / flags 0x%02X".format(x.flagsByte)))
    }

    private fun flags(ctx: WebpContext): ConformanceFinding {
        val x = ctx.vp8x ?: return ctx.skip("rfc9649.vp8x.flags", NOT_APPLICABLE, "VP8X 없음 / no VP8X chunk")
        val bad = ArrayList<String>()
        fun pair(flag: Boolean, name: String, fourcc: String) {
            val present = ctx.of(fourcc).isNotEmpty()
            if (flag && !present) bad += "$name 플래그인데 '${fourcc.trim()}' 청크 없음 / $name flag set but no '${fourcc.trim()}' chunk"
            if (!flag && present) bad += "'${fourcc.trim()}' 청크가 있는데 $name 플래그 없음 / '${fourcc.trim()}' chunk present but the $name flag is not set"
        }
        pair(x.icc, "ICC profile (I)", "ICCP")
        pair(x.exif, "Exif metadata (E)", "EXIF")
        pair(x.xmp, "XMP metadata (X)", "XMP ")
        if (x.animation && ctx.of("ANIM").isEmpty()) bad += "Animation (A) 플래그인데 ANIM 없음 / Animation flag set but no ANIM chunk"
        val hasAlph = ctx.allChunks.any { it.fourcc == "ALPH" }
        val hasVp8l = ctx.allChunks.any { it.fourcc == "VP8L" }
        if (hasAlph && !x.alpha) bad += "ALPH 청크가 있는데 Alpha (L) 플래그 없음 / ALPH chunk present but the Alpha (L) flag is not set"
        if (x.alpha && !hasAlph && !hasVp8l) bad += "Alpha (L) 플래그인데 투명도 정보가 있는 프레임 없음(ALPH·VP8L 없음) / Alpha flag set but no frame carries transparency (no ALPH, no VP8L)"
        return ctx.finding("rfc9649.vp8x.flags", bad, if (bad.isEmpty()) emptyList() else listOf(ctx.range(ctx.chunks.first())),
            listOf("I=${x.icc.b} L=${x.alpha.b} E=${x.exif.b} X=${x.xmp.b} A=${x.animation.b}, 청크와 일치 / consistent with the chunks present"))
    }

    private val Boolean.b get() = if (this) 1 else 0

    private fun metadataCount(ctx: WebpContext): ConformanceFinding {
        val bad = listOf("ICCP", "EXIF", "XMP ").mapNotNull { f -> ctx.of(f).size.takeIf { it > 1 }?.let { "'${f.trim()}' ${it}개 / $it '${f.trim()}' chunks" } }
        return ctx.finding("rfc9649.metadata.count", bad, ctx.chunks.filter { it.fourcc in setOf("ICCP", "EXIF", "XMP ") }.map { ctx.range(it) }.takeIf { bad.isNotEmpty() } ?: emptyList(),
            listOf("ICCP ${ctx.of("ICCP").size}, EXIF ${ctx.of("EXIF").size}, XMP ${ctx.of("XMP ").size} (각 1개 이하 / at most one each)"))
    }
}
```

Checker (Task 7 extends the `val rules = …` line):

```kotlin
package com.multiviewer.parser.conformance.webp

import com.multiviewer.parser.ByteReader
import com.multiviewer.parser.conformance.ConformanceFinding
import com.multiviewer.parser.conformance.ConformanceReport
import com.multiviewer.parser.conformance.ConformanceStatus.NOT_CHECKED
import com.multiviewer.parser.conformance.ImageRuleBook
import com.multiviewer.parser.conformance.trailingDataFinding
import java.util.concurrent.CancellationException

object WebpConformanceChecker {
    const val PROFILE = "RFC 9649 (WebP container, VP8L header) + RFC 6386 §9.1 (VP8 frame tag) phase 1"

    val LIMITATIONS = listOf(
        "VP8·VP8L 엔트로피 데이터와 ALPH 무손실 스트림은 해독하지 않습니다(헤더만) — 픽셀 디코딩은 디코딩 검증(ffmpeg) 몫이며 여기서는 PASS로 보지 않습니다 / " +
            "VP8 / VP8L entropy-coded data and ALPH lossless streams are not decoded (headers only); pixel decoding is left to the ffmpeg decode check and never counted as PASS here",
        "ICCP·EXIF·XMP 내용, 알 수 없는 청크 내용, VP8 show_frame·스케일 비트, ALPH 전처리 비트는 검사하지 않습니다 / " +
            "Not checked: ICCP, EXIF and XMP contents, unknown chunk contents, VP8 show_frame and scaling bits, ALPH preprocessing bits",
    )

    /** Rules whose PASS needs every chunk of the RIFF payload. */
    private val CHUNK_RULES = WebpRules.all.map { it.id }.toSet() - setOf("rfc9649.header", "rfc9649.riff.size", "rfc9649.chunk.layout", "rfc9649.trailing")

    private const val TRAILING_NOTE = "RFC 9649 §2.4: File Size 뒤에 데이터가 없어야 함(SHOULD NOT) / the file SHOULD NOT contain data after the data specified by File Size"

    fun check(reader: ByteReader): ConformanceReport {
        val findings = ArrayList<ConformanceFinding>()
        val error = try {
            val ctx = WebpContext(reader)
            val rules = WebpContainerRules.evaluate(ctx)
            val truncated = ctx.riffEnd > ctx.length || ctx.chunks.any { !it.complete } || ctx.top.capped
            findings += if (!truncated) rules
            else WebpRules.book.partial(rules, CHUNK_RULES, "RIFF 데이터가 잘림 — 읽은 청크만 검사됨 / RIFF data truncated: only the chunks read were checked")
            findings += if (!ctx.headerOk) ctx.skip("rfc9649.trailing", NOT_CHECKED, "RIFF/WEBP 헤더 아님 / not a RIFF/WEBP header")
            else trailingDataFinding(WebpRules.book, "rfc9649.trailing", reader, ctx.riffEnd, TRAILING_NOTE)
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ImageRuleBook.inspectionError(e)
        }
        return ConformanceReport("WebP", PROFILE, WebpRules.book.sorted(findings), LIMITATIONS, error)
    }
}
```

- [ ] **Step 4:** `./gradlew :app:test --tests 'com.multiviewer.parser.conformance.webp.*'` → GREEN. **Step 5:** commit
  `feat: check WebP RIFF size, padding, layout, order and VP8X`.

---

### Task 7: WebP bitstream headers, ANIM/ANMF/ALPH and the WebP fixture test

**Files:** Create `parser/conformance/webp/WebpBitstreamRules.kt`; modify `WebpConformanceChecker.kt`; tests
`webp/WebpBitstreamRulesTest.kt`, `webp/WebpFixtureTest.kt`.

- [ ] **Step 1: Failing tests.**

```kotlin
package com.multiviewer.parser.conformance.webp

import com.multiviewer.parser.conformance.ConformanceStatus.FAIL
import com.multiviewer.parser.conformance.ConformanceStatus.NOT_CHECKED
import com.multiviewer.parser.conformance.ConformanceStatus.PASS
import com.multiviewer.parser.conformance.ConformanceStatus.WARN
import com.multiviewer.parser.conformance.idsWith
import com.multiviewer.parser.conformance.notPassing
import com.multiviewer.parser.conformance.statusOf
import com.multiviewer.parser.conformance.webp.SyntheticWebp.A
import com.multiviewer.parser.conformance.webp.SyntheticWebp.L
import com.multiviewer.parser.conformance.webp.SyntheticWebp.alph
import com.multiviewer.parser.conformance.webp.SyntheticWebp.anim
import com.multiviewer.parser.conformance.webp.SyntheticWebp.anmf
import com.multiviewer.parser.conformance.webp.SyntheticWebp.check
import com.multiviewer.parser.conformance.webp.SyntheticWebp.chunk
import com.multiviewer.parser.conformance.webp.SyntheticWebp.riff
import com.multiviewer.parser.conformance.webp.SyntheticWebp.vp8
import com.multiviewer.parser.conformance.webp.SyntheticWebp.vp8l
import com.multiviewer.parser.conformance.webp.SyntheticWebp.vp8x
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WebpBitstreamRulesTest {
    private fun failsOnly(bytes: ByteArray, vararg ids: String) {
        val r = check(bytes)
        assertNull(r.inspectionError)
        assertEquals(ids.toSet(), r.idsWith(FAIL, WARN), r.notPassing().toString())
    }

    @Test fun `ANIM, ANMF and ALPH`() {
        failsOnly(riff(vp8x(A), chunk("ANIM", ByteArray(4)), anmf(0, 0, 4, 4, 0, vp8())), "rfc9649.anim")
        failsOnly(riff(vp8x(A), anim(), anmf(0, 0, 4, 4, 0x04, vp8())), "rfc9649.anim")       // reserved bit
        failsOnly(riff(vp8x(A), anim(), anmf(0, 0, 4, 4, 0, vp8(), vp8())), "rfc9649.anim")   // two bitstreams
        failsOnly(riff(vp8x(A or L), anim(), anmf(0, 0, 4, 4, 0, vp8(), alph())), "rfc9649.anim") // ALPH after bitstream
        failsOnly(riff(vp8x(L), alph(header = 0x02), vp8()), "rfc9649.alph")                 // compression method 2
        failsOnly(riff(vp8x(L), alph(header = 0x00, data = ByteArray(15)), vp8()), "rfc9649.alph") // raw alpha must be 4x4 = 16 bytes
        assertEquals(PASS, check(riff(vp8x(L), alph(header = 0x00, data = ByteArray(16)), vp8())).statusOf("rfc9649.alph"))
        failsOnly(riff(vp8x(L), alph(), vp8l()), "rfc9649.alph.vp8l")
    }

    @Test fun `bitstream headers and canvas size`() {
        failsOnly(riff(vp8(tag = 1 or (2 shl 5))), "rfc6386.frame")                           // interframe
        failsOnly(riff(vp8(tag = (4 shl 1) or (2 shl 5))), "rfc6386.frame")                    // version 4
        failsOnly(riff(vp8(tag = 3 shl 5)), "rfc6386.frame")                                   // first_part_size 3 > 2
        failsOnly(riff(vp8l(version = 1)), "rfc9649.vp8l")
        failsOnly(riff(chunk("VP8L", byteArrayOf(0x2E, 0, 0, 0, 0, 0))), "rfc9649.vp8l")
        failsOnly(riff(vp8x(0, w = 5), vp8()), "rfc9649.canvas")
        failsOnly(riff(vp8x(0, w = 0x10000, h = 0x10000), vp8()), "rfc9649.vp8x", "rfc9649.canvas")
        failsOnly(riff(vp8x(A), anim(), anmf(0, 0, 4, 4, 0, vp8(2, 2))), "rfc9649.canvas")
    }

    @Test fun `truncated data leaves the bitstream rules NOT_CHECKED`() {
        val full = riff(vp8x(L), alph(), vp8())
        val r = check(full.copyOf(full.size - 4))
        assertEquals(NOT_CHECKED, r.statusOf("rfc6386.frame"))
        assertEquals(NOT_CHECKED, r.statusOf("rfc9649.canvas"))
    }
}
```

```kotlin
package com.multiviewer.parser.conformance.webp

import com.multiviewer.parser.ByteReader
import com.multiviewer.parser.conformance.ConformanceReport
import com.multiviewer.parser.conformance.ConformanceStatus.FAIL
import com.multiviewer.parser.conformance.ConformanceStatus.NOT_CHECKED
import com.multiviewer.parser.conformance.ConformanceStatus.PASS
import com.multiviewer.parser.conformance.ConformanceStatus.WARN
import com.multiviewer.parser.conformance.conformanceFixture
import com.multiviewer.parser.conformance.idsWith
import com.multiviewer.parser.conformance.notPassing
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** libwebp 1.6.0 tool output (cwebp default = default-settings fixture, img2webp, gif2webp) and byte-level variants. */
class WebpFixtureTest {
    private fun run(name: String): ConformanceReport = ByteReader.open(conformanceFixture(name)).use { WebpConformanceChecker.check(it) }

    @Test fun `libwebp output has no FAIL, WARN or NOT_CHECKED`() {
        listOf("cwebp-default.webp", "cwebp-lossless.webp", "cwebp-alpha.webp", "img2webp-anim.webp", "gif2webp-anim.webp").forEach { name ->
            val r = run(name)
            assertNull(r.inspectionError, name)
            assertEquals(emptySet(), r.idsWith(FAIL, WARN, NOT_CHECKED), "$name: ${r.notPassing()}")
            assertEquals(PASS, r.overall, name)
        }
    }

    @Test fun `each variant fails exactly its targeted rule`() {
        val expected = mapOf(
            "webp-riff-size.webp" to setOf("rfc9649.riff.size"),
            "webp-trailing.webp" to setOf("rfc9649.trailing"),
            "webp-pad.webp" to setOf("rfc9649.chunk.layout"),
            "webp-simple-extra.webp" to setOf("rfc9649.layout"),
            "webp-flags-icc.webp" to setOf("rfc9649.vp8x.flags"),
            "webp-flags-alpha.webp" to setOf("rfc9649.vp8x.flags"),
            "webp-vp8x-reserved.webp" to setOf("rfc9649.vp8x"),
            "webp-order.webp" to setOf("rfc9649.order"),
            "webp-alph-rsv.webp" to setOf("rfc9649.alph"),
            "webp-vp8l-version.webp" to setOf("rfc9649.vp8l"),
            "webp-vp8-start.webp" to setOf("rfc6386.frame"),
            "webp-canvas.webp" to setOf("rfc9649.canvas"),
            "webp-anmf-region.webp" to setOf("rfc9649.anim"),
            "webp-anmf-noflag.webp" to setOf("rfc9649.layout", "rfc9649.anmf.flag"),
            "webp-alph-vp8l.webp" to setOf("rfc9649.alph.vp8l"),
            "webp-iccp-dup.webp" to setOf("rfc9649.metadata.count"),
        )
        expected.forEach { (name, ids) ->
            val r = run(name)
            assertNull(r.inspectionError, name)
            assertEquals(ids, r.idsWith(FAIL, WARN), "$name: ${r.notPassing()}")
        }
        listOf("webp-trailing.webp", "webp-alph-vp8l.webp", "webp-iccp-dup.webp").forEach { assertEquals(WARN, run(it).overall, it) }
    }
}
```

- [ ] **Step 2:** `./gradlew :app:test --tests 'com.multiviewer.parser.conformance.webp.*'` → RED.
- [ ] **Step 3: Implement.**

```kotlin
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
```

In `WebpConformanceChecker.check` replace

```kotlin
            val rules = WebpContainerRules.evaluate(ctx)
```

with

```kotlin
            val rules = WebpContainerRules.evaluate(ctx) + WebpBitstreamRules.evaluate(ctx)
```

- [ ] **Step 4:** `./gradlew :app:test --tests 'com.multiviewer.parser.conformance.webp.*'` → GREEN. **Step 5:** commit
  `feat: check WebP ANIM/ANMF/ALPH, VP8/VP8L headers and canvas size`.

---

### Task 8: GIF89a rules

**Files:** Create `parser/conformance/gif/GifBlocks.kt`, `GifConformanceRules.kt`, `GifConformanceChecker.kt`; tests
`gif/SyntheticGif.kt`, `gif/GifRulesTest.kt`, `gif/GifFixtureTest.kt`.

- [ ] **Step 1: Failing tests.**

```kotlin
package com.multiviewer.parser.conformance.gif

import com.multiviewer.parser.byteReaderOf
import com.multiviewer.parser.conformance.ConformanceReport

/** Minimal GIF writer for rule tests: a 4x4 screen, 2-colour Global Color Table, LZW min code size 2. */
internal object SyntheticGif {
    fun le16(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte())

    fun header(version: String = "89a", w: Int = 4, h: Int = 4, packed: Int = 0x80, background: Int = 0): ByteArray =
        "GIF$version".toByteArray() + le16(w) + le16(h) + byteArrayOf(packed.toByte(), background.toByte(), 0) +
            (if (packed and 0x80 != 0) ByteArray(3 * (1 shl ((packed and 7) + 1))) else ByteArray(0))

    /** Clear (4) then EOI (5) in 3-bit codes: 0b101_100 = 0x2C. */
    val LZW_CLEAR_EOI = byteArrayOf(0x2C, 0x00)

    fun image(x: Int = 0, y: Int = 0, w: Int = 4, h: Int = 4, packed: Int = 0, lzw: Int = 2, data: ByteArray = LZW_CLEAR_EOI): ByteArray =
        byteArrayOf(0x2C) + le16(x) + le16(y) + le16(w) + le16(h) + byteArrayOf(packed.toByte()) +
            (if (packed and 0x80 != 0) ByteArray(3 * (1 shl ((packed and 7) + 1))) else ByteArray(0)) +
            byteArrayOf(lzw.toByte()) + (if (data.isEmpty()) ByteArray(0) else byteArrayOf(data.size.toByte()) + data) + byteArrayOf(0)

    fun gce(packed: Int = 0, size: Int = 4): ByteArray = byteArrayOf(0x21, 0xF9.toByte(), size.toByte()) + byteArrayOf(packed.toByte()) + ByteArray(size - 1) + byteArrayOf(0)
    fun app(id: String = "NETSCAPE2.0", size: Int = 11): ByteArray =
        byteArrayOf(0x21, 0xFF.toByte(), size.toByte()) + id.toByteArray(Charsets.ISO_8859_1).copyOf(size) + byteArrayOf(3, 1, 0, 0, 0)
    fun comment(text: String = "hi"): ByteArray = byteArrayOf(0x21, 0xFE.toByte(), text.length.toByte()) + text.toByteArray() + byteArrayOf(0)
    fun plainText(size: Int = 12): ByteArray = byteArrayOf(0x21, 0x01, size.toByte()) + ByteArray(size) + byteArrayOf(1, 0x41, 0)
    val TRAILER = byteArrayOf(0x3B)

    fun gif(vararg parts: ByteArray): ByteArray = parts.fold(ByteArray(0)) { a, p -> a + p }

    fun check(bytes: ByteArray): ConformanceReport = byteReaderOf(bytes).use { GifConformanceChecker.check(it) }
}
```

```kotlin
package com.multiviewer.parser.conformance.gif

import com.multiviewer.parser.conformance.ConformanceStatus.FAIL
import com.multiviewer.parser.conformance.ConformanceStatus.NOT_APPLICABLE
import com.multiviewer.parser.conformance.ConformanceStatus.NOT_CHECKED
import com.multiviewer.parser.conformance.ConformanceStatus.PASS
import com.multiviewer.parser.conformance.ConformanceStatus.WARN
import com.multiviewer.parser.conformance.gif.SyntheticGif.TRAILER
import com.multiviewer.parser.conformance.gif.SyntheticGif.app
import com.multiviewer.parser.conformance.gif.SyntheticGif.check
import com.multiviewer.parser.conformance.gif.SyntheticGif.comment
import com.multiviewer.parser.conformance.gif.SyntheticGif.gce
import com.multiviewer.parser.conformance.gif.SyntheticGif.gif
import com.multiviewer.parser.conformance.gif.SyntheticGif.header
import com.multiviewer.parser.conformance.gif.SyntheticGif.image
import com.multiviewer.parser.conformance.gif.SyntheticGif.plainText
import com.multiviewer.parser.conformance.idsWith
import com.multiviewer.parser.conformance.notPassing
import com.multiviewer.parser.conformance.statusOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GifRulesTest {
    private fun failsOnly(bytes: ByteArray, vararg ids: String) {
        val r = check(bytes)
        assertNull(r.inspectionError)
        assertEquals(ids.toSet(), r.idsWith(FAIL, WARN), r.notPassing().toString())
    }

    @Test fun `87a still image and 89a animation pass`() {
        listOf(gif(header("87a"), image(), TRAILER), gif(header(), app(), gce(0x04), image(), comment(), gce(0x08), image(1, 1, 2, 2), TRAILER)).forEach { b ->
            val r = check(b)
            assertEquals(emptySet(), r.idsWith(FAIL, WARN, NOT_CHECKED), r.notPassing().toString())
            assertEquals(PASS, r.overall)
        }
        assertEquals(NOT_APPLICABLE, check(gif(header("87a"), image(), TRAILER)).statusOf("gif89a.version.earliest"))
        // A palette-only stream (Header, Logical Screen, Trailer) is allowed by the grammar.
        assertEquals(emptySet(), check(gif(header("87a"), TRAILER)).idsWith(FAIL, WARN))
    }

    @Test fun `version labels`() {
        failsOnly(gif(header("87a"), comment(), image(), TRAILER), "gif89a.version")
        failsOnly(gif(header("87a", packed = 0x88), image(), TRAILER), "gif89a.version")          // LSD Sort Flag needs 89a
        failsOnly(gif(header(), image(), TRAILER), "gif89a.version.earliest")
        assertEquals(PASS, check(gif(header(), image(packed = 0x20), TRAILER)).statusOf("gif89a.version.earliest")) // ID Sort Flag
    }

    @Test fun `Logical Screen, background index and colour tables`() {
        failsOnly(header("89a").copyOf(10), "gif89a.lsd", "gif89a.trailer")
        failsOnly(header("89a").copyOf(16), "gif89a.lsd", "gif89a.trailer")                    // 6-byte GCT cut off
        failsOnly(gif(header("87a", packed = 0x00, background = 3), image(), TRAILER), "gif89a.lsd.background")
        failsOnly(gif(header("87a"), image(packed = 0x02), TRAILER), "gif89a.lct.size")
        assertEquals(PASS, check(gif(header("87a"), image(packed = 0x81), TRAILER)).statusOf("gif89a.lct.size"))
    }

    @Test fun `block grammar, extensions and GCE`() {
        failsOnly(gif(header("87a"), byteArrayOf(0x55), image(), TRAILER), "gif89a.blocks", "gif89a.trailer")
        failsOnly(gif(header(), byteArrayOf(0x21, 0xF9.toByte(), 4, 0, 0)), "gif89a.blocks", "gif89a.trailer") // sub-blocks cut off
        failsOnly(gif(header(), gce(size = 3), image(), TRAILER), "gif89a.gce")
        failsOnly(gif(header(), gce(), gce(), image(), TRAILER), "gif89a.gce")
        failsOnly(gif(header(), image(), gce(), TRAILER), "gif89a.gce")                         // nothing for it to control
        assertEquals(PASS, check(gif(header(), gce(), comment(), image(), TRAILER)).statusOf("gif89a.gce")) // other extensions may sit between
        failsOnly(gif(header(), app(size = 10), image(), TRAILER), "gif89a.extension")
        failsOnly(gif(header(), app("NETSC\u0001PE2.0"), image(), TRAILER), "gif89a.extension")
        failsOnly(gif(header(), plainText(size = 13), TRAILER), "gif89a.extension")
        val unknown = check(gif(header(), byteArrayOf(0x21, 0x99.toByte(), 1, 0, 0), image(), TRAILER))
        assertEquals(PASS, unknown.statusOf("gif89a.blocks"))
        assertTrue(unknown.findings.single { it.rule.id == "gif89a.blocks" }.details.single().contains("0x99"))
    }

    @Test fun `reserved fields, image bounds and LZW`() {
        failsOnly(gif(header(), gce(0x20), image(), TRAILER), "gif89a.reserved")
        failsOnly(gif(header(), gce(0x10), image(), TRAILER), "gif89a.reserved")                  // disposal 4
        failsOnly(gif(header("87a"), image(packed = 0x08), TRAILER), "gif89a.reserved")
        failsOnly(gif(header("87a"), image(x = 1), TRAILER), "gif89a.image.bounds")
        failsOnly(gif(header("87a"), image(lzw = 1), TRAILER), "gif89a.lzw")
        failsOnly(gif(header("87a"), image(data = ByteArray(0)), TRAILER), "gif89a.lzw")
        failsOnly(gif(header("87a"), image(data = byteArrayOf(0x2D, 0)), TRAILER), "gif89a.lzw.clear") // first code 5 (EOI)
    }

    @Test fun `trailer and data after it`() {
        val noTrailer = check(gif(header(), gce(), image()))
        assertEquals(setOf("gif89a.trailer"), noTrailer.idsWith(FAIL, WARN), noTrailer.notPassing().toString())
        assertEquals(NOT_CHECKED, noTrailer.statusOf("gif89a.image.bounds"))                    // only the blocks read were checked
        assertEquals(NOT_CHECKED, noTrailer.statusOf("gif89a.trailing"))
        val trailing = check(gif(header("87a"), image(), TRAILER, ByteArray(5)))
        assertEquals(setOf("gif89a.trailing"), trailing.idsWith(FAIL, WARN))
        assertEquals(WARN, trailing.overall)
    }
}
```

```kotlin
package com.multiviewer.parser.conformance.gif

import com.multiviewer.parser.ByteReader
import com.multiviewer.parser.conformance.ConformanceReport
import com.multiviewer.parser.conformance.ConformanceStatus.FAIL
import com.multiviewer.parser.conformance.ConformanceStatus.NOT_CHECKED
import com.multiviewer.parser.conformance.ConformanceStatus.PASS
import com.multiviewer.parser.conformance.ConformanceStatus.WARN
import com.multiviewer.parser.conformance.conformanceFixture
import com.multiviewer.parser.conformance.idsWith
import com.multiviewer.parser.conformance.notPassing
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** ffmpeg 8.1.2 default GIF output, macOS sips (ImageIO) output and byte-level variants. */
class GifFixtureTest {
    private fun run(name: String): ConformanceReport = ByteReader.open(conformanceFixture(name)).use { GifConformanceChecker.check(it) }

    @Test fun `ffmpeg default still and animated GIFs have no FAIL, WARN or NOT_CHECKED`() {
        listOf("ffmpeg-default.gif", "ffmpeg-anim.gif").forEach { name ->
            val r = run(name)
            assertNull(r.inspectionError, name)
            assertEquals(emptySet(), r.idsWith(FAIL, WARN, NOT_CHECKED), "$name: ${r.notPassing()}")
            assertEquals(PASS, r.overall, name)
        }
    }

    @Test fun `macOS sips labels a GIF with a Graphic Control Extension as 87a`() {
        val r = run("sips.gif")
        assertEquals(setOf("gif89a.version"), r.idsWith(FAIL, WARN), r.notPassing().toString())
        assertTrue(r.findings.single { it.rule.id == "gif89a.version" }.details.single().contains("Graphic Control"))
    }

    @Test fun `each variant fails exactly its targeted rule`() {
        val expected = mapOf(
            "gif-89a-plain.gif" to setOf("gif89a.version.earliest"),
            "gif-gce-size.gif" to setOf("gif89a.gce"),
            "gif-two-gce.gif" to setOf("gif89a.gce"),
            "gif-bounds.gif" to setOf("gif89a.image.bounds"),
            "gif-lzw.gif" to setOf("gif89a.lzw"),
            "gif-clear.gif" to setOf("gif89a.lzw.clear"),
            "gif-no-trailer.gif" to setOf("gif89a.trailer"),
            "gif-trailing.gif" to setOf("gif89a.trailing"),
            "gif-reserved.gif" to setOf("gif89a.reserved"),
            "gif-lct-size.gif" to setOf("gif89a.lct.size"),
            "gif-bgindex.gif" to setOf("gif89a.lsd.background"),
            "gif-app-size.gif" to setOf("gif89a.extension"),
            "gif-badblock.gif" to setOf("gif89a.blocks", "gif89a.trailer"),
        )
        expected.forEach { (name, ids) ->
            val r = run(name)
            assertNull(r.inspectionError, name)
            assertEquals(ids, r.idsWith(FAIL, WARN), "$name: ${r.notPassing()}")
        }
    }
}
```

- [ ] **Step 2:** `./gradlew :app:test --tests 'com.multiviewer.parser.conformance.gif.*'` → RED (unresolved
  `GifConformanceChecker`).
- [ ] **Step 3: Implement.** Block scanner (header, LSD, GCT, then blocks to the Trailer):

```kotlin
package com.multiviewer.parser.conformance.gif

import com.multiviewer.parser.ByteReader
import com.multiviewer.parser.conformance.ImageRuleBook.Companion.hex

/** One block after the Logical Screen (Appendix B grammar). */
internal sealed class GifBlock {
    abstract val offset: Long
    abstract val end: Long

    /** 0x21 [label] + data sub-blocks; [first] = the first sub-block's bytes (the fixed-size "Block Size" part). */
    data class Extension(override val offset: Long, override val end: Long, val label: Int, val firstSize: Int, val first: ByteArray, val subBlocks: Int) : GifBlock()

    data class Image(
        override val offset: Long, override val end: Long,
        val left: Int, val top: Int, val width: Int, val height: Int, val packed: Int,
        val lzwMinCodeSize: Int, val dataBytes: Long, val head: ByteArray,
    ) : GifBlock() {
        val hasLct get() = packed and 0x80 != 0
        val sortFlag get() = packed and 0x20 != 0
        val reserved get() = (packed shr 3) and 0x03
        val lctSizeField get() = packed and 0x07
    }

    data class Trailer(override val offset: Long) : GifBlock() { override val end: Long get() = offset + 1 }
}

/**
 * Walk result: [problem] = why the walk stopped before a Trailer (unknown byte, truncation, cap); [cleanEof] = the file
 * simply ended between two blocks (only the Trailer is missing).
 */
internal class GifScan(val blocks: List<GifBlock>, val trailer: GifBlock.Trailer?, val problem: String?, val problemAt: Long?, val cleanEof: Boolean = false, val capped: Boolean = false)

internal class GifContext(val reader: ByteReader) {
    val length: Long = reader.length
    val book = GifRules.book
    val signature: String = if (length >= 3) String(reader.readBytes(0, 3), Charsets.ISO_8859_1) else ""
    val version: String = if (length >= 6) String(reader.readBytes(3, 3), Charsets.ISO_8859_1) else ""
    val hasLsd: Boolean = length >= 13
    val screenWidth: Int = if (hasLsd) u16(6) else 0
    val screenHeight: Int = if (hasLsd) u16(8) else 0
    val lsdPacked: Int = if (hasLsd) reader.readUInt8(10) else 0
    val backgroundIndex: Int = if (hasLsd) reader.readUInt8(11) else 0
    val hasGct: Boolean get() = lsdPacked and 0x80 != 0
    val gctBytes: Long get() = if (hasGct) 3L * (1 shl ((lsdPacked and 7) + 1)) else 0
    val dataStart: Long = 13 + gctBytes
    val scan: GifScan by lazy { if (hasLsd && dataStart <= length) GifBlockScanner.scan(reader, dataStart) else GifScan(emptyList(), null, null, null) }

    val images: List<GifBlock.Image> get() = scan.blocks.filterIsInstance<GifBlock.Image>()
    val extensions: List<GifBlock.Extension> get() = scan.blocks.filterIsInstance<GifBlock.Extension>()

    private fun u16(at: Long) = reader.readUInt8(at) or (reader.readUInt8(at + 1) shl 8)

    fun range(b: GifBlock): LongRange = b.offset until minOf(b.end, length)
}

internal object GifBlockScanner {
    const val MAX_BLOCKS = 1_000_000

    fun scan(reader: ByteReader, start: Long): GifScan {
        val len = reader.length
        val blocks = ArrayList<GifBlock>()
        var pos = start
        fun stop(text: String, cleanEof: Boolean = false) = GifScan(blocks, null, text, pos, cleanEof)
        while (true) {
            if (blocks.size >= MAX_BLOCKS) return GifScan(blocks, null, null, pos, capped = true)
            if (pos >= len) return stop("${hex(pos)}에서 파일 끝 — Trailer 없음 / end of file at ${hex(pos)} before a Trailer", cleanEof = true)
            when (val b = reader.readUInt8(pos)) {
                0x3B -> { val t = GifBlock.Trailer(pos); blocks += t; return GifScan(blocks, t, null, null) }
                0x21 -> {
                    if (pos + 2 > len) return stop("확장 레이블 전에 파일 끝 / end of file before the extension label")
                    val label = reader.readUInt8(pos + 1)
                    val chain = subBlocks(reader, pos + 2, 16) ?: return stop("확장 0x%02X @${hex(pos)}의 서브블록이 Block Terminator 전에 잘림 / extension 0x%02X sub-blocks cut off before the Block Terminator".format(label, label))
                    blocks += GifBlock.Extension(pos, chain.end, label, chain.firstSize, chain.head, chain.count)
                    pos = chain.end
                }
                0x2C -> {
                    if (pos + 10 > len) return stop("이미지 설명자 @${hex(pos)}가 잘림 / Image Descriptor cut off")
                    val d = reader.readBytes(pos + 1, 9)
                    fun u16(i: Int) = (d[i].toInt() and 0xFF) or ((d[i + 1].toInt() and 0xFF) shl 8)
                    val packed = d[8].toInt() and 0xFF
                    val lct = if (packed and 0x80 != 0) 3L * (1 shl ((packed and 7) + 1)) else 0L
                    val lzwAt = pos + 10 + lct
                    if (lzwAt >= len) return stop("이미지 @${hex(pos)}의 색상표/LZW 최소 코드 크기가 잘림 / Local Color Table or LZW Minimum Code Size cut off")
                    val chain = subBlocks(reader, lzwAt + 1, 2) ?: return stop("이미지 @${hex(pos)} 데이터 서브블록이 Block Terminator 전에 잘림 / image data sub-blocks cut off before the Block Terminator")
                    blocks += GifBlock.Image(pos, chain.end, u16(0), u16(2), u16(4), u16(6), packed, reader.readUInt8(lzwAt), chain.dataBytes, chain.head)
                    pos = chain.end
                }
                else -> return stop("${hex(pos)}의 0x%02X는 블록 시작(0x21, 0x2C, 0x3B)이 아님 / byte 0x%02X is not a block start (0x21, 0x2C, 0x3B)".format(b, b))
            }
        }
    }

    /** A data sub-block chain; [head] = the first [headBytes] data bytes across sub-blocks (for 0x2C) or the first sub-block (for 0x21). */
    class Chain(val end: Long, val count: Int, val firstSize: Int, val head: ByteArray, val dataBytes: Long)

    private fun subBlocks(reader: ByteReader, start: Long, headBytes: Int): Chain? {
        val len = reader.length
        var p = start
        var count = 0
        var firstSize = -1
        var data = 0L
        val head = java.io.ByteArrayOutputStream()
        val spanning = headBytes == 2
        while (true) {
            if (p >= len) return null
            val n = reader.readUInt8(p)
            if (firstSize < 0) firstSize = n
            p++
            if (n == 0) return Chain(p, count, firstSize, head.toByteArray(), data)
            if (p + n > len) return null
            if (spanning) { if (head.size() < headBytes) head.write(reader.readBytes(p, minOf(n, headBytes - head.size()))) }
            else if (count == 0) head.write(reader.readBytes(p, minOf(n, headBytes)))
            count++; data += n; p += n
        }
    }
}
```

```kotlin
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
        for (i in list) {
            val bits = i.lzwMinCodeSize + 1
            val raw = (i.head[0].toInt() and 0xFF) or (if (i.head.size > 1) (i.head[1].toInt() and 0xFF) shl 8 else 0)
            if (bits > 8 && i.head.size < 2) continue
            val code = raw and ((1 shl bits) - 1)
            val clear = 1 shl i.lzwMinCodeSize
            if (code != clear) { bad += "Image @${hex(i.offset)}: 첫 코드 $code ≠ Clear $clear / first code $code is not the Clear code $clear"; ranges += ctx.range(i) }
        }
        return f(ctx, "gif89a.lzw.clear", bad, ranges, "이미지 ${list.size}개 첫 코드 = Clear / first code is Clear in all ${list.size} images")
    }

    private fun trailer(ctx: GifContext): ConformanceFinding {
        if (ctx.scan.capped) return ctx.book.skip("gif89a.trailer", NOT_CHECKED, "블록 상한에서 멈춤 / stopped at the block cap")
        val t = ctx.scan.trailer
        return if (t != null) f(ctx, "gif89a.trailer", emptyList(), emptyList(), "Trailer 0x3B @${hex(t.offset)}")
        else f(ctx, "gif89a.trailer", listOf("Trailer(0x3B) 없음 / no Trailer (0x3B)" + (ctx.scan.problem?.let { " — $it" } ?: "")), listOf((ctx.scan.problemAt ?: ctx.length) until ctx.length), "")
    }
}
```

```kotlin
package com.multiviewer.parser.conformance.gif

import com.multiviewer.parser.ByteReader
import com.multiviewer.parser.conformance.ConformanceFinding
import com.multiviewer.parser.conformance.ConformanceReport
import com.multiviewer.parser.conformance.ConformanceStatus.NOT_CHECKED
import com.multiviewer.parser.conformance.ImageRuleBook
import com.multiviewer.parser.conformance.trailingDataFinding
import java.util.concurrent.CancellationException

object GifConformanceChecker {
    const val PROFILE = "GIF89a (CompuServe 1990) block structure phase 1"

    val LIMITATIONS = listOf(
        "LZW 데이터는 해독하지 않습니다(첫 코드만 읽음) — End of Information 코드, 색상표 범위 안의 인덱스, 픽셀 수는 디코딩 검증(ffmpeg) 몫이며 여기서는 PASS로 보지 않습니다 / " +
            "LZW data is not decoded (first code only): the End of Information code, indices within the active colour table and the pixel count are left to the ffmpeg decode check and never counted as PASS here",
        "NETSCAPE2.0 등 응용 확장 내용, 주석의 7비트 ASCII 권고, 평문 확장 격자, 정의되지 않은 확장 레이블의 내용은 검사하지 않습니다 / " +
            "Not checked: Application Extension contents (e.g. NETSCAPE2.0), the 7-bit ASCII comment recommendation, Plain Text grids, contents of undefined extension labels",
    )

    private const val TRAILING_NOTE = "GIF89a 부록 C: 모든 블록은 Trailer 앞에 있어야 함 / Appendix C: all control and data blocks must precede the Trailer"

    fun check(reader: ByteReader): ConformanceReport {
        val findings = ArrayList<ConformanceFinding>()
        val error = try {
            val ctx = GifContext(reader)
            findings += GifConformanceRules.evaluate(ctx)
            val t = ctx.scan.trailer
            findings += if (t == null) ctx.book.skip("gif89a.trailing", NOT_CHECKED, "Trailer 없음 — 판단 불가 / no Trailer")
            else trailingDataFinding(GifRules.book, "gif89a.trailing", reader, t.end, TRAILING_NOTE)
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ImageRuleBook.inspectionError(e)
        }
        return ConformanceReport("GIF", PROFILE, GifRules.book.sorted(findings), LIMITATIONS, error)
    }
}
```

- [ ] **Step 4:** `./gradlew :app:test --tests 'com.multiviewer.parser.conformance.gif.*'` → GREEN. **Step 5:** commit
  `feat: check GIF89a header, blocks, extensions, bounds and LZW size`.

---

### Task 9: Wiring, CLI/plan tests, citation README, manual check, spec sync

**Files:** modify `app/src/main/kotlin/com/multiviewer/ui/ContentCheckPlan.kt`,
`app/src/test/kotlin/com/multiviewer/cli/CheckFileTest.kt`, `app/src/test/kotlin/com/multiviewer/ui/ContentCheckPlanTest.kt`;
`docs/validation/spec-citations/README.md`; spec.

- [ ] **Step 1: Failing tests** (the old ones used a PNG as "a format without a container checker"; they now use a
  BMP, and PNG / WebP / GIF dispatch is asserted — the test diff below), **Step 2:** RED, **Step 3: implement the
  dispatch** (the `ContentCheckPlan.kt` hunk below):

```diff
diff --git a/app/src/main/kotlin/com/multiviewer/ui/ContentCheckPlan.kt b/app/src/main/kotlin/com/multiviewer/ui/ContentCheckPlan.kt
index 2f2818f3..72f5025a 100644
--- a/app/src/main/kotlin/com/multiviewer/ui/ContentCheckPlan.kt
+++ b/app/src/main/kotlin/com/multiviewer/ui/ContentCheckPlan.kt
@@ -4,7 +4,10 @@ import com.multiviewer.parser.BoxNode
 import com.multiviewer.parser.ByteReader
 import com.multiviewer.parser.ContainerFormat
 import com.multiviewer.parser.conformance.heif.HeifConformanceChecker
+import com.multiviewer.parser.conformance.gif.GifConformanceChecker
 import com.multiviewer.parser.conformance.jpeg.JpegConformanceChecker
+import com.multiviewer.parser.conformance.png.PngConformanceChecker
+import com.multiviewer.parser.conformance.webp.WebpConformanceChecker
 import com.multiviewer.parser.conformance.isobmff.IsoBmffConformanceChecker
 import java.util.concurrent.CancellationException
 import com.multiviewer.parser.conformance.ConformanceReport
@@ -103,18 +106,22 @@ fun contentConformanceReport(
 private const val PARSER_EVIDENCE_PREFIX = "파서 근거 / Parser evidence"
 
 private val ISO_BMFF_CONTAINERS = setOf(ContainerFormat.MP4, ContainerFormat.MOV, ContainerFormat.M4A)
+private val IMAGE_CONTAINERS = setOf(ContainerFormat.HEIF, ContainerFormat.AVIF, ContainerFormat.JPEG, ContainerFormat.PNG, ContainerFormat.WEBP, ContainerFormat.GIF)
 
 /**
  * Container conformance by content (sniffer), not extension: MP4/MOV/M4A → ISO-BMFF checker, HEIF/AVIF → HEIF
- * checker, JPEG → JPEG checker; null for every other format. Blocking file I/O: never call on the UI thread.
+ * checker, JPEG / PNG / WebP / GIF → their checkers; null for every other format. Blocking file I/O: never call on the UI thread.
  */
 fun containerConformanceFor(file: File): ConformanceReport? {
     val format = confidentFormat(file)
-    if (format !in ISO_BMFF_CONTAINERS && format != ContainerFormat.HEIF && format != ContainerFormat.AVIF && format != ContainerFormat.JPEG) return null
+    if (format !in ISO_BMFF_CONTAINERS && format !in IMAGE_CONTAINERS) return null
     return try {
         ByteReader.open(file).use {
             when (format) {
                 ContainerFormat.JPEG -> JpegConformanceChecker.check(it)
+                ContainerFormat.PNG -> PngConformanceChecker.check(it)
+                ContainerFormat.WEBP -> WebpConformanceChecker.check(it)
+                ContainerFormat.GIF -> GifConformanceChecker.check(it)
                 ContainerFormat.HEIF, ContainerFormat.AVIF -> HeifConformanceChecker.check(it)
                 else -> IsoBmffConformanceChecker.check(it)
             }
@@ -127,6 +134,9 @@ fun containerConformanceFor(file: File): ConformanceReport? {
             ContainerFormat.AVIF -> "AVIF" to HeifConformanceChecker.AVIF_PROFILE
             ContainerFormat.HEIF -> "HEIF" to HeifConformanceChecker.HEIF_PROFILE
             ContainerFormat.JPEG -> "JPEG" to JpegConformanceChecker.PROFILE
+            ContainerFormat.PNG -> "PNG" to PngConformanceChecker.PROFILE
+            ContainerFormat.WEBP -> "WebP" to WebpConformanceChecker.PROFILE
+            ContainerFormat.GIF -> "GIF" to GifConformanceChecker.PROFILE
             else -> "ISO-BMFF" to "ISO/IEC 14496-12 phase 1"
         }
         ConformanceReport(name, profile, emptyList(), inspectionError = message,
diff --git a/app/src/test/kotlin/com/multiviewer/cli/CheckFileTest.kt b/app/src/test/kotlin/com/multiviewer/cli/CheckFileTest.kt
index 6e655e91..ee5a1ed0 100644
--- a/app/src/test/kotlin/com/multiviewer/cli/CheckFileTest.kt
+++ b/app/src/test/kotlin/com/multiviewer/cli/CheckFileTest.kt
@@ -192,13 +192,27 @@ class CheckFileTest {
 
     @Test
     fun `checkFile omits containerConformance for formats without a container checker`() {
-        val file = File.createTempFile("check-iso-", ".png").also {
-            it.deleteOnExit(); it.writeBytes(com.multiviewer.parser.integrity.pngBytes())
+        val file = File.createTempFile("check-iso-", ".bmp").also {
+            it.deleteOnExit(); it.writeBytes(com.multiviewer.parser.integrity.bmpBytes())
         }
         val json = checkJson(file)
         assertTrue(!json.contains("containerConformance"), json)
     }
 
+    @Test
+    fun `checkFile includes the PNG, WebP and GIF containerConformance reports`() {
+        listOf(
+            Triple(".png", com.multiviewer.parser.integrity.pngBytes(), "png3.signature"),
+            Triple(".webp", com.multiviewer.parser.integrity.webpBytes(), "rfc9649.header"),
+            Triple(".gif", com.multiviewer.parser.integrity.gifBytes(), "gif89a.header"),
+        ).forEach { (ext, bytes, rule) ->
+            val file = File.createTempFile("check-img-", ext).also { it.deleteOnExit(); it.writeBytes(bytes) }
+            val json = checkJson(file)
+            assertTrue(json.contains("\"containerConformance\": {"), json)
+            assertTrue(Regex("\"ruleId\": \"${Regex.escape(rule)}\",\\s*\"status\": \"PASS\"").containsMatchIn(json), json)
+        }
+    }
+
     @Test
     fun `checkFile includes the JPEG containerConformance report`() {
         val file = File.createTempFile("check-jpeg-", ".jpg").also {
@@ -246,8 +260,8 @@ class CheckFileTest {
 
     @Test
     fun `analysis case omits containerConformance for formats without a container checker`() {
-        val file = File.createTempFile("check-iso-", ".png").also {
-            it.deleteOnExit(); it.writeBytes(com.multiviewer.parser.integrity.pngBytes())
+        val file = File.createTempFile("check-iso-", ".bmp").also {
+            it.deleteOnExit(); it.writeBytes(com.multiviewer.parser.integrity.bmpBytes())
         }
         val result = checkFile(file, includeCase = true) as CheckResult.Success
         assertTrue(!result.analysisCaseJson!!.contains("containerConformance"))
diff --git a/app/src/test/kotlin/com/multiviewer/ui/ContentCheckPlanTest.kt b/app/src/test/kotlin/com/multiviewer/ui/ContentCheckPlanTest.kt
index 00758eff..77318a2d 100644
--- a/app/src/test/kotlin/com/multiviewer/ui/ContentCheckPlanTest.kt
+++ b/app/src/test/kotlin/com/multiviewer/ui/ContentCheckPlanTest.kt
@@ -275,9 +275,17 @@ class ContentCheckPlanTest {
     }
 
     @Test
-    fun containerConformanceIsOnlyComputedForIsoBmffHeifAndJpeg() {
-        val png = File.createTempFile("ccp-iso-", ".png").also { it.deleteOnExit(); it.writeBytes(com.multiviewer.parser.integrity.pngBytes()) }
-        assertNull(containerConformanceFor(png))
+    fun containerConformanceIsOnlyComputedForIsoBmffHeifJpegPngWebpAndGif() {
+        val bmp = File.createTempFile("ccp-iso-", ".bmp").also { it.deleteOnExit(); it.writeBytes(com.multiviewer.parser.integrity.bmpBytes()) }
+        assertNull(containerConformanceFor(bmp))
+        // Content decides: a PNG saved as .gif is still checked as PNG.
+        val png = File.createTempFile("ccp-iso-", ".gif").also { it.deleteOnExit(); it.writeBytes(com.multiviewer.parser.integrity.pngBytes()) }
+        assertEquals(com.multiviewer.parser.conformance.png.PngConformanceChecker.PROFILE, containerConformanceFor(png)?.profile)
+        assertEquals("PNG", containerConformanceFor(png)?.format)
+        val webp = File.createTempFile("ccp-iso-", ".webp").also { it.deleteOnExit(); it.writeBytes(com.multiviewer.parser.integrity.webpBytes()) }
+        assertEquals("WebP", containerConformanceFor(webp)?.format)
+        val gif = File.createTempFile("ccp-iso-", ".gif").also { it.deleteOnExit(); it.writeBytes(com.multiviewer.parser.integrity.gifBytes()) }
+        assertEquals("GIF", containerConformanceFor(gif)?.format)
         val jpeg = File.createTempFile("ccp-iso-", ".jpg").also { it.deleteOnExit(); it.writeBytes(jpegBytes()) }
         assertEquals("JPEG", containerConformanceFor(jpeg)?.format)
         assertEquals(com.multiviewer.parser.conformance.jpeg.JpegConformanceChecker.PROFILE, containerConformanceFor(jpeg)?.profile)
```

- [ ] **Step 4:** full `./gradlew :app:test` + `:app:compileKotlin` GREEN (`ThumbnailDiskCacheTest` is the known flaky
  one).
- [ ] **Step 5: README.** Append this section to `docs/validation/spec-citations/README.md`:

```markdown
## PNG / WebP / GIF catalogs (`parser/conformance/{png,webp,gif}/*Rules.kt`)

Downloaded 2026-10-25; HTML read with a tag-stripping script that keeps `id` attributes, plain text read as is. Every
quote of the spec (`docs/superpowers/specs/2026-10-25-png-webp-gif-conformance-design.md`) was matched by script against
the whitespace-normalised text. Pages are null except `zlib.header` (RFC page 5).

| Document | Edition | URL used in citations | sha256 of downloaded copy |
|---|---|---|---|
| W3C PNG Third Edition | W3C Recommendation 2025-06-24 (byte-identical to `/TR/png-3/` on 2026-10-25) | https://www.w3.org/TR/2025/REC-png-3-20250624/ + `#anchor` | a3ac82bb9eb8664b93961a2a9ff6def3d70e3a9f63d92721143c1f391d04edec |
| RFC 1950 (ZLIB 3.3) | May 1996 | https://www.rfc-editor.org/rfc/rfc1950.html#page-5 | d3c9d031292be13c93f0bacc854b71c1654f8995df4865eb119748592a6a1cc9 |
| RFC 9649 (WebP Image Format) | November 2024 | https://www.rfc-editor.org/rfc/rfc9649.html + `#section-N` | 8880255877aac6fbcad88a54cf02ad4d6ce3042220e965fd36bc8cccccaeee06 |
| RFC 6386 (VP8) | November 2011 | https://www.rfc-editor.org/rfc/rfc6386.html#section-9.1 | 65c94e526038f28a5180b526fcedfffa4226498ce08765828bf8d766bbaf5faa |
| GIF89a Specification (CompuServe) | 31 July 1990, W3C-hosted plain text | https://www.w3.org/Graphics/GIF/spec-gif89a.txt | c0e48c12e3e91b1e78090caffb1d1ffe8e7d0face4350da675044a9a4be02aa3 |

Notes: rfc1950.html has no `#section-2.2` anchor (only top-level sections and `page-N`), so `zlib.header` cites the
page anchor of the page holding both quoted sentences. The VP8 key-frame start code is defined in RFC 6386 §9.1, which
RFC 9649 §2.5 references. The GIF89a text has no anchors: every GIF citation URL is the document itself, the section
number is the locator and page is null (the document name says "plain text, no anchors").
```

- [ ] **Step 6: Manual** (copies only, never committed): copy the ten PNG / WebP / GIF files of `~/Downloads` listed in
  the spec (`real.png`, `crc.png`, `image_5406fcbb.png`, `image_79024843.png`, `ssim_psnr_diagram.png`, `unwrapICO.png`,
  `BMPfileFormat.svg.webp`, `unwrapMedia-samples/{real.webp,anim.gif,trunc.gif}`) to a temp dir and run
  `./gradlew -q :app:run --args="check <copy>"`; confirm `containerConformance.format` (`PNG` / `WebP` / `GIF`), no
  `inspectionError`, and the outcome table of the spec (`crc.png` FAIL `png3.crc`; `trunc.gif` FAIL `gif89a.blocks` +
  `gif89a.trailer` with the block rules NOT_CHECKED; the other eight PASS). Report differences instead of changing
  rules. GUI: open `ffmpeg.apng` and `sips.gif` (fixtures) in Content Check (⌘⇧C), check the "컨테이너 포맷 규격" row
  lists the rules grouped by scope and the 출처 links open (PNG anchors scroll to the section; the GIF link opens the
  plain-text document).
- [ ] **Step 7:** sync the spec (status line, any measured differences, the user's answers to the two open decisions);
  commits `feat: show PNG/WebP/GIF conformance in Content Check and CLI`, `docs: add PNG/WebP/GIF citation sources`,
  `docs: sync PNG/WebP/GIF conformance spec with results`.
