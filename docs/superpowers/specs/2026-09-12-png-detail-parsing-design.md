# PNG Detail Parsing — Design (Phase 2)

**Date:** 2026-09-12
**Status:** Approved (pending user spec review)

## Goal

Phase 2 of the multi-phase structure-tree detail-parsing effort (Phase 1: JPEG APP2
ICC/SEFD, shipped `e5f5787`). `PngWalker.kt`'s `decodePngChunk` currently only parses
`IHDR`, `pHYs`, `tEXt`, and `eXIf` — every other PNG chunk falls to a bare
`BoxNode(type, offset, headerSize, size)` with no fields, matching the same
"offset/name only" gap this whole effort is closing.

This phase adds: `gAMA`, `cHRM`, `sRGB`, `tIME`, `iCCP`, `zTXt`, `iTXt`, and `PLTE`.

## Non-goals (explicitly out of scope, with why)

- **`tRNS` and `bKGD`**: both chunks' byte layout depends on the image's `color_type`
  (from the already-parsed `IHDR`), but `decodePngChunk` currently decodes each
  chunk independently with no cross-chunk state. Threading `color_type` through the
  chunk loop is a real, separable piece of work — deferred to its own follow-up
  rather than folded in here.
- **APNG chunks** (`acTL`, `fcTL`, `fdAT`, `IDAT` reinterpreted for animation
  frames): animated PNG is a narrow use case for this app; not pursued this phase.
- **`PLTE` entry-level detail beyond a count/preview**: showing all N palette colors
  as individual fields could mean hundreds of entries for an indexed-color image.
  Scope is a summary + a bounded preview (see Approach), not one field per color.

## Background: what's actually missing

Surveyed via direct code reading of `PngWalker.kt`'s `decodePngChunk` `when` block —
every chunk not in `{IHDR, pHYs, tEXt, eXIf}` returns the bare fallback. The PNG
chunks below are part of the core PNG 1.2 / ISO 15948 specification (a stable,
long-unchanged public format — unlike Phase 1's proprietary Samsung SEFD, there is
no verification-confidence concern here):

| Chunk | Size | Contents |
|---|---|---|
| `gAMA` | 4 bytes | Image gamma, as a `uint32` scaled by 100000 |
| `cHRM` | 32 bytes | 8 `uint32`s (scaled by 100000): white point x/y, red x/y, green x/y, blue x/y |
| `sRGB` | 1 byte | Rendering intent (0-3, same 4 named values ICC uses) |
| `tIME` | 7 bytes | Last-modified time: year (`uint16`), month/day/hour/minute/second (`uint8` each), defined by the spec to be UTC |
| `iCCP` | variable | Profile name (1-79 bytes, NUL-terminated Latin-1) + 1-byte compression method (always 0 = zlib/deflate) + zlib-compressed ICC profile bytes |
| `zTXt` | variable | Keyword (NUL-terminated Latin-1) + 1-byte compression method (always 0) + zlib-compressed Latin-1 text |
| `iTXt` | variable | Keyword + compression flag + compression method + language tag + translated keyword + UTF-8 text (optionally zlib-compressed per the flag) |
| `PLTE` | variable | N × 3-byte RGB entries (a palette) |

## Approach

### Reusing Phase 1's ICC header parser for `iCCP`

`iCCP`'s payload, once zlib-inflated, is a raw ICC profile — the exact same 128-byte
header Phase 1 already parses for JPEG's APP2. Today,
`decodeIccProfileHeader(reader: ByteReader, headerStart: Long)` (in `JpegWalker.kt`)
reads directly from a live, file-backed `ByteReader` at an absolute file offset —
but decompressed `iCCP` bytes are a fresh in-memory `ByteArray` with no
corresponding file offset (compression changes the byte count), so there's nothing
meaningful to pass as a `ByteReader` position for them.

**Refactor `decodeIccProfileHeader` (and its two helpers, `readIccSignature` and
`readS15Fixed16`) to operate on a plain `ByteArray` instead of `(ByteReader, Long
offset)`.** The JPEG APP2 call site changes to read its 128 bytes into an array
once (`reader.readBytes(headerStart, 128)`) and pass that, with `headerStart` kept
as a separate `baseOffset: Long` parameter purely so the returned `BoxField`s still
report sensible file offsets for the JPEG case. This is a behavior-preserving
refactor for JPEG (same 16 fields, same values, verified by Phase 1's existing 3
tests continuing to pass unchanged) that makes the same parser directly reusable
for PNG's `iCCP`, where the returned fields' offsets are necessarily approximate
(pointing at the `iCCP` chunk's own start, since post-decompression bytes have no
real file position — the same "synthetic field, offset 0" precedent already used
elsewhere in this codebase, e.g. `decodeApp2`'s MPF entry fields).

Visibility changes from `private` to `internal` (same Gradle module, so
`PngWalker.kt` can call it — no cross-module concern, this project has one `app`
module).

### Zlib decompression (`iCCP`, `zTXt`, and `iTXt` when its compression flag is set)

A small shared helper using `java.util.zip.Inflater` (JDK-provided, matches PNG's
"deflate/zlib" compression method 0 — the only method the spec defines, so an
unrecognized `compression_method` byte is treated as an error, not attempted). Capped
at a generous but finite output size (e.g. 64 MB) so a corrupt or adversarial chunk
can't hang decompression or exhaust memory — exceeding the cap or any
`DataFormatException` degrades to a warning with no parsed fields, never a crash.

### Simple fixed-layout chunks (`gAMA`, `cHRM`, `sRGB`, `tIME`)

Each gets its own small decode function mirroring the existing `decodeIhdr`/
`decodePhys` style already in `PngWalker.kt`: read the fixed fields, build a
`BoxField` list, set a human-readable `summary`. `sRGB`'s rendering-intent labels
reuse the same 4 named values as `RENDERING_INTENT_NAMES` (Phase 1, `JpegWalker.kt`)
— duplicated as a small local map in `PngWalker.kt` rather than shared across files,
matching this codebase's existing convention of file-local lookup tables (e.g.
`MARKER_NAMES` in `JpegWalker.kt`, `PNG_COLOR_TYPE_NAMES` already in `PngWalker.kt`
itself) rather than introducing a shared-constants module for four short strings.

### Text chunks (`zTXt`, `iTXt`)

`zTXt`: split at the keyword's NUL terminator (same pattern `decodeText` already
uses for `tEXt`), decompress the remainder, expose `keyword`/`text` fields the same
shape as `tEXt`'s.

`iTXt`: split at each of its three NUL-terminated fields (keyword, language tag,
translated keyword) in sequence, then decode the final text segment as UTF-8 —
decompressing it first if the compression flag byte is 1. Fields:
`keyword`/`language_tag`/`translated_keyword`/`text`, with `language_tag`/
`translated_keyword` allowed to be empty strings (both are optional per spec).

### `PLTE`

Field count = `size / 3` (a warning if not evenly divisible by 3, matching this
file's existing truncation-warning style). Summary: `"$count colors"`. A bounded
preview: the first 8 entries (or fewer if the palette is smaller) as individual
`color_N` fields showing `#RRGGBB` hex — not one field per palette entry, to avoid
a 256-field wall of text for a typical indexed-color PNG.

## Error handling

- Malformed/truncated fixed-size chunks (`gAMA`/`cHRM`/`sRGB`/`tIME` shorter than
  their spec'd size) add a warning and skip field parsing, matching the existing
  `decodeIhdr`/`decodePhys` truncation-warning pattern in this file.
- `iCCP`/`zTXt`/`iTXt`: a missing NUL terminator where one is required, an
  unrecognized compression method, a failed/oversized decompression, or (for
  `iCCP` specifically) fewer than 128 decompressed bytes to hand to the ICC header
  parser — all degrade to whatever fields were already successfully extracted (e.g.
  `iCCP`'s `profile_name` even if decompression then fails) plus a warning, never a
  crash and never fabricated field values.

## Testing

- Unit tests per new chunk type, added to the existing `PngWalkerTest.kt`, following
  its established byte-array-construction convention.
- A dedicated equivalence-style check that `iCCP`'s reused ICC header parser
  produces the same field values as Phase 1's JPEG APP2 path for the same
  underlying 128 profile bytes (construct one real or synthetic 128-byte header,
  feed it through both call sites, compare).
- Manual verification: a real PNG with an embedded ICC profile (macOS's `sips` can
  produce one, as Phase 1 did for JPEG) opened through the actual app, confirming
  `iCCP` renders sensibly; a PNG with `gAMA`/`cHRM`/`tIME`/text chunks if one can be
  produced or found.
