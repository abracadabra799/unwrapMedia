# ISO-BMFF / MP4·MOV container conformance — design (phase 1 of 3)

Date: 2026-10-23. Status: approved in brainstorming. First item of the standards-conformance roadmap
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

1. **Severity:** normative "shall"/required → FAIL; "should" or no keyword → WARN; informational → INFO; not
   evaluable → SKIP (never PASS, AGENTS.md rule 7).
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

Profile: **B** = both, **I** = ISO only, **Q** = QuickTime only.

| ID | Rule | Level | Profile |
|---|---|---|---|
| `bmff.box.size` | Every box: size ≥ header (8, or 16 with largesize), ends within its parent | FAIL | B |
| `bmff.box.size0` | size 0 ("to end of file") only on a top-level box (a top-level size-0 box is by definition the last one) | FAIL | B |
| `bmff.box.trailing` | No bytes after the last top-level box that are not a box | FAIL | B |
| `bmff.ftyp.present` | `ftyp` present | ISO: WARN (pre-ftyp files allowed for compatibility); QT: INFO | B |
| `bmff.ftyp.position` | `ftyp` precedes `moov` and `mdat` (only `free`/`skip`/`wide`/`uuid` may precede it) | FAIL | B |
| `bmff.ftyp.brands` | `ftyp` size = 16 + 4·n; major brand and compatible brands are 4 printable chars; ISO profile: major brand listed or a compatible brand is an ISO brand (`isom`, `iso2`…`iso9`, `mp41`, `mp42`, `M4A `, `M4V `, `3gp4`…, `avc1` etc.) — malformed size/brand chars FAIL, no recognised ISO brand WARN | FAIL / WARN | B |
| `bmff.moov.count` | Exactly one top-level `moov` | FAIL | B |
| `bmff.mvhd.count` | Exactly one `mvhd` in `moov` | FAIL | B |
| `bmff.trak.count` | At least one `trak` | FAIL | B |
| `bmff.trak.required` | Per `trak`: exactly one `tkhd`, one `mdia`; per `mdia`: one `mdhd`, one `hdlr`, one `minf`; per `minf`: one media header, one `dinf` (with one `dref`), one `stbl`; per `stbl`: one `stsd`, `stts`, `stsc`, one of `stsz`/`stz2`, one of `stco`/`co64`; at most one `ctts`, `stss`, `edts` | FAIL | B (QT additionally allows one data-handler `hdlr` inside `minf`) |
| `bmff.mediaheader.match` | Media header matches the handler: `vide`→`vmhd`, `soun`→`smhd`, `hint`→`hmhd`, `subt`→`sthd`, others→`nmhd`; QT: `gmhd` allowed for non-video/sound handlers | FAIL | B |
| `bmff.box.parent` | Known boxes appear only under their allowed parent (e.g. `mvhd` in `moov`, `stsd` in `stbl`, `mdat` top-level); unknown boxes → INFO (readers ignore them); `free`/`skip`/`wide`/`uuid`/`udta`/`meta` allowed where 14496-12 / QTFF allow them | FAIL / INFO | B |
| `bmff.fullbox.version` | `mvhd`/`tkhd`/`mdhd`/`elst`/`ctts`(0/1) versions 0 or 1 only; `hdlr`/`stsd`/`stts`/`stsc`/`stsz`/`stco`/`co64`/`stss`/`vmhd`/`smhd`/`dref` version 0 | FAIL | B |
| `bmff.mvhd.timescale` / `bmff.mdhd.timescale` | timescale > 0 | FAIL | B |
| `bmff.tkhd.track_id` | track_ID ≠ 0 and unique within the file | FAIL | B |
| `bmff.mvhd.next_track_id` | next_track_ID > every track_ID in use (0xFFFFFFFF = "no next" accepted with INFO) | FAIL | B |
| `bmff.vmhd.flags` | `vmhd` flags = 1 | FAIL | B |
| `bmff.hdlr.predefined` | `hdlr` pre_defined = 0 (ISO); QT: component type `mhlr` in `mdia`, `dhlr` in `minf` | FAIL | I / Q |
| `bmff.mdhd.language` | ISO: pad bit 0 and three 5-bit chars each 1..26 (ISO-639-2/T packing); QT: value < 0x400 = Macintosh language code, 0x7FFF = unspecified, else ISO packing | WARN | I / Q |
| `bmff.dref.entries` | `dref` entry_count ≥ 1 and entries fit; a self-contained entry (`url `/`urn ` flags & 1) is required for sample data in this file (external references → INFO and sample-location rules SKIP) | FAIL / INFO | B |
| `bmff.stsd.entries` | `stsd` entry_count ≥ 1 and every sample entry fits inside `stsd` | FAIL | B |
| `bmff.stbl.sample_count` | Σ `stts` sample_count = `stsz`/`stz2` sample_count | FAIL | B |
| `bmff.stsc.order` | `stsc` first_chunk starts at 1 and strictly increases; samples_per_chunk ≥ 1; sample_description_index within 1..`stsd` entry_count; first_chunk ≤ chunk count | FAIL | B |
| `bmff.stsc.coverage` | Samples mapped by `stsc` over all chunks = sample_count | FAIL | B |
| `bmff.ctts.count` | Σ `ctts` sample_count = sample_count (when `ctts` present); version 0 offsets non-negative | FAIL | B |
| `bmff.stss.order` | `stss` sample numbers ≥ 1, strictly increasing, ≤ sample_count | FAIL | B |
| `bmff.sample.location` | Every sample's byte range lies inside the file (FAIL) and inside a top-level `mdat` (WARN otherwise) | FAIL / WARN | B |
| `bmff.mdat.present` | When any track has self-contained samples, at least one `mdat` exists | FAIL | B |
| `bmff.elst.entries` | `elst` entry_count matches its size; media_time = −1 or ≥ 0 and < media duration; media_rate_integer ∈ {0,1}, fraction 0 | FAIL | B |
| `bmff.duration.consistency` | Without `edts`: `tkhd` duration (movie timescale) ≈ `mdhd` duration (media timescale), ±1 movie tick | WARN | B |
| `bmff.fragmented` | `mvex` or `moof` present → INFO "fragmented: sample-table rules are phase 2", sample rules SKIP | INFO | B |

Bounds: tables larger than 2,000,000 entries are not expanded; affected rules report SKIP with the reason.

### Observed real files (2026-10-23, kept out of the repo)

- ffmpeg 7 defaults — MP4 (`isom`), MOV (`qt  `, `wide`, `mhlr`/`dhlr`, language 0x7FFF), M4A (`M4A `), 3GP
  (`3gp4`): all required boxes present, `vmhd` flags 1, Σstts = stsz counts, `dref` url flags 1. Expected: no FAIL,
  no WARN.
- Samsung Galaxy camera MP4 (`mp42`, from a motion photo; `co64`, `meta` directly in `moov`, Samsung `udta`
  atoms `SDLN`/`smrd`/`smta`): `mdhd` language = 0 on both tracks → expected `bmff.mdhd.language` WARN only.
- A 64 MB screen recording (`isom`, 4834 video chunks): expected clean.

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

- Unit tests per rule on synthetic box trees built in test code (each injects one violation into a minimal valid
  movie), for both profiles where the rule differs.
- Real-file fixtures generated by `docs/validation/isobmff-conformance-fixtures/generate.sh` (ffmpeg defaults: MP4,
  MOV, M4A, 3GP — kept tiny) asserting no FAIL/WARN; corrupted variants made by a script (renamed `moov`, duplicate
  `mvhd`, track_ID 0, stts/stsz mismatch, stsc gap, chunk offset past EOF, `vmhd` flags 0, 
  trailing garbage, size-0 box nested in `moov`).
- Manual: the Samsung motion MP4 and the screen recording in `~/Downloads` (not committed) give the results above.
