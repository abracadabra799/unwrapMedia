# HEIF/AVIF Conformance (phase 1) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Content Check's Container Conformance row checks HEIC/HEIF/AVIF item structure rule by rule (HEIF public
knowledge + AVIF v1.2.0 original text), reusing the ISO-BMFF walker and box-structure rules; every rule carries a
clickable spec citation (Task 1b).

**Architecture:** New package `com.multiviewer.parser.conformance.heif`: `HeifItemModel.kt` (reads `meta` from the
ISO-BMFF `BmffTree` into items / iloc / properties / associations / references), `HeifRules.kt` (catalog),
`HeifConformanceChecker.kt` (`check(reader): ConformanceReport`). `isoBmffConformanceFor` becomes
`containerConformanceFor` and dispatches HEIF/AVIF content to the new checker.

**Tech Stack:** Kotlin, JDK 21, kotlin.test; ffmpeg (libsvtav1) and macOS `sips` for fixtures.

Spec: `docs/superpowers/specs/2026-10-24-heif-avif-conformance-design.md` (rule table = authoritative).

## Global Constraints

- Status mapping as ISO-BMFF: violated FAIL/WARN rule → `ConformanceStatus.FAIL`/`WARN`; satisfied → `PASS`; not evaluable → `NOT_CHECKED` with reason (never PASS); not applicable → `NOT_APPLICABLE`. One `ConformanceFinding` per rule; violations aggregated in `details` with byte offsets, `ranges` = offending boxes/extents.
- `ConformanceRule.source`: HEIF rules exactly `"ISO/IEC 23008-12 (공개 자료 기반, 원문 미대조)"`; AVIF rules exactly `"AVIF v1.2.0 (AOMedia)"`; reused ISO-BMFF box rules keep `"ISO/IEC 14496-12 (공개 자료 기반, 원문 미대조)"`. `reference`: HEIF section hint ("ISO/IEC 23008-12 §… (판에 따라 다를 수 있음)") or AVIF section ("AVIF v1.2.0 §7").
- `ConformanceRule.scope` ∈ {`"ftyp"`, `"meta 구조 / meta structure"`, `"항목 / Items"`, `"항목 위치 / Item locations"`, `"속성 / Properties"`, `"참조 / References"`, `"파생 항목 / Derived items"`, `"AVIF"`} (reused box rules keep their ISO-BMFF scope).
- Report `format` = `"AVIF"` when the sniffer says AVIF (or major/compatible `avif`/`avis` with an `av01` primary), else `"HEIF"`; `profile` = `"ISO/IEC 23008-12 + AVIF v1.2.0 phase 1"` / `"ISO/IEC 23008-12 phase 1"`.
- Every HEIF/AVIF rule has a `SpecCitation` (Task 1b data); never invent anchors or pages.
- Titles bilingual `"한국어 / English"`, details Korean. Never throw out of the checker except `java.util.concurrent.CancellationException` (other exceptions → `inspectionError`). Bounded reads: cap item / property / association / reference counts at 1,000,000 (→ NOT_CHECKED). Phase 1 never decodes coded item data (only the grid descriptor bytes).
- Must stay free of FAIL on: ffmpeg libsvtav1 AVIF, macOS `sips` HEIC, real Samsung Galaxy HEICs (grid + tmap + auxl + cdsc + idat, trailing free/mpvd/sefd).
- Work in `.worktrees/heif-conformance` on branch `feat/heif-conformance`; `git branch --show-current` before every commit; trailer `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

---

### Task 1: Synthetic HEIF builder and the item model

**Files:** Create `app/src/main/kotlin/com/multiviewer/parser/conformance/heif/HeifItemModel.kt`,
`app/src/test/kotlin/com/multiviewer/parser/conformance/heif/SyntheticHeif.kt`, test `HeifItemModelTest.kt`.

**Interfaces (produces):**
```kotlin
data class HeifItemInfo(val id: Long, val type: String, val infeVersion: Int, val hidden: Boolean, val infe: BmffBox)
data class HeifExtent(val offset: Long, val length: Long)            // absolute for method 0, idat-relative for method 1
data class HeifLocation(val itemId: Long, val method: Int, val dataReferenceIndex: Int, val baseOffset: Long, val extents: List<HeifExtent>)
data class HeifProperty(val index: Int /*1-based*/, val type: String, val box: BmffBox)
data class HeifAssociation(val itemId: Long, val propertyIndex: Int, val essential: Boolean)
data class HeifReference(val type: String, val from: Long, val to: List<Long>, val box: BmffBox)
data class HeifModel(
    val meta: BmffBox?, val metaCount: Int, val handler: String?, val primaryId: Long?,
    val items: List<HeifItemInfo>, val iinfEntryCount: Long?, val locations: List<HeifLocation>, val ilocVersion: Int?,
    val properties: List<HeifProperty>, val associations: List<HeifAssociation>, val ipmaOrder: List<Long>,
    val references: List<HeifReference>, val idat: BmffBox?, val problems: List<String>,
)
object HeifItemModel { fun read(tree: BmffTree, reader: ByteReader): HeifModel }
```
Field layouts: `pitm` v0 u16 / v1 u32; `iinf` v0 u16 count / else u32; `infe` v2: item_ID u16, protection_index u16, item_type 4cc; v3: item_ID u32, …; hidden = flags & 1. `iloc`: byte0 = offset_size<<4 | length_size, byte1 = base_offset_size<<4 | (index_size for v1/v2, else reserved); item_count u16 (v<2) / u32 (v2); per item: item_ID u16/u32, (v1/v2: u16 with construction_method in low 4 bits), data_reference_index u16, base_offset, extent_count u16, per extent: (index if index_size>0 and v≥1), extent_offset, extent_length. `iprp` is a plain container holding `ipco` (plain) and `ipma` FullBoxes: entry_count u32; item_ID u16 (v0) / u32 (v≥1); association_count u8; each association u8 (bit7 essential, 7-bit index) or u16 when flags & 1 (bit15 essential, 15-bit index). `iref` FullBox v0 (u16 ids) / v1 (u32 ids): children are SingleItemTypeReferenceBox(type): from_item_ID, reference_count u16, to_item_ID × count. Every malformed piece adds a `problems` entry and is skipped — never throws.

- [ ] **Step 1: Write the synthetic builder** (`SyntheticHeif.kt`, reusing `box`, `fullBox`, `be16`, `be32` from `com.multiviewer.parser.conformance.isobmff` test helpers):

```kotlin
package com.multiviewer.parser.conformance.heif

import com.multiviewer.parser.conformance.isobmff.be16
import com.multiviewer.parser.conformance.isobmff.be32
import com.multiviewer.parser.conformance.isobmff.box
import com.multiviewer.parser.conformance.isobmff.fullBox

internal data class HeifItem(
    val id: Long, val type: String, val hidden: Boolean = false,
    val data: ByteArray? = ByteArray(16) { 5 }, val method: Int = 0, val infeVersion: Int = 2,
)

internal fun hvcC() = box("hvcC", ByteArray(23))
internal fun av1C() = box("av1C", byteArrayOf(0x81.toByte(), 0, 0, 0))
internal fun ispe(w: Long, h: Long) = fullBox("ispe", 0, 0, be32(w), be32(h))
internal fun pixi(vararg bits: Int) = fullBox("pixi", 0, 0, byteArrayOf(bits.size.toByte()), bits.map { it.toByte() }.toByteArray())
internal fun irot(angle: Int) = box("irot", byteArrayOf(angle.toByte()))
internal fun pasp(h: Long, v: Long) = box("pasp", be32(h), be32(v))
internal fun auxC(urn: String) = fullBox("auxC", 0, 0, urn.toByteArray(Charsets.US_ASCII) + byteArrayOf(0))
internal fun gridData(rows: Int, cols: Int, w: Int, h: Int) =
    byteArrayOf(0, 0, (rows - 1).toByte(), (cols - 1).toByte()) + be16(w) + be16(h)

/** A minimal valid single-item HEIC; every field can be overridden to inject exactly one violation. */
internal data class HeifSpec(
    val major: String = "heic",
    val compatible: List<String> = listOf("mif1", "heic"),
    val items: List<HeifItem> = listOf(HeifItem(1, "hvc1")),
    val primary: Long = 1,
    val props: List<ByteArray> = listOf(hvcC(), ispe(64, 48)),
    val assoc: List<Pair<Long, List<Pair<Int, Boolean>>>> = listOf(1L to listOf(1 to true, 2 to false)), // (1-based index, essential)
    val refs: List<Triple<String, Long, List<Long>>> = emptyList(),
    val handler: String = "pict",
    val dropMetaChild: String? = null,       // "hdlr" | "pitm" | "iinf" | "iloc" | "iprp"
    val ilocVersion: Int = 1,
    val ilocExtentDelta: Long = 0,            // added to every method-0 extent offset
    val extraMetaChildren: List<ByteArray> = emptyList(),
    val extraTop: List<ByteArray> = emptyList(),
)

internal fun buildHeif(s: HeifSpec = HeifSpec()): ByteArray {
    val ftyp = box("ftyp", s.major.toByteArray(), be32(0), *s.compatible.map { it.toByteArray() }.toTypedArray())
    val idatItems = s.items.filter { it.method == 1 && it.data != null }
    val idatPayload = idatItems.fold(ByteArray(0)) { a, it -> a + it.data!! }
    val mdatItems = s.items.filter { it.method == 0 && it.data != null }
    val mdatPayload = mdatItems.fold(ByteArray(0)) { a, it -> a + it.data!! }

    fun meta(mdatStart: Long): ByteArray {
        val hdlr = fullBox("hdlr", 0, 0, be32(0), s.handler.toByteArray(), ByteArray(12), byteArrayOf(0))
        val pitm = fullBox("pitm", 0, 0, be16(s.primary.toInt()))
        val infes = s.items.map { item ->
            fullBox("infe", item.infeVersion, if (item.hidden) 1 else 0, be16(item.id.toInt()), be16(0), item.type.toByteArray(), byteArrayOf(0))
        }
        val iinf = fullBox("iinf", 0, 0, be16(s.items.size), *infes.toTypedArray())
        var mdatCursor = mdatStart
        var idatCursor = 0L
        val located = s.items.filter { it.data != null }
        val ilocEntries = located.map { item ->
            val offset = if (item.method == 0) (mdatCursor + s.ilocExtentDelta).also { mdatCursor += item.data!!.size } else idatCursor.also { idatCursor += item.data!!.size }
            be16(item.id.toInt()) + (if (s.ilocVersion >= 1) be16(item.method) else ByteArray(0)) + be16(0) + be16(1) + be32(offset) + be32(item.data!!.size.toLong())
        }
        val iloc = fullBox("iloc", s.ilocVersion, 0, byteArrayOf(0x44, 0x00), be16(located.size), *ilocEntries.toTypedArray())
        val ipco = box("ipco", *s.props.toTypedArray())
        val ipmaEntries = s.assoc.map { (id, list) ->
            be16(id.toInt()) + byteArrayOf(list.size.toByte()) + list.map { (idx, ess) -> ((if (ess) 0x80 else 0) or idx).toByte() }.toByteArray()
        }
        val ipma = fullBox("ipma", 0, 0, be32(s.assoc.size.toLong()), *ipmaEntries.toTypedArray())
        val iprp = box("iprp", ipco, ipma)
        val iref = if (s.refs.isEmpty()) ByteArray(0) else fullBox("iref", 0, 0, *s.refs.map { (type, from, to) ->
            box(type, be16(from.toInt()), be16(to.size), *to.map { be16(it.toInt()) }.toTypedArray())
        }.toTypedArray())
        val idat = if (idatItems.isEmpty()) ByteArray(0) else box("idat", idatPayload)
        fun keep(name: String, b: ByteArray) = if (s.dropMetaChild == name) ByteArray(0) else b
        return fullBox("meta", 0, 0, keep("hdlr", hdlr), keep("pitm", pitm), keep("iinf", iinf), keep("iloc", iloc),
            keep("iprp", iprp), iref, idat, *s.extraMetaChildren.toTypedArray())
    }
    val head = ftyp + s.extraTop.fold(ByteArray(0)) { a, b -> a + b }
    val probe = meta(0)
    val metaBytes = meta(head.size + probe.size + 8L)
    return head + metaBytes + box("mdat", mdatPayload)
}
```

- [ ] **Step 2: Failing model tests** (`HeifItemModelTest.kt`): default spec → 1 item (id 1, hvc1, v2, not hidden), primary 1, handler pict, 1 location whose extent bytes are the mdat payload (read back and compare), properties [hvcC, ispe] with indices 1,2, associations (1,1,true),(1,2,false), no problems; a grid spec (`items = listOf(HeifItem(1,"hvc1",hidden=true), HeifItem(2,"hvc1",hidden=true), HeifItem(10,"grid",data=gridData(1,2,128,48),method=1))`, `primary = 10`, `refs = listOf(Triple("dimg",10,listOf(1,2)))`) → idat present, location 10 method 1 with idat-relative extent, reference dimg 10→[1,2]; ipma with flags & 1 (16-bit associations) parsed; `iloc` version 0 entries parsed; truncated ipma (count larger than box) → a `problems` entry, no throw; a real file: copy of `app/src/test/resources/isobmff-conformance/` has no HEIF — generate in the test via `sips` only if `/usr/bin/sips` exists (assume; skip with `org.junit.jupiter.api.Assumptions.assumeTrue`) and assert one `hvc1` item with `hvcC` + `ispe` properties.
- [ ] **Step 3–5:** RED, implement `HeifItemModel.read` (use `BmffTree.top` to find `meta`; walk its children via the existing `BmffBox.children` — `meta`/`iprp`/`ipco`/`iinf`/`iref` children may not be in `BMFF_CONTAINERS`; if not, parse their children with a local bounded box loop over the payload), GREEN, commit `feat: read HEIF item metadata model`.

---

### Task 1b: Spec citation model and plumbing

Spec: `docs/superpowers/specs/2026-10-24-spec-citations-design.md` (authoritative).

**Files:** modify `app/src/main/kotlin/com/multiviewer/parser/conformance/ConformanceModel.kt` (add `SpecCitation`, `ConformanceRule.citation`), `ui/ContentFinding.kt` (`ContentFinding.citation`), `ui/ContentFindingMappers.kt` (`conformanceFindings` copies the citation), `ui/ContentFindingTable.kt` (clickable source line), `ui/ContentCheckSnapshot.kt` (prompt line), `cli/CheckFile.kt` (`containerConformanceJson` citation object), `parser/motionphoto/GoogleMotionPhotoSpecChecker.kt` (`GoogleSpecFinding.citation`, default null) and the motion-photo row rendering (`ui/SefIntegrityTableComponents.kt` CheckSection/CheckRow or a citation-aware variant); tests in the matching test files.

**Interfaces (produces):** `data class SpecCitation(document: String, section: String, page: Int?, url: String, verified: Boolean)`; `ConformanceRule(..., citation: SpecCitation? = null)`; `ContentFinding(..., citation: SpecCitation? = null)`; `GoogleSpecFinding(..., citation: SpecCitation? = null)`; `fun openCitation(url: String)` (ui, background thread `Desktop.browse`, clipboard fallback); `fun SpecCitation.label(): String` = "{document} {section}" + (", p. N" when page != null) + (" (원문 미대조 / not checked against the original)" when !verified).

- [ ] **Step 1: Failing tests:** mapper copies the citation; JSON for a finding with a citation contains `"citation": {"document": …, "section": …, "page": 7, "url": "…#page=7", "verified": true}` and omits `page` when null and omits `citation` when null; prompt section shows "출처:" with the URL for a FAIL row and not for PASS rows; `label()` formats with and without page/verified; `openCitation` with an invalid URI does not throw (unit-test the URI parsing helper, not Desktop).
- [ ] **Step 2–4:** RED; implement (UI: the source line is a Material3 `Text` with underline + `pointerHoverIcon(Hand)` + clickable → `openCitation`; structural observations show the "unwrapMedia 자체 관찰 / unwrapMedia structural observation (규격 아님)" label instead); GREEN + `:app:compileKotlin`.
- [ ] **Step 5:** commit `feat: add clickable spec citations to conformance findings`.

**Citation data for this branch (used by Tasks 2–4):** every HEIF/AVIF rule gets a citation.
- HEIF rules: `SpecCitation("ISO/IEC 23008-12:2025", "<clause hint>", null, "<ISO store page of the current edition — verify with curl -sI that it returns 200>", false)`.
- AVIF rules (verified anchors, `https://aomediacodec.github.io/av1-avif/v1.2.0.html#…`, document `"AVIF v1.2.0 (AOMedia, 2025-10-16)"`, page null, verified true):
  `avif.ftyp.miaf`, `avif.ftyp.avif` → "§7 General constraints" `#file-constraints`; `avif.primary` → "§6.2 AVIF image and image collection brand" `#image-and-image-collection-brand`; `avif.props.av1c_essential` → "§2.2.1 AV1 Item Configuration Property" `#av1-item-configuration-property`; `heif.props.codec_config` for av01 items and AVIF files → "§2.1 AV1 Image Item" `#image-item`; `avif.props.pixi` → "§9.1.1 Minimum set of boxes" `#avif-required-boxes`; `avif.props.pasp` → "§9.1.2 Requirements on additional image item related boxes" `#avif-required-boxes-additional`; `avif.props.a1op` → "§2.3.2.1 Operating Point Selector Property" `#operating-point-selector-property`; `avif.props.a1lx` → "§2.3.2.3 Layered Image Indexing Property" `#layered-image-indexing-property`; `avif.alpha.auxc` → "§4.1 Auxiliary Image Items and Sequences" `#auxiliary-images`.
- Reused ISO-BMFF box rules keep their existing (citation-less) definitions on this branch; the retrofit branch adds them.

---

### Task 2: Catalog, checker, ftyp / meta / items / iloc rules (+ reused box rules)

**Files:** Create `HeifRules.kt`, `HeifConformanceChecker.kt`; modify `isobmff/IsoBmffConformanceChecker.kt` only to expose the four box rule functions (`boxSize`, `boxSize0`, `boxTrailing`, `ftypPosition`) as `internal` (no behaviour change); test `HeifStructureRulesTest.kt`.

Rules: reused `bmff.box.size`/`bmff.box.size0`/`bmff.box.trailing`/`bmff.ftyp.position` (ISO profile), `heif.ftyp.mif1`, `heif.ftyp.codec_brand`, `heif.meta.present`, `heif.meta.required`, `heif.items.ids`, `heif.items.primary`, `heif.iloc.items`, `heif.iloc.method`, `heif.iloc.extents` (semantics: spec table). Derived item types that carry no `iloc` entry are allowed only for types whose data is optional (`iden`); `grid`/`iovl` need data; metadata items (`Exif`, `mime`, `uri `) need data.

- [ ] **Step 1: Failing tests** (`fun check(bytes) = ByteReader.open(fileOf(bytes)).use { HeifConformanceChecker.check(it) }`): default → no FAIL/WARN, format "HEIF", sources/scope per constraints; `compatible = listOf("heic")` → `heif.ftyp.mif1` WARN; HEVC primary with `compatible = listOf("mif1")`, `major = "mif1"` → `heif.ftyp.codec_brand` WARN; two metas (`extraTop = listOf(fullBox("meta",0,0))` placed before) → `heif.meta.present` FAIL; `dropMetaChild = "pitm"` / `"iloc"` → `heif.meta.required` FAIL; `handler = "vide"` → FAIL; duplicate item ids → `heif.items.ids` FAIL; `infeVersion = 1` → FAIL; `primary = 9` → `heif.items.primary` FAIL; primary hidden → FAIL; primary of type `Exif` → FAIL; an `hvc1` item with `data = null` → `heif.iloc.items` FAIL; `ilocVersion = 0` with an item `method = 1` → `heif.iloc.method` FAIL; method 1 without idat (build manually) → FAIL; `ilocExtentDelta = 10_000` → `heif.iloc.extents` FAIL; a 2nd ftyp after meta → `bmff.ftyp.position` FAIL.
- [ ] **Step 2–5:** RED, implement, GREEN, commit `feat: check HEIF ftyp, meta, items and item locations`.

---

### Task 3: Properties, references and derived items

**Files:** `HeifPropertyRules.kt` (new), register in the checker; test `HeifPropertyRulesTest.kt`.

Rules: `heif.ipma.refs`, `heif.props.ispe`, `heif.props.codec_config`, `heif.props.essential`, `heif.props.unique`, `heif.iref.targets`, `heif.derived.grid`, `heif.derived.inputs` (spec table). Image item types: coded (`hvc1`, `av01`, `j2k1`, `vvc1`, `jpeg`, `unci`, `avc1`) + derived (`grid`, `iovl`, `iden`, `tmap`). Codec config map: hvc1→hvcC, av01→av1C, vvc1→vvcC, j2k1→j2kH, avc1→avcC; `jpeg`/`unci` → NOT_APPLICABLE (phase 2). Grid descriptor: version u8 (must be 0), flags u8 (bit0 → 32-bit output sizes), rows_minus_one u8, columns_minus_one u8, output_width/height u16 or u32.

- [ ] **Step 1: Failing tests:** default passes all; ipma naming item 7 → `heif.ipma.refs` FAIL; property index 3 with 2 properties → FAIL; ipma item ids 2 then 1 → FAIL; item listed twice → FAIL; item without ispe → `heif.props.ispe` FAIL; two ispe → FAIL; hvc1 item without hvcC → `heif.props.codec_config` FAIL; `irot` not essential → `heif.props.essential` WARN; two `irot` on one item → `heif.props.unique` WARN; `thmb` from undeclared id → `heif.iref.targets` FAIL; `auxl` source without auxC → FAIL; grid 1×2 with dimg of 3 tiles → `heif.derived.grid` FAIL; tiles with different ispe → FAIL; output 300×48 with 2 tiles of 128×48 → FAIL; grid ispe ≠ output size → FAIL; grid descriptor version 1 → FAIL; `tmap` with one input → `heif.derived.inputs` FAIL; `iovl` input missing → FAIL; a Samsung-shaped spec (40 hidden tiles → use 4 tiles 2×2, grid primary, thumbnail with thmb, hidden aux grid via auxl with auxC, tmap with inputs [grid, aux grid], Exif + mime cdsc items, all ispe essential) → no FAIL and no WARN.
- [ ] **Step 2–5:** RED, implement, GREEN, commit `feat: check HEIF properties, references and derived items`.

---

### Task 4: AVIF rules

**Files:** `HeifAvifRules.kt` (new), register; test `HeifAvifRulesTest.kt`.

Rules: `avif.ftyp.miaf`, `avif.ftyp.avif`, `avif.primary`, `avif.props.av1c_essential`, `avif.props.pixi`, `avif.props.pasp`, `avif.props.a1op`, `avif.props.a1lx`, `avif.alpha.auxc` (spec table; references are the AVIF sections). Applies when the report format is AVIF; for HEIF files every AVIF rule is `NOT_APPLICABLE`. Alpha URNs accepted: `urn:mpeg:mpegB:cicp:systems:auxiliary:alpha` and `urn:mpeg:hevc:2015:auxid:1`; depth `urn:mpeg:mpegB:cicp:systems:auxiliary:depth`; other aux types → PASS (not alpha).

- [ ] **Step 1: Failing tests** with an AVIF default (`major = "avif"`, `compatible = listOf("avif","mif1","miaf","MA1B")`, item `av01`, props `[ispe(64,48), pixi(8,8,8), av1C(), colr]` with av1C essential): passes all; no `miaf` → FAIL; no `avif`/`avis` (major `mif1`) → FAIL; primary `hvc1` in an AVIF-branded file → `avif.primary` FAIL; grid of av01 tiles as primary → PASS; av1C not essential → WARN; no pixi → FAIL; `pasp(2,1)` → FAIL; `a1op` non-essential (`box("a1op", byteArrayOf(0))`) → FAIL; `a1lx` essential → FAIL; auxl alpha item with `auxC("urn:foo")` → `avif.alpha.auxc` PASS (not alpha) but with `auxC("urn:mpeg:mpegB:cicp:systems:auxiliary:alpha")` → PASS and with a misspelled alpha URN `…auxiliary:alfa` → FAIL; HEIF default → all `avif.*` NOT_APPLICABLE.
- [ ] **Step 2–5:** RED, implement, GREEN, commit `feat: check AVIF item rules`.

---

### Task 5: Wiring, fixtures, manual check, spec sync

**Files:** modify `ui/ContentCheckPlan.kt` (rename `isoBmffConformanceFor` → `containerConformanceFor`; HEIF/AVIF → `HeifConformanceChecker`; MP4/MOV/M4A → unchanged), its callers (window, CLI) and tests; ensure image tabs now get `containerConformance` in CLI/case/prompt (they previously had only the structural report). Create `docs/validation/heif-conformance-fixtures/{generate.sh,corrupt.py}`, `app/src/test/resources/heif-conformance/*`, test `HeifFixtureTest.kt`. Update the spec.

- [ ] **Step 1:** `generate.sh`: ffmpeg `-f lavfi -i testsrc2=s=64x48 -frames:v 1 -c:v libsvtav1 still.avif`; `sips -s format heic` on a 64x48 PNG → `sips.heic` (skip with a message when `sips` is missing; commit the generated file). `corrupt.py` (`python3 -I`, fixing enclosing box sizes): from `sips.heic` → `no-ispe.heic` (ipma association to ispe removed), `hidden-primary.heic` (infe flags |= 1), `ipma-index.heic` (an association index → ipco count + 1), `iloc-past-eof.heic`; from `still.avif` → `no-miaf.avif` (miaf brand → `xxxx`), `no-av1c.avif` (ipma association to av1C removed), `pasp-2-1.avif` (append `pasp` 2:1 to ipco and associate it).
- [ ] **Step 2: Tests:** `still.avif`, `sips.heic` → no FAIL/WARN (AVIF format for still.avif); each corrupted file → exactly its targeted rule FAILs (explicit allow-list only for direct consequences). Window/CLI wiring tests: a HEIC file gets a `containerConformance` object with format "HEIF"; AVIF → "AVIF"; MP4 unchanged.
- [ ] **Step 3: Manual** (copies, never committed): `./gradlew -q :app:run --args="check <file>"` on `~/Downloads/20261012_223511.heic`, `20260715_223828.heic`, `20260715_223835_motion.heic`, `20261012_131852_s26.heic` — record status and every non-PASS row; expected no FAIL. Report differences instead of changing rules.
- [ ] **Step 4:** Update the spec's rule table and "Observed real files" with what was implemented and measured. Full `./gradlew :app:test` + `:app:compileKotlin`; commits `feat: show HEIF/AVIF conformance in Content Check and CLI`, `test: add HEIF/AVIF conformance fixtures`, `docs: sync HEIF/AVIF spec with results`.
