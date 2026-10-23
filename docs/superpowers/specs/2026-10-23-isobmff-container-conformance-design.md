# ISO-BMFF / MP4·MOV container conformance — design (phase 1 of 3)

Date: 2026-10-23. Status: approved in brainstorming; implemented on `feat/isobmff-conformance` (this text was
updated after implementation to match what was built and decided). First item of the standards-conformance roadmap
(ISO-BMFF → HEIF/AVIF → JPEG/EXIF → PNG/WebP/GIF → codec deep).

## Goal

Content Check's **"컨테이너 포맷 규격" (Container Conformance)** row reports strict, rule-by-rule conformance for
track-based ISO-BMFF files (MP4, M4A, 3GP/3G2, M4V) against ISO/IEC 14496-12, and for QuickTime MOV against the
QuickTime File Format. Today that row is NOT_CHECKED for video (only parser warnings exist; an MP4 whose `moov` was
renamed passed Structure).

- **Phase 1 (this spec):** non-fragmented files — box structure, `ftyp`, required boxes and quantities, allowed
  parents, header-field constraints, sample-table consistency, edit lists.
- **Phase 2:** fragmented MP4 (`mvex`/`trex`, `moof`/`mfhd`, `traf`/`tfhd`/`trun`, `sidx`, `mfra`).
- **Phase 3:** brand-specific detail (14496-14 sample entries / `esds`, QuickTime-only atoms, 3GPP).
- HEIF/AVIF still images are the next roadmap item, not this one.

## Decisions (from brainstorming)

1. **Severity:** normative "shall"/required → FAIL; "should" or no keyword → WARN; not evaluable → NOT_CHECKED with
   the reason in the details (never PASS, AGENTS.md rule 7); not applicable to this file → NOT_APPLICABLE (see
   below). There is no INFO status in the `ConformanceStatus` model: informational observations (unknown boxes,
   `next_track_ID` 0xFFFFFFFF, external data references, a QuickTime file without `ftyp`) are PASS with an
   explanatory detail line.
2. **Sources:** the ISO text is not available to us (paid). Rules are written from public material and domain
   knowledge, and every finding is labelled "ISO/IEC 14496-12 (공개 자료 기반, 원문 미대조)" or
   "QuickTime File Format (공개 자료 기반, 원문 미대조)". Rules whose normative strength we cannot confirm are WARN.
   When the user later supplies the standard text, sections and levels get re-checked.
3. **Profile by brand:** `ftyp` major brand `qt  ` (or no `ftyp` at all with QuickTime atoms such as `wide`,
   `mhlr` handlers) → QuickTime profile; everything else → ISO profile. Common rules apply to both; differences are
   per rule (column "Profile").
4. **Display:** Container Conformance row + its detail table, grouped (박스 구조 / ftyp / 필수 박스 / 헤더 필드 /
   샘플 테이블 / 편집 목록), each row with rule id, reference, shall/should and profile; CLI JSON and AI snapshot.
   The Structure row keeps showing parser warnings.
5. **Approach A:** a dedicated checker in `parser/conformance/isobmff/` that walks boxes from the bytes itself
   (independent of the viewer's BoxNode decoders, so field values are asserted from raw bytes — AGENTS.md rule 1).

## Rules

Profile: **B** = both, **I** = ISO only, **Q** = QuickTime only. Every finding's source is exactly
`ISO/IEC 14496-12 (공개 자료 기반, 원문 미대조)` (ISO profile) or `QuickTime File Format (공개 자료 기반, 원문 미대조)`
(QT profile). Groups (`ConformanceRule.scope`): 박스 구조 / Box structure, ftyp, 필수 박스 / Required boxes, 헤더 필드 /
Header fields, 샘플 테이블 / Sample tables, 편집 목록 / Edit lists.

**Profile selection:** `ftyp` major brand `qt  ` → QT; no `ftyp` and (a top-level `wide` box or an `hdlr` with
component type `mhlr`) → QT; otherwise ISO.

**Status semantics.** PASS = the rule was evaluated and holds. NOT_CHECKED = it could not be evaluated (the box it needs
is missing or broken, a table exceeds the 2,000,000-entry cap, a prerequisite rule failed — e.g. a broken `stsc`
order makes coverage and sample location NOT_CHECKED for that track, a renamed `moov` makes every moov-dependent rule
NOT_CHECKED). NOT_APPLICABLE = the rule does not apply to this file: `vmhd.flags` without any `vmhd` (audio-only),
`duration.consistency` when every track has `edts` (tkhd duration follows the edit list; every ffmpeg file has one),
sample-table rules on fragmented files (phase 2) and on tracks whose data is externally referenced, `ftyp.position` /
`ftyp.brands` without `ftyp`, `trak.count` / `trak.required` / `mediaheader.match` on a QuickTime reference movie
(`rmra`), `mdat.present` when no track has self-contained samples. Per-track rules combine as FAIL > WARN >
NOT_CHECKED > PASS > NOT_APPLICABLE (a mix of PASS and NOT_APPLICABLE tracks is PASS). Optional tables that are absent
(`ctts`, `stss`, `edts`/`elst`) give PASS with the detail "없음".

| ID | Rule | Level | Profile |
|---|---|---|---|
| `bmff.box.size` | Every box: size ≥ header (8, or 16 with largesize), ends within its parent | FAIL | B |
| `bmff.box.size0` | size 0 ("to end of file") only on a top-level box (a top-level size-0 box is by definition the last one) | FAIL | B |
| `bmff.box.trailing` | No bytes after the last top-level box that are not a box | FAIL | B |
| `bmff.ftyp.present` | `ftyp` present | ISO: WARN (pre-ftyp files allowed for compatibility); QT: PASS with "optional" detail | B |
| `bmff.ftyp.position` | `ftyp` precedes `moov` and `mdat` (only `free`/`skip`/`wide`/`uuid` may precede it); a second `ftyp` FAILs | FAIL | B |
| `bmff.ftyp.brands` | `ftyp` size = 16 + 4·n; major brand and compatible brands are 4 printable chars; ISO profile: major brand or a compatible brand is an ISO brand (`isom`, `iso2`…`isoc`, `mp41`, `mp42`, `M4A `, `M4V `, `3g*`, `avc1` etc.) — malformed size/brand chars FAIL, no recognised ISO brand WARN; brands beyond 4096 are not listed (detail line) | FAIL / WARN | B |
| `bmff.moov.count` | Exactly one top-level `moov` | FAIL | B |
| `bmff.mvhd.count` | Exactly one `mvhd` in `moov` | FAIL | B |
| `bmff.trak.count` | At least one `trak` (QT reference movie with `rmra`: NOT_APPLICABLE) | FAIL | B |
| `bmff.trak.required` | Per `trak`: exactly one `tkhd`, one `mdia`; per `mdia`: one `mdhd`, one `hdlr`, one `minf`; per `minf`: one media header (`vmhd`/`smhd`/`hmhd`/`nmhd`/`sthd`/`gmhd`), one `dinf` (with one `dref`), one `stbl`; per `stbl`: one `stsd`, `stts`, `stsc`, one of `stsz`/`stz2`, one of `stco`/`co64`; at most one `ctts`, `stss`, `edts` | FAIL | B |
| `bmff.mediaheader.match` | Media header matches the handler: `vide`→`vmhd`, `soun`→`smhd`, `hint`→`hmhd`, `subt`→`sthd`, others→`nmhd`. `gmhd` (QuickTime base media header) counts as a media header in both profiles: QT accepts it for non-video/sound handlers; ISO gives WARN for `gmhd` on a non-video/sound handler (ffmpeg's default MP4/M4A chapter track) and FAIL for `gmhd` on `vide`/`soun` | FAIL / WARN | B |
| `bmff.box.parent` | Known boxes appear only under their allowed parent; unknown boxes are listed in a PASS detail (readers ignore them). `free`/`skip`/`uuid` allowed anywhere, QT also `wide` anywhere. Parent table beyond the core hierarchy: `meta` in `udta` (ffmpeg/iTunes practice), `hdlr`/`dinf` in `meta`, `udta`/`meta` in `moof`/`traf`, `sdtp`/`sbgp`/`sgpd`/`subs`/`saiz`/`saio` in `traf`; QT additionally a data-handler `hdlr` in `minf` and `stps` in `stbl`. ISO profile: one `hdlr` directly in `minf` → WARN (QuickTime-style data handler, at most one per `minf`), a second one → FAIL | FAIL / WARN | B |
| `bmff.fullbox.version` | `mvhd`/`tkhd`/`mdhd`/`elst`/`ctts` versions 0 or 1 only; `hdlr`/`stsd`/`stts`/`stsc`/`stsz`/`stco`/`co64`/`stss`/`vmhd`/`smhd`/`dref` version 0 | FAIL | B |
| `bmff.mvhd.timescale` / `bmff.mdhd.timescale` | timescale > 0 | FAIL | B |
| `bmff.tkhd.track_id` | track_ID ≠ 0 and unique within the file | FAIL | B |
| `bmff.mvhd.next_track_id` | next_track_ID > every track_ID in use (0xFFFFFFFF = "no next" → PASS with detail) | FAIL | B |
| `bmff.vmhd.flags` | `vmhd` flags = 1 (no `vmhd` → NOT_APPLICABLE). `vmhd` is the media header for `vide` and also the `auxv`/`pict` handlers | FAIL | B |
| `bmff.hdlr.predefined` | ISO: `hdlr` pre_defined = 0 → FAIL otherwise, except a QuickTime component type (`mhlr` in `mdia`, `dhlr` in `minf`) → WARN; QT: component type `mhlr` in `mdia`, `dhlr` in `minf` | FAIL / WARN | I / Q |
| `bmff.mdhd.language` | ISO: pad bit 0 and three 5-bit chars each 1..26 (ISO-639-2/T packing; 0 — written by some devices and by ffmpeg on chapter tracks — is WARN); QT: value < 0x400 = Macintosh language code, 0x7FFF = unspecified, else ISO packing | WARN | I / Q |
| `bmff.dref.entries` | `dref` entry_count ≥ 1 and entries fit; a self-contained entry (`url `/`urn ` flags & 1) is required for sample data in this file (external references → PASS with detail, and the track's sample-location rules NOT_APPLICABLE) | FAIL | B |
| `bmff.stsd.entries` | `stsd` entry_count ≥ 1 and every sample entry fits inside `stsd` | FAIL | B |
| `bmff.stbl.sample_count` | Σ `stts` sample_count = `stsz`/`stz2` sample_count | FAIL | B |
| `bmff.stsc.order` | `stsc` first_chunk starts at 1 and strictly increases; samples_per_chunk ≥ 1; sample_description_index within 1..`stsd` entry_count; first_chunk ≤ chunk count | FAIL | B |
| `bmff.stsc.coverage` | Samples mapped by `stsc` over all chunks = sample_count (NOT_CHECKED for a track whose `stsc` order is broken) | FAIL | B |
| `bmff.ctts.count` | Σ `ctts` sample_count = sample_count (when `ctts` present; absent → PASS "없음"); version 0 with an offset whose bit 31 is set (negative when read signed — common in real files that wrote signed offsets with version 0) → WARN | FAIL / WARN | B |
| `bmff.stss.order` | `stss` sample numbers ≥ 1, strictly increasing, ≤ sample_count (absent → PASS "없음") | FAIL | B |
| `bmff.sample.location` | Every sample's byte range lies inside the file (FAIL) and inside a top-level `mdat` payload (WARN otherwise); no top-level `mdat` at all while the track has self-contained samples → FAIL | FAIL / WARN | B |
| `bmff.mdat.present` | When any track has self-contained samples, at least one `mdat` exists (no samples / external only → NOT_APPLICABLE) | FAIL | B |
| `bmff.elst.entries` | `elst` entry_count matches its size; media_time = −1 or ≥ 0 and < media duration (bound unknown → NOT_CHECKED; fragmented → upper bound not checked); ISO: media_rate_integer ∈ {0,1}, fraction 0; QT: media_rate is one signed 16.16 value and any non-negative rate is allowed (rates listed in the detail) | FAIL | B |
| `bmff.duration.consistency` | Without `edts`: `tkhd` duration (movie timescale) ≈ `mdhd` duration (media timescale), ±1 movie tick; with `edts` → NOT_APPLICABLE | WARN | B |
| `bmff.fragmented` | `mvex` or `moof` present → PASS with detail "fragmented: sample-table rules are phase 2"; the sample-table rules are NOT_APPLICABLE | (informational) | B |

Bounds: tables larger than 2,000,000 entries are not expanded; affected rules report NOT_CHECKED with the reason. The
checker reads through `ByteReader` only (no whole-file reads) and never throws except `CancellationException`; an
unexpected exception becomes the report's `inspectionError`.

Content Check: for MP4/MOV/M4A content (decided by magic bytes, not the extension) the ISO-BMFF report replaces the
old parser-warning-only report; the old report's "파서 근거 / Parser evidence" lines are kept in the limitations. CLI
`check` JSON and the analysis case (window "Save analysis case" and CLI `--case`) carry the same
`containerConformance` object.

### Observed real files (2026-10-23, ffmpeg 8.1.2)

Committed fixtures (`app/src/test/resources/isobmff-conformance/`, from `docs/validation/isobmff-conformance-fixtures/`):

| File | Bytes | Profile | Result |
|---|---|---|---|
| `v.mp4` (libx264 64x48 8 frames + aac, `isom`) | 8,161 | ISO | no FAIL/WARN/NOT_CHECKED; NOT_APPLICABLE `duration.consistency` (edts) |
| `v.mov` (same, `qt  `, `wide`, `mhlr`/`dhlr`) | 8,216 | QT | same as v.mp4 |
| `a.m4a` (aac 0.5 s, `M4A `) | 2,222 | ISO | NOT_APPLICABLE `vmhd.flags` (no vmhd), `duration.consistency` |
| `v.3gp` (h263 176x144 + aac 8 kHz mono, `3gp4`) | 14,849 | ISO | NOT_APPLICABLE `duration.consistency` |
| `chapters.mp4` / `chapters.m4a` (aac + chapter text track) | 3,890 / 3,850 | ISO | WARN `mediaheader.match` (gmhd), WARN `mdhd.language` (language 0 on the chapter track) |

Corrupted variants of `v.mp4` (`corrupt.py`; each 8,161–8,269 bytes) — each fails exactly its targeted rule, no other
FAIL and no WARN: `no-moov` → `moov.count` (moov-dependent rules NOT_CHECKED), `dup-mvhd` → `mvhd.count`,
`track-id-0` → `tkhd.track_id`, `stts-mismatch` → `stbl.sample_count`, `stsc-gap` → `stsc.order` (coverage and
sample location NOT_CHECKED), `stco-past-eof` → `sample.location`, `vmhd-flags-0` → `vmhd.flags`, `size0-nested` →
`box.size0`, `trailing-garbage` → `box.trailing`.

Manual (kept out of the repo, `unwrapMedia check` on copies):

- Samsung Galaxy camera MP4 from a motion photo (`mp42`, `co64`, `meta` directly in `moov`, Samsung `udta` atoms;
  4.06 MB): overall WARN — the only non-PASS row is `bmff.mdhd.language` (language 0x0000 on both tracks); the
  audio track has no `edts`, so `duration.consistency` was evaluated and passed (31332 @ 10000 vs 281988 @ 90000).
- A 64.7 MB screen recording (`isom`, compatible `isom`/`iso2`/`mp41`): overall PASS; only NOT_APPLICABLE
  `duration.consistency` (both tracks have `edts`).
- Samsung motion-photo audio M4A (`M4A `, 97 KB): overall PASS; NOT_APPLICABLE `vmhd.flags` (no vmhd) and
  `duration.consistency` (edts).

## Architecture

- **New** `app/src/main/kotlin/com/multiviewer/parser/conformance/isobmff/`:
  - `IsoBmffBoxTree.kt` — bounded box walker over a `ByteReader` (known container set, size/largesize/size-0
    handling, a box records `truncated`/`overruns` instead of throwing).
  - `IsoBmffRules.kt` — rule catalog (id, title KO/EN, reference text, level, profile).
  - `IsoBmffConformanceChecker.kt` — `fun check(reader: ByteReader): IsoBmffReport` producing
    `List<ConformanceFinding>` (existing `com.multiviewer.parser.conformance` model) plus the chosen profile.
  - Sample-table reads are streamed with entry caps; no full-file reads.
- **Wiring:** `contentConformanceReport` (ui/ContentCheckPlan.kt) uses the new checker for VIDEO and AUDIO files whose
  container is MP4/MOV/M4A (`ContainerFormat.MP4/MOV/M4A`); other formats keep today's path. The Container
  Conformance detail table renders grouped findings; CLI `check` JSON and `contentCheckPromptSection` include them.
- Bilingual titles ("한국어 / English"); Material3 only; runs off the UI thread like the other stages.

## Testing

- Unit tests per rule on synthetic box trees built in test code (`SyntheticMovie.kt`: each injects one violation into
  a minimal valid movie), for both profiles where the rule differs.
- Real-file fixtures generated by `docs/validation/isobmff-conformance-fixtures/generate.sh` (ffmpeg defaults: MP4,
  MOV, M4A, 3GP — each < 20 KB) assert no FAIL/WARN; `chapters.sh` adds the chapter-track MP4/M4A (gmhd + language
  WARNs). `corrupt.py` (run with `python3 -I`) derives the corrupted variants by walking the boxes and fixing every
  enclosing size; `IsoBmffFixtureTest` asserts the targeted FAIL and an explicit (currently empty) allow-list of
  consequential FAILs per file.
- Manual: the Samsung motion MP4, the screen recording and a motion-photo M4A in `~/Downloads` (not committed) give the
  results above.
