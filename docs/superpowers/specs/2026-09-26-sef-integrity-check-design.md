# SEF Integrity Check — Design

**Date:** 2026-09-26
**Status:** Approved (pending user spec review)

## Goal

A new menu item and dedicated analysis window that checks Samsung SEF trailer
data (SEFH header + field data blocks + SEFT tail — embedded either as a JPEG
trailer or a HEIC `sefd` box, both already parsed by the existing
`SefdBoxDecoder`) for **structural integrity** and **semantic value
validity**, and reports the result as a PASS/WARNING/CRITICAL checklist —
distinct from browsing the structure tree, which shows parsed values but
never actively validates them or reports a consolidated verdict.

## Background: what's already there vs. what's missing

`SefdBoxDecoder.kt` already performs several structural checks inline while
parsing (SEFT/SEFH magic presence, directory-entry bounds, block-marker vs.
directory-marker mismatch, name_size overrun), but:
- These checks only ever produce scattered `BoxNode.warnings` strings — there
  is no consolidated report, no PASS confirmation for checks that succeeded,
  and no severity classification.
- SEFH and SEFT are consumed internally to locate field blocks but never
  themselves exposed as inspectable, checkable units.
- There is no check that field blocks' byte ranges don't overlap each other
  (a gap in the existing bounds checks — each entry is checked against the
  trailer's outer bounds, never against its sibling entries).
- There is no semantic validation of decoded values at all — a garbage UTC
  timestamp, an invalid MCC, or a malformed "JSON-shaped" field (the existing
  `isJsonShaped` is a bracket-matching heuristic, not a real parse) currently
  all display exactly like valid data.

## Architecture

Mirrors this codebase's existing `AvSyncAnalyzer.kt` / `AvSyncAnalysisWindow.kt`
pattern (pure analyzer + dedicated Compose window + menu item gated on a
tab-derived boolean):

- **`SefIntegrityAnalyzer.kt`** (new, pure Kotlin, no Compose — unit-testable
  in isolation): takes the already-parsed `sefd` `BoxNode` plus a `ByteReader`
  on the source file, re-walks the trailer (SEFT → SEFH → each directory
  entry → each field block) performing every check below, and returns a
  report: overall verdict + two ordered lists of individual check results
  (`structuralChecks: List<SefCheckResult>`, `semanticChecks: List<SefCheckResult>`),
  each carrying a severity (`PASS`/`WARNING`/`CRITICAL`), a short label, and a
  detail string. Every checkable unit (each directory entry, each field
  block, each semantically-checked field) gets its own line item — normal and
  abnormal alike, none collapsed into a summary count (confirmed with the
  user).
- **`SefIntegrityWindow.kt`** (new): displays the report — overall verdict +
  issue count banner at the top, then a "구조적 검사" (Structural Checks)
  section and a "필드별 의미론 검사" (Semantic Checks) section, each listing
  every check individually with its severity badge, matching
  `AvSyncAnalysisWindow`'s existing visual style (severity color-coding,
  badge component reuse).
- **`MccCountryNames.kt`** (new): a `Map<Int, String>` of MCC → country/area
  name, sourced directly from the official ITU-T E.212 Annex A ("List of
  Mobile Country or Geographical Area Codes"), not a secondary source like
  Wikipedia — the prior SEFD phase found a real error in a Wikipedia-sourced
  version of this table (MCC 450/467, South/North Korea, swapped). Confirmed
  during this design's writing: the official PDF
  (`https://www.itu.int/dms_pub/itu-t/opb/sp/T-SP-E.212A-2017-PDF-E.pdf`, and
  implementation should check for a more recent Annex A bulletin at build
  time since ITU republishes this periodically) is a clean, machine-parseable
  two-column table once run through `pdftotext -layout` (verified: MCC 450 →
  "Korea (Republic of)", MCC 467 → "Democratic People's Republic of Korea",
  confirming the earlier Wikipedia-sourced swap was real and this source is
  correct). Implementation will fetch the current Annex A PDF, extract the
  numerical-order table with `pdftotext -layout`, and generate the Kotlin map
  from it — the source URL and extraction date recorded as a file header
  comment for future audit/refresh.
- **Menu item**: "SEF 무결성 검사" added near the existing "구조 검사"/"AI
  프롬프트 생성" menu group, `enabled = hasSefData` where `hasSefData` is
  computed the same way `isVideo`/`hasGainmap` already are in `Main.kt`
  (`currentTab?.root?.let { findFirst(it) { node -> node.type == "sefd" } } != null`
  — this one boolean covers both the JPEG-trailer and HEIC-box cases
  automatically, since both already parse into the same `"sefd"`-typed node).

## Structural checks

Each runs independently per checkable unit; all results (pass and fail) are
listed, none summarized away:

1. SEFT tail magic ("SEFT") present at the expected position.
2. SEFH header magic ("SEFH") present at the position computed from SEFT's
   `sef_size`.
3. SEFH's declared directory-entry `count` equals the number of entries
   actually parsed.
4. **Per directory entry**: its computed field-block byte range falls within
   the trailer's payload bounds — shown as one line per entry (`✓ Entry #N
   (marker 0x...): offset=..., size=... — within bounds` or `✗ Entry #N
   (marker 0x...): computed range [...] exceeds trailer bounds [...] — field
   block skipped`), confirmed with the user as the exact display shape.
5. **Per field block**: its own embedded marker matches its directory
   entry's marker.
6. **Per field block**: its `name_size` doesn't run past the block's own
   bounds.
7. **(New)** No two field blocks' byte ranges overlap each other — not
   currently checked at all today.
8. **(New, informational only — never CRITICAL)** Gaps between consecutive
   field blocks are reported as INFO, not WARNING/CRITICAL — Samsung's format
   may legitimately pad between blocks, so an unexplained gap alone isn't
   evidence of corruption.

## Semantic checks

Run per known field marker/name, using the already-decoded value:

1. **UTC timestamp** (marker `0x0a01`): epoch value parses, and falls within
   a plausible range (year 2000 through one year from the check's run time)
   — catches garbage/corrupt values and suspiciously-future dates.
2. **MCC** (marker `0x0aa1`): value is a 3-digit number; separately, whether
   it's a currently-assigned MCC in `MccCountryNames`'s table, reporting the
   mapped country/area name when found (`✓ MCC 450: valid code (Korea
   (Republic of)) [ITU E.212 Annex A]`) or a WARNING (not CRITICAL — codes do
   get reserved/retired, an unmapped code isn't necessarily wrong) when the
   3-digit format is right but not present in the table.
3. **JSON-shaped fields** (anything currently passing `isJsonShaped`'s
   bracket-matching heuristic — `ReEditData`, `RemasterInfo`,
   `SamsungCaptureInfo`, `PEgInfo`, etc.): validated with a real, strict,
   hand-rolled recursive-descent JSON syntax checker (no external
   dependency, matching this codebase's established no-new-dependency
   convention for this exact file) — distinct from the existing
   `prettyPrintJson`'s reindenter, which only tracks brace/bracket depth and
   would happily "pretty-print" syntactically invalid JSON. Reports CRITICAL
   with the parse-failure position on malformed JSON.
4. **`MotionPhoto_Data`** (12-byte `format_tag`/`video_offset`/`video_length`):
   `video_offset + video_length` must not exceed the actual source file's
   length.
5. **Text fields generally**: the existing strict-UTF-8 decode (`decodeFieldText`)
   is already a real validity check — surfaced in this report as a PASS/FAIL
   line per text-bearing field rather than a new capability.

## Non-goals

- No gap-detection escalated past INFO severity (see structural check #8) —
  legitimate padding is common and not itself evidence of a problem.
- No repair/rewrite of SEF data — this is a read-only diagnostic, matching
  every other analysis window in this app (A/V sync, bitstream corruption).
- No validation of whether the Motion Photo video track referenced by
  `MotionPhoto_Data` is itself playable/decodable — that's a different,
  already-existing feature surface (motion photo preview playback).
- No MNC (Mobile Network Code, the sub-code identifying the specific carrier
  within a country) validation — out of scope; only the MCC (country-level)
  is checked, matching the existing SEFD decoder's own scope (it already
  only surfaces the raw MCC value with an MCC label, never attempted carrier
  identification).

## Error handling

- If `sefd` never appears in the current tab's parsed tree, the menu item is
  disabled (greyed out) — the window is simply not reachable, no error state
  needed.
- If the `sefd` node exists but is malformed from the very first structural
  check onward (e.g. missing SEFT magic entirely), that check reports
  CRITICAL and every check that structurally depends on a value it would
  have produced (SEFH position, directory entries, field blocks, and by
  extension every semantic check) is reported as `⊘ Skipped — depends on
  [failed check name]` rather than attempted with garbage inputs. Never a
  crash, never a fabricated downstream result.

## Testing

- `SefIntegrityAnalyzer.kt` gets a dedicated unit test file: hand-constructed
  byte-array SEF trailers (following this project's now-established
  programmatic-builder convention for multi-field binary structures, given
  how many offsets a full SEFH+entries+blocks+SEFT trailer involves) covering:
  a fully valid trailer (every check PASS), each individual structural
  failure mode in isolation (bad SEFT magic, bad SEFH magic, entry count
  mismatch, out-of-bounds entry, marker mismatch, name_size overrun,
  overlapping blocks), and each semantic failure mode (implausible timestamp,
  unmapped MCC, malformed JSON, out-of-range MotionPhoto_Data offsets).
- `MccCountryNames`: spot-check a handful of entries (including the
  previously-wrong South/North Korea pair) against the official ITU PDF
  extraction directly, hardcoded as literal test assertions.
- Manual verification: a real Motion Photo / SEF-bearing JPEG from an actual
  Galaxy phone, opened through the live GUI, confirming the report renders
  sensibly with the expected PASS results and no false positives.
