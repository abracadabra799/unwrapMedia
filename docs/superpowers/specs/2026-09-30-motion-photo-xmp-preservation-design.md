# Motion Photo XMP Preservation Design

## Problem

`MotionPhotoBuilder`'s "모션포토 생성" (Create Motion Photo) menu destroys the
original image's existing XMP metadata instead of preserving it:

- **JPEG** (`injectMotionPhotoXmpIntoJpeg`): walks JPEG markers, and whenever
  it finds an existing XMP APP1 segment, drops it entirely (`pos +=
  totalSegSize; continue`, never written to output). A brand-new,
  motion-photo-only XMP is always inserted, unconditionally.
- **HEIC** (`updateHeicXmpItem` + `buildGoogleMotionPhotoHeicXmp`): finds the
  existing XMP item's byte extent (via `iloc`) and overwrites that extent
  with a freshly-built motion-photo-only XMP string (space-padded if
  shorter, silently gives up if longer). The only pre-existing-content
  awareness is a boolean `hasGainMap = oldXmpStr.contains("GainMap")`, used
  to splice in a synthetic `Item:Semantic="GainMap"` entry with a
  **hardcoded fake `Item:Length="0"`** — not the real gain map byte length,
  not any of the real `hdrgm:*` parameters (GainMapMin/Max, Gamma,
  OffsetSDR/HDR, HDRCapacityMin/Max, etc.).
- **HEIC, no existing XMP item at all**: `updateHeicXmpItem` returns the
  original bytes unchanged (`findXmpExtentInHeic(...) ?: return
  baseHeicBytes`) — no Google Motion Photo XMP is created at all. The
  resulting file relies entirely on Samsung SEF fields for motion-photo
  detection; Google Photos-style XMP-based readers won't recognize it as a
  motion photo. (JPEG has no equivalent gap: `injectMotionPhotoXmpIntoJpeg`
  always inserts a fresh XMP APP1 regardless of whether one existed.)

Confirmed via code reading (not real-file dogfooding this time — this is a
forward design for a not-yet-broken-in-the-wild gap, not a bug found via a
real corrupted file).

## Goal

Whenever "모션포토 생성" runs, **preserve as much of the original image's
metadata and data as possible** while still producing a spec-valid motion
photo:

1. If the image already has XMP, merge motion-photo attributes into it
   rather than replacing it — including the specific hard case where the
   existing XMP already has its own `Container:Directory` (Ultra HDR JPEG /
   ISO 21496-1 gain map XMP), since Motion Photo uses the exact same
   `Container:Directory`/`Container:Item` schema.
2. If the image (HEIC specifically) has no XMP item at all, create one —
   correctly registered via `iinf`/`iloc` so real Motion-Photo/XMP readers
   can find it, not just this app's own parser.
3. In every case, everything about the original file that isn't
   XMP-related (other boxes/segments, other `meta`-box items, their
   properties) stays byte-for-byte untouched.

## Non-Goals

- Multiple `rdf:Description` elements in one `rdf:RDF` — only the first is
  merged into; a non-standard file with metadata split across several
  Descriptions keeps today's behavior (rest discarded), documented as a
  known limitation.
- JPEG ExtendedXMP (XMP split across multiple APP1 segments via a
  `HasExtendedXMP` GUID) — only a single-segment XMP APP1 is merged.
- Creating `iref`/`cdsc` item-reference boxes when none exist, to formally
  link a newly-created XMP item to the primary image item per strict HEIF
  spec — real-world readers generally find XMP by `item_type="mime"` +
  `content_type="application/rdf+xml"` alone; skipped for this iteration.
- Validating or correcting gain map numeric parameters
  (`hdrgm:GainMapMin`/`Max`/`Gamma`/etc.) — this work preserves whatever
  values already exist verbatim; it does not compute or sanity-check them.

## Architecture

### Shared building block: `mergeMotionPhotoXmp`

One function, format-independent (used by both the JPEG and HEIC paths),
handling the text/DOM-level merge:

**Signature (illustrative):**
```kotlin
internal fun mergeMotionPhotoXmp(
    existingXmpText: String?,
    videoOffsetOrLength: Long,       // videoOffsetFromEof (JPEG) or the HEIC equivalent
    presentationTimestampUs: Long,
    version: MotionPhotoFormatVersion,
    primaryMimeType: String,          // "image/jpeg" or "image/heic" -- only used when synthesizing a fresh Primary item (no pre-existing Directory)
): String
```

**Algorithm:**
1. `existingXmpText == null` → return the existing
   `buildGoogleMotionPhotoXmp`/`buildGoogleMotionPhotoHeicXmp` output
   unchanged (today's behavior, exact fallback).
2. Parse `existingXmpText` via the already-existing `parseXmpDocument`
   (`MotionPhotoExtractor.kt`). Parse failure (or any unexpected structure
   encountered in steps 3-5 below) → catch and fall back to step 1's
   behavior. Never let a merge failure abort motion-photo creation
   entirely; only degrade to "don't merge, build fresh" plus a logged
   warning.
3. Locate the target element: the first `rdf:Description` under `rdf:RDF`.
   (Multiple Descriptions: non-goal, see above — first one wins.)
4. Ensure required namespace declarations exist on that Description
   (`xmlns:GCamera`, and for V2 also `xmlns:Container`/`xmlns:Item`) —
   reuse if already declared (common when a `Container:Directory` already
   exists from gain map XMP), add if not.
5. Set (overwrite-if-present) the `GCamera:*` attributes this tool owns:
   V1 → `GCamera:MicroVideo="1"`, `MicroVideoVersion="1"`,
   `MicroVideoOffset`, `MicroVideoPresentationTimestampUs`. V2 →
   `GCamera:MotionPhoto="1"`, `MotionPhotoVersion="1"`,
   `MotionPhotoPresentationTimestampUs`. These are exclusively this tool's
   own attributes — safe to overwrite unconditionally.
6. V2 only — `Container:Directory`/`rdf:Seq` handling:
   - **Already exists** (gain map XMP case): leave every existing `<rdf:li>`
     untouched (order, `Item:Semantic`/`Mime`/`Length`/`Padding`/any other
     attributes, verbatim). Append one new `<rdf:li>` for
     `Item:Semantic="MotionPhoto"` at the end. Recompute the `Item:Padding`
     of whichever item was previously last in the sequence, so it
     correctly reflects the byte gap from the end of that item's data to
     the start of the newly-appended video bytes (generalizes today's
     `primaryPadding` calculation — today it always assumes "Primary is the
     item right before the video"; this generalizes to "whichever item is
     now second-to-last").
   - **Doesn't exist**: build a fresh `Container:Directory` with exactly
     today's two items (Primary + MotionPhoto), using `primaryMimeType` for
     the Primary item's `Item:Mime` — same shape as today, just attached to
     the (now-preserved) existing Description instead of a fresh one.
7. Re-serialize the DOM to a string, return it.

**Locked-in constraint (explicitly re-verified, not just inherited):** the
merged XMP's position in the final file must still land **after** any
existing Exif APP1 in JPEG (see JPEG section) — this is a property of
where the *caller* inserts the merged text, not of `mergeMotionPhotoXmp`
itself, but is called out here because it's easy to accidentally break by
insert-at-the-old-XMP's-position instead of insert-at-the-computed-`insertPos`.

### JPEG: `injectMotionPhotoXmpIntoJpeg`

- `insertPos` computation (skip a leading Exif APP1 if present) is
  **unchanged** — this already guarantees Exif-before-XMP ordering
  regardless of where the merge source's original XMP segment was.
- During the existing marker walk, when an XMP APP1 segment is found (the
  `hasXmpPrefix` branch), capture its XMP text (in addition to today's
  drop-it behavior) instead of just dropping it silently.
- Before/after the walk, call `mergeMotionPhotoXmp(capturedText, ...)` and
  build `app1Segment` from its result; write that at `insertPos` (same as
  today, just with merged instead of always-fresh text).
- If more than one XMP APP1 segment is encountered (non-standard), the
  first one's text is used for merging; all XMP APP1 segments encountered
  are still dropped from their original positions (unchanged from today).

### HEIC, existing XMP item present: repoint, don't overwrite-in-place

Replaces today's `updateHeicXmpItem`'s "overwrite in place, give up if
longer" approach entirely (a merged XMP is almost always longer than the
original, since it's additive) with an approach reviewed and confirmed
spec-valid against ISO/IEC 14496-12 `iloc` semantics: **an item's data
extent can point at any absolute byte offset in the file — there's no
requirement it live inside a specific box, box size, or the original
location.** Rewriting only that one item's *existing* `iloc` entry costs
nothing else in the file:

1. Locate the existing XMP item's `iloc` entry (reuse
   `findXmpExtentInHeic`'s existing `meta`→`iloc` walk, extended to report
   the item's `item_ID`, `construction_method`, and the entry's byte
   position within `iloc` — not just the resolved data extent).
2. Build the merged XMP text via `mergeMotionPhotoXmp`.
3. Wrap the merged XMP bytes in a fresh top-level `mdat` box (8-byte
   header: `size` + `"mdat"` FourCC, then the XMP bytes) — not spec-required
   for `construction_method=0` extents, but keeps the file conformant with
   parsers that expect item data to live inside a properly-typed box (low
   cost: 8 bytes).
4. Append that new `mdat` box to the file (alongside where `mpvd`/`sefd`
   already get appended in the existing builder flow).
5. Rewrite, in place, **only** that one existing `iloc` item entry's fixed-width
   fields:
   - `construction_method` → `0` (was possibly `1`/idat-relative; the new
     data is absolute-offset, not idat-relative, regardless of what the
     original was).
   - `base_offset` → `0`.
   - the single extent's `extent_offset` → the new `mdat`'s payload start
     (its file offset + 8, past its own header).
   - the single extent's `extent_length` → the merged XMP's byte length.
   All of these are fixed-width fields (per `iloc`'s own
   `offset_size`/`length_size`/`base_offset_size` from its header) — same
   byte count in, same byte count out. **No box anywhere changes size, no
   other offset in the file changes.** (Defensive bound: if the new
   offset/length can't fit in the existing field width — astronomically
   unlikely for real photo/video sizes — fall back to today's "give up,
   keep original bytes" behavior rather than attempting anything riskier.)
6. If the pre-existing entry had more than one extent (`extent_count > 1`),
   this is out of scope for the "cheap repoint" path — fall back to
   today's give-up behavior (single-extent XMP items are what every
   observed encoder, and this app's own writer, produces).

### HEIC, no existing XMP item: create a new item

The one case that still requires growing `meta`'s internal structure,
since there's no existing item to repoint. Every other existing box,
item, and property stays untouched — only `iinf` and `iloc` gain one new
entry each, appended at the end of their respective lists (never inserted
in the middle):

1. Locate `meta`'s children boundaries, specifically `iinf` and `iloc` (an
   extension of the `meta`-box walk already used above/in
   `findXmpExtentInHeic` — always resolve `iinf`/`iloc` positions, not just
   when an XMP match is found).
2. Scan all existing `infe` entries inside `iinf` for their `item_ID`;
   pick `(max existing item_ID) + 1` for the new item.
3. Build a new `infe` entry: `item_type = "mime"`, `item_name = ""`,
   `content_type = "application/rdf+xml"`. Match the `infe`/`iinf` version
   already used by the file's existing entries (read from an existing
   `infe` if any exist; version 2 unless the new item_ID needs version 3's
   wider ID field).
4. Build the merged XMP the same way as the "existing item" path (§
   above): `mergeMotionPhotoXmp(null, ...)` (no existing XMP text — this is
   the null-fallback path from `mergeMotionPhotoXmp` step 1, i.e. today's
   from-scratch XMP, since there's nothing to merge with) wrapped in a
   fresh `mdat` box appended near the end of the file.
5. Build a new `iloc` item entry (matching the file's existing
   `offset_size`/`length_size`/`base_offset_size`/`index_size`,
   `construction_method = 0`) pointing at that new `mdat`'s payload.
6. Append the new `infe` as the last child of `iinf`; append the new item
   entry as the last item of `iloc`. Grow `iinf`'s and `iloc`'s own box
   `size` fields by exactly what was appended. Grow `meta`'s box `size`
   field by the same total delta (nothing else inside `meta` changes size).
7. Because `meta` grew, every **pre-existing** `iloc` item extent with
   `construction_method = 0` whose absolute offset was **past the original
   end of the `meta` box** must have that offset increased by the total
   bytes inserted — otherwise those items (most importantly, the primary
   image's own pixel data extents) would now point at the wrong bytes,
   corrupting the visible image. `construction_method = 1` (idat-relative)
   entries need no adjustment (their offsets are relative to `idat`'s own
   data, which itself doesn't move relative to where it sits inside
   `meta` — we are only appending entries to `iinf`/`iloc`, never touching
   `idat`'s own bytes or position within `meta`).
8. Assemble the final file: `[meta-grown base, with corrected pre-existing
   offsets]` + `[new mdat holding the new XMP]` + `[mpvd]` + `[sefd]` (same
   trailing order the builder already uses today for mpvd/sefd, with the
   new XMP mdat inserted just before them).

## Testing Plan

The existing `MotionPhotoBuilderTest.kt` HEIC fixture (`[ftyp][mdat, 8
garbage bytes]`) has no `meta`/`iinf`/`iloc` at all — it only exercises
"no XMP item" by accident, and doesn't validate XMP content at all. This
work needs a new, more realistic synthetic HEIC fixture builder (a
`meta` box with `hdlr`, `pitm`, one real primary-image `infe`+`iloc`
entry with known offset/length, `iinf`, `iloc`) — parallel in spirit to
`SefIntegrityAnalyzerTest.kt`'s `buildSefTrailer` helper for SEF trailers.

**JPEG:**
- Exif + gain map XMP (`Container:Directory` with Primary+GainMap already
  present) → merged result has Exif first, then the merged XMP; existing
  GainMap item preserved verbatim; new MotionPhoto item appended; GainMap's
  (now second-to-last) `Item:Padding` recomputed correctly.
- Plain camera XMP (no `Container:Directory`) → existing attributes/
  namespaces preserved on the same Description; fresh Directory
  (Primary+MotionPhoto) attached.
- No existing XMP → byte-for-byte identical to today's fresh-build output
  (regression guard — this is the existing, already-tested path).

**HEIC, existing item:**
- Repoint success: item's `iloc` entry ends up pointing at the new `mdat`;
  re-parsing the output file with this app's own parser finds the XMP
  there with the merged content.
- `construction_method = 1` (idat-relative) original entry → confirms it's
  rewritten to `0` and still resolves correctly.
- `extent_count > 1` original entry → confirms fallback to today's
  give-up-cleanly behavior (not a crash, not corrupted output).

**HEIC, new item:**
- New `item_ID` doesn't collide with any existing one.
- The pre-existing primary-image item's `iloc` offset, after the
  insertion, still resolves to **the exact same original bytes** (the
  single most important regression check — decode-compare against the
  original image bytes before mutation).
- Re-parsing the output file with this app's own parser finds the new XMP
  item with the freshly-built (non-merged, since there was nothing to
  merge) motion-photo XMP content.

**Common, every case:** re-parse the produced file with `parseFile` +
decode both the still-embedded original image (ImageIO/ffmpeg) and the
newly-appended video (ffmpeg) to confirm neither was corrupted — the
final safety net for "XMP got fixed but the image broke" class of bugs.
