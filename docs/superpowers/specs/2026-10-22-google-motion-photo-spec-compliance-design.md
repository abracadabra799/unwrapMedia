# Google Motion Photo 1.0 spec compliance — design (phase 1 of 2)

Date: 2026-10-22. Status: approved in brainstorming.

## Goal

The Google **Motion Photo Format 1.0** spec (https://developer.android.com/media/platform/motion-photo-format) is the
first-priority standard for every motion photo this app inspects. Any deviation must surface as FAIL or WARN — never
INFO/PASS — including on genuine Samsung files.

- **Phase 1 (this spec):** XMP (Camera / Container / Item), directory → byte layout, HEIC/AVIF `mpvd`, Ultra HDR
  `GainMap` item, legacy MicroVideo fields, filename.
- **Phase 2 (separate spec later):** video container contents (§5: primary/secondary video track codecs and order, AAC
  audio constraints) and the optional `meta` track (§6: `mett` + `application/motionphoto-image-meta`, descriptor
  syntax, XMP timestamp agreement).

## Decisions (from brainstorming)

1. **Severity:** a rule whose spec text says MUST / MUST NOT / REQUIRED / "Required" → **FAIL**; SHOULD or no
   normative keyword → **WARN**; informational → **INFO**. Every finding names its spec section and requirement level.
2. **Legacy MicroVideo:** the app still recognizes and plays MicroVideo-only files. In the compliance check, a file with
   no `Container:Directory` FAILs §3.2 (a 1.0 reader must ignore `MicroVideo*` and so cannot find the video). When
   both are present, only the Directory is checked; the legacy fields get an INFO ("deleted in 1.0, ignored").
3. **Filename (§2.1, SHOULD):** checked; a mismatch is WARN.
4. **Architecture:** a dedicated checker (approach A) replaces `analyzeGoogleXmpSection` and `analyzeAppleMpvdSection`.
5. **Scope of application:** runs for **every** detected motion photo (Samsung SEF-only files included — they FAIL
   §3.1 `Camera:MotionPhoto`).
6. **Accepted consequence:** genuine Samsung motion photos FAIL (see "Observed Samsung files").

## Rules

Namespaces are matched by **URI**, never by prefix (Samsung writes the Camera namespace under the prefix `GCamera`,
which is valid).

| ID | § | Rule | Level → severity |
|---|---|---|---|
| `gmp.ns.uris` | 3 | Motion photo properties use `http://ns.google.com/photos/1.0/camera/`, `…/container/`, `…/container/item/`. A `MotionPhoto`/`Directory`/`Item` property under any other URI is reported (readers won't find it). | MUST (defined by namespace) → FAIL |
| `gmp.camera.motionphoto` | 3.1 | `Camera:MotionPhoto` present and `1`. Absent, `0`, negative or other → the file is not treated as a motion photo. | MUST → FAIL |
| `gmp.camera.version` | 3.1 | `Camera:MotionPhotoVersion` present and `1`. | no keyword → WARN |
| `gmp.camera.pts` | 3.1 | `Camera:MotionPhotoPresentationTimestampUs` is a Long. `-1` = unspecified (PASS). `≥ 0` must lie within the video duration. Other negatives or non-integer → finding. Absent → INFO (readers use mid-video). | type/range → WARN |
| `gmp.legacy.microvideo` | 3.1 | `Camera:MicroVideo*` present alongside a Directory → INFO "deleted in 1.0, ignored". | INFO |
| `gmp.dir.present` | 3.2 | `Container:Directory` (an ordered `rdf:Seq` of `Container:Item`) is present. | MUST → FAIL |
| `gmp.dir.primary_first` | 3.2/3.3 | The first item's `Item:Semantic` is `Primary`. | MUST → FAIL |
| `gmp.dir.primary_unique` | 3.4 | Exactly one `Primary` item. | MUST → FAIL |
| `gmp.dir.motionphoto_unique` | 3.4 | Exactly one `MotionPhoto` item. | MUST → FAIL |
| `gmp.item.mime_required` | 3.3 | Every item has `Item:Mime`. | MUST → FAIL |
| `gmp.item.semantic_required` | 3.3 | Every item has `Item:Semantic`. | Required → FAIL |
| `gmp.item.mime_value` | 3.4 | `Item:Mime` ∈ {`image/jpeg`, `image/heic`, `image/avif`, `video/mp4`, `video/quicktime`}. | defined values → FAIL |
| `gmp.item.primary_mime` | 3.3 | The Primary item's Mime is an image type matching the real container (JPEG / HEIC / AVIF from content sniffing). | MUST → FAIL |
| `gmp.item.length_required` | 3.3 | Every secondary item has `Item:Length`, a non-negative integer; `0` is allowed only when the item shares the previous item's resource (e.g. a HEIC GainMap inside the primary). The MotionPhoto item's Length must be > 0. | MUST → FAIL |
| `gmp.item.primary_length` | 3.3 | The Primary item's Length, if present, is `0`. | "expected" → WARN |
| `gmp.item.padding_first_only` | 3.3 | Only the first item carries `Item:Padding`. | MUST ("Only … may") → FAIL |
| `gmp.item.padding_jpeg` | 3.3 | JPEG primary: Padding, if present, is a positive integer (`0` is not positive). | type → WARN |
| `gmp.item.padding_isobmff` | 3.3/4 | HEIC/AVIF primary: Padding present and equal to `8` (the `mpvd` header). | Required/MUST → FAIL |
| `gmp.item.video_mime` | 3.4 | The MotionPhoto item's Mime matches the real video container at the resolved start (`qt  ` major brand → `video/quicktime`, otherwise `video/mp4`). | FAIL |
| `gmp.layout.video_start` | 3.3 | `fileLength − Length(MotionPhoto item)` (the item is last, §3.4) lands exactly on the video's `ftyp` box (items are stored "in original form"). If the real `ftyp` is found nearby, the detail names the byte difference. | MUST → FAIL |
| `gmp.layout.packed` | 3.2/3.3 | Tightly packed: primary image length + Padding + Σ(Length of the secondary items before MotionPhoto) = the video item's location from the end. Primary length: JPEG = through the primary image's EOI; HEIC/AVIF = offset of the `mpvd` box. | MUST → FAIL |
| `gmp.layout.video_last` | 3.4 | The MotionPhoto item is the last thing in the file: no bytes after the video container's last top-level box. The detail reports the trailing byte count (and names a Samsung SEF trailer when recognized). | MUST NOT → FAIL |
| `gmp.gainmap.item` | 3.4 | If the primary image is Ultra HDR (gain map detected by `GainmapParser`), the directory has a `GainMap` item placed before the MotionPhoto item. | MUST → FAIL |
| `gmp.mpvd.present` | 4 | HEIC/AVIF: a top-level `mpvd` box exists. | MUST → FAIL |
| `gmp.mpvd.size` | 4 | `mpvd` size field is not `0` and the box lies within the file. | MUST → FAIL |
| `gmp.mpvd.last` | 4 | `mpvd` comes after all other top-level boxes (it is the last box). | MUST → FAIL |
| `gmp.mpvd.length` | 4/3.3 | `mpvd` payload length (size − header) equals the MotionPhoto item's Length. | MUST (data holds all video bytes) → FAIL |
| `gmp.filename` | 2.1 | Filename matches `^([^\s\/\\][^\/\\]*MP)\.(JPG|jpg|JPEG|jpeg|HEIC|heic|AVIF|avif)$`. Detail quotes the spec: readers may ignore the XMP/video otherwise. | SHOULD → WARN |

When the XMP cannot be parsed, a single FAIL (`gmp.xmp.parse`) is reported and the remaining XMP rules are reported as
not checked (SKIP), never PASS. A rule that cannot be evaluated because an earlier rule failed (e.g. no MotionPhoto item
→ no layout) is SKIP with the reason, never PASS.

### Observed Samsung files (real devices, kept out of the repo)

- **JPEG** (`…_motion.jpg`, 5,777,014 B): EOI ends at 1,815,857; `Item:Length`=3,961,157 → declared start 1,815,857
  (packed rule passes), but the bytes there are SEF entries; `ftyp` is at 1,816,018 (+161) → `video_start` FAIL. A SEF
  trailer follows the MP4 → `video_last` FAIL. The MotionPhoto item carries `Item:Padding="0"` →
  `padding_first_only` FAIL. Primary `Padding="0"` → `padding_jpeg` WARN. Filename → WARN.
- **HEIC** (`…_motion.heic`): Primary `Padding="0"` → `padding_isobmff` FAIL; `sefd` box after `mpvd` →
  `mpvd.last` and `video_last` FAIL; Length 3,961,781 ≠ `mpvd` payload 3,960,904 → `mpvd.length` FAIL.
- **HEIC with gain map** (`20260715_223828.heic`): Primary `Padding="8"` passes; `GainMap` item with `Length="0"`
  passes (shared resource); `sefd` after `mpvd` and Length ≠ `mpvd` payload → FAIL.

## Architecture

- **New** `parser/motionphoto/GoogleMotionPhotoSpecChecker.kt`:
  `object GoogleMotionPhotoSpecChecker { fun check(file: File, root: BoxNode, reader: ByteReader, videoDurationUs: Long?, hasGainmap: Boolean): List<GoogleSpecFinding> }`
  - `data class GoogleSpecFinding(val ruleId: String, val section: String, val requirement: SpecRequirement, val severity: SefIntegritySeverity, val title: String, val detail: String)`
  - `enum class SpecRequirement { MUST, SHOULD, INFO }`
  - Pure function of its inputs (no subprocesses). The video duration is passed in (already probed by the analyzer).
  - XMP read via the existing `parseXmpDocument`; namespace-aware lookups by URI. Directory items are read in
    `rdf:Seq` order with their own attributes (or child elements), not via `findPropertyValue`'s first-match search.
- **`MotionPhotoIntegrityAnalyzer`:** calls the checker for every detected motion photo. `MotionPhotoIntegrityReport`
  replaces `googleXmpChecks` and `appleMpvdChecks` with `googleSpec: List<GoogleSpecFinding>`; `overallSeverity` and
  `verdictStatus()` fold it in. Format detection is unchanged except that the `APPLE_MPVD` label becomes
  "HEIC/AVIF mpvd (Google Motion Photo)". SEF checks and the decode check are unchanged.
- **Consumers updated:** `MotionPhotoReportContent` (one "구글 모션포토 1.0 스펙 준수" section grouped Camera XMP /
  Directory / Layout / mpvd / Filename, each row showing `§`, MUST/SHOULD, status, title, detail),
  `ContentFindingMappers` (finding id = rule id), `ContentCheckSnapshot` (AI prompt), `cli/ImageIntegrityJson`
  (category `google_motion_photo` with `ruleId`, `section`, `requirement`), `ImageInspectorUI`.
- User-visible strings bilingual (KO/EN) via `label(korean, english)`.

## Testing

- Unit tests per rule with synthetic XMP strings + `BoxNode` trees: each test injects exactly one violation into an
  otherwise compliant input and asserts that rule's severity, and that a fully compliant input yields no FAIL/WARN.
- Real-file fixtures under `app/src/test/resources/google-motion-photo/` generated by
  `docs/validation/google-motion-photo-fixtures/generate.sh` (ffmpeg JPEG + small MP4, XMP injected): a compliant
  JPEG motion photo (`…MP.jpg`) and violating variants (trailing bytes, padding on the video item, wrong Length,
  legacy-only). HEIC fixtures: verify during planning whether `sips` can produce a HEIC to wrap with `mpvd` + XMP; if
  not, HEIC rules are covered by synthetic box trees only and noted as such.
- Manual check against the real Samsung files in `~/Downloads` (expected results above), not committed.
- Rule 7 of AGENTS.md: no PASS when a rule could not be evaluated.

## Out of scope (phase 2)

§5 video track/audio constraints and §6 metadata track. Samsung SEF semantics remain covered by the existing SEF check.
