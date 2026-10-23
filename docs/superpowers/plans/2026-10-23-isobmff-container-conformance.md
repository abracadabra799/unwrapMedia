# ISO-BMFF Container Conformance (phase 1) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Content Check's Container Conformance row checks non-fragmented MP4/M4A/3GP/MOV rule by rule against
ISO/IEC 14496-12 (ISO profile) or the QuickTime File Format (QT profile).

**Architecture:** New package `com.multiviewer.parser.conformance.isobmff`: a bounded byte-level box walker
(`IsoBmffBoxTree.kt`), a rule catalog (`IsoBmffRules.kt`) and the checker (`IsoBmffConformanceChecker.kt`)
returning the existing `ConformanceReport`/`ConformanceFinding` model. `contentConformanceReport` uses it for
MP4/MOV/M4A containers; the CLI `check` JSON gains a `containerConformance` object.

**Tech Stack:** Kotlin, JDK 21, kotlin.test/JUnit5, ffmpeg + python3 for fixtures.

Spec: `docs/superpowers/specs/2026-10-23-isobmff-container-conformance-design.md` (rule table = authoritative).

## Global Constraints

- Status mapping: rule level FAIL/WARN violated → `ConformanceStatus.FAIL`/`WARN`; satisfied → `PASS`; not evaluable → `NOT_CHECKED` with the reason in `details` (never PASS); not applicable (e.g. sample rules on fragmented files, external data references) → `NOT_APPLICABLE`.
- Every `ConformanceRule.source` is exactly `"ISO/IEC 14496-12 (공개 자료 기반, 원문 미대조)"` (ISO profile) or `"QuickTime File Format (공개 자료 기반, 원문 미대조)"` (QT profile) — never starting with "unwrapMedia structural check".
- `ConformanceRule.scope` is the group label, one of: `"박스 구조 / Box structure"`, `"ftyp"`, `"필수 박스 / Required boxes"`, `"헤더 필드 / Header fields"`, `"샘플 테이블 / Sample tables"`, `"편집 목록 / Edit lists"`.
- Titles bilingual `"한국어 / English"`; details Korean.
- Profile: `ftyp` major brand `qt  ` → QT; no `ftyp` and (a top-level `wide` box or an `hdlr` with component type `mhlr`) → QT; otherwise ISO.
- Table caps: never expand a table with more than 2,000,000 entries — affected rules `NOT_CHECKED` with reason. No whole-file reads; read through `ByteReader` only.
- Never throw out of the checker except `java.util.concurrent.CancellationException`; an unexpected exception becomes `inspectionError`.
- Work in `.worktrees/isobmff-conformance` on branch `feat/isobmff-conformance`; check `git branch --show-current` before every commit; commit trailer `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

---

### Task 1: Bounded box walker and the synthetic movie builder

**Files:**
- Create: `app/src/main/kotlin/com/multiviewer/parser/conformance/isobmff/IsoBmffBoxTree.kt`
- Create: `app/src/test/kotlin/com/multiviewer/parser/conformance/isobmff/SyntheticMovie.kt` (test helper used by every later task)
- Test: `app/src/test/kotlin/com/multiviewer/parser/conformance/isobmff/IsoBmffBoxTreeTest.kt`

**Interfaces (produces):**
```kotlin
data class BmffBox(
    val type: String, val offset: Long, val headerSize: Int, val size: Long,
    val rawSize: Long,               // 0 = "to end", 1 = largesize
    val problem: String?,            // null, or why the header is invalid (too small / overruns parent)
    val children: List<BmffBox>,
    val parent: String?,             // parent type, null at top level
) { val end: Long get() = offset + size; val payload: Long get() = offset + headerSize }
data class BmffTree(val top: List<BmffBox>, val trailingBytes: Long, val trailingOffset: Long?)
object IsoBmffBoxTree { fun walk(reader: ByteReader): BmffTree }
val BMFF_CONTAINERS: Set<String> // moov trak mdia minf dinf stbl edts udta mvex moof traf mfra tref
```
`meta` is a FullBox container in ISO (4 version/flags bytes before children) but a plain container in QuickTime; the walker reads `meta` children starting at payload+4 when the 4 bytes after the header are not a plausible box header (size ≥ 8 and printable type), else at payload. Stop descending at depth 12.

- [ ] **Step 1: Write the synthetic movie builder** (`SyntheticMovie.kt`):

```kotlin
package com.multiviewer.parser.conformance.isobmff

internal fun be16(v: Int) = byteArrayOf((v ushr 8).toByte(), v.toByte())
internal fun be32(v: Long) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
internal fun box(type: String, vararg payload: ByteArray): ByteArray {
    val body = payload.fold(ByteArray(0)) { a, b -> a + b }
    return be32(8L + body.size) + type.toByteArray(Charsets.ISO_8859_1) + body
}
internal fun fullBox(type: String, version: Int, flags: Int, vararg payload: ByteArray) =
    box(type, byteArrayOf(version.toByte(), (flags ushr 16).toByte(), (flags ushr 8).toByte(), flags.toByte()), *payload)

/** A minimal valid one-video-track movie; each field can be overridden to inject exactly one violation. */
internal data class MovieSpec(
    val ftyp: ByteArray? = box("ftyp", "isom".toByteArray(), be32(512), "isommp41".toByteArray()),
    val movieTimescale: Long = 1000,
    val nextTrackId: Long = 2,
    val trackId: Long = 1,
    val mvhdVersion: Int = 0,
    val handler: String = "vide",
    val handlerPreDefined: String = "\u0000\u0000\u0000\u0000",
    val mediaHeader: ByteArray? = fullBox("vmhd", 0, 1, ByteArray(8)),
    val mediaTimescale: Long = 1000,
    val language: Int = 0x55C4, // "und"
    val sampleSizes: List<Int> = listOf(10, 10, 10),
    val sttsCounts: List<Long>? = null,           // default: one entry covering every sample
    val stsc: List<Triple<Long, Long, Long>>? = null, // default: (1, n, 1)
    val chunkOffsetDelta: Long = 0,
    val stss: List<Long>? = listOf(1),
    val ctts: List<Pair<Long, Long>>? = null,      // (sample_count, offset)
    val elst: List<Triple<Long, Long, Int>>? = null, // (segment_duration, media_time, media_rate_integer)
    val drefEntry: ByteArray = fullBox("url ", 0, 1),
    val vmhdInStbl: Boolean = false,               // misplaces vmhd for the parent rule
    val extraTopLevelBefore: List<ByteArray> = emptyList(),
    val extraMoov: List<ByteArray> = emptyList(),
    val trailing: ByteArray = ByteArray(0),
    val dropBox: String? = null,                   // remove the first box of this type
)

internal fun buildMovie(s: MovieSpec = MovieSpec()): ByteArray {
    val n = s.sampleSizes.size
    val mdatPayload = ByteArray(s.sampleSizes.sum()) { 7 }
    val mdat = box("mdat", mdatPayload)
    val head = (s.ftyp ?: ByteArray(0)) + s.extraTopLevelBefore.fold(ByteArray(0)) { a, b -> a + b }
    val firstSampleOffset = head.size + 8L + s.chunkOffsetDelta
    val mediaDuration = 100L * n
    val movieDuration = mediaDuration * s.movieTimescale / s.mediaTimescale
    fun drop(type: String, bytes: ByteArray) = if (s.dropBox == type) ByteArray(0) else bytes

    val mvhd = if (s.mvhdVersion == 0) fullBox("mvhd", 0, 0, be32(0), be32(0), be32(s.movieTimescale), be32(movieDuration),
        be32(0x00010000), be16(0x0100), ByteArray(10), ByteArray(36), ByteArray(24), be32(s.nextTrackId))
    else fullBox("mvhd", s.mvhdVersion, 0, ByteArray(16), be32(s.movieTimescale), ByteArray(8), ByteArray(80), be32(s.nextTrackId))
    val tkhd = fullBox("tkhd", 0, 3, be32(0), be32(0), be32(s.trackId), be32(0), be32(movieDuration), ByteArray(8),
        be16(0), be16(0), be16(0), be16(0), ByteArray(36), be32(64L shl 16), be32(48L shl 16))
    val mdhd = fullBox("mdhd", 0, 0, be32(0), be32(0), be32(s.mediaTimescale), be32(mediaDuration), be16(s.language), be16(0))
    val hdlr = fullBox("hdlr", 0, 0, s.handlerPreDefined.toByteArray(Charsets.ISO_8859_1), s.handler.toByteArray(), ByteArray(12), byteArrayOf(0))
    val stsd = fullBox("stsd", 0, 0, be32(1), box("avc1", ByteArray(78)))
    val sttsCounts = s.sttsCounts ?: listOf(n.toLong())
    val stts = fullBox("stts", 0, 0, be32(sttsCounts.size.toLong()), *sttsCounts.map { be32(it) + be32(100) }.toTypedArray())
    val stscEntries = s.stsc ?: listOf(Triple(1L, n.toLong(), 1L))
    val stsc = fullBox("stsc", 0, 0, be32(stscEntries.size.toLong()), *stscEntries.map { be32(it.first) + be32(it.second) + be32(it.third) }.toTypedArray())
    val stsz = fullBox("stsz", 0, 0, be32(0), be32(n.toLong()), *s.sampleSizes.map { be32(it.toLong()) }.toTypedArray())
    val stco = fullBox("stco", 0, 0, be32(1), be32(firstSampleOffset))
    val stss = s.stss?.let { list -> fullBox("stss", 0, 0, be32(list.size.toLong()), *list.map(::be32).toTypedArray()) } ?: ByteArray(0)
    val ctts = s.ctts?.let { list -> fullBox("ctts", 0, 0, be32(list.size.toLong()), *list.map { be32(it.first) + be32(it.second) }.toTypedArray()) } ?: ByteArray(0)
    val mediaHeader = s.mediaHeader ?: ByteArray(0)
    val stbl = box("stbl", drop("stsd", stsd), drop("stts", stts), ctts, drop("stss", stss), drop("stsc", stsc), drop("stsz", stsz),
        drop("stco", stco), if (s.vmhdInStbl) mediaHeader else ByteArray(0))
    val dinf = box("dinf", fullBox("dref", 0, 0, be32(1), s.drefEntry))
    val minf = box("minf", if (s.vmhdInStbl) ByteArray(0) else mediaHeader, drop("dinf", dinf), drop("stbl", stbl))
    val mdia = box("mdia", drop("mdhd", mdhd), drop("hdlr", hdlr), drop("minf", minf))
    val edts = s.elst?.let { list ->
        box("edts", fullBox("elst", 0, 0, be32(list.size.toLong()),
            *list.map { be32(it.first) + be32(it.second and 0xFFFFFFFFL) + be16(it.third) + be16(0) }.toTypedArray()))
    } ?: ByteArray(0)
    val trak = box("trak", drop("tkhd", tkhd), edts, drop("mdia", mdia))
    val moov = box("moov", drop("mvhd", mvhd), drop("trak", trak), *s.extraMoov.toTypedArray())
    return head + drop("mdat", mdat) + drop("moov", moov) + s.trailing
}
```

- [ ] **Step 2: Write the failing walker tests** (`IsoBmffBoxTreeTest.kt`): (a) `buildMovie()` walks to `ftyp, mdat, moov` with `moov>trak>mdia>minf>stbl` children and every `problem == null`, `trailingBytes == 0`; (b) a box whose size runs past its parent gets a `problem` and the walk of that level stops; (c) a top-level `size 0` box ends at EOF with `rawSize == 0`; (d) a largesize (`size 1`) `mdat` is read with headerSize 16; (e) 20 non-box bytes after `moov` give `trailingBytes == 20` and `trailingOffset` at moov.end; (f) an ISO FullBox `meta` (4 zero bytes then a `hdlr` box) and a QuickTime plain `meta` (directly a `hdlr` box) both list `hdlr` as their child. Use `com.multiviewer.parser.fileOf` + `ByteReader.open`.
- [ ] **Step 3: Run** `./gradlew :app:test --tests 'com.multiviewer.parser.conformance.isobmff.*'` → RED (unresolved `IsoBmffBoxTree`).
- [ ] **Step 4: Implement `IsoBmffBoxTree.walk`** per the interface: iterative over levels with explicit bounds (child walk limited to the parent's payload range), type must be 4 bytes in 0x20..0x7E or 0xA9 (©) else the level stops; `size < header` or `offset + size > limit` → record the box with `problem` and stop that level; top level: bytes after the last valid box up to EOF are `trailingBytes`.
- [ ] **Step 5: GREEN**, then commit `feat: add bounded ISO-BMFF box walker`.

---

### Task 2: Rule catalog, profile, structure / ftyp / required-box / parent rules

**Files:**
- Create: `app/src/main/kotlin/com/multiviewer/parser/conformance/isobmff/IsoBmffRules.kt`, `IsoBmffConformanceChecker.kt`
- Test: `app/src/test/kotlin/com/multiviewer/parser/conformance/isobmff/IsoBmffStructureRulesTest.kt`

**Interfaces (produces):**
```kotlin
enum class BmffProfile { ISO, QUICKTIME }
data class BmffRuleDef(val id: String, val titleKo: String, val titleEn: String, val reference: String, val scope: String)
object IsoBmffRules { val all: List<BmffRuleDef>; fun rule(def: BmffRuleDef, profile: BmffProfile): ConformanceRule }
object IsoBmffConformanceChecker {
    fun check(reader: ByteReader): ConformanceReport   // format "ISO-BMFF" or "QuickTime", profile text "ISO/IEC 14496-12 phase 1" / "QuickTime File Format phase 1"
    internal fun profileOf(tree: BmffTree, reader: ByteReader): BmffProfile
}
```
`reference` holds the section hint, e.g. `"ISO/IEC 14496-12 §8.2.1 (moov), 판에 따라 다를 수 있음"`; QT rows use the QTFF chapter name (e.g. `"QTFF: Movie Atoms"`). Section hints: box 4.2, ftyp 4.3, mdat 8.1.1, free 8.1.2, moov 8.2.1, mvhd 8.2.2, trak 8.3.1, tkhd 8.3.2, mdia 8.4.1, mdhd 8.4.2, hdlr 8.4.3, minf 8.4.4, nmhd 8.4.5.2, stbl 8.5.1, stsd 8.5.2, stts 8.6.1.2, ctts 8.6.1.3, stss 8.6.2, edts 8.6.5, elst 8.6.6, dinf 8.7.1, dref 8.7.2, stsz 8.7.3, stsc 8.7.4, stco 8.7.5, mvex 8.8.1, vmhd 12.1.2, smhd 12.2.2, hmhd 12.4.2, sthd 12.6.2.

Rules implemented in this task (exact semantics in the spec table): `bmff.box.size`, `bmff.box.size0`, `bmff.box.trailing`, `bmff.ftyp.present`, `bmff.ftyp.position`, `bmff.ftyp.brands`, `bmff.moov.count`, `bmff.mvhd.count`, `bmff.trak.count`, `bmff.trak.required`, `bmff.mediaheader.match`, `bmff.box.parent`. Allowed-parent table (ISO; QT adds `wide` anywhere top-level and `hdlr` in `minf`): `ftyp`,`moov`,`mdat`,`free`,`skip`,`uuid`,`meta`,`moof`,`mfra`,`sidx`,`styp`,`pdin`→top; `mvhd`,`trak`,`udta`,`meta`,`mvex`,`iods`→`moov`; `tkhd`,`mdia`,`edts`,`tref`,`udta`,`meta`→`trak`; `elst`→`edts`; `mdhd`,`hdlr`,`minf`→`mdia`; `vmhd`,`smhd`,`hmhd`,`nmhd`,`sthd`,`gmhd`(QT),`dinf`,`stbl`→`minf`; `dref`→`dinf`; `stsd`,`stts`,`ctts`,`stss`,`stsc`,`stsz`,`stz2`,`stco`,`co64`,`sdtp`,`sbgp`,`sgpd`,`subs`,`saiz`,`saio`,`cslg`,`stps`(QT),`stsh`→`stbl`; `free`/`skip`/`uuid` allowed in any container. Boxes not in the table → PASS row detail lists them as "알 수 없는 박스(무시 허용)".
`bmff.box.size0`: the walker treats a nested size-0 box as running to its parent's end and records `rawSize == 0`; the rule FAILs for any box with `rawSize == 0` and a non-null parent.
Each rule emits exactly one `ConformanceFinding` (aggregate all violations into its `details`, one line each, with byte offsets; `ranges` hold the offending boxes' byte ranges).

- [ ] **Step 1: Failing tests** — one test per case, using `buildMovie(MovieSpec(...))` and `fun check(bytes) = ByteReader.open(fileOf(bytes)).use { IsoBmffConformanceChecker.check(it) }`, `fun ConformanceReport.status(id) = findings.single { it.rule.id == id }.status`:
  - default movie → no FAIL/WARN findings at all, profile ISO, every `rule.source` = the ISO source string;
  - `dropBox = "moov"` → `bmff.moov.count` FAIL; `extraMoov = listOf(<a second mvhd>)` → `bmff.mvhd.count` FAIL; `dropBox = "trak"` → `bmff.trak.count` FAIL; `dropBox = "stsz"` / `"stco"` / `"dinf"` / `"hdlr"` (each) → `bmff.trak.required` FAIL with the box name in details;
  - `handler = "soun"` with the default vmhd → `bmff.mediaheader.match` FAIL; `mediaHeader = fullBox("smhd",0,0,ByteArray(4))` with `handler = "soun"` → PASS;
  - `vmhdInStbl = true` → `bmff.box.parent` FAIL;
  - `ftyp = null` → `bmff.ftyp.present` WARN (ISO); `ftyp = null, trailing = box("ftyp", "isom".toByteArray(), be32(0))` (ftyp after moov) → `bmff.ftyp.position` FAIL; `ftyp = box("ftyp","isom".toByteArray(), be32(0), "ab".toByteArray())` (size not 16+4n) → `bmff.ftyp.brands` FAIL; `ftyp` with major `zzzz` and compatible `zzzz` → `bmff.ftyp.brands` WARN;
  - `trailing = ByteArray(20) { 0x0E }` → `bmff.box.trailing` FAIL; `trailing = be32(0) + "free".toByteArray()` (a top-level size-0 box, which by definition runs to EOF) → `bmff.box.size0` PASS; `extraMoov = listOf(be32(0) + "free".toByteArray())` (a size-0 box nested inside `moov`) → `bmff.box.size0` FAIL — size 0 is only defined for top-level boxes;
  - QuickTime: `ftyp = box("ftyp", "qt  ".toByteArray(), be32(0), "qt  ".toByteArray())`, `handlerPreDefined = "mhlr"`, plus an extra `hdlr` (`dhlr`/`url `) inside `minf`… (add a `MovieSpec` field `qtDataHandler: Boolean = false` if needed) → profile QUICKTIME, no FAIL/WARN, sources = the QT string; the same file without `ftyp` → QT profile and `bmff.ftyp.present` PASS with detail "QuickTime: 선택 사항".
- [ ] **Step 2: RED**, **Step 3: implement** (`profileOf`, rule catalog, the 12 rules; findings for rules implemented in later tasks are not emitted yet), **Step 4: GREEN**, **Step 5: commit** `feat: check ISO-BMFF box structure, ftyp and required boxes`.

---

### Task 3: Header-field rules

**Files:** Modify `IsoBmffConformanceChecker.kt` (+ helpers in a new `IsoBmffFieldRules.kt` if the checker exceeds ~400 lines); Test: `IsoBmffFieldRulesTest.kt`.

Rules: `bmff.fullbox.version`, `bmff.mvhd.timescale`, `bmff.mdhd.timescale`, `bmff.tkhd.track_id`, `bmff.mvhd.next_track_id`, `bmff.vmhd.flags`, `bmff.hdlr.predefined`, `bmff.mdhd.language`, `bmff.dref.entries`, `bmff.stsd.entries`. Field offsets (version 0 / version 1): mvhd timescale payload+12 / +20, next_track_ID = last 4 bytes; tkhd track_ID +12 / +20; mdhd timescale +12 / +20, language +20 / +32 (16 bits: pad bit 15, chars bits 14-10, 9-5, 4-0); hdlr pre_defined +4, handler_type +8; dref entry_count +4 then entries; stsd entry_count +4 then sample entries.

- [ ] **Step 1: Failing tests** (default movie passes all of them; then one violation each): `mvhdVersion = 2` → version FAIL; `movieTimescale = 0` / `mediaTimescale = 0` → timescale FAIL (and sample/duration rules must not divide by zero — they report NOT_CHECKED); `trackId = 0` → track_id FAIL; two tracks with the same ID (extend `MovieSpec` with `secondTrackId: Long? = null` producing a second identical `trak`) → FAIL; `nextTrackId = 1` → next_track_id FAIL; `nextTrackId = 0xFFFFFFFF` → PASS; `mediaHeader = fullBox("vmhd",0,0,ByteArray(8))` → vmhd.flags FAIL; `handlerPreDefined = "mhlr"` in ISO profile → hdlr.predefined FAIL, but PASS in QT profile; `language = 0` → language WARN (ISO); in QT profile `language = 0` (Mac code English) PASS and `0x7FFF` PASS; `language = 0x55C4 or 0x8000` (pad bit) → WARN; `drefEntry = fullBox("url ", 0, 0)` (external) → `bmff.dref.entries` PASS with detail "외부 데이터 참조" and later sample-location rules NOT_APPLICABLE; dref entry_count 0 → FAIL; stsd entry_count 0 → FAIL.
- [ ] **Step 2–5:** RED, implement, GREEN, commit `feat: check ISO-BMFF header field constraints`.

---

### Task 4: Sample-table, edit-list, duration and fragmented rules

**Files:** New `IsoBmffSampleRules.kt` (streamed table reads with the 2,000,000-entry cap), wire into the checker; Test: `IsoBmffSampleRulesTest.kt`.

Rules: `bmff.stbl.sample_count`, `bmff.stsc.order`, `bmff.stsc.coverage`, `bmff.ctts.count`, `bmff.stss.order`, `bmff.sample.location`, `bmff.mdat.present`, `bmff.elst.entries`, `bmff.duration.consistency`, `bmff.fragmented`. Sample location: expand chunks via stsc × stco/co64 and sizes via stsz (fixed `sample_size` or table) / `stz2` (field_size 4/8/16); each sample range must be inside the file (FAIL) and inside some top-level `mdat` payload (WARN). Fragmented (`mvex` in `moov` or any top-level `moof`) → `bmff.fragmented` PASS-with-detail plus every sample rule NOT_APPLICABLE.

- [ ] **Step 1: Failing tests** (default passes all): `sttsCounts = listOf(2)` → sample_count FAIL; `stsc = listOf(Triple(2,3,1))` → stsc.order FAIL; `stsc = listOf(Triple(1,2,1))` (covers 2 of 3) → stsc.coverage FAIL; `stsc` with sample_description_index 2 → stsc.order FAIL; `ctts = listOf(2L to 0L)` → ctts.count FAIL; `stss = listOf(2,1)` / `listOf(4)` / `listOf(0)` → stss.order FAIL; `chunkOffsetDelta = 10_000` → sample.location FAIL; `chunkOffsetDelta = -8` (inside file but in mdat header) → sample.location WARN; `dropBox = "mdat"` with valid tables → mdat.present FAIL (and sample.location FAIL); `elst = listOf(Triple(300, -1, 1), Triple(300, 0, 1))` PASS; `elst = listOf(Triple(300, -2, 1))` / rate 2 / media_time ≥ media duration → elst.entries FAIL; elst entry_count larger than the box → FAIL; no edts and tkhd duration off by 50 movie ticks (add `MovieSpec.tkhdDurationDelta: Long = 0`) → duration.consistency WARN, with edts → PASS-not-applied (NOT_APPLICABLE); `extraMoov = listOf(box("mvex", fullBox("trex", 0, 0, ByteArray(20))))` → fragmented PASS + sample rules NOT_APPLICABLE; a 2,000,001-entry `stsz` is not built — instead unit-test the cap on the reader helper with a fake count field (count 2_000_001, short box) → NOT_CHECKED "표 항목 상한 초과" (and not a FAIL from the overflow itself; the separate `bmff.box.size` already covers truncation).
- [ ] **Step 2–5:** RED, implement, GREEN, commit `feat: check ISO-BMFF sample tables and edit lists`.

---

### Task 5: Wire into Content Check, CLI JSON and the AI snapshot

**Files:** Modify `app/src/main/kotlin/com/multiviewer/ui/ContentCheckPlan.kt` (`contentConformanceReport`), `ui/ContentCheckWindow.kt` (`runStructure` computes the ISO-BMFF report inside its existing `runInterruptible(Dispatchers.IO)` block and passes it in), `cli/CheckFile.kt` + JSON builder (new `containerConformance` object), tests `ContentCheckPlanTest.kt`, `cli/CheckFileTest.kt` (or the existing CLI JSON test file).

**Interfaces:** `fun contentConformanceReport(type, structure = null, audioStructure = null, inspectionError = null, isoBmff: ConformanceReport? = null): ConformanceReport` — when `isoBmff != null` it is returned (with the existing Samsung-limitation line appended to `limitations`) instead of the parser-warning-only report. `fun isoBmffConformanceFor(file: File): ConformanceReport?` (blocking; returns null unless `confidentFormat(file)` is `MP4`, `MOV` or `M4A`; catches `Exception` except CancellationException into `ConformanceReport(..., inspectionError = message)`).

- [ ] **Step 1: Failing tests:** `contentConformanceReport(MediaType.VIDEO, isoBmff = report)` returns its findings; `isoBmffConformanceFor` returns null for a JPEG and non-null for `buildMovie()` written to `x.mp4`; CLI `checkFile(<x.mp4>)` JSON contains `"containerConformance"` with `"ruleId": "bmff.moov.count"` and `"status": "PASS"`, and for a moov-less file `"status": "FAIL"`; `contentCheckPromptSection` lists a FAIL ISO-BMFF finding by its title.
- [ ] **Step 2–5:** RED, implement, GREEN (also `./gradlew :app:compileKotlin`), commit `feat: show ISO-BMFF conformance in Content Check and CLI`.

---

### Task 6: Real-file fixtures, corrupted variants, manual check

**Files:** Create `docs/validation/isobmff-conformance-fixtures/{generate.sh,corrupt.py}`, `app/src/test/resources/isobmff-conformance/*`, test `app/src/test/kotlin/com/multiviewer/parser/conformance/isobmff/IsoBmffFixtureTest.kt`.

- [ ] **Step 1:** `generate.sh` makes ffmpeg-default tiny files: `v.mp4` (64x48, 8 frames, libx264 + aac), `v.mov` (same, `.mov`), `a.m4a` (aac 0.5 s), `v.3gp` (h263 176x144 + aac 8 kHz mono, 0.5 s). `corrupt.py` derives, by locating boxes in the bytes: `no-moov.mp4` (moov type → `free`), `dup-mvhd.mp4` (moov with mvhd duplicated, sizes fixed), `track-id-0.mp4`, `stts-mismatch.mp4` (first stts count −1), `stsc-gap.mp4` (first_chunk of entry 1 → 2), `stco-past-eof.mp4`, `vmhd-flags-0.mp4`, `size0-nested.mp4` (a `free` box with size field 0 appended inside `moov`, moov size fixed), `trailing-garbage.mp4` (+20 bytes 0x0E). Keep each under ~20 KB.
- [ ] **Step 2: Test:** the four defaults → no FAIL and no WARN findings (`v.mov` in QT profile); each corrupted file → exactly the targeted rule is FAIL (other FAILs allowed only when they are direct consequences, e.g. no-moov also fails `trak.count` — assert the targeted rule, and assert no unexpected FAIL outside an explicit allow-list per file).
- [ ] **Step 3: Manual** (not committed): run `./gradlew -q :app:run --args="check <file>"` on copies of `~/Downloads/20260718_200431_motion.mp4` (Samsung camera, expect only `bmff.mdhd.language` WARN) and `~/Downloads/temp_1792216678145.mp4` (expect clean); record results in the task report; if they differ from the spec's "Observed real files", report instead of changing rules.
- [ ] **Step 4:** full `./gradlew :app:test`, commit `test: add ISO-BMFF conformance real-file fixtures`.
