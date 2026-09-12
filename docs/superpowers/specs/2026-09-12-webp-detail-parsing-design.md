# WebP Detail Parsing — Design (Phase 3)

**Date:** 2026-09-12
**Status:** Approved (pending user spec review)

## Goal

Phase 3 of the multi-phase structure-tree detail-parsing effort (Phase 1: JPEG
APP2 ICC/SEFD, shipped `e5f5787`. Phase 2: PNG, shipped `f55b308`. Prerequisite
little-endian parsing bug fix, shipped `2aa1dc1`). `WebpWalker.kt`'s
`decodeWebpChunk` currently only parses `VP8X`, `VP8 `, `VP8L`, and `EXIF` —
every other WebP chunk falls to a bare `BoxNode(type, offset, headerSize, size)`
with no fields, matching the same "offset/name only" gap this whole effort is
closing.

This phase adds: `ICCP`, `XMP `, `ANIM`, `ANMF`, `ALPH`.

## Non-goals (explicitly out of scope, with why)

- **Recursing into `ANMF`'s nested sub-chunks** (`ALPH`/`VP8 `/`VP8L`, the
  actual per-frame image data): `ANMF` is a container chunk — its own 16-byte
  header (frame position/size/duration/flags) is followed by these sub-chunks.
  Parsing only the header and summarizing the sub-chunk region's byte size
  (not recursing into it as child tree nodes) keeps this phase's scope and risk
  in line with Phases 1-2, matching the existing precedent of `VP8X` not
  recursing into anything either. Confirmed with the user directly (chose
  "헤더만 + 요약" over full recursion) rather than assumed.
- **Actual alpha-bitstream or animation-frame image decoding**: `ALPH`'s
  4-field 1-byte header (reserved/preprocessing/filtering/compression method)
  is parsed and labeled, but the compressed or filtered alpha data itself is
  not decoded — same posture as `VP8 `/`VP8L` already in this file, which
  parse only the bitstream header (width/height), not the image itself.

## Background: what's actually missing

Surveyed via direct code reading of `WebpWalker.kt`'s `decodeWebpChunk` `when`
block (current state, post little-endian fix) — every chunk type not in
`{VP8X, VP8 , VP8L, EXIF}` returns the bare fallback at the bottom of the
function. The WebP chunks below are part of the stable, long-published Google
WebP Container Specification (`https://developers.google.com/speed/webp/docs/riff_container`)
— like Phase 2's PNG, there is no proprietary-format verification-confidence
concern here, unlike Phase 1's Samsung SEFD.

| Chunk | Size | Contents |
|---|---|---|
| `ICCP` | variable | Raw (uncompressed) ICC profile bytes — unlike PNG's `iCCP`, no name prefix and no compression |
| `XMP ` | variable | Raw UTF-8 XMP/RDF text, no prefix (unlike JPEG's APP1, which has an identifier string before the XMP payload) |
| `ANIM` | 6 bytes | Background Color (4 bytes, BGRA byte order) + Loop Count (`uint16`, little-endian, 0 = infinite) |
| `ANMF` | 16-byte header + sub-chunks | Frame X (`uint24` LE, in units of 2 pixels), Frame Y (same), Frame Width Minus One (`uint24` LE), Frame Height Minus One (`uint24` LE), Frame Duration (`uint24` LE, 1ms units), then 1 flags byte: bits 7-2 reserved, bit 1 = blending method, bit 0 = disposal method |
| `ALPH` | 1-byte header + alpha data | 1 byte: Reserved (2 bits) \| Preprocessing (2 bits) \| Filtering method (2 bits) \| Compression method (2 bits) |

## Approach

### `ICCP` — reusing Phase 1/2's ICC header parser directly (no decompression)

WebP's `ICCP` chunk payload **is** a raw ICC profile — no name prefix, no
compression, unlike PNG's `iCCP` (which needed the zlib-inflate detour Phase 2
built). This makes it the simplest possible reuse of
`decodeIccProfileHeader(headerBytes: ByteArray, baseOffset: Long): List<BoxField>`
(already `internal` in `JpegWalker.kt`, already reused as-is by PNG's `iCCP`
in Phase 2 — no further refactor needed here): read
`reader.readBytes(payloadStart, 128)` when `payloadSize >= 128` and pass it
straight through, with `payloadStart` as the base offset (these ARE real file
offsets, unlike PNG's post-decompression case).

- If `payloadSize < 128`: no fields, warning `"ICC profile too short to
  contain a valid header"`, matching this file's existing truncation-warning
  posture (e.g. `VP8X`'s `payloadSize >= 10` guard).
- Summary (matching Phase 1's exact JPEG APP2 wording convention):
  `"ICC Profile v$version ($payloadSize bytes)"`, where `$version` is read
  back out of the returned fields (`headerFields.first { it.name == "version" }.value`,
  same pattern `JpegWalker.kt:369` already uses).

### `XMP ` — reusing the existing `"xmp"` field-name convention

JPEG's APP1 XMP handling (`JpegWalker.kt`'s `decodeApp1`) already establishes
the UI convention: a single `BoxField("xmp", text, ...)`. WebP's `XMP ` chunk
payload is the raw UTF-8 XMP/RDF text directly (no `http://ns.adobe.com/...`
identifier prefix the way JPEG's APP1 has one, since the WebP chunk type
itself already disambiguates it) — read the whole payload as UTF-8, no
trimming needed (JPEG's `.trimEnd(' ', Char(0))` exists because JPEG APP1
payloads can have trailing padding; WebP's declared `chunkSize` is exact, so
skip that unless a real test file shows otherwise).

- Field: `BoxField("xmp", text, payloadStart, payloadSize)`
- Summary: `"XMP (${text.length} chars)"` (exact match to JPEG's wording)

### `ANIM` — two fixed fields

Small dedicated decode function mirroring this file's existing `VP8X`-style
fixed-layout parsing:

- `background_color`: read 4 raw bytes at `payloadStart` (B, G, R, A in that
  file order per spec) and format as `"#RRGGBBAA"` (reordered for the
  conventional hex-color reading order, matching PNG Phase 2's `PLTE`
  `#RRGGBB` convention) — i.e. `"#%02X%02X%02X%02X".format(r, g, b, a)` where
  `r/g/b/a` are read from byte offsets `+2/+1/+0/+3` respectively.
- `loop_count`: `readUInt16LE(payloadStart + 4)`; value `0` gets the label
  `"0 (infinite)"`, any other value just its number as a string (no special
  label needed since the field name already says what it counts).
- Summary: `"Loop count: $loopCountLabel"`
- Guard: `payloadSize >= 6`, else a warning and no fields (same posture as
  `VP8X`'s `payloadSize >= 10` guard).

### `ANMF` — header fields only, sub-chunk region summarized not parsed

New `private fun ByteReader.readUInt24LE` does **not** need to be added — the
file already has exactly this (`readUInt24`, used today for `VP8X`'s
width/height, already little-endian per the prior fix's verified regression
test). Reuse it directly for all five 24-bit fields below.

- `frame_x` = `readUInt24(payloadStart) * 2`
- `frame_y` = `readUInt24(payloadStart + 3) * 2`
- `width` = `readUInt24(payloadStart + 6) + 1`
- `height` = `readUInt24(payloadStart + 9) + 1`
- `duration_ms` = `readUInt24(payloadStart + 12)`
- flags byte at `payloadStart + 15` (verified against Google's official WebP
  Container Specification's raw bit-diagram: `| Reserved(6 bits) | B | D |`,
  i.e. bits 7-2 reserved, bit 1 = B, bit 0 = D — corrected during plan-writing
  from an earlier, incorrect bit6/bit7 assumption in this spec's first draft):
  - `blending`: bit 1 (`0x02`) — `0` → `"Blend"`, `1` → `"Do not blend"`
  - `disposal`: bit 0 (`0x01`) — `0` → `"Do not dispose"`, `1` → `"Dispose to background"`
- Summary: `"${width}x${height}, ${duration_ms}ms (frame data: $frameDataSize bytes, not parsed)"`,
  where `frameDataSize = payloadSize - 16` (the sub-chunk region this phase
  deliberately does not recurse into, per the Non-goals section).
- Guard: `payloadSize >= 16`, else a warning and no fields.

### `ALPH` — 1-byte header, 4 bit-packed fields with name lookups

- Read 1 byte at `payloadStart`.
- `reserved` = `(byte shr 6) and 0x3` — included as a raw field (not just
  silently dropped) since a non-zero value would itself be diagnostically
  interesting, matching this codebase's general posture of surfacing
  spec-violating values rather than hiding them.
- `preprocessing` = `(byte shr 4) and 0x3` — label: `0` → `"None"`, `1` →
  `"Level reduction"`, `2`/`3` → `"Reserved ($value)"` (spec only defines 0/1;
  surface unknown values rather than guessing).
- `filtering_method` = `(byte shr 2) and 0x3` — label: `0` → `"None"`, `1` →
  `"Horizontal"`, `2` → `"Vertical"`, `3` → `"Gradient"`.
- `compression_method` = `byte and 0x3` — label: `0` → `"None"`, `1` →
  `"Lossless (WebP)"`, `2`/`3` → `"Reserved ($value)"` (spec only defines 0/1).
- Summary: `"$filteringLabel filtering, $compressionLabel compression"`.
- Guard: `payloadSize >= 1`, else a warning and no fields.

## Error handling

Every new decoder follows this file's existing posture (see `VP8X`/`VP8 `/
`VP8L`'s `payloadSize >= N` guards): a payload shorter than the chunk's
minimum required size adds a warning and returns whatever fields were already
parsed (none, for these fixed-layout chunks, since they're parsed as a single
atomic block rather than incrementally) — never a crash, never a fabricated
value. `ICCP`'s `payloadSize < 128` case is the one exception with a slightly
different message (calling out the ICC-specific requirement) since 128 bytes
is a much larger minimum than this file's other chunks.

## Testing

- Unit tests per new chunk type, added to the existing `WebpWalkerTest.kt`,
  following its established byte-array-construction convention (see the 4
  existing tests from the prerequisite little-endian fix).
- `ICCP`: construct a 128-byte synthetic ICC header (or reuse a real one, the
  same pattern Phase 1/2 used) and confirm the returned fields match calling
  `decodeIccProfileHeader` directly on the same bytes — an equivalence check,
  not a re-verification of ICC parsing itself (already covered by Phase 1's
  tests).
- `ANMF`: at least one test with realistic 24-bit values verified by hand
  (not just round-tripped through the same formula the code uses), and one
  truncation-guard test (`payloadSize < 16`).
- `ALPH`: a table-style test covering at least one value from each 2-bit
  field's full 0-3 range, confirming both raw values and labels (including
  the "Reserved ($value)" fallback for preprocessing/compression's undefined
  2/3 values).
- Manual verification: real WebP files with an embedded ICC profile, XMP
  metadata, and animation (frames + loop) produced via `cwebp`/`img2webp`
  (both available in this environment — confirmed via `which cwebp dwebp
  webpmux` during the prerequisite fix's investigation; `img2webp` should be
  checked for and used if present, or an animated WebP sourced/generated
  another way if not) opened through the actual app (CLI `dump` + live GUI),
  confirming each new chunk type renders sensibly with no warnings on a
  well-formed file.
