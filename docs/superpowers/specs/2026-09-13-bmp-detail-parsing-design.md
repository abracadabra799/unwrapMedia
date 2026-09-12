# BMP Detail Parsing — Design (Phase 4)

**Date:** 2026-09-13
**Status:** Approved (pending user spec review)

## Goal

Phase 4 (final phase) of the multi-phase structure-tree detail-parsing effort
(Phase 1: JPEG, shipped `e5f5787`. Phase 2: PNG, shipped `f55b308`. Phase 3:
WebP, shipped `80c8fbe`). `BmpWalker.kt`'s `BITMAPINFOHEADER` decoder only
parses 4 of the struct's 11 fields (`width`/`height`/`bit_count`/
`compression`, the last shown as a raw number with no name mapping). Any DIB
header whose declared size isn't exactly 40 bytes — including the two modern,
still-commonly-produced header variants, `BITMAPV4HEADER` (108 bytes) and
`BITMAPV5HEADER` (124 bytes) — falls to a generic `DIBHEADER` node showing
only `header_size`, matching the same "offset/name only" gap this whole
effort is closing.

This phase adds the 6 missing `BITMAPINFOHEADER` fields, a `compression`
name mapping, and full recognition of `BITMAPV4HEADER`/`BITMAPV5HEADER`.

## Non-goals (explicitly out of scope, with why)

- **A color-table (palette) or pixel-data tree node**: `parseBmpHeaders`
  currently returns exactly 2 nodes (`BITMAPFILEHEADER`, then the DIB
  header) — there is no palette/pixel-data node today, under-parsed or
  otherwise. This effort's scope has consistently been filling in *existing*
  offset/name-only nodes, not adding entirely new sections the tree doesn't
  currently represent at all — matching how Phase 2 (PNG) declined `tRNS`/
  `bKGD` and Phase 3 (WebP) declined `ANMF` sub-chunk recursion for a similar
  reason (new tree structure, not detail-filling).
- **OS/2 `BITMAPCOREHEADER` (12 bytes) or other non-Windows DIB header
  sizes**: real-world BMP files essentially never use these today; the
  existing generic `DIBHEADER` fallback (showing `header_size`) remains
  unchanged for any header size other than 40/108/124.
- **Actual RLE4/RLE8 pixel-data decompression**: `compression`'s value gets
  a name (e.g. "RLE 8-bit (BI_RLE8)"), but the compressed pixel bytes
  themselves are not decoded — consistent with this whole effort's posture
  elsewhere (e.g. WebP's `ALPH`/`VP8 `/`VP8L` parse only bitstream headers,
  never the compressed image data).

## Background: what's actually missing

Surveyed via direct code reading of `BmpWalker.kt` (current state, 73 lines,
unchanged since before this effort). All field offsets/sizes below were
independently verified against Microsoft's official Win32 API documentation
during this design's writing (not from memory) — every numeric constant that
follows has a cited, confirmed source, precisely because an earlier phase in
this same effort (WebP's `ANMF`) already produced one near-miss from an
unverified bit-position assumption.

### `BITMAPINFOHEADER` (40 bytes) — currently parses 4 of 11 fields

| Offset | Field | Size | Status |
|---|---|---|---|
| 0 | `header_size` | 4 | parsed (used only for dispatch, not shown as a field) |
| 4 | `width` | 4 (signed) | parsed |
| 8 | `height` | 4 (signed) | parsed |
| 12 | `planes` | 2 | **missing** |
| 14 | `bit_count` | 2 | parsed |
| 16 | `compression` | 4 | parsed, but shown as a raw number |
| 20 | `image_size` | 4 | **missing** |
| 24 | `x_pixels_per_meter` | 4 (signed) | **missing** |
| 28 | `y_pixels_per_meter` | 4 (signed) | **missing** |
| 32 | `colors_used` | 4 | **missing** |
| 36 | `colors_important` | 4 | **missing** |

`compression` values (from [Microsoft's BITMAPINFOHEADER docs](https://learn.microsoft.com/en-us/windows/win32/api/wingdi/ns-wingdi-bitmapinfoheader)):
`0`=BI_RGB, `1`=BI_RLE8, `2`=BI_RLE4, `3`=BI_BITFIELDS, `4`=BI_JPEG,
`5`=BI_PNG, `6`=BI_ALPHABITFIELDS, `11`=BI_CMYK, `12`=BI_CMYKRLE8,
`13`=BI_CMYKRLE4. `MediaSummaryBuilder.kt` already has a private
`BMP_COMPRESSION_NAMES` map (values 0-5 only, used by a separate "BMP
Detail" summary panel from an earlier, unrelated phase) — this phase
promotes it to `internal` and extends it with 6/11/12/13 so both the
structure tree and the existing summary panel share one definition rather
than two independently-maintained copies.

### `BITMAPV4HEADER` (108 bytes) — currently unrecognized (falls to generic `DIBHEADER`)

Confirmed via [Microsoft's BITMAPV4HEADER docs](https://learn.microsoft.com/en-us/windows/win32/api/wingdi/ns-wingdi-bitmapv4header)
to be the same 40-byte `BITMAPINFOHEADER` layout above, followed immediately by:

| Offset | Field | Size |
|---|---|---|
| 40 | `red_mask` | 4 |
| 44 | `green_mask` | 4 |
| 48 | `blue_mask` | 4 |
| 52 | `alpha_mask` | 4 |
| 56 | `color_space_type` | 4 |
| 60 | `endpoints` (CIEXYZTRIPLE: 3 × 3 × `FXPT2DOT30`) | 36 |
| 96 | `gamma_red` | 4 |
| 100 | `gamma_green` | 4 |
| 104 | `gamma_blue` | 4 |

Total: 40 + 16 + 4 + 36 + 12 = 108, matching the documented size exactly
(cross-checked by summing the struct's own fields, not just trusting the
docs' stated total).

`color_space_type` values (confirmed via web search cross-referencing
Microsoft's LogicalColorSpace enumeration docs and a real-world bug report —
see Approach below for why this needed extra care):
`0x00000000`=LCS_CALIBRATED_RGB, `0x73524742`=LCS_sRGB ("sRGB" as ASCII),
`0x57696E20`=LCS_WINDOWS_COLOR_SPACE ("Win " as ASCII, V5-only in practice
but the bit pattern is valid at any header size), `0x4C494E4B`=PROFILE_LINKED
("LINK" as ASCII, V5-only field semantics — see below),
`0x4D424544`=PROFILE_EMBEDDED ("MBED" as ASCII, V5-only).

`endpoints`/`gamma_red`/`gamma_green`/`gamma_blue` are, per Microsoft's own
docs, "ignored unless `color_space_type` specifies LCS_CALIBRATED_RGB" — in
practice this is exceedingly rare (virtually every real encoder uses sRGB or
Windows Color Space). Showing 12 mostly-meaningless fields for the common
case would be noise, not detail — see Approach.

### `BITMAPV5HEADER` (124 bytes) — currently unrecognized

Confirmed via [Microsoft's BITMAPV5HEADER docs](https://learn.microsoft.com/en-us/windows/win32/api/wingdi/ns-wingdi-bitmapv5header)
to be `BITMAPV4HEADER` (above) followed by:

| Offset | Field | Size |
|---|---|---|
| 108 | `intent` | 4 |
| 112 | `profile_data_offset` | 4 |
| 116 | `profile_size` | 4 |
| 120 | (reserved) | 4 |

Total: 108 + 16 = 124, matching the documented size.

`intent` values (confirmed via web search of Microsoft's GamutMappingIntent
enumeration — **this needed independent verification because a real
third-party project, [bmpsuite issue #11](https://github.com/jsummers/bmpsuite/issues/11),
documents someone actually confusing this field with `color_space_type`
before**, i.e. this is a genuinely easy field to get wrong, not a
hypothetical concern): `1`=LCS_GM_BUSINESS ("Saturation"),
`2`=LCS_GM_GRAPHICS ("Relative Colorimetric"), `4`=LCS_GM_IMAGES
("Perceptual"), `8`=LCS_GM_ABS_COLORIMETRIC ("Absolute Colorimetric").

`profile_data_offset` is a byte offset **from the start of the DIB header
structure itself** (not from the start of the file) to either an embedded
ICC profile's raw bytes (`color_space_type` = PROFILE_EMBEDDED) or a
NUL-terminated Windows-1252 filename string naming a linked profile
(`color_space_type` = PROFILE_LINKED) — per Microsoft's docs, this field is
"ignored unless `color_space_type` specifies PROFILE_LINKED or
PROFILE_EMBEDDED".

## Approach

### Fixed-layout fields (`BITMAPINFOHEADER`'s 6 missing fields)

Extend the existing `decodeBitmapInfoHeader` with the 6 additional
`readUInt16LE`/`readUInt32LE`/`readInt32LE` reads at their documented
offsets (all already-available helpers in this file), mirroring the file's
existing style exactly. `compression`'s value is looked up in the (now
`internal`) `BMP_COMPRESSION_NAMES` map with an `"Unknown ($value)"`
fallback, matching this codebase's established name-mapping-with-fallback
convention (e.g. Phase 1's `RENDERING_INTENT_NAMES`).

### `BITMAPV4HEADER`/`BITMAPV5HEADER` dispatch

`parseBmpHeaders`'s existing `when (headerSize)`-shaped dispatch (currently
just an `if (headerSize == 40L)`) becomes a proper `when`: `40L` →
`decodeBitmapInfoHeader` (unchanged), `108L` → a new
`decodeBitmapV4Header`, `124L` → a new `decodeBitmapV5Header`, `else` → the
existing generic `DIBHEADER` fallback (unchanged). `decodeBitmapV4Header`
and `decodeBitmapV5Header` each **reuse** `decodeBitmapInfoHeader`'s field
list for their shared first 40 bytes rather than re-deriving it — refactor
`decodeBitmapInfoHeader` to expose its computed `List<BoxField>` (plus the
already-good `width`/`height`/`bit_count` values needed for the summary
string) so V4/V5 can extend that list rather than duplicating the 6+4 read
calls. This mirrors this effort's established reuse pattern (e.g. PNG's
`iCCP` reusing JPEG's `decodeIccProfileHeader`).

### Conditional gamma/endpoints display

`gamma_red`/`gamma_green`/`gamma_blue`/`endpoints` fields are only added to
the returned field list when `color_space_type == 0` (LCS_CALIBRATED_RGB) —
otherwise the spec explicitly says these bytes are meaningless, and showing
12 fields of noise for the overwhelmingly common sRGB/Windows-Color-Space
case would work against this whole effort's goal of surfacing genuinely
useful detail. This is the same judgment call already applied elsewhere in
this effort (e.g. PNG's conditional field presence, WebP's decision not to
recurse into rarely-useful sub-structure).

`endpoints` (a `CIEXYZTRIPLE`: 3 `CIEXYZ` structs for red/green/blue, each 3
`FXPT2DOT30` fixed-point values for X/Y/Z) decodes each `FXPT2DOT30` as a
signed 32-bit big-endian... **no** — BMP is a Windows/Intel format, so
unlike ICC's big-endian `s15Fixed16Number` (already implemented for
JPEG/PNG/WebP ICC profiles), `FXPT2DOT30` here is **little-endian**, same as
every other BMP field in this file. Formula: signed 32-bit integer value
(2's-complement, LE) divided by `2^30` (`1073741824.0`) — a `Q2.30`
fixed-point format (1 sign bit, 1 integer bit, 30 fractional bits). This is
structurally the same shape as `readS15Fixed16` (`JpegWalker.kt`) but a
different divisor and different endianness — a small new local function in
`BmpWalker.kt`, not a shared one, matching `WebpWalker.kt`'s existing
precedent of file-local endianness helpers rather than touching shared code.
All 9 values are shown individually (`x1`/`y1`/`z1` for red,
`x2`/`y2`/`z2` for green, `x3`/`y3`/`z3` for blue) rather than as 3
combined strings, so each is independently inspectable — matching how ICC's
`pcs_illuminant` combines X/Y/Z into one string is a *deliberate* precedent
for a single conceptual measurement, whereas endpoints are 3 genuinely
separate colors and deserve separate fields.

### `BITMAPV5HEADER`'s ICC profile reuse

When `color_space_type` is PROFILE_EMBEDDED: read `profile_size` bytes
starting at `dibStart + profile_data_offset` (the offset is relative to the
DIB header's own start, per the spec — not the file start, and not
`dibStart + 124`). If `profile_size >= 128`, hand the first 128 bytes to the
same `decodeIccProfileHeader(headerBytes, baseOffset)` already shared by
JPEG (Phase 1), PNG (Phase 2), and WebP (Phase 3) — the fourth reuse of this
one parser. When PROFILE_LINKED instead: read up to 260 bytes (a generous
bound comfortably covering any real Windows path) starting at the same
offset, find the first NUL byte, and decode as Windows-1252
(`Charsets.ISO_8859_1` is byte-identical to Windows-1252 for this codebase's
purposes, and is what this file already uses for text elsewhere — see
`profile_name` in PNG's `iCCP`, Phase 2) as a `linked_profile_path` field.
Neither case is attempted when `profile_size`/the computed byte range would
run past the end of the file — degrades to the `profile_data_offset`/
`profile_size` fields alone plus a warning, never a crash.

## Error handling

Matches this file's and this whole effort's existing posture: a DIB header
whose declared size claims 40/108/124 bytes but the actual available bytes
(`end - offset`) are fewer produces the existing "Truncated..." warning with
no further field parsing (unchanged for `BITMAPINFOHEADER`; the same pattern
extends to V4/V5). PROFILE_EMBEDDED/PROFILE_LINKED profile-data reads that
would run past the end of the file degrade to a warning rather than a crash,
per the ICC-profile-reuse section above.

## Testing

- Unit tests per new field group, added to a new `BmpWalkerTest.kt` (this
  walker currently has **no test file at all**, matching the situation
  `WebpWalkerTest.kt` was in before Phase 3's prerequisite fix — following
  the same byte-array-construction convention every other walker's tests
  use).
- `BITMAPINFOHEADER`: all 6 new fields plus the `compression` name mapping
  (including an unrecognized value's `"Unknown ($value)"` fallback).
- `BITMAPV4HEADER`: masks, `color_space_type` name mapping (all 5 named
  values plus an unrecognized raw-fourCC fallback), and both branches of the
  conditional gamma/endpoints display (present when LCS_CALIBRATED_RGB,
  absent otherwise) — with at least one endpoints value hand-verified
  against the `FXPT2DOT30` formula, not just round-tripped through the same
  code being tested.
- `BITMAPV5HEADER`: `intent` name mapping (all 4 named values plus an
  unrecognized fallback), and both `profile_data_offset`/`color_space_type`
  branches — PROFILE_EMBEDDED reusing a real or synthetic 128-byte ICC
  header (same fixture-reuse approach as Phase 3's `ICCP` test, which
  itself reused Phase 1's JPEG fixture) and PROFILE_LINKED with a sample
  filename string.
- Manual verification: real BMP files covering each header size. `sips` can
  write classic 40-byte `BITMAPINFOHEADER` BMPs; producing genuine
  `BITMAPV4HEADER`/`BITMAPV5HEADER` files needs investigation during
  implementation (candidates: Python `Pillow` — confirmed available in this
  environment from Phase 3's manual verification — writes 40-byte headers
  by default, so this may need a small hand-assembled test file, or a
  Windows-side tool if one becomes available; the unit tests are the
  primary correctness evidence for V4/V5 regardless, matching this effort's
  established precedent of accepting unit-test-only coverage when no real
  sample is available — e.g. Phase 3's TIFF/RAW note in the sibling
  `image-formats-overview-detail` project).
