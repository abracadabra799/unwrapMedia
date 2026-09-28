# SEF Integrity Check — Directory Entry Table — Design

**Date:** 2026-09-28
**Status:** Approved (pending user spec review)

## Goal

The existing "SEF 무결성 검사" report window shows the SEFH directory's
per-entry structural checks (bounds, marker match, name_size) as a flat
list of individually-labeled PASS/CRITICAL rows (e.g. "Entry #2 (marker
0x0a30)", "Entry #2 marker match", "Entry #2 name_size" — three separate
rows per entry, scattered among the other structural checks). The user
asked for this to be shown "more clearly": for each SEF entry declared in
the header, see in one place what its declared offset/length are, where
that computes to in the file, whether real SEF data with a matching marker
actually exists there, and whether the declared count of entries matches
what's actually found — replacing the flat per-entry rows with a genuine
table.

## Approach

### Data flow: `SefIntegrityAnalyzer.kt`

`SefIntegrityAnalyzer.analyze()` already computes every value the table
needs internally (in its existing `dirEntries`/`fieldBlocks` local
variables) — it just currently only emits them as flattened `SefCheckResult`
text rows, not as structured data the caller can render as a table. Add a
new data class and two new fields on `SefIntegrityReport`:

```kotlin
data class SefDirectoryEntryRow(
    val entryIndex: Int,
    val markerHex: String,        // e.g. "0x0a31"
    val name: String?,             // null if the block was too short to read its own header/name
    val declaredOffset: Long,      // raw "offset" field from the directory entry (measured backward from SEFH)
    val declaredLength: Long,      // raw "size" field from the directory entry
    val computedDataStart: Long,   // sefhPosition - declaredOffset
    val computedDataEnd: Long,     // computedDataStart + declaredLength
    val inBounds: Boolean,         // computed range falls within the trailer
    val markerMatches: Boolean?,   // block's own marker == directory's declared marker; null if unreachable (out of bounds, or block too short for its own header)
    val status: SefIntegritySeverity, // PASS if inBounds && markerMatches == true && name != null; CRITICAL otherwise
)
```

```kotlin
data class SefIntegrityReport(
    val overallSeverity: SefIntegritySeverity,
    val structuralChecks: List<SefCheckResult>,
    val semanticChecks: List<SefCheckResult>,
    val declaredEntryCount: Long,             // NEW -- SEFH's own declared count
    val directoryEntries: List<SefDirectoryEntryRow>, // NEW -- one row per entry actually walked
)
```

`structuralChecks` keeps every check that is NOT about a single directory
entry's own bounds/marker/name (SEFT tail magic, SEFH header position,
SEFH header magic, field block overlap, field block gaps — all either
whole-trailer checks or cross-entry checks, not naturally one table row
each). The three existing per-entry checks ("Entry #N (marker ...)"
bounds, "Entry #N marker match", "Entry #N name_size") are REMOVED from
`structuralChecks` and their information moves into `directoryEntries`
instead — no duplication between the flat list and the table.
`semanticChecks` (UTC timestamp, MCC, JSON validity, MotionPhoto_Data
bounds) is untouched — those aren't per-directory-entry structural facts,
they're semantic validation of already-confirmed-valid field data.

### Report window: `SefIntegrityWindow.kt`

Above the existing structural-checks section, add:
1. A prominent summary line for the count check: `"SEFH 선언 엔트리 수: N개, 실제 발견: M개"`
   with its own severity badge (PASS if `declaredEntryCount ==
   directoryEntries.size.toLong()`, CRITICAL otherwise) — this is exactly
   today's existing "Directory entry count" check, just promoted to a
   standalone summary line instead of buried in the flat list (also
   removed from `structuralChecks` to avoid duplication).
2. A table (`Row`-per-entry inside a `Column`, header row + one row per
   `directoryEntries` entry) with columns: `#` / 이름 / 마커 / 선언된
   오프셋 / 선언된 길이 / 실제 계산된 위치(시작~끝) / 범위 내 / 마커
   일치 / 상태. The 상태 column shows the same `SeverityBadge` already
   used elsewhere in this window (color-coded PASS/CRITICAL), and a
   CRITICAL row's other columns (범위 내, 마커 일치) render `false`/`null`
   distinctly (e.g. "✗"/"—") so a scanning eye lands on the failing
   column immediately, not just the summary badge.

The rest of the window (SEFT/SEFH/overlap/gap checks, the "필드별 의미론
검사" section) is otherwise unchanged.

## Non-goals

- No new verification logic beyond what `SefIntegrityAnalyzer` already
  computes — this is a display/structuring change, not a new-checks
  change. "Is the declared length accurate" has no independent oracle to
  check it against beyond the existing bounds/marker/name_size validity
  already computed (there is no second, ground-truth source for a generic
  SEF field's length the way `MotionPhoto_Data`'s video length can be
  cross-checked against the real file length) — the table surfaces exactly
  that existing validity signal, it does not invent a new one.
- No change to the semantic-checks section or to the standalone "모션포토
  정합성 검사" window's own embedded SEF section (which delegates to the
  same `SefIntegrityAnalyzer.analyze()` and will automatically pick up the
  new `SefIntegrityReport` fields, but the plan does not add a table to
  that window in this pass — out of scope, that window already has its
  own multi-section layout and wasn't part of this request).

## Error handling

- A directory entry whose computed range is out of bounds: `markerMatches
  = null` (can't check what's out of bounds), `name = null`, `status =
  CRITICAL` — the row still renders (declared offset/length are always
  knowable from the directory entry itself, regardless of whether the
  computed location turned out to be valid).
- A block in-bounds but too short for its own 8-byte header: same
  degrade-to-null-for-unreachable-fields pattern.
- Existing SKIPPED-cascade behavior (e.g. when SEFT/SEFH itself is
  malformed) is unchanged: `directoryEntries` is simply empty and
  `declaredEntryCount` is `0` in that case, matching today's behavior
  where the per-entry checks never ran either.

## Testing

- `SefIntegrityAnalyzerTest.kt` (existing test file, existing
  well-formed-trailer builder helper) gets new/updated assertions
  checking `report.directoryEntries` and `report.declaredEntryCount`
  directly (exact offset/length/computed-position values, `markerMatches`,
  `status`) for: a well-formed trailer (all rows PASS), a trailer with one
  out-of-bounds entry (that row CRITICAL, others PASS), a trailer with a
  marker mismatch (that row CRITICAL with `markerMatches = false`), and a
  trailer where SEFH declares more entries than actually fit (`declaredEntryCount
  > directoryEntries.size`).
- No new tests needed for `SefIntegrityWindow.kt` (Compose UI, matches
  this codebase's existing precedent of no dedicated window tests).
- Manual verification: open a real Samsung SEF file (or the synthesized
  one from prior manual-verification passes) and confirm the table renders
  correctly, all rows show 상태=PASS, and the "SEFH 선언 엔트리 수" summary
  matches the actual entry count.
